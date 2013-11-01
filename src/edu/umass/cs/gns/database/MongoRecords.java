/*
 * Copyright (C) 2013
 * University of Massachusetts
 * All Rights Reserved 
 */
package edu.umass.cs.gns.database;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.util.JSON;
import edu.umass.cs.gns.exceptions.RecordExistsException;
import edu.umass.cs.gns.exceptions.RecordNotFoundException;
import edu.umass.cs.gns.main.GNS;
import edu.umass.cs.gns.main.StartNameServer;
import edu.umass.cs.gns.nameserver.NameRecord;
import edu.umass.cs.gns.nameserver.NameServer;
import edu.umass.cs.gns.nameserver.ResultValue;
import edu.umass.cs.gns.nameserver.ValuesMap;
import edu.umass.cs.gns.nameserver.replicacontroller.ReplicaControllerRecord;
import edu.umass.cs.gns.util.ConfigFileInfo;
import edu.umass.cs.gns.util.HashFunction;
import edu.umass.cs.gns.util.JSONUtils;
import org.bson.BSONObject;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.UnknownHostException;
import java.util.*;

/**
 * Provides insert, update, remove and lookup operations for guid, key, record triples using JSONObjects as the intermediate
 * representation
 * 
 * *** THIS CODE NEEDS SOME MORE WORK TO REMOVE REDUNDANT CODE AND CLEAN UP SOME OF THE EXCEPTION HANDLING ***
 *
 * @author westy
 */
public class MongoRecords implements NoSQLRecords {

  private static final String DBROOTNAME = "GNS";
  public static final String DBNAMERECORD = "NameRecord";
  public static final String DBREPLICACONTROLLER = "ReplicaControllerRecord";
  public static final String PAXOSLOG = "PaxosLog";
  private DB db;
  private String dbName;

  public static MongoRecords getInstance() {
    return MongoRecordCollectionHolder.INSTANCE;
  }

  private static class MongoRecordCollectionHolder {

    private static final MongoRecords INSTANCE = new MongoRecords();
  }

  private MongoRecords() {
    init();
  }

  private void init() {
    MongoCollectionSpec.addCollectionSpec(DBNAMERECORD, NameRecord.NAME);
    MongoCollectionSpec.addCollectionSpec(DBREPLICACONTROLLER, ReplicaControllerRecord.NAME);
    // add location as another index
    //MongoCollectionSpec.getCollectionSpec(DBNAMERECORD).addOtherIndex(new BasicDBObject(NameRecord.VALUES_MAP.getName() + "." + "location", 1));
    MongoCollectionSpec.getCollectionSpec(DBNAMERECORD)
            .addOtherIndex(new BasicDBObject(NameRecord.VALUES_MAP.getName() + "." + "location", "2d"));
    MongoCollectionSpec.getCollectionSpec(DBNAMERECORD)
            .addOtherIndex(new BasicDBObject(NameRecord.VALUES_MAP.getName() + "." + "ipAddress", 1));
    try {
      // use a unique name in case we have more than one on a machine
      dbName = DBROOTNAME + "-" + NameServer.nodeID;
      MongoClient mongoClient;
      if (StartNameServer.mongoPort > 0) {
        mongoClient = new MongoClient("localhost", StartNameServer.mongoPort);
      } else {
        mongoClient = new MongoClient("localhost");
      }
      db = mongoClient.getDB(dbName);
      initializeIndexes();
    } catch (UnknownHostException e) {
      GNS.getLogger().severe("Unable to open Mongo DB: " + e);
    }
  }

  private void initializeIndexes() {
    for (MongoCollectionSpec spec : MongoCollectionSpec.allCollectionSpecs()) {
      initializeIndex(spec.getName());
    }
  }

  private void initializeIndex(String collectionName) {
    MongoCollectionSpec spec = MongoCollectionSpec.getCollectionSpec(collectionName);
    db.getCollection(spec.getName()).ensureIndex(spec.getPrimaryIndex(), new BasicDBObject("unique", true));
    for (BasicDBObject index : spec.getOtherIndexes()) {
      db.getCollection(spec.getName()).ensureIndex(index);
    }
  }

