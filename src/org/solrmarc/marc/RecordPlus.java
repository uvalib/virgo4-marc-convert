package org.solrmarc.marc;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.marc4j.MarcError;
import org.marc4j.marc.ControlField;
import org.marc4j.marc.DataField;
import org.marc4j.marc.Leader;
import org.marc4j.marc.Record;
import org.marc4j.marc.VariableField;

public class RecordPlus implements Record {
    private static final long serialVersionUID = 1110801786023226073L;

    Record wrappedRecord;
    Map<String, String> extraDataMap;

    public RecordPlus(Record wrapThis)
    {
        wrappedRecord = wrapThis;
        extraDataMap = new LinkedHashMap<String, String>();
    }

    public void replaceRecord(Record rec)
    {
        wrappedRecord = rec;
    }

    public void addExtraData(String key, String value)
    {
        extraDataMap.put(key, value);
    }

    public String getExtraData(String key)
    {
        return extraDataMap.get(key);
    }

    public boolean hasExtraData(String key)
    {
        return extraDataMap.containsKey(key);
    }

    public Collection<String> getExtraDataKeys(String key)
    {
        return extraDataMap.keySet();
    }

    @Override
    public void setId(Long id)
    {
        wrappedRecord.setId(id);
    }

    @Override
    public Long getId()
    {
        return wrappedRecord.getId();
    }

    @Override
    public void setType(String type)
    {
        wrappedRecord.setType(type);
    }

    @Override
    public String getType()
    {
        return wrappedRecord.getType();
    }

    @Override
    public void addVariableField(VariableField field)
    {
        wrappedRecord.addVariableField(field);
    }

    @Override
    public void removeVariableField(VariableField field)
    {
        wrappedRecord.removeVariableField(field);
    }

    @Override
    public List<VariableField> getVariableFields()
    {
        return wrappedRecord.getVariableFields();
    }

    @Override
    public List<VariableField> getVariableFieldsWithLeader()
    {
        return wrappedRecord.getVariableFieldsWithLeader();
    }

    @Override
    public List<ControlField> getControlFields()
    {
        return wrappedRecord.getControlFields();
    }

    @Override
    public List<DataField> getDataFields()
    {
        return wrappedRecord.getDataFields();
    }

    @Override
    public ControlField getControlNumberField()
    {
        return wrappedRecord.getControlNumberField();
    }

    @Override
    public String getControlNumber()
    {
        return wrappedRecord.getControlNumber();
    }

    @Override
    public VariableField getVariableField(String tag)
    {
        return wrappedRecord.getVariableField(tag);
    }

    @Override
    public List<VariableField> getVariableFields(String tag)
    {
        return wrappedRecord.getVariableFields(tag);
    }

    @Override
    public List<VariableField> getVariableFields(String[] tags)
    {
        return wrappedRecord.getVariableFields(tags);
    }

    @Override
    public Leader getLeader()
    {
        return wrappedRecord.getLeader();
    }

    @Override
    public void setLeader(Leader leader)
    {
        wrappedRecord.setLeader(leader);
    }

    @Override
    public List<VariableField> find(String pattern)
    {
        return wrappedRecord.find(pattern);
    }

    @Override
    public List<VariableField> find(String tag, String pattern)
    {
        return wrappedRecord.find(tag, pattern);
    }

    @Override
    public List<VariableField> find(String[] tags, String pattern)
    {
        return wrappedRecord.find(tags, pattern);
    }

    @Override
    public void addError(String field, String subfield, int severity, String message)
    {
        wrappedRecord.addError(field, subfield, severity, message);
    }

    @Override
    public void addErrors(List<MarcError> newErrors)
    {
        wrappedRecord.addErrors(newErrors);
    }

    @Override
    public boolean hasErrors()
    {
        return wrappedRecord.hasErrors();
    }

    @Override
    public List<MarcError> getErrors()
    {
        return wrappedRecord.getErrors();
    }
}
