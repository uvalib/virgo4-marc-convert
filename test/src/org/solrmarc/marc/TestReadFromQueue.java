package org.solrmarc.marc;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

import org.junit.Test;
import org.marc4j.MarcReaderConfig;
import org.marc4j.marc.Record;
import org.marc4j.util.RawRecord;
import org.marc4j.util.RawRecordReader;

import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.PurgeQueueRequest;
import com.amazonaws.services.sqs.model.PurgeQueueResult;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageResult;
import com.amazonaws.util.Base64;


public class TestReadFromQueue
{
    @Test
    public void TestReadCombinedRec()
    {
        String queueName= "virgo4-ingest-sirsi-marc-ingest-development";
        String s3BucketName = "virgo4-ingest-staging-messages";
        
        String filename="data/u4606651.mrc";
        String queueUrl = AwsSqsSingleton.getInstance(s3BucketName).getQueueUrlForName(queueName, true);
        
        // Make Sure The Queue is empty
        PurgeQueueResult purgeResult = AwsSqsSingleton.getInstance(s3BucketName).getSQS().purgeQueue(new PurgeQueueRequest(queueUrl));
        
        try
        {
            RawRecordReader rawRecs = new RawRecordReader(new FileInputStream(filename));
            RawRecord rec = rawRecs.hasNext() ? rawRecs.next() : null;
            String id = rec.getRecordId();
            byte[] buffer = rec.getRecordBytes();
            assertTrue("record reader should be empty", rawRecs.hasNext() == false);
            
            String base64message = Base64.encodeAsString(buffer);
            SendMessageRequest request = new SendMessageRequest(queueUrl, base64message)
                    .addMessageAttributesEntry("id", new MessageAttributeValue().withDataType("String").withStringValue(id))
                    .addMessageAttributesEntry("datasource", new MessageAttributeValue().withDataType("String").withStringValue("sirsi"))
                    .addMessageAttributesEntry("type", new MessageAttributeValue().withDataType("String").withStringValue("base64/marc"));
            SendMessageResult result = AwsSqsSingleton.getInstance(s3BucketName).getSQS().sendMessage(request);
        }
        catch (FileNotFoundException e)
        {
            fail("Unable to find or open file "+ new File(filename).getAbsolutePath());
        }
        
        MarcReaderConfig config = new MarcReaderConfig().setCombineConsecutiveRecordsFields("852|853|863|866|867|868|999").setToUtf8(true).setPermissiveReader(true);
        
        MarcSQSReader reader = new MarcSQSReader(config, queueName, s3BucketName);
        
        Record rec = reader.hasNext() ? reader.next() : null;
        
        AwsSqsSingleton.getInstance(s3BucketName).remove(rec.getControlNumber());

        assertTrue("Incorrect Record Read", rec.getControlNumber().equals("u4606651"));
        
        // check to see whether the Item record fields are present
        RecordTestingUtils.assertSubfieldHasExpectedValues(rec, "999", 'a', "PT9746 .F6 K6 2007 d.1", "PT9746 .F6 K6 2007 d.2", "PT9746 .F6 K6 2007 d.3");
        
        // check to see whether the Summary Holdings fields were added
        RecordTestingUtils.assertSubfieldHasExpectedValues(rec, "866", 'a', "d.1-3");
    }
    
}
