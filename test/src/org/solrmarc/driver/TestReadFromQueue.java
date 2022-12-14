package org.solrmarc.driver;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;
import org.marc4j.MarcReaderConfig;
import org.marc4j.marc.Record;
import org.marc4j.util.RawRecord;
import org.marc4j.util.RawRecordReader;
import org.solrmarc.driver.Indexer;
import org.solrmarc.driver.RecordAndCnt;
import org.solrmarc.driver.RecordAndDoc;
import org.solrmarc.driver.SQSQueueDriver;
import org.solrmarc.marc.AwsSqsSingleton;
import org.solrmarc.marc.MarcSQSReader;
import org.solrmarc.marc.RecordPlus;
import org.solrmarc.tools.SolrMarcIndexerException;

import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.PurgeQueueRequest;
import com.amazonaws.services.sqs.model.PurgeQueueResult;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageResult;
import com.amazonaws.util.Base64;


public class TestReadFromQueue
{
	static String inputQueueName;
	static String outputQueueName;
	static  String s3BucketName;
    
	static String inputQueueUrl;
	static SQSQueueDriver DummyDriver;
    
    
    

	@BeforeClass
	static public void initializeQueue()
	{
        inputQueueName= "virgo4-ingest-sirsi-marc-ingest-development";
        outputQueueName= "virgo4-ingest-solr-update-development";
        s3BucketName = "virgo4-ingest-development-messages";
        
        inputQueueUrl = AwsSqsSingleton.getInstance(s3BucketName).getQueueUrlForName(inputQueueName, true);

        // Make Sure The Queue is empty
        @SuppressWarnings("unused")
		PurgeQueueResult purgeResult = AwsSqsSingleton.getInstance(s3BucketName).getSQS().purgeQueue(new PurgeQueueRequest(inputQueueUrl));
	}
	
	
	@Test
    public void testReadCombinedRec()
    {
        String filename="data/u4606651.mrc";
               
        pushRecordsToQueue(filename, inputQueueUrl, s3BucketName, "sirsi");
        
        MarcReaderConfig config = new MarcReaderConfig().setCombineConsecutiveRecordsFields("852|853|863|866|867|868|999").setToUtf8(true).setPermissiveReader(true);
        
        MarcSQSReader reader = new MarcSQSReader(config, inputQueueName, s3BucketName);
        
        Record rec = reader.hasNext() ? reader.next() : null;
        
        assertTrue("RecordPlus cast invalid", rec instanceof RecordPlus);
        
        String messageHandle = ((RecordPlus)rec).getExtraData("message-receipt-handle");
        String recId = ((RecordPlus)rec).getExtraData("message-attribute-id");
        
        assertTrue("Message Id not equal to record Id", recId.equals(rec.getControlNumber()));
        
        AwsSqsSingleton.getInstance(s3BucketName).remove(rec.getControlNumber(), messageHandle);

        assertTrue("Incorrect Record Read", rec.getControlNumber().equals("u4606651"));
        
        // check to see whether the Item record fields are present
        RecordTestingUtils.assertSubfieldHasExpectedValues(rec, "999", 'a', "PT9746 .F6 K6 2007 d.1", "PT9746 .F6 K6 2007 d.2", "PT9746 .F6 K6 2007 d.3");
        
        // check to see whether the Summary Holdings fields were added
        RecordTestingUtils.assertSubfieldHasExpectedValues(rec, "866", 'a', "d.1-3");
    }
    
    @Test
    public void testReconfig()
    {      
        pushRecordsToQueue("data/kanopy_test.mrc", inputQueueUrl, s3BucketName, "kanopy");
        pushRecordsToQueue("data/swank_test.mrc", inputQueueUrl, s3BucketName, "swank");
        System.setProperty("solrmarc.sqsdriver.terminate.on.empty", "true");
        String[] reconfigArgs = { "-debug", "-sqs-in", inputQueueName, 
        		                  "-solrURL", "devnull", "-s3", s3BucketName, 
	                              "-reconfig", "other_index_specs.properties", };

        SQSQueueDriver driver = makeDummySQSQueueDriver(reconfigArgs);
        Indexer indexer = driver.getIndexerForJunitTest();
        RecordAndCnt recordAndCnt = indexer.getRecord(driver.getReaderForJunitTest());
        List<String> result1 = new ArrayList<String>();
        List<String> result2 = new ArrayList<String>();
        while (recordAndCnt != null)
        {
        	result1.add(((RecordPlus)recordAndCnt.getRecord()).getExtraData("message-attribute-source"));
        	RecordAndDoc recDoc = null;
	        try {
	            recDoc = indexer.getIndexDoc(recordAndCnt.getRecord(), recordAndCnt.getCnt());
	            result2.add(recDoc.getDoc().getFieldValue("data_source_f_stored").toString());
	            String messageHandle = ((RecordPlus)recDoc.getRec()).getExtraData("message-receipt-handle");
	            String recId = ((RecordPlus)recDoc.getRec()).getExtraData("message-attribute-id");
	            
	            assertTrue("Message Id not equal to record Id", recId.equals(recDoc.getRec().getControlNumber()));
	            
	            AwsSqsSingleton.getInstance(s3BucketName).remove(recId, messageHandle);

	        }
	        catch (SolrMarcIndexerException smie)
	        {
	            break;
	        }
	        recordAndCnt = indexer.getRecord(driver.getReaderForJunitTest());
        }
        assertTrue("Different number of records and solr documents", result1.size() == result2.size());
        for (int i = 0; i < result1.size(); i++)
        {
        	String val1 = result1.get(i);
        	String val2 = result2.get(i);
        	assertTrue("record source and data source in solr document don't match at index "+i , val1.equals(val2));
        }
    }

    
    
