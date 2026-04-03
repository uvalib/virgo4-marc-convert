package org.solrmarc.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.marc4j.marc.Record;
import org.marc4j.marc.DataField;
import org.marc4j.marc.Subfield;
import org.marc4j.marc.VariableField;
import org.solrmarc.index.SolrIndexerMixin;
import org.solrmarc.index.extractor.formatter.FieldFormatterBase;
import org.solrmarc.index.extractor.formatter.FieldFormatter;
import org.solrmarc.index.extractor.formatter.FieldFormatter.eCleanVal;
import org.solrmarc.index.extractor.formatter.FieldFormatter.eJoinVal;
import org.solrmarc.index.extractor.impl.direct.DirectMultiValueExtractor;
import org.solrmarc.index.extractor.impl.direct.ModifyableMultiValueExtractor;
import org.solrmarc.index.indexer.IndexerSpecException;
import org.solrmarc.index.indexer.ValueIndexerFactory;
import org.solrmarc.index.mapping.AbstractMultiValueMapping;
import org.solrmarc.index.specification.AbstractSpecificationFactory;
import org.solrmarc.index.specification.CompositeSpecification;
import org.solrmarc.index.specification.SingleDataFieldSpecification;
import org.solrmarc.index.specification.Specification;
import org.solrmarc.tools.Utils;
import org.solrmarc.tools.DataUtil;

/***
 * 
 * @author rh9ec
 *  author_tsearch = 100abcdeq4:110abcde4:111acdejnq4:LNK100abcdeq4:LNK110abcde4:LNK111acdejnq4, cleanEnd, relator_map.properties, unique
 *  author_a = 100abcdeq4:110abcde4:111acdeq4, relator_map.properties, cleanEnd, unique
 *  author_vern_a = LNK100ab:LNK110a:LNK111a:LNK700a:LNK710a:LNK711a, relator_map.properties, clean, unique
 *  author_director_tsearchf_stored = custom(org.solrmarc.mixin.DirectorMixin), getVideoDirector
 *  #author_f = 100abcdeq4:110abcde4:111acdejnq4:700abcdeq4:710acdejq4:711acdejq4:800abcdeq4:810abcde4:811acdejq4:773a, cleanEnd
 *  #author_facet = 100abcdeq4:110abcde4:111acdejnq4:700abcdeq4:710acdejq4:711acdejq4:800abcdeq4:810abcde4:811acdejq4:773a, cleanEnd
 *  author_facet_f_stored = 100abcdeq4:110abcde4:111acdejnq4:700abcdeq4:710acdejq4:711acdejq4:800abcdeq4:810abcde4:811acdejq4:773a, cleanEach, format("d=> [%d]", "e=>(%d)", "4=>(%d)"), relator_map.properties, join(" ")
 *  author_added_entry_tsearch_stored = 700abcdeq4:710acdejq4:711acdejq4:800abcdeq4:810abcde4:811acdejq4:773a:LNK700abcdeq4:LNK710acdejq4:LNK711acdejq4:LNK800abcdeq4:LNK810abcde4:LNK811acdejq4:LNK773a, clean, relator_map.properties, unique
 *  author_ssort_stored = 100abcdeq4:110abcde4:111acdeq4, titleSortLower, relator_map.properties, first
    author_mixin = getAuthorFieldsWithRole("author_mixin", "100abcdeq4:110abcde4:111acdejnq4:LNK100abcdeq4:LNK110abcde4:LNK111acdejnq4", "", "cleanEnd, relator_map3.properties($4), unique")
 */


public class AuthorMixin extends SolrIndexerMixin
{
    private static Map<String, DirectMultiValueExtractor> extractorMap = null;
   
    public static class AuthorFieldFormatter extends FieldFormatterBase {
        DataField df = null;
        String roleFormat;
        EnumSet<eCleanVal> roleCleanVal;
        
        public AuthorFieldFormatter(String roleFormat, boolean clean) {
            super(clean);
            this.roleFormat = roleFormat;
            this.df = null;
            roleCleanVal = EnumSet.noneOf(eCleanVal.class);
            roleCleanVal.add(eCleanVal.CLEAN_EACH);
        }
        
