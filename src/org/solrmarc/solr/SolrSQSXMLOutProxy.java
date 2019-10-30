package org.solrmarc.solr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrInputDocument;
import org.solrmarc.marc.AwsSqsSingleton;

import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.SendMessageBatchResult;
import com.amazonaws.services.sqs.model.SendMessageBatchResultEntry;
import com.amazonaws.services.sqs.model.SendMessageRequest;

public class SolrSQSXMLOutProxy extends SolrProxy
{
    private String queueUrl = null;
    private String queueName; // = "virgo4-ingest-sirsi-marc-convert-staging";
 //   private String s3BucketName; // = "virgo4-ingest-staging-messages";
    private AwsSqsSingleton aws_sqs = null;
    private boolean createQueueIfNotExists = false;
//    private boolean destroyQueueAtEnd = false;
    private final static Logger logger = Logger.getLogger(SolrSQSXMLOutProxy.class);

    public SolrSQSXMLOutProxy(String queueName, String s3BucketName)
    {
        this.queueName = queueName;
        init(queueName, s3BucketName);
    }
    
    public SolrSQSXMLOutProxy(String queueName, String s3BucketName, boolean createQueue)
    {
        this.queueName = queueName;
        this.createQueueIfNotExists = createQueue;
        init(queueName, s3BucketName);
    }
    
    private void init(String queueName, String s3BucketName)
    {
        this.queueName = queueName;
        
        aws_sqs = AwsSqsSingleton.getInstance(s3BucketName);
        queueUrl = aws_sqs.getQueueUrlForName(this.queueName, createQueueIfNotExists);
    }

    public int addDoc(SolrInputDocument inputDoc)
    {
        String id = inputDoc.getFieldValue("id").toString();
        String xml = ClientUtils.toXML(inputDoc);
        SendMessageRequest message = new SendMessageRequest(queueUrl, xml)
                .addMessageAttributesEntry("id", new MessageAttributeValue().withDataType("String").withStringValue(id))
                .addMessageAttributesEntry("datasource", new MessageAttributeValue().withDataType("String").withStringValue("solrmarc"))
                .addMessageAttributesEntry("type", new MessageAttributeValue().withDataType("String").withStringValue("application/xml"));
        aws_sqs.getSQS().sendMessage(message);
        aws_sqs.remove(id);
        return(1);
    }

    @Override
    public int addDocs(Collection<SolrInputDocument> docQ)
    {
        Iterator <SolrInputDocument> iter = docQ.iterator();
        PushbackIterator<SolrInputDocument> pbIter = PushbackIterator.pushbackIterator(iter);
        int num = 0;
        int messageBatchSize;
        SendMessageBatchRequestEntry messageReq;
        String messageSizes[];
        int i;
        boolean oversizeOnly = Boolean.parseBoolean(System.getProperty("solrmarc-sqs-oversize-only", "false"));
        while (pbIter.hasNext())
        {
            List<SendMessageBatchRequestEntry> messageBatchReq = new ArrayList<SendMessageBatchRequestEntry>(10);
            List<String> deleteBatchIds = new ArrayList<String>(10);
            messageBatchSize = 0;
            messageSizes = new String[10];
            for (i = 0; i < 10 && pbIter.hasNext(); i++)
            {
                SolrInputDocument inputDoc = pbIter.next();
                String xml = ClientUtils.toXML(inputDoc);
                String id = inputDoc.getFieldValue("id").toString();
                // The attributes here must be the same (is size at least) as those added below note id is include twice since it is used as an attribute and as the batch id
                int curMessageSize = getTotalMessageSize(xml, id, "id", id, "datasource", "solrmarc", "type", "application/xml");
                if (i > 0 && messageBatchSize + curMessageSize >= AwsSqsSingleton.SQS_SIZE_LIMIT)
                {
                    logger.info("Message batch would be too large, only sending " + (i + 1) + " messages in batch");
                    pbIter.pushback(inputDoc);
                    break;
                }
                messageSizes[i] = id + " : " + curMessageSize;
                messageReq = new SendMessageBatchRequestEntry(queueUrl, xml).withId(id)
                        .addMessageAttributesEntry("id", new MessageAttributeValue().withDataType("String").withStringValue(id))
                        .addMessageAttributesEntry("datasource", new MessageAttributeValue().withDataType("String").withStringValue("solrmarc"))
                        .addMessageAttributesEntry("type", new MessageAttributeValue().withDataType("String").withStringValue("application/xml"));
                messageBatchReq.add(messageReq);
                num++;
                messageBatchSize += curMessageSize;
            }
            if (oversizeOnly && ( i > 1 || messageBatchSize < AwsSqsSingleton.SQS_SIZE_LIMIT))  continue;
            SendMessageBatchRequest sendBatchRequest = new SendMessageBatchRequest().withQueueUrl(queueUrl)
                    .withEntries(messageBatchReq);
            try {
                SendMessageBatchResult result = aws_sqs.getSQS().sendMessageBatch(sendBatchRequest);
                for (SendMessageBatchResultEntry success : result.getSuccessful())
                {
                    deleteBatchIds.add(success.getId());
                }
                aws_sqs.removeBatch(deleteBatchIds);   
            }
            catch (com.amazonaws.services.sqs.model.BatchRequestTooLongException tooBig)
            {
                logger.warn("Amazon sez I cannot handle that batch, it is too big. Perhaps I could handle a smaller one though.");
                for (int j = 0; j < i; j++)
                {
                    logger.warn("message : "+ messageSizes[j]);
                }
                logger.warn("My computed batch size was "+ messageBatchSize, tooBig);
            }
        }
        if (num < docQ.size()) 
        {
            logger.debug("Not all queued documents sent");
        }
        return(num);
    }

    private int getTotalMessageSize(String message, String batchId, String ... attributes)
    {
        int len = message.length();
       // len += batchId.length();
        for (String attribute : attributes)
        {
            len += attribute.length() + 3;
        }
        len += 500;  // fudge factor.    MMMmm  fudge.
        return(len);
    }

    @Override
    public void commit(boolean optimize)
    {
    }

    @Override
    public void delete(String id)
    {
    }

    @Override
    public QueryResponse query(SolrQuery params)
    {
        return null;
    }

}
