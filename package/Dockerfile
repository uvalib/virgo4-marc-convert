#
# build the target application
#

FROM public.ecr.aws/docker/library/amazoncorretto:11-al2023-jdk as builder
RUN yum -y update && yum -y install ant
WORKDIR /build
COPY build.xml ./
COPY src ./src
COPY lib_solrmarc_install ./lib_solrmarc_install
COPY lib_aws ./lib_aws
COPY lib_xml ./lib_xml
RUN ant

#
# build the target container
#
FROM public.ecr.aws/docker/library/amazoncorretto:11-al2023-jdk

# update packages
RUN yum -y update && yum -y install shadow-utils

# Create the run user and group
RUN groupadd webservice && useradd -g webservice webservice

# set the timezone appropriatly
ENV TZ=UTC
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# Specify home 
ENV APP_HOME /virgo4-marc-convert
WORKDIR $APP_HOME

# Create necessary directories
RUN mkdir -p $APP_HOME/scripts $APP_HOME/lib_aws $APP_HOME/lib_solrmarc_install $APP_HOME/dist
RUN chown -R webservice $APP_HOME && chgrp -R webservice $APP_HOME

# run command
CMD scripts/entry.sh

# Move in necessary assets
COPY package/data/container_bash_profile /home/webservice/.profile
COPY package/scripts/entry.sh $APP_HOME/scripts/entry.sh

COPY lib_aws $APP_HOME/lib_aws/
COPY lib_xml $APP_HOME/lib_xml/
COPY lib_solrmarc_install $APP_HOME/lib_solrmarc_install/
COPY --from=builder /build/dist $APP_HOME/dist

# Ensure permissions are correct
RUN chown -R webservice:webservice /home/webservice/.profile $APP_HOME && chmod 755 /home/webservice/.profile $APP_HOME/scripts/entry.sh

# Add the build tag
ARG BUILD_TAG
RUN test -n "$BUILD_TAG" && touch $APP_HOME/buildtag.build-$BUILD_TAG || touch $APP_HOME/buildtag.build-0

# Specify the user
USER webservice

#
# end of file
#
