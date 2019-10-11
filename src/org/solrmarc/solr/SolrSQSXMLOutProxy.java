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
    private boolean destroyQueueAtEnd = false;
    private final static Logger logger = Logger.getLogger(SolrSQSXMLOutProxy.class);

    public SolrSQSXMLOutProxy(String queueName, String s3BucketName)
    {
        this.queueName = queueName;
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
        int num = 0;
        while (iter.hasNext())
        {
            List<SendMessageBatchRequestEntry> messageBatchReq = new ArrayList<SendMessageBatchRequestEntry>(10);
            List<String> deleteBatchIds = new ArrayList<String>(10);
            for (int i = 0; i < 10 && iter.hasNext(); i++)
            {
                SolrInputDocument inputDoc = iter.next();
                String id = inputDoc.getFieldValue("id").toString();
                String xml = ClientUtils.toXML(inputDoc);
                SendMessageBatchRequestEntry messageReq = new SendMessageBatchRequestEntry(queueUrl, xml)
                        .withId(id)
                        .addMessageAttributesEntry("id", new MessageAttributeValue().withDataType("String").withStringValue(id))
                        .addMessageAttributesEntry("datasource", new MessageAttributeValue().withDataType("String").withStringValue("solrmarc"))
                        .addMessageAttributesEntry("type", new MessageAttributeValue().withDataType("String").withStringValue("application/xml"));
                messageBatchReq.add(messageReq);
                num++;
            }
            SendMessageBatchRequest sendBatchRequest = new SendMessageBatchRequest().withQueueUrl(queueUrl)
                    .withEntries(messageBatchReq);
            SendMessageBatchResult result = aws_sqs.getSQS().sendMessageBatch(sendBatchRequest);
            for (SendMessageBatchResultEntry success : result.getSuccessful())
            {
                deleteBatchIds.add(success.getId());
            }
            aws_sqs.removeBatch(deleteBatchIds);   
        }
        return(num);
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