    //  parser.accepts("sqs-in", "sqs queue to read records from").withRequiredArg().ofType( String.class );
//  parser.accepts("sqs-out", "sqs queue to write solr docs to").withRequiredArg().ofType( String.class );
//  parser.accepts("sqs-delete", "sqs queue to write ids to delete to").withRequiredArg().ofType( String.class );
//  parser.accepts("s3", "s3 bucket to use for oversize records").withRequiredArg().ofType( String.class );
//  parser.accepts("reconfig", "specifies that the indexer can be reconfigured at runtime, providing a mapping from data source name to index specification").withRequiredArg().ofType( String.class );
//  readOpts = parser.acceptsAll(Arrays.asList( "r", "reader_opts"), "file containing MARC Reader options").withRequiredArg().defaultsTo("marcreader.properties");
//  configSpecs = parser.acceptsAll(Arrays.asList( "c", "config"), "index specification file to use").withRequiredArg();
//  homeDirs = parser.accepts("dir", "directory to look in for scripts, mixins, and translation maps").withRequiredArg().ofType( String.class );
//  addnlLibDirs = parser.accepts("lib_local", "directory to look in for additional jars and libraries").withRequiredArg().defaultsTo("lib_local");
//  solrjDir = parser.accepts("solrj", "directory to look in for jars required for SolrJ").withRequiredArg().ofType( File.class );
//  solrjClass = parser.accepts("solrjClassName", "Classname of class to use for talking to solr").withRequiredArg();
//  errorMarcErrOutFile = parser.accepts("marcerr", "File to write records with errors.(not yet implemented)").withRequiredArg().ofType( File.class );
//  errorIndexErrOutFile = parser.accepts("indexerr", "File to write the solr documents for records with errors.(not yet implemented)").withRequiredArg().ofType( File.class );
//  errorSolrErrOutFile = parser.accepts("solrerr", "File to write the solr documents for records with errors.(not yet implemented)").withRequiredArg().ofType( File.class );
//  deleteRecordByIdFile = parser.accepts("del", "File to read list of document ids that are to be deleted").withRequiredArg().ofType( File.class );
//  parser.accepts("debug", "non-multithreaded debug mode");
//  parser.acceptsAll(Arrays.asList( "solrURL", "u"), "URL of Remote Solr to use").withRequiredArg();
//  parser.acceptsAll(Arrays.asList( "solrCommit", "c"), "Whether to commit, true or false").withRequiredArg();
////  parser.acceptsAll(Arrays.asList("print", "stdout"), "write output to stdout in user readable format").availableUnless("solrURL");
////   parser.acceptsAll(Arrays.asList("null"), "discard all output, and merely show errors and warnings").availableUnless("solrURL");
//  parser.acceptsAll(Arrays.asList("?", "help"), "show this usage information").forHelp();
//  //parser.mutuallyExclusive("stdout", "solrURL");
//  processAddnlArgs(parser);
//  files = parser.nonOptions().ofType( String.class );

    public SQSQueueDriver makeDummySQSQueueDriver(String[] args)
    {
    	SQSQueueDriver tmpDriver = new SQSQueueDriver(args);
    	tmpDriver.initializeForJunitTest(args);
    	return(tmpDriver);
    }
    
    public void pushRecordsToQueue(String filename, String queueUrl, String s3BucketName, String indexSpecName)
    {
        try
        {
            RawRecordReader rawRecs = new RawRecordReader(new FileInputStream(filename));
            while (rawRecs.hasNext())
            {
                RawRecord rec = rawRecs.hasNext() ? rawRecs.next() : null;
                String id = rec.getRecordId();
                byte[] buffer = rec.getRecordBytes();
                //assertTrue("record reader should be empty", rawRecs.hasNext() == false);
                
                String base64message = Base64.encodeAsString(buffer);
                SendMessageRequest request = new SendMessageRequest(queueUrl, base64message)
                        .addMessageAttributesEntry("id", new MessageAttributeValue().withDataType("String").withStringValue(id))
                        .addMessageAttributesEntry("source", new MessageAttributeValue().withDataType("String").withStringValue(indexSpecName))
                        .addMessageAttributesEntry("type", new MessageAttributeValue().withDataType("String").withStringValue("base64/marc"));
                @SuppressWarnings("unused")
				SendMessageResult result = AwsSqsSingleton.getInstance(s3BucketName).getSQS().sendMessage(request);
            }
        }
        catch (FileNotFoundException e)
        {
            fail("Unable to find or open file "+ new File(filename).getAbsolutePath());
        }
    }
}
