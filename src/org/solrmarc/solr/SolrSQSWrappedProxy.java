package org.solrmarc.solr;

import java.util.Collection;
import java.util.Iterator;

import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.solrmarc.marc.AwsSqsSingleton;

public class SolrSQSWrappedProxy extends SolrProxy
{
    private SolrProxy wrapped = null;
    private AwsSqsSingleton aws_sqs = null;
    private final static Logger logger = Logger.getLogger(SolrSQSWrappedProxy.class);

    public SolrSQSWrappedProxy(SolrProxy wrapped)
    {
        this.wrapped = wrapped;
        aws_sqs = AwsSqsSingleton.getInstance(null);
    }
    
    public int addDoc(SolrInputDocument inputDoc)
    {
        wrapped.addDoc(inputDoc);
        String id = inputDoc.getFieldValue("raw_id") != null ? inputDoc.getFieldValue("raw_id").toString() : 
                    inputDoc.getFieldValue("id") != null ? inputDoc.getFieldValue("id").toString() : "<no id>";
        aws_sqs.remove(id);
        return(1);
    }

    @Override
    public int addDocs(Collection<SolrInputDocument> docQ)
    {
        Iterator <SolrInputDocument> iter = docQ.iterator();
        int num = 0;
        while (iter.hasNext())
        {
            SolrInputDocument inputDoc = iter.next();
            num += addDoc(inputDoc);
        }
        return(num);
    }

    @Override
    public void commit(boolean optimize)
    {
    }

    @Override
    public void delete(String id)
    {
    }

    @Override
    public QueryResponse query(SolrQuery params)
    {
        return null;
    }

}