        @Override
        public String cleanData(VariableField vf, boolean isSubfieldA, String data)
        {
            String str = super.cleanData(vf,  isSubfieldA, data);
            str = DataUtil.cleanByVal(str, roleCleanVal);
            return(str);
        }
        
        @Override
        public Collection<String> prepData(VariableField vf, char sfCode, String data) throws Exception
        {
            if (this.df == null && vf != null && vf instanceof DataField) 
            {
                this.df = ((DataField)vf);
            }
            return (super.prepData(vf, sfCode, data));
        }
        /*
         *         
         *         
        for (Subfield subfield : df.getSubfields())
        {
            final String codeStr = "" + subfield.getCode();
            if (subfieldPattern.equals(dotPattern) || subfieldPattern.matcher(codeStr).matches())
            {
                fmt.addSeparator(sb,cnt);
                //fmt.addCode(sb,codeStr);
                Collection<String> prepped = fmt.prepData(vf, subfield.getCode(), subfield.getData());
                for (String val : prepped)
                {
                    val = fmt.handleSubFieldFormat(codeStr, vf, val);
                    fmt.addVal(sb, codeStr, val);
                    fmt.addAfterSubfield(sb, result);
                }
                cnt++;
            }
        }
        fmt.addAfterField(sb, result);
         */
        
        
        @Override
        public void addAfterField(StringBuilder sb, Collection<String> result)
        {
            Collection<String> roles = new LinkedHashSet<String>();
            
            for (Subfield subfield : df.getSubfields())
            {
                try {
                    if (subfield.getCode() == 'e') 
                    {
                        roles.addAll(prepData(df, subfield.getCode(), subfield.getData()));
                    }
                    else if (subfield.getCode() == '4')
                    {
                        roles.addAll(prepData(df, subfield.getCode(), subfield.getData()));
                    }
                }
                catch (Exception e) {
                    // As Weird Al says, "Just Eat It!"
                }
            }
            for (String role : roles)
            {
                String output = roleFormat.replaceAll("%d",  role);
                sb.append(output);
            }
            super.addAfterField(sb, result);
        }
    }
 