  @Override
  public void reset(String collectionName) {
    if (MongoCollectionSpec.getCollectionSpec(collectionName) != null) {
      db.requestStart();
      try {
        db.requestEnsureConnection();
        db.getCollection(collectionName).dropIndexes();
        db.getCollection(collectionName).drop();
        GNS.getLogger().info("MONGO DB RESET. DBNAME: " + dbName + " Collection name: " + collectionName);

        // IMPORTANT... recreate the index
        initializeIndex(collectionName);
      } finally {
        db.requestDone();
      }
    } else {
      GNS.getLogger().severe("MONGO DB: No collection named: " + collectionName);
    }

  }

  @Override
  public JSONObject lookup(String collectionName, String guid) throws RecordNotFoundException {
    return lookup(collectionName, guid, false);
  }

  private JSONObject lookup(String collectionName, String guid, boolean explain) {
    db.requestStart();
    try {
      String primaryKey = MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey().getName();
      db.requestEnsureConnection();
      DBCollection collection = db.getCollection(collectionName);
      BasicDBObject query = new BasicDBObject(primaryKey, guid);
      DBCursor cursor = collection.find(query);
      if (explain) {
        System.out.println(cursor.explain().toString());
      }
      if (cursor.hasNext()) {
        DBObject obj = cursor.next();
        return new JSONObject(obj.toString());
      } else {
        return null;
      }
    } catch (JSONException e) {
      GNS.getLogger().warning("Unable to parse JSON: " + e);
      return null;
    } finally {
      db.requestDone();
    }
  }

  @Override
  public String lookup(String collectionName, String guid, String key) {
    return lookup(collectionName, guid, key, false);
  }

  private String lookup(String collectionName, String guid, String key, boolean explain) {
    db.requestStart();
    try {
      String primaryKey = MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey().getName();
      db.requestEnsureConnection();
      DBCollection collection = db.getCollection(collectionName);
      BasicDBObject query = new BasicDBObject(primaryKey, guid);
      BasicDBObject projection = new BasicDBObject(key, 1).append("_id", 0);
      DBCursor cursor = collection.find(query, projection);
      if (explain) {
        System.out.println(cursor.explain().toString());
      }
      if (cursor.hasNext()) {
        DBObject obj = cursor.next();
        JSONObject json = new JSONObject(obj.toString());
        if (json.has(key)) {
          return json.getString(key);
        } else {
          return null;
        }
      } else {
        return null;
      }
    } catch (JSONException e) {
      GNS.getLogger().warning("Unable to parse JSON: " + e);
      return null;
    } finally {
      db.requestDone();
    }
  }

  @Override
  public ResultValue lookup(String collectionName, String guid, ArrayList<String> keys) {
    return lookup(collectionName, guid, keys, false);
  }

  private ResultValue lookup(String collectionName, String guid, ArrayList<String> keys, boolean explain) {
    db.requestStart();
    try {
      String primaryKey = MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey().getName();
      db.requestEnsureConnection();

      DBCollection collection = db.getCollection(collectionName);
      BasicDBObject query = new BasicDBObject(primaryKey, guid);
      //The projection parameter takes a document of the following form:
      // { field1: <boolean>, field2: <boolean> ... } where boolean is 0 or 1.
      BasicDBObject projection = new BasicDBObject().append("_id", 0);
      for (String key : keys) {
        projection.append(key, 1);
      }
      DBCursor cursor = collection.find(query, projection);
      if (explain) {
        System.out.println(cursor.explain().toString());
      }
      ResultValue values = new ResultValue();
      if (cursor.hasNext()) {
        DBObject obj = cursor.next();
        for (String key : keys) {
          Object field = obj.get(key);
          if (field == null) {
            values.add(null);
          } else {
            values.add(field.toString());
          }
        }
      } else {
        return null;
      }
      return values;
    } finally {
      db.requestDone();
    }
  }

  /**
   * Given a key and a value return all the records that have a *user* key with that value.
   * User keys are stored in the valuesMap field.
   * The key should be declared as an index otherwise this baby will be slow.
   * 
   * @param collectionName
   * @param key
   * @param value
//   * @param explain
   * @return a MongoRecordCursor
   */
  @Override
  public MongoRecordCursor selectRecords(String collectionName, Field valuesMapField, String key, Object value) {
    return selectRecords(collectionName, valuesMapField, key, value, false);
  }

