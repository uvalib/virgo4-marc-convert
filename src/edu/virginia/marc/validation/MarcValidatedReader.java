package edu.virginia.marc.validation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import javax.xml.transform.stream.StreamSource;

import org.apache.log4j.Logger;
import org.marc4j.MarcReader;
import org.marc4j.MarcXmlReader;
import org.marc4j.marc.Record;
import org.solrmarc.driver.PausableReader;
import org.solrmarc.marc.MarcSQSReader;
import org.solrmarc.marc.RecordPlus;
import org.xml.sax.InputSource;

import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;
import com.github.difflib.text.DiffRow.Tag;

public class MarcValidatedReader implements MarcReader, PausableReader
{
    private final static Logger logger = Logger.getLogger(MarcValidatedReader.class);

    MarcReader wrappedReader;
    ReusuableMarcXmlWriter writerRaw;
    ReusuableMarcXmlWriter writerTransformed;

    public MarcValidatedReader(MarcReader toWrap, String transformFileName)
    {
        wrappedReader = toWrap;
        File transform = new File(transformFileName);
        String transformstr = null;
        try
        {
            transformstr = readStreamIntoString(new FileInputStream(transform));
        }
        catch (IOException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        writerRaw = makeXMLWriter(null);
        writerTransformed = makeXMLWriter(transformstr);

    }

    public MarcValidatedReader(MarcReader toWrap, InputStream transformStream)
    {
        wrappedReader = toWrap;
        String transformstr = null;
        try
        {
            transformstr = readStreamIntoString(transformStream);
        }
        catch (IOException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        writerTransformed = makeXMLWriter(transformstr);
        writerRaw = makeXMLWriter(null);

    }

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

    public static ReusuableMarcXmlWriter makeXMLWriter(String transformString)
    {
        StringWriter swriter = new StringWriter();

        StreamSource transform = null;
        if (transformString != null) 
        {
            transform = new StreamSource(new StringReader(transformString));
        }
        //ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ReusuableMarcXmlWriter writer = transform != null ? new ReusuableMarcXmlWriter(swriter, transform) 
                                                          : new ReusuableMarcXmlWriter(swriter);
        return(writer);
    }

 
    @Override
    public boolean hasNext()
    {
        return (wrappedReader.hasNext());
    }

    @Override
    public Record next()
    {
        Record recordRead = wrappedReader.next();
        RecordPlus toDecorate;
        if (recordRead instanceof RecordPlus)
        {
            toDecorate = (RecordPlus)recordRead;
        }
        else
        {
            toDecorate = new RecordPlus(recordRead);
        }
        
        logger.debug("Transforming record "+ toDecorate.getControlNumber());

        String recordAsXML = getRecordAsXML(toDecorate, writerRaw);
        String recordAsXML2 = getRecordAsXML(toDecorate, writerTransformed);
        if (recordsDifferent(recordAsXML, recordAsXML2))
        {
            MarcReader xmlReader = new MarcXmlReader(new InputSource(new StringReader(recordAsXML2)));
            if (xmlReader.hasNext()) 
            {
                Record fixedRecord = xmlReader.next();
                toDecorate.replaceRecord(fixedRecord);
                logger.debug("Using transformed record "+ toDecorate.getControlNumber());
            }
            toDecorate.addExtraData("originalXML", recordAsXML);
            toDecorate.addExtraData("transformedXML", recordAsXML2);
        }
        else // they are "identical" so you could add either.
        {
            toDecorate.addExtraData("transformedXML", recordAsXML2);
            logger.debug("Not using transformed record "+ toDecorate.getControlNumber());
        }
        return toDecorate;
    }

    public static String unchanged(String str) {
        return str;
    }

    public static Function<String, String> LINE_NORMALIZER_FOR_UNCHANGED = MarcValidatedReader::unchanged;

    private boolean recordsDifferent(String recordAsXML, String recordAsXML2)
    {
        String record11 = recordAsXML.replaceAll("marc:","").replaceAll("><", ">\n<")   .replaceAll(" xmlns(:marc)?=\"http://www.loc.gov/MARC21/slim\"", "").replaceAll(">\n<(sub|/data)", "><$1").replaceAll("<(/)?collection>(\n)?", "");
        record11 = record11.replaceAll("<datafield tag=\"991\"[^\n]*\n", "");

        String record22 = recordAsXML2.replaceAll("marc:","").replaceAll("\n[ ]+", "\n").replaceAll(" xmlns(:marc)?=\"http://www.loc.gov/MARC21/slim\"", "").replaceAll(">\n<(sub|/data)", "><$1").replaceAll("<(/)?collection>(\n)?", "");
        record22 = record22.replaceAll("<datafield tag=\"991\"[^\n]+\n", "");

//        if (!record11.equals(record22))
//        {
//            String[] l1 = record11.split("\n");
//            String[] l2 = record22.split("\n");
//            DiffRowGenerator generator = DiffRowGenerator.create()
//                    .showInlineDiffs(false)
//                    .mergeOriginalRevised(false)
//                    .inlineDiffByWord(true)
//                    .lineNormalizer(LINE_NORMALIZER_FOR_UNCHANGED)
//                    .oldTag(f -> "~")      //introduce markdown style for strikethrough
//                    .newTag(f -> "**")     //introduce markdown style for bold
//                    .build();
//
//            //compute the differences for two test texts.
//            List<DiffRow> rows = generator.generateDiffRows(Arrays.asList(l1), Arrays.asList(l2));
//            
//            for (DiffRow row : rows) 
//            {
//                if (row.getTag() == Tag.EQUAL)
//                {
//                    System.out.println("===" + row.getOldLine());
//                }
//                else if (row.getTag() == Tag.INSERT)
//                {
//                    System.out.println("+++" + row.getNewLine());
//                }
//                else if (row.getTag() == Tag.DELETE)
//                {
//                    System.out.println("---" + row.getOldLine());
//                }
//                else if (row.getTag() == Tag.CHANGE)
//                {
//                    System.out.println(" < " + row.getOldLine());
//                    System.out.println(" > " + row.getNewLine());
//                }
//
//            }
//        }
//        
        return (!record11.equals(record22));
    }

    public static String getRecordAsXML(Record marcRecord, ReusuableMarcXmlWriter writer)
    {
        writer.write(marcRecord);
        String strResult = writer.getRecordAndReset();
        return(strResult);
    }

    @Override
    public boolean isPaused()
    {
        if (wrappedReader instanceof PausableReader)
        {
            boolean result = ((PausableReader)wrappedReader).isPaused();
            if (result) 
            {
                logger.debug("wrapped reader is paused");
            }
            return (result);
        }
        return false;
    }

}
