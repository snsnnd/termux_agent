package local.termux.agentbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.UUID;

/** Authorized ADB migration only. Normal apps cannot import data or trigger tests. */
public final class MigrationProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private void shellOnly(){if(Binder.getCallingUid()!=2000)throw new SecurityException("Authorized ADB shell only");}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws java.io.FileNotFoundException {
        shellOnly();if(!"/import".equals(uri.getPath())||!mode.equals("w"))throw new java.io.FileNotFoundException();
        if(AgentService.isBusy())throw new java.io.FileNotFoundException("Stop active task before migration");
        try {
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
            AgentStore store=AgentStore.get(getContext());store.setMeta("migration_state","receiving");
            new Thread(()->{
                try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];
                    for(int n;(n=in.read(buffer))!=-1;){if(bytes.size()+n>64_000_000)throw new IllegalArgumentException("Import too large");bytes.write(buffer,0,n);}
                    JSONObject input=new JSONObject(new String(Base64.decode(bytes.toByteArray(),Base64.DEFAULT),StandardCharsets.UTF_8));
                    JSONObject result=store.migrate(input);store.setMeta("migration_state","completed");store.setMeta("migration_result",result.toString());
                }catch(Exception e){store.setMeta("migration_state","failed: "+e.getClass().getSimpleName());}
                AgentService.update();
            },"private-migration").start();
            return pipe[1];
        }catch(Exception e){throw new java.io.FileNotFoundException("Cannot start private import");}
    }
    @Override public Bundle call(String method,String arg,Bundle extras) {
        shellOnly();Bundle result=new Bundle();
        try {
            AgentStore store=AgentStore.get(getContext());JSONObject out;
            if(method.equals("selftest")) {
                if(AgentService.isBusy())throw new IllegalStateException("Wait for the active task before developer tests");
                out=selftest(store);
            }
            else if(method.equals("history_metrics")) {
                out=new JSONObject(MainActivity.historyMetrics.toString());JSONArray stats=new JSONArray();
                try(Cursor c=store.getReadableDatabase().rawQuery("SELECT session,COUNT(*),SUM(LENGTH(content)),MAX(LENGTH(content)) FROM messages GROUP BY session",null)){
                    while(c.moveToNext())stats.put(new JSONObject().put("session",c.getString(0)).put("rows",c.getLong(1)).put("chars",c.getLong(2)).put("max_chars",c.getLong(3)));
                }
                out.put("session_stats",stats);
            }
            else if(method.equals("test_model")) {
                if(!store.prefs.getBoolean("cloud_consent",false))throw new SecurityException("Owner cloud consent required in app");
                store.session("native-selftest","原生连接自检");
                getContext().startForegroundService(new Intent(getContext(),AgentService.class).setAction(AgentService.SEND)
                    .putExtra("session","native-selftest").putExtra("text","这是原生服务自检。只调用 phone_status，随后用一句中文说明连接状态。不要调用其他工具，不读取屏幕、不执行点击。"));
                out=new JSONObject().put("started",true);
            }else {
                out=new JSONObject().put("migration",store.meta("migration_state","not_started"))
                    .put("migration_result",store.meta("migration_result",""))
                    .put("sessions",store.sessions().length()).put("model",store.prefs.getString("model","deepseek-flash"))
                    .put("configured",!store.apiKey().isEmpty()).put("busy",AgentService.isBusy())
                    .put("cloud_consent",store.prefs.getBoolean("cloud_consent",false));
                out.put("phone_confirmation",store.prefs.getString("phone_confirmation","task")).put("extended_automation",store.prefs.getBoolean("unattended_tools",false));
                AgentService service=AgentService.current;if(service!=null){out.put("task_status",service.status).put("task_granted",service.busy&&service.phoneTaskGrant==service.generation);
                    if(service.approval!=null)out.put("pending",new JSONObject().put("id",service.approval.id).put("tool",service.approval.name).put("task_scope",service.approval.taskGrant));}
                BridgeService bridge=BridgeService.current;if(bridge!=null)out.put("phone",bridge.nativeCall("/status",new JSONObject()));
                if(method.equals("test_result"))out.put("messages",store.messages("native-selftest",20));
            }
            result.putString("result",out.toString());
        }catch(Exception e){result.putString("error",e.getClass().getSimpleName()+": "+e.getMessage());}
        return result;
    }
    private JSONObject selftest(AgentStore store)throws Exception {
        JSONArray passed=new JSONArray();
        try{AgentPolicy.safeFile(store.workspace,"../escape");throw new AssertionError("escape accepted");}catch(SecurityException good){passed.put("path traversal blocked");}
        try{AgentPolicy.endpoint("http://api.deepseek.com");throw new AssertionError("HTTP accepted");}catch(IllegalArgumentException good){passed.put("HTTPS enforced");}
        try{AgentPolicy.endpoint("https://key@example.com");throw new AssertionError("URL credentials accepted");}catch(IllegalArgumentException good){passed.put("URL credentials blocked");}
        if(!AgentPolicy.mutates("phone_act","click")||!AgentPolicy.mutates("shell","")||AgentPolicy.mutates("phone_observe",""))throw new AssertionError("approval policy");passed.put("mutation policy");
        HashSet<String> names=new HashSet<>();JSONArray defs=NativeTools.definitions();
        for(int i=0;i<defs.length();i++)if(!names.add(defs.getJSONObject(i).getJSONObject("function").getString("name")))throw new AssertionError("duplicate schema");passed.put("unique tool schemas");
        NativeTools tools=new NativeTools(store);
        // Remove only artifacts owned by these developer self-tests, including
        // a previous failed test; never consume a user's undo snapshot.
        File[] artifacts=tools.snapshots.listFiles(File::isDirectory);
        if(artifacts!=null)for(File artifact:artifacts){File marker=new File(artifact,"path");
            if(marker.isFile()&&new String(java.nio.file.Files.readAllBytes(marker.toPath()),StandardCharsets.UTF_8).matches("(?s).*__selftest_[a-f0-9-]+/sample\\.txt")){
                new File(artifact,"data").delete();marker.delete();artifact.delete();
            }
        }
        String folder="__selftest_"+UUID.randomUUID();String path=folder+"/sample.txt";
        try {
            tools.execute("write_file",new JSONObject().put("path",path).put("content","alpha\nbeta"));
            if(!tools.execute("read_file",new JSONObject().put("path",path)).contains("alpha"))throw new AssertionError("read/write");passed.put("file write and numbered read");
            tools.execute("edit_file",new JSONObject().put("path",path).put("old_string","alpha").put("new_string","gamma"));
            if(!tools.execute("read_file",new JSONObject().put("path",path)).contains("gamma"))throw new AssertionError("edit");passed.put("exact edit");
            tools.execute("undo",new JSONObject());if(!tools.execute("read_file",new JSONObject().put("path",path)).contains("alpha"))throw new AssertionError("undo");passed.put("snapshot undo");
            if(!tools.execute("grep_files",new JSONObject().put("path",folder).put("pattern","beta")).contains("sample.txt"))throw new AssertionError("grep");passed.put("grep");
            if(!tools.execute("glob_files",new JSONObject().put("pattern",folder+"/**/*.txt")).contains("sample.txt"))throw new AssertionError("glob");passed.put("glob");
            String shell=tools.execute("shell",new JSONObject().put("command","id").put("timeout",5));if(!shell.contains("uid="))throw new AssertionError("native shell");passed.put("native app-UID shell");
        }finally{AgentPolicy.safeFile(store.workspace,path).delete();AgentPolicy.safeFile(store.workspace,folder).delete();}
        return new JSONObject().put("passed",passed).put("count",passed.length()).put("termux_required",false);
    }
    @Override public String getType(Uri u){return "application/octet-stream";}
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String order){throw new SecurityException();}
    @Override public Uri insert(Uri u,ContentValues v){throw new SecurityException();}
    @Override public int delete(Uri u,String s,String[] a){throw new SecurityException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new SecurityException();}
}
