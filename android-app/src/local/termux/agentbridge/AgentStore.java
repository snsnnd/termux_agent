package local.termux.agentbridge;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Private app storage. API credentials are encrypted with Android Keystore. */
public final class AgentStore extends SQLiteOpenHelper {
    private static AgentStore instance;
    final Context context;
    final SharedPreferences prefs;
    final File workspace;
    private final AtomicLong revision=new AtomicLong();
    long revision(){return revision.get();}
    public static synchronized AgentStore get(Context context) {
        if (instance == null) instance = new AgentStore(context.getApplicationContext());
        return instance;
    }
    private AgentStore(Context context) {
        super(context, "agent.db", null, 1);
        this.context = context;
        prefs = context.getSharedPreferences("agent", Context.MODE_PRIVATE);
        try { workspace = new File(context.getFilesDir(), "workspace").getCanonicalFile(); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot resolve private workspace",e); }
        workspace.mkdirs();
        session("main", "新的开始");
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,title TEXT,updated INTEGER,summary TEXT DEFAULT '')");
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,session TEXT,role TEXT,content TEXT,kind TEXT,created INTEGER)");
        db.execSQL("CREATE INDEX msg_session ON messages(session,id)");
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }
    synchronized void session(String id, String title) {
        ContentValues row = new ContentValues();
        row.put("id", id); row.put("title", title); row.put("updated", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("sessions", null, row, SQLiteDatabase.CONFLICT_IGNORE);
    }
    synchronized String newSession() {
        String id = UUID.randomUUID().toString(); session(id, "新对话");
        prefs.edit().putString("session", id).apply(); return id;
    }
    String selected() { return prefs.getString("session", "main"); }
    int visualBudget(){return Math.max(1,Math.min(5,prefs.getInt("visual_budget",3)));}
    synchronized void rename(String id, String title) {
        ContentValues row = new ContentValues(); row.put("title", title);
        getWritableDatabase().update("sessions", row, "id=?", new String[]{id});
        revision.incrementAndGet();
    }
    synchronized void deleteSession(String id) {
        getWritableDatabase().delete("messages", "session=?", new String[]{id});
        getWritableDatabase().delete("sessions", "id=?", new String[]{id});
        if (selected().equals(id)) newSession();
        revision.incrementAndGet();
    }
    synchronized long add(String id, String role, String content, String kind) {
        ContentValues row = new ContentValues(); row.put("session", id); row.put("role", role);
        row.put("content", content); row.put("kind", kind); row.put("created", System.currentTimeMillis());
        long result = getWritableDatabase().insertOrThrow("messages", null, row);
        ContentValues update = new ContentValues(); update.put("updated", System.currentTimeMillis());
        getWritableDatabase().update("sessions", update, "id=?", new String[]{id});revision.incrementAndGet();return result;
    }
    synchronized JSONArray sessions() throws Exception {
        JSONArray rows = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,title,updated,summary FROM sessions ORDER BY updated DESC", null)) {
            while (c.moveToNext()) rows.put(new JSONObject().put("id",c.getString(0)).put("title",c.getString(1))
                .put("updated",c.getLong(2)).put("summary",c.getString(3)));
        }
        return rows;
    }
    synchronized JSONArray messages(String id, int limit) throws Exception {
        JSONArray rows = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM (SELECT id,role,content,kind,created FROM messages WHERE session=? ORDER BY id DESC LIMIT ?) ORDER BY id",new String[]{id,String.valueOf(limit)})) {
            while(c.moveToNext()) rows.put(new JSONObject().put("id",c.getLong(0)).put("role",c.getString(1))
                .put("content",c.getString(2)).put("kind",c.getString(3)).put("created",c.getLong(4)));
        }
        return rows;
    }
    synchronized JSONArray messagePage(String id,long before,int limit)throws Exception {
        JSONArray rows=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM (SELECT id,role,content,kind,created FROM messages WHERE session=? AND id<? ORDER BY id DESC LIMIT ?) ORDER BY id",
                new String[]{id,String.valueOf(before<=0?Long.MAX_VALUE:before),String.valueOf(Math.min(50,Math.max(1,limit)))})) {
            while(c.moveToNext())rows.put(new JSONObject().put("id",c.getLong(0)).put("role",c.getString(1)).put("content",c.getString(2)).put("kind",c.getString(3)).put("created",c.getLong(4)));
        }
        return rows;
    }
    synchronized String summary(String id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT summary FROM sessions WHERE id=?",new String[]{id})) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }
    synchronized void summary(String id,String summary) {
        ContentValues row=new ContentValues();row.put("summary",summary);
        getWritableDatabase().update("sessions",row,"id=?",new String[]{id});
    }
    synchronized String meta(String key,String fallback) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM meta WHERE key=?",new String[]{key})) {
            return c.moveToFirst()?c.getString(0):fallback;
        }
    }
    synchronized void setMeta(String key,String value) {
        ContentValues row=new ContentValues();row.put("key",key);row.put("value",value);
        getWritableDatabase().insertWithOnConflict("meta",null,row,SQLiteDatabase.CONFLICT_REPLACE);
    }
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        String alias="pocket-agent-api-v1";
        if(!store.containsAlias(alias)) {
            KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            gen.generateKey();
        }
        return (SecretKey)store.getKey(alias,null);
    }
    synchronized void saveKey(String value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        prefs.edit().putString("key_iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
            .putString("key_cipher",Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP)).commit();
    }
    synchronized String apiKey() throws Exception {
        if(!prefs.contains("key_cipher"))return "";
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("key_iv",""),Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(prefs.getString("key_cipher",""),Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    synchronized JSONObject config() throws Exception {
        return new JSONObject().put("base_url",prefs.getString("base_url","https://api.deepseek.com"))
            .put("model",prefs.getString("model","deepseek-flash")).put("api_key",apiKey());
    }
    /** One-time migration; old DB rows and workspace are preserved, not reinterpreted as instructions. */
    synchronized JSONObject migrate(JSONObject input) throws Exception {
        if(AgentService.isBusy())throw new IllegalStateException("Stop the active task before importing");
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(input.toString().getBytes(StandardCharsets.UTF_8));
        String stamp=Base64.encodeToString(digest,Base64.NO_WRAP);
        if(meta("migration_digest","").equals(stamp))return new JSONObject().put("already_imported",true);
        JSONObject cfg=input.getJSONObject("config");
        AgentPolicy.endpoint(cfg.getString("base_url"));
        JSONArray files=input.optJSONArray("workspace");
        if(files==null)files=new JSONArray();
        long total=0;
        for(int i=0;i<files.length();i++) {
            JSONObject row=files.getJSONObject(i);File f=AgentPolicy.safeFile(workspace,row.getString("path"));
            byte[] bytes=Base64.decode(row.getString("data"),Base64.DEFAULT);total+=bytes.length;
            if(total>32_000_000 || bytes.length>4_000_000)throw new IllegalArgumentException("Workspace import too large");
            if(f.exists()&&!MessageDigest.isEqual(Files.readAllBytes(f.toPath()),bytes))throw new IllegalArgumentException("Workspace file exists: "+row.getString("path"));
        }
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        int count=0;
        try {
            JSONArray messages=input.getJSONArray("messages");
            for(int i=0;i<messages.length();i++) {
                JSONObject row=messages.getJSONObject(i);String id="legacy:"+row.getString("session");
                session(id,"旧版 · "+row.getString("session"));
                ContentValues v=new ContentValues();v.put("session",id);v.put("role",row.getString("role"));
                v.put("content",row.optString("content",""));v.put("kind","text");
                v.put("created",(long)(row.optDouble("timestamp",0)*1000));db.insertOrThrow("messages",null,v);count++;
            }
            JSONArray summaries=input.optJSONArray("summaries");
            if(summaries!=null)for(int i=0;i<summaries.length();i++) {
                JSONObject row=summaries.getJSONObject(i);String id="legacy:"+row.getString("session");
                session(id,"旧版 · "+row.getString("session"));summary(id,row.optString("content",""));
            }
            JSONArray metas=input.optJSONArray("meta");
            if(metas!=null)for(int i=0;i<metas.length();i++) {
                JSONObject row=metas.getJSONObject(i);setMeta("legacy:"+row.getString("key"),row.optString("value",""));
            }
            for(int i=0;i<files.length();i++) {
                JSONObject row=files.getJSONObject(i);File f=AgentPolicy.safeFile(workspace,row.getString("path"));
                f.getParentFile().mkdirs();Files.write(f.toPath(),Base64.decode(row.getString("data"),Base64.DEFAULT));
            }
            saveKey(cfg.getString("api_key"));
            prefs.edit().putString("base_url",cfg.getString("base_url")).putString("model",cfg.getString("model")).putBoolean("migrated",true).commit();
            setMeta("migration_digest",stamp);setMeta("migration_messages",String.valueOf(count));
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        revision.incrementAndGet();
        return new JSONObject().put("messages",count).put("workspace_files",files.length()).put("api_key_encrypted",true);
    }
}
