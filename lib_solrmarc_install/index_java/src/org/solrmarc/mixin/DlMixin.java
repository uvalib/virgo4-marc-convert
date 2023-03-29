package org.solrmarc.mixin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.net.*;
import java.io.*;
import javax.json.*;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import org.apache.log4j.Logger;
import org.marc4j.marc.Record;
import org.solrmarc.driver.SQSQueueDriver;
import org.solrmarc.index.SolrIndexerMixin;
import org.solrmarc.index.indexer.IndexerSpecException;
import org.solrmarc.index.indexer.ValueIndexerFactory;
import org.solrmarc.tools.PropertyUtils;

public class DlMixin extends SolrIndexerMixin
{
    private static final JsonObject NULL_JSON_OBJECT = Json.createObjectBuilder().add("value", "null").build();
    private final static Logger logger = Logger.getLogger(DlMixin.class);

    private static final String JSON_SERVICE_URL = "https://tracksys-api-ws.internal.lib.virginia.edu/api/enriched/sirsi/";
    private static final String JSON_SERVICE_URL2 = "https://tracksys-api-ws.internal.lib.virginia.edu/api/published/virgo?type=sirsi";

    private static final ConcurrentHashMap<String, String> publishedMap = new ConcurrentHashMap<String, String>();

    public final static String s3Bucket = "s3://";
    public final static String s3UrlPrefix = "https://s3.us-east-1.amazonaws.com/";
    public static long lastInitializedTimeStamp = 0L;
    public final static long ONE_HOUR_IN_MILLISECONDS = (1000L * 60 * 60 * 1);
    // "https://s3.us-east-1.amazonaws.com/digital-content-metadata-cache-staging/u6288359"


    private static String readStreamIntoString(InputStream stream) throws IOException
    {
        Reader in = new BufferedReader(new InputStreamReader(stream));
        
        StringBuilder sb = new StringBuilder();
        char[] chars = new char[4096];
        int length;
        
        while ((length = in.read(chars)) > 0)
        {
            sb.append(chars, 0, length);
        }
        return sb.toString();
    }

    static void initializePublishedMap()
    {
//        logger.info("Initializing PublishedMap");
        synchronized (publishedMap)
        {
            long now = java.lang.System.currentTimeMillis();
            if (publishedMap.size() == 0 || now - lastInitializedTimeStamp > ONE_HOUR_IN_MILLISECONDS)
            {
                logger.info(publishedMap.size() == 0 ? "Initializing PublishedMap" : "Re-Initializing PublishedMap after 1 hour");
                lastInitializedTimeStamp = now; 
                try
                {
                    final URL url = new URL(JSON_SERVICE_URL2);
                    InputStream urlInputStream = url.openStream();
                    
                    try
                    {
                        String allIds = readStreamIntoString(urlInputStream).replaceFirst("^[^,]*,[^:]*:.", "");
                        int offset = 0;
                        int endoffset;
                        do
                        {
                            endoffset = allIds.indexOf(',', offset);
                            String id = allIds.substring(offset, endoffset == -1 ? allIds.length() : endoffset);
                            id = id.replaceFirst("\"([^\"]*)\".*", "$1");
                            publishedMap.putIfAbsent(id, "");
                            offset = endoffset + 1;
                        } while (endoffset != -1);
                    }
                    finally
                    {
                        urlInputStream.close();
                    }
                }
                catch (IOException ioe)
                {
                    throw new RuntimeException(ioe);
                }
            }
        }
        logger.info("PublishedMap initialized, size is " + publishedMap.size()+ " items");
    }

    private Cache<String, JsonObject> cache = new Cache<String, JsonObject>(64);

