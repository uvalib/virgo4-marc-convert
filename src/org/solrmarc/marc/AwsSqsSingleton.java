package org.solrmarc.marc;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.log4j.Logger;
import org.marc4j.MarcException;

import com.amazon.sqs.javamessaging.AmazonSQSExtendedClient;
import com.amazon.sqs.javamessaging.ExtendedClientConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.CreateQueueResult;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.GetQueueUrlResult;

public class AwsSqsSingleton
{
    private static boolean alwaysThroughS3 = false;
    private static final int SQS_SIZE_LIMIT = 262144;
    private AmazonSQS sqs;
    private AmazonS3 s3;
    private Map<String, String> recHandleMap = null;
    
    private final static Logger logger = Logger.getLogger(AwsSqsSingleton.class);

    private static AwsSqsSingleton instance = null;
    
    private AwsSqsSingleton() {} ; // private to ensure its a singleton
    
    public static AwsSqsSingleton getInstance(String s3BucketName)
    {
        if (instance != null) return (instance);
        AwsSqsSingleton local = new AwsSqsSingleton();
        AmazonSQS sqstmp = AmazonSQSClientBuilder.defaultClient();
        if (s3BucketName != null)
        {
            local.s3 = AmazonS3ClientBuilder.standard().build();
            ExtendedClientConfiguration extendedClientConfig = new ExtendedClientConfiguration();
            extendedClientConfig.withLargePayloadSupportEnabled(local.s3, s3BucketName)
                .withAlwaysThroughS3(alwaysThroughS3).withMessageSizeThreshold(SQS_SIZE_LIMIT);
            
            final AmazonSQSExtendedClient sqsx = new AmazonSQSExtendedClient(sqstmp, extendedClientConfig);
            local.sqs = sqsx;
        }
        else  // Use the normal, non-extended client
        {
            local.sqs = sqstmp;
        }
        local.recHandleMap = new LinkedHashMap<String, String>();
        instance = local;
        return(instance);
    }
    
    public AmazonSQS getSQS()
    {
        return(sqs);     
    }
    
    public String getQueueUrlForName(String queueName, boolean createQueueIfNotExists)
    {
        logger.debug("Listing all queues in your account.");
        GetQueueUrlResult queueUrlResult = sqs.getQueueUrl(queueName);
        if (queueUrlResult != null)
        {
            return (queueUrlResult.getQueueUrl());
        }
        if (createQueueIfNotExists)
        {
            logger.debug("Creating a new SQS queue called "+ queueName);
            final CreateQueueRequest createQueueRequest = new CreateQueueRequest(queueName);
            CreateQueueResult createResult = sqs.createQueue(createQueueRequest);
            if (createResult != null) 
            {
                return (createResult.getQueueUrl());
            }
        }
        logger.debug("SQS queue named "+ queueName+ " not found");
        throw new MarcException("SQS queue named "+ queueName+ " not found");
    }
    
    public void add(String queueUrl, String id, String messageHandle)
    {
        String key = queueUrl + id;
        if (recHandleMap.containsKey(key))
        {
            logger.warn("Already Have a stored message handle with id "+ id + " in queue " + queueUrl + " deleting the existing one!!!");
            String messageReceiptHandle = recHandleMap.get(key);
            getSQS().deleteMessage(new DeleteMessageRequest(queueUrl, messageReceiptHandle));
        }
        recHandleMap.put(key,  messageHandle);
    }
    
    public void remove(String queueUrl, String id)
    {
        String key = queueUrl + id;
        if (!recHandleMap.containsKey(key))
        {
            logger.warn("Do not have a stored message handle with id "+ id + " in queue " + queueUrl );
        }
        else 
        {
            String messageReceiptHandle = recHandleMap.get(key);
            getSQS().deleteMessage(new DeleteMessageRequest(queueUrl, messageReceiptHandle));
            recHandleMap.remove(key);
        }
    }
}