  private MongoRecordCursor selectRecords(String collectionName, Field valuesMapField, String key, Object value, boolean explain) {
    db.requestEnsureConnection();
    DBCollection collection = db.getCollection(collectionName);
    // note that if the value of the key in the database is a list (which it is) this
    // query will find all records where the value (a list) *contains* an element whose value is the value
    //
    //FROM MONGO DOC: Match an Array Element
    //Equality matches can specify a single element in the array to match. These specifications match 
    //if the array contains at least one element with the specified value.
    //In the following example, the query matches all documents where the value of the field tags is 
    //an array that contains 'fruit' as one of its elements:
    //db.inventory.find( { tags: 'fruit' } )

    String fieldName = valuesMapField.getName() + "." + key;
    BasicDBObject query = new BasicDBObject(fieldName, value);
    //System.out.println("***QUERY***: " + query.toString());
    DBCursor cursor = collection.find(query);
    if (explain) {
      System.out.println(cursor.explain().toString());
    }
    return new MongoRecordCursor(cursor, MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey());
  }

  @Override
  public MongoRecordCursor selectRecordsWithin(String collectionName, Field valuesMapField, String key, String value) {
    return selectRecordsWithin(collectionName, valuesMapField, key, value, false);
  }

  private MongoRecordCursor selectRecordsWithin(String collectionName, Field valuesMapField, String key, String value, boolean explain) {
    db.requestEnsureConnection();
    DBCollection collection = db.getCollection(collectionName);

//    db.<collection>.find( { <location field> :
//                         { $geoWithin :
//                            { <shape operator> : <coordinates>
//                      } } } )

    BasicDBList box = parseJSONArrayLocationStringIntoDBList(value);
    //System.out.println("***BOX: " + box);
    String fieldName = valuesMapField.getName() + "." + key;
    BasicDBObject shapeClause = new BasicDBObject("$box", box);
    BasicDBObject withinClause = new BasicDBObject("$within", shapeClause);
    BasicDBObject query = new BasicDBObject(fieldName, withinClause);
    //System.out.println("***QUERY***: " + query.toString());
    DBCursor cursor = collection.find(query);
    if (explain) {
      System.out.println(cursor.explain().toString());
    }
    return new MongoRecordCursor(cursor, MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey());
  }

  private BasicDBList parseJSONArrayLocationStringIntoDBList(String string) {
    BasicDBList box1 = new BasicDBList();
    BasicDBList box2 = new BasicDBList();
    BasicDBList box = new BasicDBList();
    try {
      JSONArray json = new JSONArray(string);
      box1.add(json.getJSONArray(0).getDouble(0));
      box1.add(json.getJSONArray(0).getDouble(1));
      box2.add(json.getJSONArray(1).getDouble(0));
      box2.add(json.getJSONArray(1).getDouble(1));
      box.add(box1);
      box.add(box2);
    } catch (JSONException e) {
      GNS.getLogger().severe("Unable to parse JSON: " + e);
    }
    return box;
  }

  @Override
  public MongoRecordCursor selectRecordsNear(String collectionName, Field valuesMapField, String key, String value, Object maxDistance) {
    return selectRecordsNear(collectionName, valuesMapField, key, value, maxDistance, false);
  }

  private MongoRecordCursor selectRecordsNear(String collectionName, Field valuesMapField, String key, String value, Object maxDistance, boolean explain) {
    db.requestEnsureConnection();
    DBCollection collection = db.getCollection(collectionName);

//   db.<collection>.find( { <location field> :
//                         { $near : [ <x> , <y> ] ,
//                           $maxDistance: <distance>
//                    } } )
    BasicDBList tuple = new BasicDBList();
    try {
      JSONArray json = new JSONArray(value);
      tuple.add(json.getDouble(0));
      tuple.add(json.getDouble(1));
    } catch (JSONException e) {
      GNS.getLogger().severe("Unable to parse JSON: " + e);
    }
    String fieldName = valuesMapField.getName() + "." + key;
    BasicDBObject nearClause = new BasicDBObject("$near", tuple).append("$maxDistance", maxDistance);
    BasicDBObject query = new BasicDBObject(fieldName, nearClause);
    //System.out.println("***QUERY***: " + query.toString());
    DBCursor cursor = collection.find(query);
    if (explain) {
      System.out.println(cursor.explain().toString());
    }
    return new MongoRecordCursor(cursor, MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey());
  }

