/*
 * Copyright (C) 2016
 * University of Massachusetts
 * All Rights Reserved 
 *
 * Initial developer(s): Westy.
 */
package edu.umass.cs.gnsserver.database;

import com.mongodb.util.JSON;
import edu.umass.cs.gnscommon.exceptions.server.FailedDBOperationException;
import edu.umass.cs.gnscommon.exceptions.server.RecordExistsException;
import edu.umass.cs.gnscommon.exceptions.server.RecordNotFoundException;
import static edu.umass.cs.gnsserver.database.MongoRecords.DBNAMERECORD;
import edu.umass.cs.gnsserver.gnsapp.recordmap.NameRecord;
import edu.umass.cs.gnsserver.utils.JSONUtils;
import edu.umass.cs.gnsserver.utils.ValuesMap;
import edu.umass.cs.utils.DiskMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 *
 * @author westy
 */
public class DiskMapRecords implements NoSQLRecords {

  private Map<String, DiskMapCollection> collections;
  private String mongoNodeID;
  private int mongoPort;

  private DiskMapCollection getCollection(String name) {
    DiskMapCollection collection = collections.get(name);
    if (collection == null) {
      collections.put(name, collection = new DiskMapCollection(mongoNodeID, mongoPort, name));
    }
    return collection;
  }

  public DiskMap<String, JSONObject> getMap(String name) {
    return getCollection(name).getMap();
  }

  public MongoRecords<String> getMongoRecords(String name) {
    return getCollection(name).getMongoRecords();
  }

  public DiskMapRecords(String nodeID) {
    this(nodeID, -1);
  }

  public DiskMapRecords(String nodeID, int port) {
    this.collections = new ConcurrentHashMap<>();
    this.mongoNodeID = nodeID;
    this.mongoPort = port;
  }

  private String generateName(String collection, String name) {
    return collection + "/" + name;
  }

  @Override
  public void insert(String collection, String name, JSONObject value) throws FailedDBOperationException, RecordExistsException {
    getMap(collection).put(name, value);
  }

  @Override
  public JSONObject lookupEntireRecord(String collection, String name) throws FailedDBOperationException, RecordNotFoundException {
    return getMap(collection).get(name);
  }

  @Override
  // FIXME: Why does this still have
  public HashMap<ColumnField, Object> lookupSomeFields(String collection, String guid,
          ColumnField nameField, ColumnField valuesMapField, ArrayList<ColumnField> valuesMapKeys)
          throws RecordNotFoundException, FailedDBOperationException {

    JSONObject fullRecord = getMap(collection).get(guid);
    if (fullRecord == null) {
      throw new RecordNotFoundException(guid);
    }
    HashMap<ColumnField, Object> hashMap = new HashMap<>();
    hashMap.put(nameField, guid);
    if (valuesMapField != null && valuesMapKeys != null) {
      try {
        JSONObject valuesMapIn = fullRecord.getJSONObject(valuesMapField.getName());
        ValuesMap valuesMapOut = new ValuesMap();
        for (int i = 0; i < valuesMapKeys.size(); i++) {
          String userKey = valuesMapKeys.get(i).getName();
          if (containsFieldDotNotation(userKey, valuesMapIn) == false) {
            DatabaseConfig.getLogger().log(Level.INFO,
                    "DBObject doesn't contain {0}", new Object[]{userKey});

            continue;
          }
          try {
            switch (valuesMapKeys.get(i).type()) {
              case USER_JSON:
                Object value = getWithDotNotation(userKey, valuesMapIn);
                DatabaseConfig.getLogger().log(Level.INFO,
                        "Object is {0}", new Object[]{value.toString()});
                valuesMapOut.put(userKey, value);
                break;
              case LIST_STRING:
                valuesMapOut.putAsArray(userKey,
                        JSONUtils.JSONArrayToResultValue(
                                new JSONArray(getWithDotNotation(userKey, valuesMapIn).toString())));
                break;
              default:
                DatabaseConfig.getLogger().log(Level.SEVERE,
                        "ERROR: Error: User keys field {0} is not a known type:{1}",
                        new Object[]{userKey, valuesMapKeys.get(i).type()});
                break;
            }
          } catch (JSONException e) {
            DatabaseConfig.getLogger().log(Level.SEVERE, "Error parsing json: {0}", e);
            e.printStackTrace();
          }

        }
        hashMap.put(valuesMapField, valuesMapIn);
      } catch (JSONException e) {
        DatabaseConfig.getLogger().log(Level.FINE,
                "Problem getting values map: ", new Object[]{e.getMessage()});
      }
    }
    return hashMap;
  }

  private Object getWithDotNotation(String key, JSONObject json) throws JSONException {
    if (key.contains(".")) {
      int indexOfDot = key.indexOf(".");
      String subKey = key.substring(0, indexOfDot);
      JSONObject subJSON = (JSONObject) json.get(subKey);
      if (subJSON == null) {
        throw new JSONException(subKey + " is null");
      }
      try {
        return getWithDotNotation(key.substring(indexOfDot + 1), subJSON);
      } catch (JSONException e) {
        throw new JSONException(subKey + "." + e.getMessage());
      }
    } else {
      Object result = json.get(key);
      return result;
    }
  }

  private boolean containsFieldDotNotation(String key, JSONObject json) {
    try {
      return getWithDotNotation(key, json) != null;
    } catch (JSONException e) {
      return false;
    }
  }