    private synchronized JsonObject lookupSirsiId(final Record record)
    {
        final String id = record.getControlNumber();
        long now = java.lang.System.currentTimeMillis();
        if (publishedMap.size() == 0 || now - lastInitializedTimeStamp > ONE_HOUR_IN_MILLISECONDS)
        {
            initializePublishedMap();
            lastInitializedTimeStamp = now; 
        }
        if (!publishedMap.containsKey(id))
        {
            return (null);
        }
        if (cache.containsKey(id))
        {
            return decodeNull(cache.get(id));
        }
        final String urlStr = JSON_SERVICE_URL + id;
        InputStream urlInputStream = null;
        JsonObject jsonObject = null;
        try
        {
            final URL url = new URL(urlStr);
            urlInputStream = url.openStream();
            jsonObject = Json.createReader(urlInputStream).readObject();
            cache.put(id, jsonObject);
        }
        catch (IOException ex)
        {
            cache.put(id, encodeNull(null));
            logger.warn(urlStr + " not found, so " + id + " must not be in the tracksys system");
        }
        finally
        {
            if (urlInputStream != null) 
            {
                try { 
                    urlInputStream.close();
                }
                catch (IOException ioe)
                {}
            }
        }
        return jsonObject;
    }

    private JsonObject decodeNull(JsonObject o)
    {
        if (o == NULL_JSON_OBJECT)
        {
            return null;
        }
        return o;
    }

    private JsonObject encodeNull(JsonObject o)
    {
        if (o == null)
        {
            return NULL_JSON_OBJECT;
        }
        return o;
    }

    private boolean isInDL(final Record record)
    {
        return lookupSirsiId(record) != null;
    }

    public Set<String> getFormatFacetsToAdd(final Record record)
    {
        Set<String> result = new HashSet<String>();
        if (isInDL(record))
        {
            result.add("Online");
        }
        return result;
    }

    public Set<String> getSourceFacetsToAdd(final Record record)
    {
        Set<String> result = new HashSet<String>();
        if (isInDL(record))
        {
            result.add("UVA Library Digital Repository");
        }
        return result;
    }

    public Set<String> getFeatureFacetsToAdd(final Record record)
    {
        Set<String> result = new HashSet<String>();
        final JsonObject dlSummary = lookupSirsiId(record);
        if (dlSummary != null)
        {
            result.add("availability");
            result.add("iiif");
            result.add("dl_metadata");
            result.add("rights_wrapper");
            if (dlSummary.getString("pdfServiceRoot") != null)
            {
                result.add("pdf_service");
            }
        }
        return result;
    }

    public List<String> getThumbnailUrlDisplay(final Record record)
    {
        try
        {
            List<String> result = new ArrayList<String>();
            final JsonObject dlSummary = lookupSirsiId(record);
            if (dlSummary != null)
            {
                for (JsonValue item : dlSummary.getJsonArray("items"))
                {
                    String thumbnailURL;
                    if (item != null)
                    {
                        JsonValue jv = ((JsonObject) item).get("thumbnailUrl");
                        if (jv != null)
                        {
                            thumbnailURL = jv.toString();
                            if (thumbnailURL != null)
                            {
                                result.add(thumbnailURL);
                            }
                        }
                    }
                }
            }
            return result;
        }
        catch (NullPointerException npe)
        {
            npe.printStackTrace();
            throw (npe);
        }
    }

    public List<String> getPdfUrlDisplay(final Record record)
    {
        List<String> result = new ArrayList<String>();
        final JsonObject dlSummary = lookupSirsiId(record);
        if (dlSummary != null && dlSummary.getString("pdfServiceRoot") != null)
        {
            result.add(dlSummary.getString("pdfServiceRoot"));
        }
        return result;
    }

    public List<String> getPdfDownloadUrlDisplay(final Record record)
    {
        final JsonObject dlSummary = lookupSirsiId(record);
        if (dlSummary != null)
        {
            return getPdfDownloadUrlDisplay(dlSummary);
        }
        return new ArrayList<String>();
    }

