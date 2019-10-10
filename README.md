# virgo4-marc-convert
Service which allows you to run SolrMarc and have it read MARC records to index from an Amazon Web Services (AWS) SQS queue, and write the produced Solr Add Docs to another AWS queue.

This repo contains only the source code required to adapt SolrMarc to read from and write to AWS SQS queues.

The jars from a current build of SolrMarc are vendored here. As are the required jars for a current(-ish) version of SolrJ and the necessary jars from AWS Java SDK.

To run the program first set up the necessary AWS environment variables:
    export AWS_ACCESS_KEY_ID=<your access key>
    export AWS_SECRET_ACCESS_KEY=<your secret access key>
    export AWS_REGION=us-east-1

Then run the following java command:  (note that a minimum version of Java 1.8 is required and access to a Java JDK is required.)

    java -Dsolrmarc.track.solr.progress=true -Dsolrmarc.indexer.threadcount=4 \
        -Dsolrmarc.solrj.threadcount=16 -Dsolrmarc.indexer.chunksize=5  \
        -classpath dist/marcsqs_reader.jar:lib_solrmarc_install/solrmarc_core_3.3-6-gb6bf218-SNAPSHOT.jar \
        org.solrmarc.driver.Boot SQSQueueDriver  \
        -sqs-in "virgo4-ingest-sirsi-marc-ingest-staging"  \
        -sqs-out "virgo4-ingest-sirsi-marc-convert-staging"  \
        -s3 "virgo4-ingest-staging-messages" \
        -dir .  -config solr7_index.properties,rda_index_and_maps.properties,available_index.properties 

Instead of specifying the input queue name and the output queue name and the s3 bucket to use via command line parameters you can also specify them via environment variables:

    export VIRGO4_MARC_CONVERT_IN_QUEUE=virgo4-ingest-sirsi-marc-ingest-staging
    export VIRGO4_MARC_CONVERT_OUT_QUEUE=virgo4-ingest-sirsi-marc-convert-staging
    export VIRGO4_SQS_MESSAGE_BUCKET=virgo4-ingest-staging-messages


The program will start, initialize itself, and then read records from the input queue, and write the corresponding solr add doc to the output queue.   If you signal it (or CTRL-C) it will flush out what it is doing, and terminate cleanly.

The system properties defined on the command line have the follow effects:
 * solrmarc.indexer.threadcount  - define the number of indexer threads processing MARC records
 * solrmarc.solrj.threadcount  -  define the number of threads used for writing Solr Add Docs to the output queue
 * solrmarc.indexer.chunksize - define how many records will be handled by each solrj indexer worker
 
 The values specified for those properties are far from being optimized.
 
