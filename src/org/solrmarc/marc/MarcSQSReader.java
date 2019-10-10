package org.solrmarc.marc;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.util.List;

import org.apache.log4j.Logger;
import org.marc4j.MarcException;
import org.marc4j.MarcJsonReader;
import org.marc4j.MarcPermissiveStreamReader;
import org.marc4j.MarcReader;
import org.marc4j.marc.Record;

import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.util.Base64;

public class MarcSQSReader implements MarcReader
{
    private String queueUrl = null;
    private String queueName; // = "virgo4-ingest-sirsi-inbound-staging";
   // private String s3BucketName; // = "virgo4-ingest-staging-messages";
    ReceiveMessageRequest receiveMessageRequest;
    private boolean createQueueIfNotExists = false;
    private boolean destroyQueueAtEnd = false;
    private List<Message> curMessages;
    private int curMessageIndex;
    private AwsSqsSingleton aws_sqs = null;
    private final static Logger logger = Logger.getLogger(MarcSQSReader.class);
    
    public MarcSQSReader(String queueName)
    {
        init(queueName, null);
    }
    
    public MarcSQSReader(String queueName, String s3BucketName)
    {
        init(queueName, s3BucketName);
    }
    
    public MarcSQSReader(String queueName, String s3BucketName, boolean createQueueIfNotExists, boolean destroyQueueAtEnd)
    {
        this.createQueueIfNotExists = createQueueIfNotExists;
        this.destroyQueueAtEnd = destroyQueueAtEnd;
        init(queueName, s3BucketName);
    }

    
    private void init(String queueName, String s3BucketName)
    {
        this.queueName = queueName;
        
        aws_sqs = AwsSqsSingleton.getInstance(s3BucketName);
        queueUrl = aws_sqs.getQueueUrlForName(this.queueName, createQueueIfNotExists);
        receiveMessageRequest = new ReceiveMessageRequest(queueUrl).withMaxNumberOfMessages(10).withMessageAttributeNames("All")/*.withWaitTimeSeconds(2) */;
    }
    

    @Override
    public boolean hasNext()
    {
        if (this.curMessages == null && this.curMessageIndex == 0 || this.curMessageIndex >= this.curMessages.size())
        {
            // make blocking call
            fetchMessages();
        }
        return(curMessages == null ? false : this.curMessageIndex < this.curMessages.size());
    }

    private void fetchMessages()
    {
        try {
            curMessages = aws_sqs.getSQS().receiveMessage(receiveMessageRequest).getMessages();
            curMessageIndex = 0;
        }
        catch(com.amazonaws.AbortedException abort)
        {
            curMessages = null; 
        }
    }

    @Override
    public Record next()
    {
        if (hasNext())
        {
            Message message = curMessages.get(curMessageIndex++);
            Record record = processMessageToRecord(message);
            return(record);
        }
        else 
        {
            return null;
        }
    }

    private Record processMessageToRecord(Message message)
    {
        Record rec;
        if (logger.isTraceEnabled())
        {
            logger.trace("Message");
            logger.trace("  MessageId:     " + message.getMessageId());
            logger.trace("  ReceiptHandle: " + message.getReceiptHandle());
            logger.trace("  MD5OfBody:     " + message.getMD5OfBody());
            logger.trace("  Body:          " + message.getBody());
            logger.trace("  Body Length:   " + message.getBody().length());
            for (final String key : message.getMessageAttributes().keySet())
            {
                logger.trace("Attribute");
                logger.trace("  Name:  " + key);
                logger.trace("  Value: " + message.getMessageAttributes().get(key).getStringValue());
            }
        }
        String messageBody = message.getBody();
        try {
            String messageType = message.getMessageAttributes().get("type").getStringValue();
            if (messageType.equals("marcinjson"))
            {
                MarcReader jsreader = new MarcJsonReader(new StringReader(messageBody)); 
                rec = jsreader.next();
            }
            else if (messageType.equals("base64/marc"))
            {
                byte[] expandedMessageBodyBytes = Base64.decode(messageBody);
                MarcReader binreader = new MarcPermissiveStreamReader(new ByteArrayInputStream(expandedMessageBodyBytes), true, true); 
                rec = binreader.next();
            }
            else
            {
                logger.info("Unknown message type "+ messageType);
                throw new MarcException("Unknown message type "+ messageType);
            }
            logger.trace(rec.toString());
            if (!Thread.currentThread().isInterrupted())
            {
                try {
                    final String messageReceiptHandle = message.getReceiptHandle();
                    aws_sqs.getSQS().deleteMessage(new DeleteMessageRequest(queueUrl, messageReceiptHandle));
                }
                catch(com.amazonaws.AbortedException abort)
                {
                    logger.debug("thread interrupted, not deleting message");
                }
            }
            else
            {
                logger.debug("thread interrupted, not deleting message");
            }
            return(rec);
        } 
        catch (NullPointerException npe)
        {
            logger.info("Message missing attrribute \"type\"");
            throw new MarcException("SQS queue named "+ queueName+ " not found");
        }
        finally // in any case delete the message
        {
        }
    }
    
    public static void main(String[] args)
    {
        String queueName= "virgo4-ingest-sirsi-inbound-staging";
        String s3BucketName = "virgo4-ingest-staging-messages";
        boolean alwaysThroughS3 = false;

        MarcSQSReader reader = new MarcSQSReader(queueName, s3BucketName);
        boolean done = false;
        int messageCount = 0;
        for (int i = 0; i < 15; i++)
        {
            Record record = reader.next();
            if (record != null)
            {
                System.out.println(record.toString());
            }
        }
    }

}
