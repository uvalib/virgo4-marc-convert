package org.solrmarc.mixin;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

import javax.xml.transform.stream.StreamSource;

import org.apache.log4j.Logger;
import org.marc4j.marc.MarcFactory;
import org.marc4j.marc.Record;
import org.solrmarc.index.extractor.impl.fullrecord.FullRecordAsXMLValueExtractor;
import org.solrmarc.index.indexer.ValueIndexerFactory;
import org.solrmarc.marc.RecordPlus;
import org.solrmarc.tools.PropertyUtils;

import com.helger.schematron.ISchematronResource;
import com.helger.schematron.pure.SchematronResourcePure;
import com.helger.schematron.svrl.SVRLFailedAssert;
import com.helger.schematron.svrl.SVRLSuccessfulReport;
import com.helger.schematron.svrl.jaxb.ActivePattern;
import com.helger.schematron.svrl.jaxb.FailedAssert;
import com.helger.schematron.svrl.jaxb.FiredRule;
import com.helger.schematron.svrl.jaxb.SchematronOutputType;
import com.helger.schematron.svrl.jaxb.SuccessfulReport;

import edu.virginia.marc.validation.MarcValidatedReader;
import edu.virginia.marc.validation.ReusuableMarcXmlWriter;

public class SchematronErrors
{
    private final static Logger logger = Logger.getLogger(SchematronErrors.class);
    
    static ISchematronResource aResPure = null;
    static Object semaphore = new Object();
    ReusuableMarcXmlWriter writerRaw = null;
    
    public SchematronErrors()
    {
        
    }
    
    boolean isInited()
    {
        if (aResPure != null) 
        {
            return (true);
        }
        return(false);
    }
    
    private void initializeSchematron(String schematronFileName)
    {
        InputStream schematronStream = PropertyUtils.getPropertyFileInputStream(ValueIndexerFactory.instance().getHomeDirs(), schematronFileName);
        String sResourceID = schematronFileName;
        aResPure = SchematronResourcePure.fromInputStream(sResourceID, schematronStream);
    }
    
    public Collection<String> getErrors(Record record, String schematronFileName)
    {
        Collection<String> result = null;
        logger.debug("Calling Schematron on record "+ record.getControlNumber());
        if (!isInited())
        {
            synchronized (semaphore) 
            {
                if (!isInited())
                {
                    initializeSchematron(schematronFileName);
                }
            }
        }
        String recordName = record.getControlNumber();
        String recordAsXML2 = null;
        if (record instanceof RecordPlus)
        {
            recordAsXML2 = ((RecordPlus)record).getExtraData("transformedXML");
        }
        if (recordAsXML2 == null)
        {
//            recordAsXML2 = FullRecordAsXMLValueExtractor.toXMLString(record, false); 
            if (writerRaw == null)
            {
                writerRaw = MarcValidatedReader.makeXMLWriter(null);
            }
            recordAsXML2 = MarcValidatedReader.getRecordAsXML(record, writerRaw);
        }
        if (recordAsXML2 != null)
        {
            result = getErrorsXMLViaPureSchematron (aResPure, recordAsXML2, record, recordName);
        }
        else
        {
            result = new ArrayList<String>();
        }
        logger.debug("Done with Schematron for record "+ record.getControlNumber());
        return result;
    }
    
    public Collection<String> getWasEdited(Record record)
    {
        Collection<String> result = new ArrayList<String>();
        String recordAsXML1 = null;
        String recordAsXML2 = null;
        if (record instanceof RecordPlus)
        {
            recordAsXML1 = ((RecordPlus)record).getExtraData("originalXML");
            recordAsXML2 = ((RecordPlus)record).getExtraData("transformedXML");
            if (recordAsXML1 != null && recordAsXML2 != null)
            {
                // was edited by XSLT transform
                result.add("Edited");
            }
        }

        return result;
    }

    
    private Collection<String> getErrorsXMLViaPureSchematron(ISchematronResource aResPure, String recordAsXMLStr, Record record, String recordName)
    {
        StreamSource sss;
        List<String> result = new ArrayList<String>();

        try
        {
            sss = new StreamSource(new StringReader(recordAsXMLStr));
            SchematronOutputType ot = null;
            synchronized (semaphore) {
                ot = aResPure.applySchematronValidationToSVRL(sss);
            }
            List<Object> errs = ot.getActivePatternAndFiredRuleAndFailedAssert();
            ActivePattern ap = null;
            FiredRule fr = null;
            for (Object obj : errs)
            {
                if (obj instanceof FiredRule) 
                {
                    fr = (FiredRule)obj;
                }
                else if (obj instanceof ActivePattern)
                {
                    ap = (ActivePattern)obj;
                }
                else if (obj instanceof FailedAssert)
                {
                    FailedAssert fa = (FailedAssert)obj;
                    SVRLFailedAssert sfa = new SVRLFailedAssert(fa);
                    String translatedLocation = translateLocation(sfa.getLocation(), recordAsXMLStr);
                    result.add(sfa.getFlag().toString() + " : " + translatedLocation + " : " + sfa.getText().replaceAll("\n"," "));
                  //  System.out.println(translatedLocation);
                }
                else if (obj instanceof SuccessfulReport)
                {
                    SuccessfulReport fa = (SuccessfulReport)obj;
                    SVRLSuccessfulReport sfa = new SVRLSuccessfulReport(fa);
                    String translatedLocation = translateLocation(sfa.getLocation(), recordAsXMLStr);
                    result.add(sfa.getFlag().toString() + " : " + translatedLocation + " : " + sfa.getText().replaceAll("\n"," "));
                   // System.out.println(translatedLocation);
                }
                else 
                {
                   // System.out.println(obj.toString());
                }
            }
            return(result);
        }
        catch (Exception e)
        {
            result.add("Exception "+e.getClass().toString() +" while processing record via schematron : "+ e.getMessage());
        }
        return(result);
    }


    private static String translateLocation(String location, String recordAsXML)
    {
        String recordAsXmlparsed = recordAsXML.replaceAll("><marc:",">\n<marc:").replaceAll("marc:",  "").replaceAll("\n[ ]+", "\n").replaceAll(" xmlns(:marc)?=\"http://www.loc.gov/MARC21/slim\"", "").replaceAll(">\n<(sub|/data)", "><$1");
        String[] recordlines = recordAsXmlparsed.split("\n");
        if (location.matches(".*controlfield.*"))
        {
            String sNum = location.replaceFirst(".*controlfield\\[([0-9]+)].*", "$1");
            int num = Integer.parseInt(sNum);
            String line = null;
            for (int i = 0, j = 0; i < recordlines.length; i++)
            {
                line = recordlines[i];
                if (line.contains("<controlfield"))
                {
                    if (j == num-1) 
                    {
                        return(line);
                    }
                    j++;
                }
            }
        }
        else if (location.matches(".*datafield.*"))
        {
            String sNum = location.replaceFirst(".*datafield\\[([0-9]+)].*", "$1");
            int num = Integer.parseInt(sNum);
            String line = null;
            for (int i = 0, j = 0; i < recordlines.length; i++)
            {
                line = recordlines[i];
                if (line.contains("<datafield"))
                {
                    if (j == num-1) 
                    {
                        return(line);
                    }
                    j++;
                }
            }
        }
        else if (location.matches(".*leader"))
        {
            String line = null;
            for (int i = 0; i < recordlines.length; i++)
            {
                line = recordlines[i];
                if (line.contains("<leader>"))
                {
                    return(line);
                }
            }
        }
        return location;
    }   
}    
    
