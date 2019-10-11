package org.solrmarc.solr;

import java.util.Collection;

import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrInputDocument;
import org.solrmarc.marc.AwsSqsSingleton;

import com.amazonaws.services.sqs.model.MessageAttributeValue;
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
                .addMessageAttributesEntry("id", new MessageAttributeValue().withDataType("String").withStringValue(inputDoc.getFieldValue("id").toString()))
                .addMessageAttributesEntry("datasource", new MessageAttributeValue().withDataType("String").withStringValue("solrmarc"))
                .addMessageAttributesEntry("type", new MessageAttributeValue().withDataType("String").withStringValue("application/xml"));
        aws_sqs.getSQS().sendMessage(message);
        aws_sqs.remove(queueUrl, id);
        return(1);
    }

    @Override
    public int addDocs(Collection<SolrInputDocument> docQ)
    {
        int num = 0;
        for (SolrInputDocument doc : docQ)
        {
            num += this.addDoc(doc);
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