    private DirectMultiValueExtractor buildExtractor(String specName, String fieldSpecs, String roleFormat, String formatSpec)
    {
        if (extractorMap == null) { 
            extractorMap = new LinkedHashMap<>();
        }
        if (extractorMap.containsKey(specName)) {
            return(extractorMap.get(specName));
        }
        List<IndexerSpecException> currentExceptions = new ArrayList<>();
        CompositeSpecification specification = null;
        DirectMultiValueExtractor extractor = null;
        specification = new CompositeSpecification();
        String specs[] = fieldSpecs.split(":");
        if (roleFormat.equals(""))
        {
            roleFormat = ", %d";
        }
        for (String spec : specs) 
        {
            FieldFormatter fmt = new AuthorFieldFormatter(roleFormat, false).setJoinVal(eJoinVal.JOIN).setSeparator(" ");
            Specification s;
            if (spec.startsWith("LNK"))
            {
                s = new SingleDataFieldSpecification(spec.substring(0,6),  spec.substring(6), null, fmt);
            }
            else 
            {
                s = new SingleDataFieldSpecification(spec.substring(0,3),  spec.substring(3), null, fmt);
            }
            specification.addSpec(s);
        }
        extractor = new DirectMultiValueExtractor(specification);
        if (formatSpec != null) 
        {
            String[] formatSpecs = formatSpec.split(",");
            List<List<String>>mapParts = new ArrayList<List<String>>();
            for (String part : formatSpecs)
            {
                mapParts.add(Collections.singletonList(part));
            }
            decorateMultiValueExtractor("", specName, extractor, mapParts, currentExceptions);
           // AbstractValueIndexer<?> indexer = makeMultiValueIndexer("", specName, extractor, mapParts, currentExceptions);
        }   
        return extractor;
    }
    
    
    public Collection<String> getAuthorFieldsWithRole(Record record, String specName, String fieldSpecs, String roleFormatting, String formatSpec)
    {
        DirectMultiValueExtractor extractor = this.buildExtractor(specName, fieldSpecs, roleFormatting, formatSpec);

        Collection<String> result = new ArrayList<String>(); 
        try
        {
            extractor.extract(result, record);
        }
        catch (Exception e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return(result);
    }

    public Collection<String> getAuthorFields(Record record, String specName, String fieldSpec, String formatSpec )
    {
        DirectMultiValueExtractor extractor = this.buildExtractor(specName, fieldSpec, "", formatSpec);
        Collection<String> result = new ArrayList<String>(); 
        try
        {
            extractor.extract(result, record);
        }
        catch (Exception e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return(result);
    }
    
    private boolean isACollectorConfiguration(String string)
    {
        if (string.equals("unique") || string.equals("first") || string.equals("sort") || string.equals("notunique") ||
            string.equals("notfirst") || string.equals("all") || string.equals("DeleteRecordIfFieldEmpty") ||
            string.equals("DeleteRecordIfFieldNotEmpty") || string.equals("SkipRecordIfFieldEmpty") || string.equals("SkipRecordIfFieldNotEmpty") ||
            string.equals("normalize") || string.equals("unnormalize"))
            return (true);
        return (false);
    }

    public int decorateMultiValueExtractor(String origSpec, String fieldnames, ModifyableMultiValueExtractor multiValueExtractor, List<List<String>> mapSpecs, List<IndexerSpecException> currentExceptions)
    {
        if (mapSpecs.size() == 0)
        {
            return -1;
        }

        int currentIndex = 0;
        int joinIndex = -1;
        EnumSet<eCleanVal> tmpVal;
        for (List<String> mapSpec : mapSpecs)
        {
            String[] mapParts = (String[]) mapSpec.toArray(new String[0]);
            if (isACollectorConfiguration(mapParts[0]))
            {
                /* ignore, handle it elsewhere */
            }
            else if (mapParts[0].equals("join"))
            {
                multiValueExtractor.setJoinVal(eJoinVal.JOIN);
                if (mapParts.length > 1)
                {
                    multiValueExtractor.setSeparator(mapParts[1]);
                }
                joinIndex = currentIndex;
            }
            else if (mapParts[0].equals("separate"))
            {
                multiValueExtractor.setJoinVal(eJoinVal.SEPARATE);
            }
            else if (mapParts[0].equals("format"))
            {
                if (mapParts.length > 1)
                {
                    multiValueExtractor.setFormatPatterns(mapParts);
                }
            }
            else if (mapParts[0].equals("substring"))
            {
                try {
                    if (mapParts.length > 2)
                    {
                        multiValueExtractor.setSubstring(mapParts[1], mapParts[2]);
                    }
                    else
                    {
                        multiValueExtractor.setSubstring(mapParts[1], "toEnd");
                    }
                }
                catch (IndexerSpecException ise)
                {
                    ise.setSolrFieldAndSpec(fieldnames, origSpec);
                    currentExceptions.add(ise);
                }
            }
            else if (mapParts[0].trim().matches("[-_A-Za-z0-9]*[.]properties.*") && joinIndex == -1)
            {
                AbstractMultiValueMapping valueMapping = ValueIndexerFactory.instance().createMultiValueMapping(mapParts[0].trim());
                multiValueExtractor.addMap(valueMapping);
            }
            /* 
             *  this one line should now handle all of the settings for the lines below
             */
            else if ((tmpVal = DataUtil.getCleanValForParam(mapParts[0])) != EnumSet.noneOf(eCleanVal.class) )
            {
                multiValueExtractor.setCleanVal(tmpVal);
            }
            else
            {
                currentExceptions.add(new IndexerSpecException(fieldnames, origSpec, "Illegal format specification: " + Utils.join(mapParts, " ")));
            }
            currentIndex++;
        }
        return (joinIndex);
    }

}
