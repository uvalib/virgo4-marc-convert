package org.solrmarc.driver;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import org.apache.log4j.Logger;
import org.solrmarc.index.indexer.IndexerSpecException;
import org.solrmarc.index.indexer.ValueIndexerFactory;
import org.solrmarc.marc.MarcSQSReader;
import org.solrmarc.marc.SolrMarcMarcReaderFactory;
import org.solrmarc.solr.DevNullProxy;
import org.solrmarc.solr.SolrCoreLoader;
import org.solrmarc.solr.SolrRuntimeException;
import org.solrmarc.solr.SolrSQSXMLOutProxy;
import org.solrmarc.solr.StdOutProxy;
import org.solrmarc.solr.XMLOutProxy;
import org.solrmarc.tools.PropertyUtils;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

/**
 * Uses the command-line arguments to create a MarcReader, a collection of AbstractValueIndexer
 * objects, and a SolrProxy object and then passes them to the Indexer class which loops through
 * the MARC records, builds SolrInputDocuments and then sends them to Solr
 *
 * @author rh9ec
 *
 */
public class SQSQueueDriver extends IndexDriver
{
    private static Logger logger = null;

    public final static String VIRGO4_MARC_CONVERT_IN_QUEUE =  "VIRGO4_MARC_CONVERT_IN_QUEUE";
    public final static String VIRGO4_MARC_CONVERT_OUT_QUEUE = "VIRGO4_MARC_CONVERT_OUT_QUEUE";
    public final static String VIRGO4_SQS_MESSAGE_BUCKET =     "VIRGO4_SQS_MESSAGE_BUCKET";
    public final static int VIRGO4_MARC_CONVERT_QUEUE_POLL_TIMEOUT = 20; // in seconds
    /**
     * The main entry point of the SolrMarc indexing process. Typically called by the Boot class.
     *
     * @param args - The command-line arguments passed to the program
     */
    public static void main(String[] args)
    {
        SQSQueueDriver driver = new SQSQueueDriver(args);
        driver.execute();
    }

    /**
     * Provided as an optional entry-point for the SolrMarc indexing process.  It merely stores the
     * command-line arguments so then can be used by the method execute.
     *
     * @param args - The command-line arguments passed to the program
     */
    public SQSQueueDriver(String[] args)
    {
        super(args);
        logger = Logger.getLogger(SQSQueueDriver.class);
    }

    /**
     *  Creates a MarcReader, a collection of AbstractValueIndexer objects, and a SolrProxy object 
     *  based on the values in the command-line arguments.  It creates a Indexer object
     *  and calls processInput which passes the MarcReader to the Indexer object to index all of the 
     *  MARC records.
     */
    @Override 
    public void execute()
    {
        processArgs(args, true);
        String[] awsLibDirStrs = {"lib_aws"};
        Boot.extendClasspathWithLocalJarDirs(homeDirStrs, awsLibDirStrs);

        indexerFactory = ValueIndexerFactory.initialize(homeDirStrs);
        initializeFromOptions();

        String inputQueueName = getSqsParm(options, "sqs-in", VIRGO4_MARC_CONVERT_IN_QUEUE);
        String s3BucketName = getSqsParm(options, "s3", VIRGO4_SQS_MESSAGE_BUCKET);
        logger.info("Opening input queue: "+ inputQueueName);
        this.configureReader(inputQueueName, s3BucketName);
        
        this.processInput();
    }

    @Override
    protected void processAddnlArgs(OptionParser parser)
    {
        parser.accepts("sqs-in", "sqs queue to read records from").withRequiredArg().ofType( String.class );
        parser.accepts("sqs-out", "sqs queue to write solr docs to").withRequiredArg().ofType( String.class );
        parser.accepts("s3", "s3 bucket to use for oversize records").withRequiredArg().ofType( String.class );
    }
    
    private void initializeFromOptions()
    {
        String[] inputSource = new String[1];
        String propertyFileAsURLStr = PropertyUtils.getPropertyFileAbsoluteURL(homeDirStrs, options.valueOf(readOpts), true, inputSource);

        try
        {
            configureReaderProps(propertyFileAsURLStr);
        }
        catch (IOException e1)
        {
            logger.fatal("Fatal error: Exception opening reader properties input stream: " + inputSource[0]);
            logger.error("Exiting...");
            System.exit(1);
        }

        String sqsOutQueue = getSqsParm(options, "sqs-out", VIRGO4_MARC_CONVERT_OUT_QUEUE);
        boolean multithread =  sqsOutQueue != null && !options.has("debug") ? true : false;
        try
        {
            this.configureOutput(options);
        }
        catch (SolrRuntimeException sre)
        {
            logger.debug("", sre);
            logger.error("Exiting...");
            System.exit(6);
        }
        String specs = options.valueOf(configSpecs);
        try
        {
            logger.info("Reading and compiling index specifications: " + specs);
            this.configureIndexer(specs, multithread);
        }
        catch (IOException | IllegalAccessException | InstantiationException e1)
        {
            logger.error("Error opening or reading index configurations: " + specs, e1);
            logger.error("Exiting...");
            System.exit(2);
        }
        List<IndexerSpecException> exceptions = this.indexerFactory.getValidationExceptions();
        if (!exceptions.isEmpty())
        {
            logger.error("Error processing index configurations: " + specs);
            logTextForExceptions(exceptions);
            logger.error("Exiting...");
            System.exit(5);
        }
    }