  @Override
  public void insert(String collectionName, String guid, JSONObject value) throws RecordExistsException {
    db.requestStart();
    try {
      db.requestEnsureConnection();
      DBCollection collection = db.getCollection(collectionName);
      DBObject dbObject = (DBObject) JSON.parse(value.toString());
      try {
        collection.insert(dbObject);
      } catch (Exception e) {
        throw new RecordExistsException(collectionName, guid);
//        e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
      }
    } finally {
      db.requestDone();
    }
  }

  @Override
  public void update(String collectionName, String guid, JSONObject value) {
    db.requestStart();
    try {
      String primaryKey = MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey().getName();
      db.requestEnsureConnection();
      DBCollection collection = db.getCollection(collectionName);
      BasicDBObject query = new BasicDBObject(primaryKey, guid);
      DBObject dbObject = (DBObject) JSON.parse(value.toString());
      collection.update(query, dbObject);
    } finally {
      db.requestDone();
    }
  }

  public void updateSingleValue(String collectionName, String name, String key, String value) {
    updateField(collectionName, name, key, new ArrayList(Arrays.asList(value)));
  }

  @Override
  public void updateField(String collectionName, String guid, String key, Object object) {
    db.requestStart();
    try {
      String primaryKey = MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey().getName();
      db.requestEnsureConnection();
      DBCollection collection = db.getCollection(collectionName);
      BasicDBObject query = new BasicDBObject(primaryKey, guid);
      BasicDBObject newValue = new BasicDBObject(key, object);
      BasicDBObject updateOperator = new BasicDBObject("$set", newValue);
      collection.update(query, updateOperator);
    } finally {
      db.requestDone();
    }
  }

  @Override
  public boolean contains(String collectionName, String guid) {
    db.requestStart();
    try {
      String primaryKey = MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey().getName();
      db.requestEnsureConnection();
      DBCollection collection = db.getCollection(collectionName);
      BasicDBObject query = new BasicDBObject(primaryKey, guid);
      DBCursor cursor = collection.find(query);
      if (cursor.hasNext()) {
        return true;
      } else {
        return false;
      }
    } finally {
      db.requestDone();
    }
  }

  @Override
  public void remove(String collectionName, String guid) {
    db.requestStart();
    try {
      String primaryKey = MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey().getName();
      db.requestEnsureConnection();
      DBCollection collection = db.getCollection(collectionName);
      BasicDBObject query = new BasicDBObject(primaryKey, guid);
      collection.remove(query);
    } finally {
      db.requestDone();
    }
  }

  @Override
  public HashMap<Field, Object> lookup(String collectionName, String guid, Field nameField, ArrayList<Field> fields1) throws RecordNotFoundException {
    return lookup(collectionName, guid, nameField, fields1, null, null);
  }