  @Override
  public boolean contains(String collection, String name) throws FailedDBOperationException {
    return getMap(collection).containsKey(name);
  }

  @Override
  public void removeEntireRecord(String collection, String name) throws FailedDBOperationException {
    getMap(collection).remove(name);
  }

  @Override
  public void updateEntireRecord(String collection, String name, ValuesMap valuesMap) throws FailedDBOperationException {
    getMap(collection).put(name, valuesMap);
  }

  @Override
  public void updateIndividualFields(String collection, String guid,
          ColumnField valuesMapField, ArrayList<ColumnField> valuesMapKeys,
          ArrayList<Object> valuesMapValues) throws FailedDBOperationException {
    JSONObject json = getMap(collection).get(guid);
    if (json == null) {
      throw new FailedDBOperationException(collection, guid);
    }
    if (valuesMapField != null && valuesMapKeys != null) {
      try {
        for (int i = 0; i < valuesMapKeys.size(); i++) {
          String fieldName = valuesMapField.getName() + "." + valuesMapKeys.get(i).getName();
          switch (valuesMapKeys.get(i).type()) {
            case LIST_STRING:
              // special case for old format
              json.put(fieldName, valuesMapValues.get(i));
              break;
            case USER_JSON:
              json.put(fieldName, JSONParse(valuesMapValues.get(i)));
              break;
            default:
              DatabaseConfig.getLogger().log(Level.WARNING,
                      "Ignoring unknown format: {0}", valuesMapKeys.get(i).type());
              break;
          }
        }
      } catch (JSONException e) {
        DatabaseConfig.getLogger().log(Level.SEVERE,
                "Problem updating json: {0}", e.getMessage());
      }
    }
    getMap(collection).put(guid, json);
  }
  // not sure why the JSON.parse doesn't handle things this way but it doesn't

  private Object JSONParse(Object object) {
    if (object instanceof String || object instanceof Number) {
      return object;
    } else {
      return JSON.parse(object.toString());
    }
  }

  @Override
  public void removeMapKeys(String collection, String name,
          ColumnField mapField, ArrayList<ColumnField> mapKeys)
          throws FailedDBOperationException {
    JSONObject json = getMap(collection).get(name);
    if (json == null) {
      throw new FailedDBOperationException(collection, name);
    }
    if (mapField != null && mapKeys != null) {
      for (int i = 0; i < mapKeys.size(); i++) {
        String fieldName = mapField.getName() + "." + mapKeys.get(i).getName();
        json.remove(fieldName);
      }
    }
    getMap(collection).put(name, json);
  }

  @Override
  public AbstractRecordCursor getAllRowsIterator(String collection) throws FailedDBOperationException {
    getMap(collection).commit();
    return getMongoRecords(collection).getAllRowsIterator(collection);
  }

  @Override
  public AbstractRecordCursor selectRecords(String collection, ColumnField valuesMapField, String key, Object value) throws FailedDBOperationException {
    getMap(collection).commit();
    return getMongoRecords(collection).selectRecords(collection, valuesMapField, key, value);
  }

  @Override
  public AbstractRecordCursor selectRecordsWithin(String collection, ColumnField valuesMapField, String key, String value) throws FailedDBOperationException {
    getMap(collection).commit();
    return getMongoRecords(collection).selectRecordsWithin(collection, valuesMapField, key, value);
  }

  @Override
  public AbstractRecordCursor selectRecordsNear(String collection, ColumnField valuesMapField, String key, String value, Double maxDistance) throws FailedDBOperationException {
    getMap(collection).commit();
    return getMongoRecords(collection).selectRecordsNear(collection, valuesMapField, key, value, maxDistance);
  }

  @Override
  public AbstractRecordCursor selectRecordsQuery(String collection, ColumnField valuesMapField, String query) throws FailedDBOperationException {
    getMap(collection).commit();
    return getMongoRecords(collection).selectRecordsQuery(collection, valuesMapField, query);
  }

  @Override
  public void createIndex(String collection, String field, String index) {
    getMap(collection).commit();
    getMongoRecords(collection).createIndex(collection, field, index);
  }

  @Override
  public void printAllEntries(String collection) throws FailedDBOperationException {
    getMap(collection).commit();
    getMongoRecords(collection).printAllEntries(collection);
  }

  // test code... there's also a junit test elsewhere
  public static void main(String[] args) throws Exception, RecordNotFoundException {
    String collection = "test_collection";
    String guid = "testGuid";
    String field = "testField";
    DiskMapRecords records = new DiskMapRecords("test");
    JSONObject json = new JSONObject();
    try {
      json.put("testField", "some value");
    } catch (JSONException e) {
      System.out.println("Problem creating json " + e);
    }
    // insert
    records.insert(DBNAMERECORD, guid, json);

    records.printAllEntries(DBNAMERECORD);

    System.out.println(records.lookupEntireRecord(DBNAMERECORD, guid));
    //
    ArrayList<ColumnField> userFields = new ArrayList<>(Arrays.asList(new ColumnField(field,
            ColumnFieldType.USER_JSON)));
    System.out.println(records.lookupSomeFields(DBNAMERECORD, guid, NameRecord.NAME, NameRecord.VALUES_MAP, userFields));

    System.exit(0);
  }
}