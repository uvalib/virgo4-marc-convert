# set environment
export PATH=$PATH:/usr/local/openjdk-8/bin

# determine the correct jarfile to use
SOLRMARC_JAR=$(ls -t lib_solrmarc_install/solrmarc_core_* | head -1)

# environment variable descriptions:
# VIRGO4_MARC_CONVERT_READER_CONFIG     
#     for sirsi    -r marcreader_sirsi.properties
#     for everything else  <undefined>
# VIRGO4_MARC_CONVERT_CONFIG
#     for sirsi    -c solr7_index.properties,rda_index_and_maps.properties,solr7_sirsi_index.properties
#     for hathi    -c solr7_index.properties,rda_index_and_maps.properties,solr7_hathi_index.properties
#     for dynamic  --reconfig  other_index_specs.properties                    
# VIRGO4_MARC_CONVERT_IN_QUEUE
#    staging       virgo4-ingest-sirsi-marc-convert-staging
#    production    virgo4-ingest-sirsi-marc-convert-production
# VIRGO4_SQS_MESSAGE_BUCKET
#    staging       virgo4-ingest-staging-messages
#    production    virgo4-ingest-production-messages
# VIRGO4_MARC_CONVERT_OUT_QUEUE
#    staging sirsi        ???
#    staging other        ???
#    production sirsi     ???
#    production other     ???
# AWS_REGION  =  us-east-1
# AWS_ACCESS_KEY_ID   <required>
# AWS_SECRET_ACCESS_KEY  <required>

# run application
java -Dsolrmarc.track.solr.progress=false \
     -Dsolrmarc.indexer.threadcount=$VIRGO4_MARC_CONVERT_INDEXER_WORKERS \
     -Dsolrmarc.solrj.threadcount=$VIRGO4_MARC_CONVERT_SOLRJ_WORKERS \
     -Dsolrmarc.indexer.chunksize=10 \
     -Dsolrmarc.terminate.on.marc.exception=false \
     -Dsolrmarc.track.progress=5000 \
     -classpath dist/marcsqs_reader.jar:$SOLRMARC_JAR org.solrmarc.driver.Boot SQSQueueDriver \
     -dir . \
     $VIRGO4_MARC_CONVERT_READER_CONFIG \
     $VIRGO4_MARC_CONVERT_CONFIG

#
# end of file
#
