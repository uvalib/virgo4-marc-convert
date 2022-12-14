package org.solrmarc.solr;

import java.util.Collection;
import java.util.Iterator;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.solrmarc.driver.RecordAndDoc;
import org.solrmarc.marc.AwsSqsSingleton;
import org.solrmarc.marc.RecordPlus;

public class SolrSQSWrappedProxy extends SolrProxy
{
    private SolrProxy wrapped = null;
    private AwsSqsSingleton aws_sqs = null;
//  private final static Logger logger = Logger.getLogger(SolrSQSWrappedProxy.class);

    public SolrSQSWrappedProxy(SolrProxy wrapped)
    {
        this.wrapped = wrapped;
        aws_sqs = AwsSqsSingleton.getInstance(null);
    }

    public int addDoc(RecordAndDoc recDoc)
    {
        SolrInputDocument inputDoc = recDoc.getDoc();
        String id = inputDoc.getFieldValue("raw_id") != null ? inputDoc.getFieldValue("raw_id").toString() : 
                inputDoc.getFieldValue("id") != null ? inputDoc.getFieldValue("id").toString() : "<no id>";
        RecordPlus recPlus = (RecordPlus)recDoc.getRec();
        if (recPlus.hasExtraData("message-attribute-message-id"))
        {
            id = recPlus.getExtraData("message-attribute-message-id");
        }
        String messageReceiptHandle = null;
        if (recPlus.hasExtraData("message-receipt-handle"))
        {
            messageReceiptHandle = recPlus.getExtraData("message-receipt-handle");
        }

        wrapped.addDoc(recDoc);
        aws_sqs.remove(id, messageReceiptHandle);
        return(1);
    }

    @Override
    public int addDocs(Collection<RecordAndDoc> docQ)
    {
        Iterator <RecordAndDoc> iter = docQ.iterator();
        int num = 0;
        while (iter.hasNext())
        {
            RecordAndDoc inputDoc = iter.next();
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