    public List<String> getPdfDownloadUrlDisplay(JsonObject dlSummary)
    {
        List<String> result = new ArrayList<String>();
        if (dlSummary != null && dlSummary.getString("pdfServiceRoot") != null)
        {
            String pdfRoot = dlSummary.getString("pdfServiceRoot");
            JsonArray itemArr = dlSummary.getJsonArray("items");
            if (itemArr.size() == 1)
            {
                JsonObject item = itemArr.getJsonObject(0);
                String statusURLstr = pdfRoot + "/" + item.getString("pid") + "/status";
                URL statusURL;
                try
                {
                    statusURL = new URL(statusURLstr);
                    StringBuilder sb = new StringBuilder();
                    int code;
                    code = getContentsOfURL(statusURL, sb);
                    if (code == 200 && sb.toString().equals("READY"))
                    {
                        logger.info("Status from URL "+statusURLstr+": code = "+code+" Result = "+sb.toString());
                        String downloadURLstr = pdfRoot + "/" + item.getString("pid") + "/download";
                        result.add(downloadURLstr);
                    }
                }
                catch (MalformedURLException e1)
                {
                    logger.info("Malformed URL "+statusURLstr+": assuming PDF download is not available");
                }
                catch (IOException e)
                {
                    logger.info("Error reading from URL "+statusURLstr+": assuming PDF download is not available");
                }
            }
        }
        return result;
    }

    public List<String> getRightsStatementURI(final Record record)
    {
        List<String> result = new ArrayList<String>();
        final JsonObject dlSummary = lookupSirsiId(record);
        if (dlSummary != null)
        {
            for (JsonValue item : dlSummary.getJsonArray("items"))
            {
                if (((JsonObject) item).getString("rsURI") != null)
                {
                    result.add(((JsonObject) item).getString("rsURI"));
                }
            }
        }
        return result;
    }

    public List<String> getAlternateIds(final Record record)
    {
        List<String> result = new ArrayList<String>();
        final JsonObject dlSummary = lookupSirsiId(record);
        if (dlSummary != null)
        {
            for (JsonValue item : dlSummary.getJsonArray("items"))
            {
                if (((JsonObject) item).getString("pid") != null)
                {
                    result.add(((JsonObject) item).getString("pid"));
                }
            }
        }
        return result;
    }

    public List<String> getCallNumbers(final Record record)
    {
        List<String> result = new ArrayList<String>();
        final JsonObject dlSummary = lookupSirsiId(record);
        if (dlSummary != null)
        {
            for (JsonValue item : dlSummary.getJsonArray("items"))
            {
                if (((JsonObject) item).getString("callNumber") != null)
                {
                    result.add(((JsonObject) item).getString("callNumber"));
                }
            }
        }
        return result;
    }

    public List<String> getAdditionalCollectionFacets(final Record record)
    {
        List<String> result = new ArrayList<String>();
        final JsonObject dlSummary = lookupSirsiId(record);
        if (dlSummary != null && dlSummary.getJsonString("collection") != null)
        {
            result.add(dlSummary.getString("collection"));
        }
        return result;
    }

    /**
     * All of the Gannon collection was despined and requires special metadata in
     * order to display a note to Virgo users.
     */
    public List<String> getDespinedBarcodesDisplay(final Record record)
    {
        List<String> result = new ArrayList<String>();
        final JsonObject dlSummary = lookupSirsiId(record);
        if (dlSummary != null && dlSummary.getJsonString("collection") != null
                && "Gannon Collection".equals(dlSummary.getString("collection")))
        {
            for (JsonValue item : dlSummary.getJsonArray("items"))
            {
                final String barcode = ((JsonObject) item).getString("barcode");
                if (barcode != null)
                {
                    result.add(barcode);
                }
            }
        }
        return result;
    }