  @Override
  public HashMap<Field, Object> lookup(String collectionName, String guid, Field nameField, ArrayList<Field> fields1, Field valuesMapField, ArrayList<Field> valuesMapKeys) throws RecordNotFoundException {
    long t0 = System.currentTimeMillis();
    long tA = 0;
    long tB = 0;
    long tC = 0;
    long tD = 0;
    long tE = 0;
    long tF = 0;
    long tG = 0;
    if (guid == null) {
      GNS.getLogger().fine("GUID is null: " + guid);
      throw new RecordNotFoundException(guid);
    }
    db.requestStart();
    tA = System.currentTimeMillis();
    try {
      String primaryKey = MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey().getName();
      db.requestEnsureConnection();


      DBCollection collection = db.getCollection(collectionName);
      tB = System.currentTimeMillis();
      BasicDBObject query = new BasicDBObject(primaryKey, guid);
      BasicDBObject projection = new BasicDBObject().append("_id", 0);
      if (fields1 != null) {
        for (Field f : fields1) {
          projection.append(f.getName(), 1);
        }
      }

      if (valuesMapField != null && valuesMapKeys != null) {
        for (int i = 0; i < valuesMapKeys.size(); i++) {
          String fieldName = valuesMapField.getName() + "." + valuesMapKeys.get(i).getName();
          projection.append(fieldName, 1);
        }
      }
      tC = System.currentTimeMillis();

      DBObject dbObject = collection.findOne(query, projection);
      if (dbObject == null) throw  new RecordNotFoundException(guid);

//      DBCursor cursor = collection.find(query, projection);
      tD = System.currentTimeMillis();
//      if (t1 - t0 > 20) {
//        GNS.getLogger().severe("\t" + (t1 - t0) + "\t" + t0);
//      }
      HashMap<Field, Object> hashMap = new HashMap<Field, Object>();
//      if (cursor.hasNext()) {
        hashMap.put(nameField, guid);// put the name in the hashmap!! very important!!
//        t0 = System.currentTimeMillis();
//        DBObject dbObject = cursor.next();
        tE = System.currentTimeMillis();
        FieldType.populateHashMap(hashMap, dbObject, fields1);
        tF = System.currentTimeMillis();
        if (valuesMapField != null && valuesMapKeys != null) {
          BSONObject bson = (BSONObject) dbObject.get(valuesMapField.getName());

          ValuesMap valuesMap = new ValuesMap();
          for (int i = 0; i < valuesMapKeys.size(); i++) {
            JSONArray fieldValue;
            if (bson.containsField(valuesMapKeys.get(i).getName()) == false) {
              continue;
            }
            try {
              fieldValue = new JSONArray(bson.get(valuesMapKeys.get(i).getName()).toString());
//                System.out.println("\nKEY = " + valuesMapKeys.get(i).getFieldName() + " \tVALUE = " + fieldValue+"\n");
            } catch (JSONException e) {
              e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
              continue;
            }
            if (valuesMapKeys.get(i).type().equals(FieldType.LIST_STRING)) {
              try {
                valuesMap.put(valuesMapKeys.get(i).getName(), JSONUtils.JSONArrayToResultValue(fieldValue));
              } catch (JSONException e) {
                GNS.getLogger().fine("Error parsing json");
                e.printStackTrace();
              }
            } else {
              GNS.getLogger().fine("ERROR: Error: User keys field is not of type " + FieldType.LIST_STRING);
              System.exit(2);
            }
          }
          hashMap.put(valuesMapField, valuesMap);
        }
        tG = System.currentTimeMillis();
        long t1 = System.currentTimeMillis();
        if (t1 - t0 > 20) {
          GNS.getLogger().severe(" MongoLookup longlatency " + (t1 - t0) + "\tbreakdown\t" + (tA - t0)  + "\t"+ (tB - tA)  + "\t"  + (tC - tB)+ "\t" + (tD - tC) + "\t" + (tE - tD) + "\t" + (tF - tE) + "\t" + (tG - tF) + "\t" + (t1 - tG)) ;
        }
        return hashMap;
    } finally {
      db.requestDone();
    }
  }

  @Override
  public void update(String collectionName, String guid, Field nameField, ArrayList<Field> fields1, ArrayList<Object> values1) {
    update(collectionName, guid, nameField, fields1, values1, null, null, null);
  }

  @Override
  public void update(String collectionName, String guid, Field nameField, ArrayList<Field> fields, ArrayList<Object> values,
          Field valuesMapField, ArrayList<Field> valuesMapKeys, ArrayList<Object> valuesMapValues) {
    db.requestStart();
    try {
      String primaryKey = MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey().getName();
      db.requestEnsureConnection();
      DBCollection collection = db.getCollection(collectionName);
      BasicDBObject query = new BasicDBObject(primaryKey, guid);
      BasicDBObject updates = new BasicDBObject();
      if (fields != null) {
        for (int i = 0; i < fields.size(); i++) {
          Object newValue;
          if (fields.get(i).type().equals(FieldType.VALUES_MAP)) {
            newValue = ((ValuesMap) values.get(i)).getMap();
          } else {
            newValue = values.get(i);
          }
          updates.append(fields.get(i).getName(), newValue);
        }
      }
      if (valuesMapField != null && valuesMapKeys != null) {
        for (int i = 0; i < valuesMapKeys.size(); i++) {
          String fieldName = valuesMapField.getName() + "." + valuesMapKeys.get(i).getName();
          updates.append(fieldName, valuesMapValues.get(i));
        }
      }
      if (updates.keySet().size() > 0) {
        long t0 = System.currentTimeMillis();
        collection.update(query, new BasicDBObject("$set", updates));
        long t1 = System.currentTimeMillis();
        if (t1 - t0 > 10) {
          //System.out.println(" Long latency mongoUpdate " + (t1 - t0) + "\ttime\t" + t0);
          GNS.getLogger().warning(" Long latency mongoUpdate " + (t1 - t0));

        }
//        System.out.println("\nTHIS SHOULD NOT PRINT !!!--> "  );
      }
    } finally {
      db.requestDone();
    }
  }

