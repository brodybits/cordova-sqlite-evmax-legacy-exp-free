/*
 * Copyright (c) 2012-2018: Christopher J. Brody (aka Chris Brody)
 * Copyright (c) 2005-2010, Nitobi Software Inc.
 * Copyright (c) 2010, IBM Corporation
 *
 * License for this version: GPL v3 (http://www.gnu.org/licenses/gpl.txt) or commercial license.
 * Contact for commercial license: info@litehelpers.net
 */

package io.sqlc;

import android.annotation.SuppressLint;

import android.util.Log;

import java.io.File;

import java.lang.IllegalArgumentException;
//import java.lang.Number;

import java.net.URI;
import java.net.URISyntaxException;

import java.util.concurrent.ConcurrentHashMap;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.sql.SQLException;

import org.apache.cordova.PluginResult;

public class SQLitePlugin extends CordovaPlugin {

    /**
     * Multiple database runner map (static).
     *
     * NOTE: no public static accessor to db (runner) map since it is not
     * expected to work properly with db threading.
     *
     * FUTURE TBD put DBRunner into a public class that can provide external accessor.
     *
     * ADDITIONAL NOTE: Storing as Map<String, DBRunner> to avoid portabiity issue
     * between Java 6/7/8 as discussed in:
     * https://gist.github.com/AlainODea/1375759b8720a3f9f094
     *
     * THANKS to @NeoLSN (Jason Yang/楊朝傑) for giving the pointer in:
     * https://github.com/litehelpers/Cordova-sqlite-storage/issues/727
     */
    static Map<String, DBRunner> dbrmap = new ConcurrentHashMap<String, DBRunner>();
    // static ConcurrentHashMap<Integer, DBRunner> dbrmap2 = new ConcurrentHashMap<Integer, DBRunner>();
    static Map<Integer, DBRunner> dbrmap2 = new ConcurrentHashMap<Integer, DBRunner>();

    static int lastdbid = 0;

    /**
     * NOTE: Using default constructor, no explicit constructor.
     */