    private int getContentsOfURL(URL url, StringBuilder sb) throws IOException
    {
        HttpURLConnection connection = null;
        int code = 0;
        BufferedReader in = null;
        try
        {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            ((HttpURLConnection) connection).setInstanceFollowRedirects(true);
            connection.connect();
            code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK)
            {
                in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                boolean atStart = true;
                while ((inputLine = in.readLine()) != null)
                {
                    if (atStart)
                        sb.append(inputLine);
                    else sb.append("\n" + inputLine);
                }
            }
        }
        catch (IOException e)
        {
            try
            {
                code = connection.getResponseCode();
                logger.info("Exception connection to URL " + url.toString() + "  response code is " + code);
            }
            catch (IOException e1)
            {}
            if (code != HttpURLConnection.HTTP_UNAVAILABLE && code != HttpURLConnection.HTTP_OK)
            {
                throw e;
            }
            if (code == HttpURLConnection.HTTP_OK)
            {
                e.printStackTrace();
            }
        }
        finally
        {
            if (connection != null) connection.disconnect();
            if (in != null)
            {
                try
                {
                    in.close();
                }
                catch (IOException e)
                {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
        return (code);
    }

    public List<String> getIIIFManifestURL(final Record record) throws Exception
    {
        List<String> result = new ArrayList<String>();
        final JsonObject dlSummary = lookupSirsiId(record);
        if (dlSummary != null)
        {
            for (JsonValue item : dlSummary.getJsonArray("items"))
            {
                String backendManifestURL = ((JsonObject) item).getString("backendIIIFManifestUrl");
                if (backendManifestURL != null)
                {
                    result.add(backendManifestURL);
                }
            }
        }
        return result;
    }

    public List<String> makeDigitalContentCache(final Record record, String templateFile)
            throws Exception
    {
        List<String> result = new ArrayList<String>();
        final JsonObject dlSummary = lookupSirsiId(record);
        if (dlSummary != null)
        {
            String cacheURL = createDigitalContentCacheEntry(record.getControlNumber(), dlSummary, templateFile);
            if (cacheURL != null)
            {
                result.add(cacheURL);
            }
        }
        return (result);
    }

    private String createDigitalContentCacheEntry(String catKey, JsonObject dlSummary, String templateFile) throws Exception
    {
        String s3BucketName = SQSQueueDriver.DlMixin_S3_BucketName;
        if (s3BucketName == null || s3BucketName.length() == 0)
        {
            logger.warn("No bucket name defined.  Not creating digital content cache entry.");
            return(null);
        }
        String[] template = readTemplate(templateFile);
        String digitalContent = createMultiPidMetadataContent(catKey, dlSummary, template);
        if (digitalContent != null)
        {
            String urlstring = storeCacheData(digitalContent, s3BucketName, catKey);
            return (urlstring);
        }
        return (null);
    }

    private String storeCacheData(String digitalContent, String s3BucketName, String catKey)
    {
        logger.info("Uploading tracksys cache for " + catKey + " to S3 bucket " + s3Bucket + s3BucketName + "...\n");
        String urlStrWrite = s3UrlPrefix + s3BucketName + "/" + catKey;
        final AmazonS3 s3 = AmazonS3ClientBuilder.standard().build();
        try
        {
            s3.putObject(s3BucketName, catKey, digitalContent);
        }
        catch (AmazonServiceException e)
        {
            System.err.println(e.getErrorMessage());
            return null;
        }
        return (urlStrWrite);
    }


//<	{
//<	    "id":"${CATKEY}",
//<	    "parts": [
//=      {
//=        "iiif_manifest_url": "${MANIFEST}",
//=        "oembed_url": "https://curio.lib.virginia.edu/oembed?url=https://curio.lib.virginia.edu/view/${PID}",
//=        "label": "${CALLNUM}",
//=        "ocr": {
//=          "urls": {
//=            "delete": "https://ocr-service-dev.lib.virginia.edu/ocr/${PID}/delete",
//=            "download": "https://ocr-service-dev.lib.virginia.edu/ocr/${PID}/text",
//=            "generate": "https://ocr-service-dev.lib.virginia.edu/ocr/${PID}",
//=            "status": "https://ocr-service-dev.lib.virginia.edu/ocr/${PID}/status"
//=          }
//=        },
//=        "pdf": {
//=          "urls": {
//=            "delete": "${PDF}/${PID}/delete",
//=            "download": "${PDF}/${PID}/download",
//=            "generate": "${PDF}/${PID}",
//=            "status": "${PDF}/${PID}/status"
//=          }
//=        },
//=        "pid": "${PID}",
//=        "thumbnail_url":"${THUMBNAIL}"
//=      }
//?      ,
//>	  ]
//>	}
//
    private String[] readTemplate(String templateFile)
    {
        List<String> result = new ArrayList<String>();
        InputStream templateStream = PropertyUtils
                .getPropertyFileInputStream(ValueIndexerFactory.instance().getHomeDirs(), templateFile);
        BufferedReader reader = new BufferedReader(new InputStreamReader(templateStream));
        String line;
        try
        {
            while ((line = reader.readLine()) != null)
            {
                result.add(line);
            }
        }
        catch (FileNotFoundException e)
        {
            throw new IndexerSpecException("Unable to open lookup file " + templateFile);
        }
        catch (IOException e)
        {
            throw new IndexerSpecException("Unable to read lookup file " + templateFile);
        }
        return (result.toArray(new String[result.size()]));
    }

    private String createMultiPidMetadataContent(String catKey, JsonObject dlSummary, String[] template)
    {
        // build the dataset for the template generation
        String oembed_Root = SQSQueueDriver.DlMixin_Oembed_Root;
        String ocrRoot = SQSQueueDriver.DlMixin_OCR_Root;

        StringBuilder sb = new StringBuilder();
        int iteration = 0;
        Map<String, String> valueMap = new LinkedHashMap<String, String>();
        String pdfServiceRoot = dlSummary.getString("pdfServiceRoot");
        valueMap.put("CATKEY", catKey);
        valueMap.put("OEMBED", oembed_Root);
        valueMap.put("OCR", ocrRoot);
        valueMap.put("PDF", pdfServiceRoot);
        handleTemplate(sb, template, "<", valueMap);
        for (JsonValue item : dlSummary.getJsonArray("items"))
        {
            valueMap.put("PID", (((JsonObject) item).getString("pid")));
            valueMap.put("CALLNUM", (((JsonObject) item).getString("callNumber")));
            valueMap.put("MANIFEST", (((JsonObject) item).getString("backendIIIFManifestUrl")));
            valueMap.put("THUMBNAIL", (((JsonObject) item).getString("thumbnailUrl")));
            if (iteration > 0) handleTemplate(sb, template, "?", valueMap);
            handleTemplate(sb, template, "=", valueMap);
            iteration++;
        }
        handleTemplate(sb, template, ">", valueMap);
        return sb.toString();
    }


    private void handleTemplate(StringBuilder sb, String[] template, String prefixStr, Map<String, String> valueMap)
    {
        for (String templateLine : template)
        {
            char start = templateLine.charAt(0);
            String templateRest = templateLine.substring(1);
            if (prefixStr.charAt(0) == start)
            {
                handleTemplateLine(sb, templateRest, valueMap);
            }
        }
    }


    private void handleTemplateLine(StringBuilder sb, String templateRest, Map<String, String> valueMap)
    {
        int offset = 0;
        // if template line starts with '?'   the variable immediately after that must be defined or the line will be skipped
        if (templateRest.charAt(0) == '?') 
        {
            int ifend = templateRest.indexOf(' ');
            String onlyif = templateRest.substring(1, ifend).replaceFirst("^[$][{]", "").replaceFirst("[}]$", "");
            if (!valueMap.containsKey(onlyif) || valueMap.get(onlyif).length() == 0)
                return;
            templateRest = templateRest.substring(ifend);
        }
        int rep = templateRest.indexOf("${", offset);
        while (rep > 0)
        {
            sb.append(templateRest.substring(offset, rep));
            int endOffset = templateRest.indexOf("}", offset);
            String toReplace = templateRest.substring(rep + 2, endOffset);
            if (valueMap.containsKey(toReplace))
            {
                sb.append(valueMap.get(toReplace));
            }
            offset = endOffset + 1;
            rep = templateRest.indexOf("${", offset);
        }
        sb.append(templateRest.substring(offset)).append("\n");
    }

    private static class Cache<K, V> extends LinkedHashMap<K, V>
    {
        private int size;
        
        public Cache(int size)
        {
            super(16, 0.75f, true);
            this.size = size;
        }
        
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest)
        {
            return this.size() >= size;
        }
    }
}