  @Override
  public void updateConditional(String collectionName, String guid, Field nameField, Field conditionField, Object conditionValue, ArrayList<Field> fields, ArrayList<Object> values,
                     Field valuesMapField, ArrayList<Field> valuesMapKeys, ArrayList<Object> valuesMapValues) {
    db.requestStart();
    try {
      String primaryKey = MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey().getName();
      db.requestEnsureConnection();
      DBCollection collection = db.getCollection(collectionName);
      BasicDBObject query = new BasicDBObject(primaryKey, guid);
      query.append(conditionField.getName(), conditionValue);

      BasicDBObject updates = new BasicDBObject();
      if (fields != null) {
        for (int i = 0; i < fields.size(); i++) {
          Object newValue;
          if (fields.get(i).type().equals(FieldType.VALUES_MAP)) {
            newValue = ((ValuesMap) values.get(i)).getMap();
          } else {
            newValue = values.get(i);
          }
          updates.append(fields.get(i).getName(), newValue);
        }
      }
      if (valuesMapField != null && valuesMapKeys != null) {
        for (int i = 0; i < valuesMapKeys.size(); i++) {
          String fieldName = valuesMapField.getName() + "." + valuesMapKeys.get(i).getName();
          updates.append(fieldName, valuesMapValues.get(i));
        }
      }
      if (updates.keySet().size() > 0) {
        long t0 = System.currentTimeMillis();
        collection.update(query, new BasicDBObject("$set", updates));
        long t1 = System.currentTimeMillis();
        if (t1 - t0 > 10) {
          //System.out.println(" Long latency mongoUpdate " + (t1 - t0) + "\ttime\t" + t0);
          GNS.getLogger().warning(" Long latency mongoUpdate " + (t1 - t0));

        }
//        System.out.println("\nTHIS SHOULD NOT PRINT !!!--> "  );
      }
    } finally {
      db.requestDone();
    }
  }

  @Override
  public void increment(String collectionName, String guid, ArrayList<Field> fields, ArrayList<Object> values) {
    increment(collectionName, guid, fields, values, null, null, null);
  }

  @Override
  public void increment(String collectionName, String guid, ArrayList<Field> fields, ArrayList<Object> values,
          Field votesMapField, ArrayList<Field> votesMapKeys, ArrayList<Object> votesMapValues) {
    db.requestStart();
    try {
      String primaryKey = MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey().getName();
      db.requestEnsureConnection();
      DBCollection collection = db.getCollection(collectionName);
      BasicDBObject query = new BasicDBObject(primaryKey, guid);
      BasicDBObject updates = new BasicDBObject();
      if (fields != null) {
        for (int i = 0; i < fields.size(); i++) {
          Object newValue;
          if (fields.get(i).type().equals(FieldType.VALUES_MAP)) {
            newValue = ((ValuesMap) values.get(i)).getMap();
          } else {
            newValue = values.get(i);
          }
          updates.append(fields.get(i).getName(), newValue);
        }
      }
      if (votesMapField != null && votesMapKeys != null) {
        for (int i = 0; i < votesMapKeys.size(); i++) {
          String fieldName = votesMapField.getName() + "." + votesMapKeys.get(i).getName();
          updates.append(fieldName, votesMapValues.get(i));
        }
      }
      if (updates.keySet().size() > 0) {
        collection.update(query, new BasicDBObject("$inc", updates));
      }
    } finally {
      db.requestDone();
    }
  }

  @Override
  public MongoRecordCursor getAllRowsIterator(String collectionName, Field nameField, ArrayList<Field> fields) {
    return new MongoRecordCursor(db, collectionName, MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey(), fields);
  }

