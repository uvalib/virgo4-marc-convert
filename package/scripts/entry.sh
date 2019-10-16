# set environment
export PATH=$PATH:/usr/local/openjdk-8/bin

# run application
java -Dsolrmarc.track.solr.progress=true -Dsolrmarc.indexer.threadcount=4 \
    -Dsolrmarc.solrj.threadcount=4 -Dsolrmarc.indexer.chunksize=10 -Dsolrmarc.track.progress=5000 \
    -classpath dist/marcsqs_reader.jar:lib_solrmarc_install/solrmarc_core_3.3-10-gfc4f937-SNAPSHOT.jar \
    org.solrmarc.driver.Boot SQSQueueDriver  \
    -dir .  -config solr7_index.properties,rda_index_and_maps.properties,available_index.properties

#
# end of file
#
