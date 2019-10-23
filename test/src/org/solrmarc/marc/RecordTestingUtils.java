package org.solrmarc.marc;

import static org.junit.Assert.*;

import org.marc4j.MarcError;
import org.marc4j.marc.*;

import java.io.*;
import java.util.*;

/**
 * Methods to assert when Record objects are equal or not, etc.
 * @author naomi
 *
 */
public class RecordTestingUtils
{

    /**
     * Assert that each instance of the subfield is in the expected values
     *  and that the number of instances match.
     */
    public static void assertSubfieldHasExpectedValues(Record record, String fieldTag, char subfieldCode, String ... expectedVals)
    {
        Set<String> expectedSet = new LinkedHashSet<String>();
        expectedSet.addAll(Arrays.asList(expectedVals));
        Set<String> resultSet = new LinkedHashSet<String>();
        for (VariableField vf : record.getVariableFields(fieldTag))
        {
            DataField df = (DataField) vf;
            List<Subfield> sfList = df.getSubfields(subfieldCode);
            for (Iterator<Subfield> iter2 = sfList.iterator(); iter2.hasNext();)
            {
                Subfield sf = (Subfield) iter2.next();
                String val = sf.getData();
                resultSet.add(val);
                assertTrue("Got unexpected value " + val, expectedSet.contains(val));
            }
        }
        org.junit.Assert.assertEquals("Number of values doesn't match", expectedSet.size(), resultSet.size());
    }

    /**
     * Assert that the given record noted an error containing the specified message
     */
    public static void assertRecordHasErrorMatching(Record record, String errorMessage)
    {
        if (record.hasErrors())
        {
            List<MarcError> errors = record.getErrors();
            for (MarcError error : errors) 
            {
                if (error.message.contains(errorMessage)) 
                {
                    // found expected error message, return and be happy
                    return;
                }
            }
        }
        fail("Didn't find expected an error message stating: " + errorMessage );
    }

   /**
     * Assign id of record to be the ckey. Our ckeys are in 001 subfield a.
     * Marc4j is unhappy with subfields in a control field so this is a kludge
     * work around.
     */
    public static String getRecordIdFrom001(Record record)
    {
        String id = null;
        ControlField fld = (ControlField) record.getVariableField("001");
        if (fld != null && fld.getData() != null)
        {
            String rawVal = fld.getData();
            // 'u' is for testing
            if (rawVal.startsWith("a") || rawVal.startsWith("u"))
                id = rawVal.substring(1);
        }
        return id;
    }
}