  @Override
  public MongoRecordCursor getAllRowsIterator(String collectionName) {
    return new MongoRecordCursor(db, collectionName, MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey());
  }

  @Override
  public Set<String> keySet(String collectionName) {
    Set<String> result = new HashSet<String>();
    // Get a cursor for all the rows with just the name column filled in.
    MongoRecordCursor cursor = getAllRowsIterator(collectionName, MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey(), null);
    String nameField = MongoCollectionSpec.getCollectionSpec(collectionName).getPrimaryKey().getName();
    while (cursor.hasNext()) {
      result.add(cursor.nextRowField(nameField));
    }
    return result;
  }

  @Override
  public void printAllEntries(String collectionName) {
    MongoRecordCursor cursor = getAllRowsIterator(collectionName);
    while (cursor.hasNext()) {
      System.out.println(cursor.nextJSONObject());
    }
  }

  @Override
  public String toString() {
    return "DB " + dbName;
  }

  //THIS ISN'T TEST CODE
  // the -clear option is currently used by the EC2 installer so keep it working
  // this use will probably go away at some point
  public static void main(String[] args) throws Exception, RecordNotFoundException {
    if (args.length > 0 && args[0].startsWith("-clear")) {
      dropAllDatabases();
    } else if (args.length == 3) {
      queryTest(Integer.parseInt(args[0]), args[1], args[2], null);
    } else if (args.length == 4) {
      queryTest(Integer.parseInt(args[0]), args[1], args[2], args[3]);
    } else {
    }
  }

  public static void dropAllDatabases() {
    MongoClient mongoClient;
    try {
      mongoClient = new MongoClient("localhost");
    } catch (UnknownHostException e) {
      GNS.getLogger().severe("Unable to open Mongo DB: " + e);
      return;
    }
    List<String> names = mongoClient.getDatabaseNames();
    for (String name : names) {
      mongoClient.dropDatabase(name);
    }
    System.out.println("Dropped mongo DBs: " + names.toString());
    // reinit the instance
    getInstance().init();
  }

  // ALL THE CODE BELOW IS TEST CODE
//  //test code
  private static void queryTest(int nodeID, String key, String searchArg, String otherArg) throws RecordNotFoundException, Exception {
    NameServer.nodeID = nodeID;
    ConfigFileInfo.readHostInfo("ns1", NameServer.nodeID);
    HashFunction.initializeHashFunction();
    MongoRecords instance = MongoRecords.getInstance();
    System.out.println("***ALL RECORDS***");
    instance.printAllEntries(DBNAMERECORD);
    System.out.println("***ALL RECORD KEYS ->" + instance.keySet(DBNAMERECORD).toString());

    Object search;
    try {
      search = Double.parseDouble(searchArg);
    } catch (NumberFormatException e) {
      search = searchArg;
    }

    Object other = null;
    if (otherArg != null) {
      try {
        other = Double.parseDouble(otherArg);
      } catch (NumberFormatException e) {
        other = otherArg;
      }
    }

    System.out.println("***LOCATION QUERY***");
    MongoRecordCursor cursor;
    if (search instanceof Double) {
      cursor = instance.selectRecords(DBNAMERECORD, NameRecord.VALUES_MAP, key, search, true);
    } else if (other != null) {
      cursor = instance.selectRecordsNear(DBNAMERECORD, NameRecord.VALUES_MAP, key, (String) search, other, true);
    } else {
      cursor = instance.selectRecordsWithin(DBNAMERECORD, NameRecord.VALUES_MAP, key, (String) search, true);
    }
    while (cursor.hasNext()) {
      try {
        JSONObject json = cursor.next();
        System.out.println(json.getString(NameRecord.NAME.getName()) + " -> " + json.toString());
      } catch (Exception e) {
        System.out.println("Exception: " + e);
        e.printStackTrace();
      }
    }
    System.out.println("***ALL RECORDS ACTIVE FIELD***");
    cursor = instance.getAllRowsIterator(DBNAMERECORD, NameRecord.NAME, new ArrayList<Field>(Arrays.asList(NameRecord.ACTIVE_NAMESERVERS)));
    while (cursor.hasNext()) {
      System.out.println(cursor.nextJSONObject().toString());
    }
  }

  
  public static String Version = "$Revision$";
}