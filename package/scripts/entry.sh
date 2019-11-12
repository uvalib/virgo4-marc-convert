# set environment
export PATH=$PATH:/usr/local/openjdk-8/bin

# determine the correct jarfile to use
SOLRMARC_JAR=$(ls -t lib_solrmarc_install/solrmarc_core_* | head -1)

# run application
java -Dsolrmarc.track.solr.progress=false \
     -Dsolrmarc.indexer.threadcount=$VIRGO4_MARC_CONVERT_INDEXER_WORKERS \
     -Dsolrmarc.solrj.threadcount=$VIRGO4_MARC_CONVERT_SOLRJ_WORKERS \
     -Dsolrmarc.indexer.chunksize=10 \
     -Dsolrmarc.terminate.on.marc.exception=false \
     -Dsolrmarc.track.progress=5000 \
     -classpath dist/marcsqs_reader.jar:$SOLRMARC_JAR org.solrmarc.driver.Boot SQSQueueDriver \
     -dir . \
     -config $VIRGO4_MARC_CONVERT_CONFIG

#
# end of file
#