    @Override
    public boolean execute(String actionAsString, String argsAsString, CallbackContext cbc) {
        if (actionAsString.startsWith("fj")) {
            int sep1pos = actionAsString.indexOf(':');
            int sep2pos = actionAsString.indexOf(';');

            int ll = Integer.parseInt(actionAsString.substring(sep1pos+1, sep2pos));
            ll += 10; // plus overhead with extra space extra space

            int s1pos = argsAsString.indexOf('[');
            int s2pos = argsAsString.indexOf(',');
            int dbid = Integer.parseInt(argsAsString.substring(s1pos+1, s2pos));

            // put db query in the queue to be executed in the db thread:
            DBQuery q = new DBQuery(argsAsString, ll, cbc);
            DBRunner r = dbrmap2.get(dbid);
            if (r != null) {
                try {
                    r.q.put(q);
                } catch(Exception e) {
                    Log.e(SQLitePlugin.class.getSimpleName(), "couldn't add to queue", e);
                    cbc.error("couldn't add to queue");
                }
            } else {
                cbc.error("database not open");
            }
            return true;
        } else {
            try {
                return execute(actionAsString, new JSONArray(argsAsString), cbc);
            } catch (JSONException e) {
                // TODO: signal JSON problem to JS
                Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error", e);
                return false;
            }
        }
    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param actionAsString The action to execute.
     * @param args   JSONArry of arguments for the plugin.
     * @param cbc    Callback context from Cordova API
     * @return       Whether the action was valid.
     */
    @Override
    public boolean execute(String actionAsString, JSONArray args, CallbackContext cbc) {

        Action action;
        try {
            action = Action.valueOf(actionAsString);
        } catch (IllegalArgumentException e) {
            // shouldn't ever happen
            Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error", e);
            return false;
        }

        try {
            return executeAndPossiblyThrow(action, args, cbc);
        } catch (JSONException e) {
            // TODO: signal JSON problem to JS
            Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error", e);
            return false;
        }
    }

    private boolean executeAndPossiblyThrow(Action action, JSONArray args, CallbackContext cbc)
            throws JSONException {

        boolean status = true;
        JSONObject o;
        String echo_value;
        String dbname;
        String filename;

        switch (action) {
            case echoStringValue:
                o = args.getJSONObject(0);
                echo_value = o.getString("value");
                cbc.success(echo_value);
                break;

            case open:
                o = args.getJSONObject(0);
                dbname = o.getString("name");
                filename = o.getString("filename");
                // open database and start reading its queue
                this.startDatabase(dbname, filename, o, cbc);
                break;

            case close:
                o = args.getJSONObject(0);
                dbname = o.getString("path");
                // put request in the q to close the db
                this.closeDatabase(dbname, cbc);
                break;

            case delete:
                o = args.getJSONObject(0);
                dbname = o.getString("path");
                String dblocation = null;
                if (o.has("androidDatabaseLocation"))
                    dblocation = o.getString("androidDatabaseLocation");

                deleteDatabase(dbname, dblocation, cbc);

                break;

            case executeSqlBatch:
            case backgroundExecuteSqlBatch:
                JSONObject allargs = args.getJSONObject(0);

                JSONObject dbargs = allargs.getJSONObject("dbargs");
                dbname = dbargs.getString("dbname");

                //* ** TBD OLD:
                JSONArray txargs = allargs.getJSONArray("executes");

                if (txargs.isNull(0)) {
                    cbc.error("missing executes list");
                } else {
                    int len = txargs.length();
                    String[] queries = new String[len];
                    JSONArray[] jsonparams = new JSONArray[len];

                    for (int i = 0; i < len; i++) {
                        JSONObject a = txargs.getJSONObject(i);
                        queries[i] = a.getString("sql");
                        jsonparams[i] = a.getJSONArray("params");
                    }

                    // put db query in the queue to be executed in the db thread:
                    DBQuery q = new DBQuery(queries, jsonparams, cbc);
                    DBRunner r = dbrmap.get(dbname);
                    if (r != null) {
                        try {
                            r.q.put(q);
                        } catch(Exception e) {
                            Log.e(SQLitePlugin.class.getSimpleName(), "couldn't add to queue", e);
                            cbc.error("couldn't add to queue");
                        }
                    } else {
                        cbc.error("database not open");
                    }
                }
                // */

                /* ** FUTURE TBD (???):
                int mylen = allargs.getInt("flen");

                JSONArray flatlist = allargs.getJSONArray("flatlist");
                int ai = 0;

                String[] queries = new String[mylen];

                // XXX TODO: currently goes through flatlist in multiple [2] passes
                for (int i = 0; i < mylen; i++) {
                    queries[i] = flatlist.getString(ai++);
                    int alen = flatlist.getInt(ai++);
                    ai += alen;
                }

                // put db query in the queue to be executed in the db thread:
                DBQuery q = new DBQuery(queries, flatlist, cbc);
                DBRunner r = dbrmap.get(dbname);
                if (r != null) {
                    try {
                        r.q.put(q); 
                    } catch(Exception e) {
                        Log.e(SQLitePlugin.class.getSimpleName(), "couldn't add to queue", e);
                        cbc.error("couldn't add to queue");
                    }
                } else {
                    cbc.error("database not open");
                }
                // */
                break;
        }

        return status;
    }

    /**
     * Clean up and close all open databases.
     */
    @Override
    public void onDestroy() {
        while (!dbrmap.isEmpty()) {
            String dbname = dbrmap.keySet().iterator().next();

            this.closeDatabaseNow(dbname);

            DBRunner r = dbrmap.get(dbname);
            try {
                // stop the db runner thread:
                r.q.put(new DBQuery());
            } catch(Exception e) {
                Log.e(SQLitePlugin.class.getSimpleName(), "couldn't stop db thread", e);
            }
            dbrmap.remove(dbname);
            dbrmap2.remove(r.dbid);
        }
    }

    // --------------------------------------------------------------------------
    // LOCAL METHODS
    // --------------------------------------------------------------------------

    private void startDatabase(String dbname, String filename, JSONObject options, CallbackContext cbc) {
        DBRunner r = dbrmap.get(dbname);

        if (r != null) {
            // NO LONGER EXPECTED due to BUG 666 workaround solution:
            cbc.error("INTERNAL ERROR: database already open for db name: " + dbname);
        } else {
            r = new DBRunner(dbname, filename, options, cbc, ++lastdbid);
            dbrmap.put(dbname, r);
            dbrmap2.put(r.dbid, r);
            this.cordova.getThreadPool().execute(r);
        }
    }

    /**
     * Get a database file.
     *
     * @param dbName   The name of the database file
     */
    private File getDatabaseFile(String dbname, String dblocation) throws URISyntaxException {
        if (dblocation == null) {
            File dbfile = this.cordova.getActivity().getDatabasePath(dbname);

            if (!dbfile.exists()) {
                dbfile.getParentFile().mkdirs();
            }

            return dbfile;
        }

        return new File(new File(new URI(dblocation)), dbname);
    }

    /**
     * Open a database.
     *
     * @param dbName   The name of the database file
     */
    private SQLiteNativeDatabase openDatabase(String dbname, String dblocation, CallbackContext cbc, boolean old_impl_xxx_ignored, int dbid) throws Exception {
        try {
            // ASSUMPTION: no db (connection/handle) is already stored in the map
            // [should be true according to the code in DBRunner.run()]

            File dbfile = getDatabaseFile(dbname, dblocation);
            Log.v("info", "Open sqlite db: " + dbfile.getAbsolutePath());

            SQLiteNativeDatabase mydb = new SQLiteNativeDatabase();
            mydb.open(dbfile);

            // Indicate Android version with flat JSON interface
            JSONObject a1 = new JSONObject();
            a1.put("dbid", dbid);
            cbc.success(a1);

            return mydb;
        } catch (Exception e) {
            if (cbc != null) // XXX Android locking/closing BUG workaround
                cbc.error("can't open database " + e);
            throw e;
        }
    }

    /*- ** XXX TBD SKIP FOR NOW:
    private SQLiteAndroidDatabase openDatabase2(String dbname, String dblocation, CallbackContext cbc, boolean old_impl_xxx_ignored) throws Exception {
        try {
            // ASSUMPTION: no db (connection/handle) is already stored in the map
            // [should be true according to the code in DBRunner.run()]

            File dbfile = getDatabaseFile(dbname, dblocation);
            Log.v("info", "Open sqlite db: " + dbfile.getAbsolutePath());

            SQLiteAndroidDatabase mydb = new SQLiteAndroidDatabase();
            mydb.open(dbfile);

            if (cbc != null) // XXX Android locking/closing BUG workaround
                cbc.success(); // (TBD) Indicate Android version with normal JSON interface

            return mydb;
            //- XXX **

            //- XXX [TBD] GONE:
            //- SQLiteConnection mydbc = connector.newSQLiteConnection(dbfile.getAbsolutePath(),
            //-     SQLiteOpenFlags.READWRITE | SQLiteOpenFlags.CREATE);

            //- // Indicate Android version with flat JSON interface
            //- cbc.success("a1");

            //- return mydbc;
            //- XXX **
        } catch (Exception e) {
            cbc.error("can't open database " + e);
            throw e;
        }
    }
    //- ** */

    /**
     * Close a database (in another thread).
     *
     * @param dbName   The name of the database file
     */
    private void closeDatabase(String dbname, CallbackContext cbc) {
        DBRunner r = dbrmap.get(dbname);
        if (r != null) {
            try {
                r.q.put(new DBQuery(false, cbc));
            } catch(Exception e) {
                if (cbc != null) {
                    cbc.error("couldn't close database" + e);
                }
                Log.e(SQLitePlugin.class.getSimpleName(), "couldn't close database", e);
            }
        } else {
            if (cbc != null) {
                cbc.success();
            }
        }
    }

    /**
     * Close a database (in the current thread).
     *
     * @param dbname   The name of the database file
     */
    private void closeDatabaseNow(String dbname) {
        /* ** [FUTURE TBD] BROKEN:
        DBRunner r = dbrmap.get(dbname);

        if (r != null) {
            SQLiteConnection mydbc = r.mydbc;

            try {
                if (mydbc != null)
                    mydbc.dispose();
            } catch(Exception e) {
                Log.e(SQLitePlugin.class.getSimpleName(), "couldn't close database", e);
            }
        }
        // */
    }

    private void deleteDatabase(String dbname, String dblocation, CallbackContext cbc) {
        DBRunner r = dbrmap.get(dbname);
        if (r != null) {
            try {
                r.q.put(new DBQuery(true, cbc));
            } catch(Exception e) {
                if (cbc != null) {
                    cbc.error("couldn't close database" + e);
                }
                Log.e(SQLitePlugin.class.getSimpleName(), "couldn't close database", e);
            }
        } else {
            boolean deleteResult = this.deleteDatabaseNow(dbname, dblocation);
            if (deleteResult) {
                cbc.success();
            } else {
                cbc.error("couldn't delete database");
            }
        }
    }

    /**
     * Delete a database.
     *
     * @param dbName   The name of the database file
     *
     * @return true if successful or false if an exception was encountered
     */
    private boolean deleteDatabaseNow(String dbname, String dblocation) {
        try {
            File dbfile = getDatabaseFile(dbname, dblocation);

            return cordova.getActivity().deleteDatabase(dbfile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(SQLitePlugin.class.getSimpleName(), "couldn't delete database", e);
            return false;
        }
    }

    /**
     * XXX [TBD] GONE:
     *
     * Executes a batch request and sends the results via cbc.
     *
     * @param mydbc      sqlite connection reference
     * @param queryarr   Array of query strings
     * @param flatlist   Flat array of JSON query statements and parameters
     * @param cbc        Callback context from Cordova API
     *
    void executeSqlBatch(SQLiteConnection mydbc, String[] queryarr, JSONArray flatlist, CallbackContext cbc) {
        if (mydbc == null) {
            // not allowed - can only happen if someone has closed (and possibly deleted) a database and then re-used the database
            cbc.error("database has been closed");
            return;
        }

        int len = queryarr.length;
        JSONArray batchResultsList = new JSONArray();

        int ai=0;

        for (int i = 0; i < len; i++) {
            int rowsAffectedCompat = 0;
            boolean needRowsAffectedCompat = false;

            try {
                String query = queryarr[i];

                ai++;
                int alen = flatlist.getInt(ai++);

                // Need to do this in case this.executeSqlStatement() throws:
                int query_ai = ai;
                ai += alen;

                this.executeSqlStatement(mydbc, query, flatlist, query_ai, alen, batchResultsList);
            } catch (Exception ex) {
                ex.printStackTrace();
                Log.e("executeSqlBatch", "SQLitePlugin.executeSql[Batch](): Error=" + ex.getMessage());
                // TODO what to do?
            }
        }

        cbc.success(batchResultsList);
    }

    private void executeSqlStatement(SQLiteConnection mydbc, String query, JSONArray paramsAsJson,
                                     int firstParamIndex, int paramCount, JSONArray batchResultsList) throws Exception {
        boolean hasRows = false;

        SQLiteStatement myStatement = null;

        long newTotal = 0;
        long rowsAffected = 0;
        long insertId = -1;

        String errorMessage = null;

        try {
            myStatement = mydbc.prepareStatement(query);

            for (int i = 0; i < paramCount; ++i) {
                int jsonParamIndex = firstParamIndex + i;
                if (paramsAsJson.isNull(jsonParamIndex)) {
                    myStatement.bindNull(i + 1);
                } else {
                    Object p = paramsAsJson.get(jsonParamIndex);
                    if (p instanceof Float || p instanceof Double) 
                        myStatement.bindDouble(i + 1, paramsAsJson.getDouble(jsonParamIndex));
                    else if (p instanceof Number) 
                        myStatement.bindLong(i + 1, paramsAsJson.getLong(jsonParamIndex));
                    else
                        myStatement.bindTextNativeString(i + 1, paramsAsJson.getString(jsonParamIndex));
                }
            }

            long lastTotal = mydbc.getTotalChanges();
            hasRows = myStatement.step();

            newTotal = mydbc.getTotalChanges();
            rowsAffected = newTotal - lastTotal;

            if (rowsAffected > 0)
                insertId = mydbc.getLastInsertRowid();

        } catch (Exception ex) {
            ex.printStackTrace();
            errorMessage = ex.getMessage();
            Log.e("executeSqlBatch", "SQLitePlugin.executeSql[Batch](): Error=" + errorMessage);
        }

        // If query result has rows
        if (hasRows) {
            String key = "";
            int colCount = myStatement.getColumnCount();

            // XXX ASSUMPTION: in this case insertId & rowsAffected would not apply here
            batchResultsList.put("okrows");

            // Build up JSON result object for each row
            do {
                try {
                    batchResultsList.put(colCount);

                    for (int i = 0; i < colCount; ++i) {
                        key = myStatement.getColumnName(i);
                        batchResultsList.put(key);

                        switch (myStatement.getColumnType(i)) {
                        case SQLColumnType.NULL:
                            batchResultsList.put(JSONObject.NULL);
                            break;

                        case SQLColumnType.REAL:
                            batchResultsList.put(myStatement.getColumnDouble(i));
                            break;

                        case SQLColumnType.INTEGER:
                            batchResultsList.put(myStatement.getColumnLong(i));
                            break;

                        // For TEXT & BLOB:
                        default:
                            batchResultsList.put(myStatement.getColumnTextNativeString(i));
                        }

                    }

                } catch (JSONException e) {
                    e.printStackTrace();
                    // TODO what to do?
                }
            } while (myStatement.step());

            batchResultsList.put("endrows");
        } else if (errorMessage != null) {
            batchResultsList.put("errormessage");
            batchResultsList.put(errorMessage);
        } else if (rowsAffected > 0) {
            batchResultsList.put("ch2");
            batchResultsList.put(rowsAffected);
            batchResultsList.put(insertId);
        } else {
            batchResultsList.put("ok");
        }

        if (myStatement != null) myStatement.dispose();
    }
     */

    static boolean isNativeLibLoaded = false;

    class SQLiteNativeDatabase extends SQLiteAndroidDatabase {
        long mydbhandle;

        /**
         * Open a database.
         *
         * @param dbFile   The database File specification
         */
        @Override
        void open(File dbFile) throws Exception {
            if (!isNativeLibLoaded) {
                System.loadLibrary("sqlc-evcore-native-driver");
                isNativeLibLoaded = true;
            }

            mydbhandle = EVCoreNativeDriver.sqlc_evcore_db_open(EVCoreNativeDriver.SQLC_EVCORE_API_VERSION,
              dbFile.getAbsolutePath(),
              EVCoreNativeDriver.SQLC_OPEN_READWRITE | EVCoreNativeDriver.SQLC_OPEN_CREATE);

            if (mydbhandle < 0) throw new SQLException("open error", "failed", -(int)mydbhandle);
        }

        /**
         * Close a database (in the current thread).
         */
        @Override
        void closeDatabaseNow() {
            try {
                if (mydbhandle > 0) EVCoreNativeDriver.sqlc_db_close(mydbhandle);
            } catch (Exception e) {
                Log.e(SQLitePlugin.class.getSimpleName(), "couldn't close database, ignoring", e);
            }
        }

        /**
         * Ignore Android bug workaround for native version
         */
        @Override
        void bugWorkaround() { }

        String flatBatchJSON(String batch_json, int ll) {
            long ch = EVCoreNativeDriver.sqlc_evcore_db_new_qc(mydbhandle);
            String jr = EVCoreNativeDriver.sqlc_evcore_qc_execute(ch, batch_json, ll);
            EVCoreNativeDriver.sqlc_evcore_qc_finalize(ch);
            return jr;
        }
    }

    private class DBRunner implements Runnable {
        final int dbid;
        final String dbname;
        final String filename;
        final String dblocation;
        /*- *** XXX TBD SKIP FOR NOW:
        // expose oldImpl:
        boolean oldImpl;
        private boolean bugWorkaround;
        //- XXX *** */

        final BlockingQueue<DBQuery> q;
        final CallbackContext openCbc;

        SQLiteNativeDatabase mydb1;
        SQLiteAndroidDatabase mydb;
        /* ** XXX GONE:
        SQLiteConnection mydbc;
        // */

        DBRunner(final String dbname, final String filename, JSONObject options, CallbackContext cbc, int dbid) {
            this.dbid = dbid;
            this.dbname = dbname;
            this.filename = filename;
            /*- *** XXX TBD SKIP FOR NOW:
            this.oldImpl = options.has("androidOldDatabaseImplementation");
            //- Log.v(SQLitePlugin.class.getSimpleName(), "Android db implementation: ...");
            this.bugWorkaround = this.oldImpl && options.has("androidBugWorkaround");
            //- XXX *** */

            String mydblocation = null;
            if (options.has("androidDatabaseLocation")) {
                try {
                    mydblocation = options.getString("androidDatabaseLocation");
                } catch (Exception e) {
                    // IGNORED
                    Log.e(SQLitePlugin.class.getSimpleName(), "unexpected JSON exception, IGNORED", e);
                }
            }
            this.dblocation = mydblocation;

            /*- *** XXX TBD SKIP FOR NOW:
            if (this.bugWorkaround)
                Log.v(SQLitePlugin.class.getSimpleName(), "Android db closing/locking workaround applied");
            //- XXX *** */

            this.q = new LinkedBlockingQueue<DBQuery>();
            this.openCbc = cbc;
        }

        public void run() {
            try {
                //- XXX
                //- if (!oldImpl)
                //-     this.mydb = this.mydb1 = openDatabase(dbname, dblocation, this.openCbc, this.oldImpl, this.dbid);
                //- else
                //-     this.mydb = openDatabase2(dbname, dblocation, this.openCbc, this.oldImpl);
                this.mydb1 = openDatabase(filename, dblocation, this.openCbc, false, this.dbid);
            } catch (Exception e) {
                Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error, stopping db thread", e);
                dbrmap.remove(dbname);
                dbrmap2.remove(dbid);
                return;
            }

            DBQuery dbq = null;

            try {
                dbq = q.take();

                while (!dbq.stop) {
                    //- XXX [TBD] SKIP oldImpl functionality for now:
                    //- if (oldImpl) {
                    //-     mydb.executeSqlBatch(dbq.queries, dbq.jsonparams, dbq.cbc);
                    //- } else {
                    if (true) // XXX TBD
                        dbq.cbc.sendPluginResult(new MyPluginResult(mydb1.flatBatchJSON(dbq.fj, dbq.ll)));
                    //- }

                    /* ** XXX TBD SKIP FOR NOW:
                    if (this.oldImpl && this.bugWorkaround && dbq.queries.length == 1 && dbq.queries[0] == "COMMIT")
                        mydb.bugWorkaround();
                    //- XXX GONE:
                    //- executeSqlBatch(mydbc, dbq.queries, dbq.flatlist, dbq.cbc);
                    // */

                    dbq = q.take();
                }
            } catch (Exception e) {
                Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error", e);
            }

            if (dbq != null && dbq.close) {
                try {
                    closeDatabaseNow(dbname);

                    dbrmap.remove(dbname); // (should) remove ourself
                    dbrmap.remove(dbid); // (should) remove ourself

                    if (!dbq.delete) {
                        dbq.cbc.success();
                    } else {
                        try {
                            boolean deleteResult = deleteDatabaseNow(dbname, dblocation);
                            if (deleteResult) {
                                dbq.cbc.success();
                            } else {
                                dbq.cbc.error("couldn't delete database");
                            }
                        } catch (Exception e) {
                            Log.e(SQLitePlugin.class.getSimpleName(), "couldn't delete database", e);
                            dbq.cbc.error("couldn't delete database: " + e);
                        }
                    }
                } catch (Exception e) {
                    Log.e(SQLitePlugin.class.getSimpleName(), "couldn't close database", e);
                    if (dbq.cbc != null) {
                        dbq.cbc.error("couldn't close database: " + e);
                    }
                }
            }
        }
    }

    private class MyPluginResult extends PluginResult {
        final String jr;

        MyPluginResult(String jr) {
            super(PluginResult.Status.OK);
            this.jr = jr;
        }

        @Override
        public int getMessageType() { return PluginResult.MESSAGE_TYPE_JSON; }

        @Override
        public String getMessage() { return jr; }
    }

    private final class DBQuery {
        // XXX TODO replace with DBRunner action enum:
        final boolean stop;
        final boolean close;
        final boolean delete;
        final int ll;
        final String fj;
        final String[] queries;
        // XXX ???:
        //* ** TBD OLD:
        final JSONArray[] jsonparams;
        // */
        /* ** XXX FUTURE [TBD] ???:
        final JSONArray flatlist;
        // */
        final CallbackContext cbc;

        //* ** TBD OLD:
        DBQuery(String[] myqueries, JSONArray[] params, CallbackContext c) {
            this.fj = null;
            this.ll = -1;
            this.stop = false;
            this.close = false;
            this.delete = false;
            this.queries = myqueries;
            this.jsonparams = params;
            this.cbc = c;
        }
        // */

        /* ** XXX FUTURE [TBD] ???:
        DBQuery(String[] myqueries, JSONArray flatlist, CallbackContext c) {
            this.stop = false;
            this.close = false;
            this.delete = false;
            this.queries = myqueries;
            this.flatlist = flatlist;
            this.cbc = c;
        }
        // */

        DBQuery(String fj, int ll, CallbackContext c) {
            this.fj = fj;
            this.ll = ll;
            this.stop = false;
            this.close = false;
            this.delete = false;
            this.queries = null;
            this.jsonparams = null;
            this.cbc = c;
        }

        DBQuery(boolean delete, CallbackContext cbc) {
            this.fj = null;
            this.ll = -1;
            this.stop = true;
            this.close = true;
            this.delete = delete;
            this.queries = null;
            // XXX ???:
            //* TBD OLD:
            this.jsonparams = null;
            // */
            /* ** XXX FUTURE [TBD] ???:
            this.flatlist = null;
            // */
            this.cbc = cbc;
        }

        // signal the DBRunner thread to stop:
        DBQuery() {
            this.fj = null;
            this.ll = -1;
            this.stop = true;
            this.close = false;
            this.delete = false;
            this.queries = null;
            //* TBD OLD:
            this.jsonparams = null;
            // */
            /* ** FUTURE TBD:
            this.flatlist = null;
            // */
            this.cbc = null;
        }
    }

    private static enum Action {
        echoStringValue,
        open,
        close,
        delete,
        executeSqlBatch,
        backgroundExecuteSqlBatch,
    }
}

/* vim: set expandtab : */
