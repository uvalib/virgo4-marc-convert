package org.solrmarc.marc;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.log4j.Logger;
import org.marc4j.MarcException;
import org.marc4j.MarcReader;
import org.marc4j.MarcReaderConfig;
import org.marc4j.MarcReaderFactory;
import org.marc4j.marc.Record;

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
   // private boolean destroyQueueAtEnd = false;
    private boolean alreadyWaiting = false;
    private List<Message> curMessages;
    private int curMessageIndex;
    private AwsSqsSingleton aws_sqs = null;
    private MarcReaderConfig config = null;
    
    private final static Logger logger = Logger.getLogger(MarcSQSReader.class);
    
    public MarcSQSReader(MarcReaderConfig config, String queueName)
    {
        init(config, queueName, null);
    }
    
    public MarcSQSReader(MarcReaderConfig config, String queueName, String s3BucketName)
    {
        init(config, queueName, s3BucketName);
    }
    
    public MarcSQSReader(MarcReaderConfig config, String queueName, String s3BucketName, boolean createQueueIfNotExists)
    {
        this.createQueueIfNotExists = createQueueIfNotExists;
   //     this.destroyQueueAtEnd = destroyQueueAtEnd;
        init(config, queueName, s3BucketName);
    }

    private void init(MarcReaderConfig config, String queueName, String s3BucketName)
    {
        this.queueName = queueName;
        this.config = config;
        
        aws_sqs = AwsSqsSingleton.getInstance(s3BucketName);
        queueUrl = aws_sqs.getQueueUrlForName(this.queueName, createQueueIfNotExists);
        receiveMessageRequest = new ReceiveMessageRequest(queueUrl).withMaxNumberOfMessages(10).withMessageAttributeNames("All").withWaitTimeSeconds(20);
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
        curMessageIndex = -1;
        while (curMessageIndex == -1 && ! Thread.currentThread().isInterrupted())
        {
            try {
                curMessages = aws_sqs.getSQS().receiveMessage(receiveMessageRequest).getMessages();
                if (curMessages.size() > 0)
                {
                    curMessageIndex = 0;
                    if (alreadyWaiting) 
                    {
                        logger.info("Read queue " + this.queueName + " active again. Getting to work.");
                        alreadyWaiting = false;
                    }
                }
                else if (Boolean.parseBoolean(System.getProperty("solrmarc.sqsdriver.terminate.on.empty", "false")))
                {
                    logger.info("Read queue " + this.queueName + " is empty and solrmarc.sqsdriver.terminate.on.empty it true.  Calling it a day.");
                    curMessages = null;
                    curMessageIndex = 0;
                }
                else // timed out without finding any records.   If there is a partial chunk waiting to be sent, flush it out.
                {
                    if (!alreadyWaiting)
                    {
                        logger.info("Read queue " + this.queueName + " is empty. Waiting for more records");
                        alreadyWaiting = true;
                    }
                }
            }
            // this is sent when the sqs object is shutdown.  It causes the reader thread to terminate cleanly.
            // although at present it should actually be triggered.
            catch(com.amazonaws.AbortedException abort)
            {
                curMessages = null;
                curMessageIndex = 0;
            }
            catch(com.amazonaws.services.s3.model.AmazonS3Exception s3e)
            {
                logger.error("Read queue " + this.queueName + " Failed to get the S3 object associated with large SQS message. ");
            }
            catch(com.amazonaws.AmazonServiceException s3e)
            {
                logger.error("Read queue " + this.queueName + " Failed to get the S3 object associated with large SQS message. ");
            }
        }
        if (Thread.currentThread().isInterrupted())
        {
            curMessages = null;
            curMessageIndex = 0;
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
                MarcReader jsreader = MarcReaderFactory.makeReader(config, new ByteArrayInputStream(messageBody.getBytes("UTF-8"))); //new MarcJsonReader(new StringReader(messageBody)); 
                try {
                    rec = jsreader.next();
                }
                catch (org.marc4j.MarcException me)
                {
                    String messageId = message.getMessageAttributes().get("id")!= null ? message.getMessageAttributes().get("id").getStringValue() : " <with no 'id' attribute>";
                    logger.error("Error processing message for record "+ messageId);
                    throw(me);
                }
            }
            else if (messageType.equals("base64/marc"))
            {
                byte[] expandedMessageBodyBytes = Base64.decode(messageBody);
                MarcReader binreader = MarcReaderFactory.makeReader(config, new ByteArrayInputStream(expandedMessageBodyBytes));
               //         new MarcPermissiveStreamReader(new ByteArrayInputStream(expandedMessageBodyBytes), true, true); 
                try { 
                    rec = binreader.next();
                }
                catch (org.marc4j.MarcException me)
                {
                    String messageId = message.getMessageAttributes().get("id")!= null ? message.getMessageAttributes().get("id").getStringValue() : " <with no 'id' attribute>";
                    if (System.getProperty("solrmarc.sqsdriver.write.failed.messages")!= null)
                    {
                       File outputdir = new File(System.getProperty("solrmarc.sqsdriver.write.failed.messages"));
                       if (outputdir != null && outputdir.isDirectory() && outputdir.canWrite() )
                       {
                           File outputfile = new File(outputdir, messageId+".mrc");
                           FileOutputStream fout = new FileOutputStream(outputfile);
                           fout.write(expandedMessageBodyBytes);
                           fout.close();
                       }
                    }
                    logger.error("Error processing message for record "+ messageId);
                    throw(me);
                }
            }
            else
            {
                logger.warn("Unknown message type "+ messageType);
                throw new MarcException("Unknown message type "+ messageType);
            }
            logger.trace(rec.toString());
            
            String id = message.getMessageAttributes().get("id").getStringValue();
            final String messageReceiptHandle = message.getReceiptHandle();
            //  Add message Receipt Handle to a map for later deletion
            aws_sqs.add(queueUrl, id, messageReceiptHandle);
            
            return(rec);
        } 
        catch (NullPointerException npe)
        {
            logger.warn("Message missing attrribute \"type\"");
            throw new MarcException("Message missing attrribute \"type\"");
        }
        catch (IOException e)
        {
            logger.warn("I/O error decoding record -- this shouldn't happen");
            throw new MarcException("I/O error decoding record -- this shouldn't happen");
        }
    }
        
    public static void main(String[] args)
    {
        //    -sqs-in "virgo4-ingest-sirsi-marc-ingest-staging"
        //    -s3 "virgo4-ingest-staging-messages" 
        String queueName= "virgo4-ingest-sirsi-marc-ingest-staging";
        String s3BucketName = "virgo4-ingest-staging-messages";
        MarcReaderConfig config = new MarcReaderConfig().setCombineConsecutiveRecordsFields("852|853|863|866|867|868|999").setToUtf8(true).setPermissiveReader(true);
        
        MarcSQSReader reader = new MarcSQSReader(config, queueName, s3BucketName);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 1000; )
        {
            reader.fetchMessages();
            i += reader.curMessages.size();
        }
        long end = System.currentTimeMillis();
        System.out.println("Total time (fetch only)= "+ ((1.0 * (end - start)) / 1000.0) + " seconds");
        
        start = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++)
        {
            @SuppressWarnings("unused")
            Record record = reader.next();
        //    if (record != null)
       //     {
       //         System.out.println(i);
        //    }
        }
        end = System.currentTimeMillis();
        System.out.println("Total time (fetch and convert to MARC)= "+ ((1.0 * (end - start)) / 1000.0) + " seconds");
    }

}
