# set environment
export PATH=$PATH:/usr/local/openjdk-8/bin

# run application
java -Dsolrmarc.track.solr.progress=false \
     -Dsolrmarc.indexer.threadcount=$VIRGO4_MARC_CONVERT_INDEXER_WORKERS \
     -Dsolrmarc.solrj.threadcount=$VIRGO4_MARC_CONVERT_SOLRJ_WORKERS \
     -Dsolrmarc.indexer.chunksize=10 \
     -Dsolrmarc.track.progress=5000 \
     -classpath dist/marcsqs_reader.jar:lib_solrmarc_install/solrmarc_core_3.3-12-g1b2c032-SNAPSHOT.jar org.solrmarc.driver.Boot SQSQueueDriver \
     -dir . \
     -config solr7_index.properties,rda_index_and_maps.properties,available_index.properties

#
# end of file
#