    private String getSqsParm(OptionSet options, String clOptName, String propertyOrEnvName)
    {
        return (options.has(clOptName) ? options.valueOf(clOptName).toString() : 
            System.getProperty(propertyOrEnvName)!= null ?  System.getProperty(propertyOrEnvName) :
                System.getenv(propertyOrEnvName));
    }


    final static String [] solrmarcPropertyStrings = {
            "solrmarc.indexer.chunksize",
            "solrmarc.indexer.buffersize",
            "solrmarc.indexer.threadcount",
            "solrmarc.solrj.threadcount",
            "solrmarc.track.solr.progress",
            "solrmarc.terminate.on.marc.exception",
            "solrmarc.output.redirect",
            "solrmarc.indexer.test.fire.method",
            "solrmarc.method.report",
    };

    private void configureReaderProps(String propertyFileURLStr) throws FileNotFoundException, IOException
    {
        List<String> propertyStringsToCopy = Arrays.asList(solrmarcPropertyStrings);
        readerProps = new Properties();
        if (propertyFileURLStr != null)
        {
            readerProps.load(PropertyUtils.getPropertyFileInputStream(propertyFileURLStr));
            Enumeration<?> iter = readerProps.propertyNames();
            while (iter.hasMoreElements())
            {
                String propertyName = iter.nextElement().toString();
                if (propertyName.startsWith("solrmarc.") && propertyStringsToCopy.contains(propertyName) && System.getProperty(propertyName) == null)
                {
                    System.setProperty(propertyName, readerProps.getProperty(propertyName));
                }
                if (propertyName.startsWith("org.marc4j.marc") && System.getProperty(propertyName) == null)
                {
                    System.setProperty(propertyName, readerProps.getProperty(propertyName));
                }
            }
//            try {
//                readerConfig = new MarcReaderConfig(readerProps);
//            }
//            catch(NoClassDefFoundError ncdfe)
//            {
//                readerConfig = null;
//            }
        }
    }

    private void configureReader(String inputQueueName, String s3BucketName)
    {
        try
        {
            reader = new MarcSQSReader(inputQueueName, s3BucketName);
        }
//        catch (IOException e)
//        {
//            throw new IllegalArgumentException(e.getMessage(), e);
//        }
        catch (NoClassDefFoundError ncdfe)
        {
            logger.warn("Using SolrMarc with a marc4j version < 2.8 uses deprecated code in SolrMarc");
            reader = SolrMarcMarcReaderFactory.instance().makeReader(readerProps, ValueIndexerFactory.instance().getHomeDirs(), inputQueueName);
        }
    }

    @Override 
    protected void configureOutput(OptionSet options)
    {
        String solrJClassName = solrjClass.value(options);
        String solrURL = options.has("solrURL") ? options.valueOf("solrURL").toString() : options.has("null") ? "devnull" : "stdout";
        String sqsOutQueue = getSqsParm(options, "sqs-out", VIRGO4_MARC_CONVERT_OUT_QUEUE);
        String s3Bucket = options.has("s3") ? options.valueOf("s3").toString() : System.getProperty(VIRGO4_SQS_MESSAGE_BUCKET);
        if (sqsOutQueue != null)
        {
            solrProxy = new SolrSQSXMLOutProxy(sqsOutQueue, s3Bucket);
        }
        else if (solrURL.equals("stdout"))
        {
            try
            {
                PrintStream out = new PrintStream(System.out, true, "UTF-8");
                System.setOut(out);
                solrProxy = new StdOutProxy(out);
            }
            catch (UnsupportedEncodingException e)
            {
                // since the encoding is hard-coded, and is valid, this Exception cannot occur.
            }
        }
        else if (solrURL.equals("xml"))
        {
            try
            {
                PrintStream out = new PrintStream(System.out, true, "UTF-8");
                System.setOut(out);
                solrProxy = new XMLOutProxy(out);
            }
            catch (UnsupportedEncodingException e)
            {
                // since the encoding is hard-coded, and is valid, this Exception cannot occur.
            }
        }
        else if (solrURL.equals("devnull"))
        {
            solrProxy = new DevNullProxy();
        }
        else
        {
            try  {
                solrProxy = SolrCoreLoader.loadRemoteSolrServer(solrURL, solrJClassName, true);
            }
            catch (SolrRuntimeException sre) 
            {
                logger.error("Error connecting to solr at URL " + solrURL + " : " + sre.getMessage());
                throw(sre);
            }
       }
    }
}
