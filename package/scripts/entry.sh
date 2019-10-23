# set environment
export PATH=$PATH:/usr/local/openjdk-8/bin

# determine the correct jarfile to use
SOLRMARC_JAR=$(ls -t lib_solrmarc_install/solrmarc_core_* | head -1)

# run application
java -Dsolrmarc.track.solr.progress=false \
     -Dsolrmarc.indexer.threadcount=$VIRGO4_MARC_CONVERT_INDEXER_WORKERS \
     -Dsolrmarc.solrj.threadcount=$VIRGO4_MARC_CONVERT_SOLRJ_WORKERS \
     -Dsolrmarc.indexer.chunksize=10 \
     -Dsolrmarc.track.progress=5000 \
     -classpath dist/marcsqs_reader.jar:$SOLRMARC_JAR org.solrmarc.driver.Boot SQSQueueDriver \
     -dir . \
     -config solr7_index.properties,rda_index_and_maps.properties,available_index.properties

#
# end of file
#
