package org.solrmarc.driver;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import org.apache.log4j.Logger;
import org.marc4j.MarcReader;
import org.marc4j.MarcReaderConfig;
import org.solrmarc.index.indexer.IndexerSpecException;
import org.solrmarc.index.indexer.ValueIndexerFactory;
import org.solrmarc.marc.MarcSQSReader;
import org.solrmarc.marc.SolrMarcMarcReaderFactory;
import org.solrmarc.solr.DevNullProxy;
import org.solrmarc.solr.SolrCoreLoader;
import org.solrmarc.solr.SolrRuntimeException;
import org.solrmarc.solr.SolrSQSWrappedProxy;
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
    public final static String VIRGO4_MARC_CONVERT_DELETE_QUEUE = "VIRGO4_MARC_CONVERT_DELETE_QUEUE";
    public final static String VIRGO4_SQS_MESSAGE_BUCKET =     "VIRGO4_SQS_MESSAGE_BUCKET";
    public final static int VIRGO4_MARC_CONVERT_QUEUE_POLL_TIMEOUT = 20; // in seconds
    private boolean reconfigurable = false;
    private Properties indexSpecMap = null; 
    private String indexSpecName = null;

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

    public void initializeForJunitTest(String[] args)
    {
        processArgs(args, true);
        String[] awsLibDirStrs = {"lib_aws"};
        Boot.extendClasspathWithLocalJarDirs(homeDirStrs, awsLibDirStrs);

        indexerFactory = ValueIndexerFactory.initialize(homeDirStrs);
        initializeFromOptions();

        String inputQueueName = getSqsParm(options, "sqs-in", VIRGO4_MARC_CONVERT_IN_QUEUE);
        String s3BucketName = getSqsParm(options, "s3", VIRGO4_SQS_MESSAGE_BUCKET);
        logger.info("Opening input queue: "+ inputQueueName + ((s3BucketName != null) ? " (with S3 bucket: "+ s3BucketName + " )" : ""));
        this.configureReader(inputQueueName, s3BucketName);
    }

    public Indexer getIndexerForJunitTest()
    {
        return indexer;
    }

    public MarcReader getReaderForJunitTest()
    {
        return reader;
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
        logger.info("Opening input queue: "+ inputQueueName + ((s3BucketName != null) ? " (with S3 bucket: "+ s3BucketName + " )" : ""));
        this.configureReader(inputQueueName, s3BucketName);

        this.processInput();
    }

    @Override
    protected void processAddnlArgs(OptionParser parser)
    {
        parser.accepts("sqs-in", "sqs queue to read records from").withRequiredArg().ofType( String.class );
        parser.accepts("sqs-out", "sqs queue to write solr docs to").withRequiredArg().ofType( String.class );
        parser.accepts("sqs-delete", "sqs queue to write ids to delete to").withRequiredArg().ofType( String.class );
        parser.accepts("s3", "s3 bucket to use for oversize records").withRequiredArg().ofType( String.class );
        parser.accepts("reconfig", "specifies that the indexer can be reconfigured at runtime, providing a mapping from data source name to index specification").withRequiredArg().ofType( String.class );
        if (System.getProperty("solrmarc.indexer.test.fire.method","undefined").equals("undefined"))
        {
            System.setProperty("solrmarc.indexer.test.fire.method", "true");
        }
    }

    private void initializeFromOptions()
    {
        String[] inputSource = new String[1];
        String propertyFileAsURLStr = PropertyUtils.getPropertyFileAbsoluteURL(homeDirStrs, options.valueOf(readOpts), true, inputSource);
        logger.info("marcreader option is "+options.valueOf(readOpts));
        logger.info("propertyFileAsURLStr is "+propertyFileAsURLStr);
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
        reconfigurable = options.has("reconfig");
        if (reconfigurable) 
        {
            String reconfigFile = options.valueOf("reconfig").toString();
            indexSpecMap = PropertyUtils.loadProperties(ValueIndexerFactory.instance().getHomeDirs(), reconfigFile, false, null, null);
        }
        boolean multithread =  sqsOutQueue != null && !options.has("debug") && !reconfigurable ? true : false;
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
        if (indexSpecMap != null && indexSpecMap.containsKey("default") && specs == null)
        {
            specs = indexSpecMap.getProperty("default");
        }
        else if (indexSpecMap != null && !indexSpecMap.containsKey("default"))
        {
            indexSpecMap.put("default", specs);
        }

        this.indexSpecName = "default";
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

    public void reconfigureIndexer(String specSelector)
    {
        if (specSelector.equals(indexSpecName)) 
        {
            return;
        }
        String indexSpecString; 
        logger.info("Received records from different source, re-initializing specs for "+ specSelector);
        if (indexSpecMap.containsKey(specSelector))
        {
            indexSpecString = indexSpecMap.getProperty(specSelector);
        }
        else
        {
            indexSpecString = indexSpecMap.getProperty("default");
        }
        logger.info("Using specifications: "+ indexSpecString);

        try {
            String[] indexSpecs = indexSpecString.split("[ ]*[;,][ ]*");
            File[] specFiles = new File[indexSpecs.length];
            int i = 0;
            for (String indexSpec : indexSpecs)
            {
                File specFile = new File(indexSpec);
                if (!specFile.isAbsolute())
                {
                    specFile = PropertyUtils.findFirstExistingFile(homeDirStrs, indexSpec);
                }
                logger.info("Opening index spec file: " + specFile);
                specFiles[i++] = specFile;
            }
            indexers = indexerFactory.createValueIndexers(specFiles);
            indexer.indexers.clear();
            indexer.indexers.addAll(indexers);

            indexSpecName = specSelector;
        }
        catch (IllegalAccessException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        catch (InstantiationException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        catch (IOException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
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
            try {
                readerConfig = new MarcReaderConfig(readerProps);
            }
            catch(NoClassDefFoundError ncdfe)
            {
                readerConfig = null;
            }
        }
    }

    private void configureReader(String inputQueueName, String s3BucketName)
    {
        try
        {
            if (this.reconfigurable) 
            {
                reader = new MarcSQSReader(readerConfig, inputQueueName, s3BucketName, false, this);
            }
            else 
            {
                reader = new MarcSQSReader(readerConfig, inputQueueName, s3BucketName);
            }
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
        String s3Bucket = getSqsParm(options, "s3", VIRGO4_SQS_MESSAGE_BUCKET);
        boolean oversizeOnly = Boolean.parseBoolean(System.getProperty("solrmarc-sqs-oversize-only", "false"));
        boolean wrapped = false;

        if (sqsOutQueue != null)
        {
            logger.info("Opening output queue: "+ sqsOutQueue + ((s3Bucket != null) ? " (with S3 bucket: "+ s3Bucket + " )" : ""));
            solrProxy = new SolrSQSXMLOutProxy(sqsOutQueue, s3Bucket, oversizeOnly);
            return;
        }
        else if (solrURL.startsWith("wrapped"))
        {
            solrURL = solrURL.replace("wrapped", "");
            wrapped = true;
        }
        if (solrURL.equals("stdout"))
        {
            try
            {
                PrintStream out = new PrintStream(System.out, true, "UTF-8");
                System.setOut(out);
                solrProxy = new StdOutProxy(out);
                if (wrapped) solrProxy = new SolrSQSWrappedProxy(solrProxy);
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
                if (wrapped) solrProxy = new SolrSQSWrappedProxy(solrProxy);
            }
            catch (UnsupportedEncodingException e)
            {
                // since the encoding is hard-coded, and is valid, this Exception cannot occur.
            }
        }
        else if (solrURL.equals("devnull"))
        {
            solrProxy = new DevNullProxy();
            if (wrapped) solrProxy = new SolrSQSWrappedProxy(solrProxy);
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
