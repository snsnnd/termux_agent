package local.termux.agentbridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** JVM tests use real org.json, Android stubs only for class linkage, no device/API calls. */
public final class EfficiencyTest {
    static int passed;
    interface Checked {void run()throws Exception;}
    static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);passed++;}
    static void rejects(Checked action,String label)throws Exception{try{action.run();}catch(IllegalStateException|IllegalArgumentException expected){passed++;return;}throw new AssertionError(label);}
    static JSONObject row(int id,String text,boolean click,int parent)throws Exception {
        return new JSONObject().put("node_id",id).put("text",text).put("description","").put("clickable",click).put("click_id",parent).put("enabled",true)
            .put("bounds",new JSONArray(new int[]{0,id*10,400,id*10+10})).put("class","android.widget.TextView").put("view_id","example:id/row");
    }
    static JSONObject raw()throws Exception {
        return new JSONObject().put("ok",true).put("snapshot","s.1").put("scene","scene1").put("package","example.app").put("width",1000).put("height",800)
            .put("nodes",new JSONArray().put(row(0,"",true,0)).put(row(1,"显示和亮度",false,0)).put(row(2,"列表",false,-1).put("scrollable",true)));
    }
    static JSONObject tool(JSONArray messages,String id,String name,JSONObject result)throws Exception {
        messages.put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id",id).put("function",new JSONObject().put("name",name)))));
        JSONObject tool=new JSONObject().put("role","tool").put("tool_call_id",id).put("content",result.toString());messages.put(tool);return tool;
    }
    static JSONObject image()throws Exception {
        return new JSONObject().put("role","user").put("content",new JSONArray()
            .put(new JSONObject().put("type","text").put("text","look at this"))
            .put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/jpeg;base64,PRIVATE_IMAGE"))));
    }
    public static void main(String[] args)throws Exception {
        JSONObject raw=raw(),compact=ObservationCodec.encode(raw,"",false,false);JSONArray nodes=compact.getJSONArray("nodes");
        check(nodes.length()==2,"text child merged into clickable parent");
        check(nodes.getJSONArray(0).getInt(0)==0&&nodes.getJSONArray(0).getString(2).equals("显示和亮度"),"correct actionable id and label");
        check(!compact.toString().contains("android.widget")&&!compact.toString().contains("bounds"),"verbose details omitted");
        JSONObject filtered=ObservationCodec.encode(raw,"没有这个目标",false,false);
        check(filtered.getJSONArray("nodes").length()==1&&filtered.getInt("matches")==0,"query keeps scroll container");
        check(ObservationCodec.encode(raw,"亮度",true,false).getJSONArray("details").length()==1,"bounded matching detail");
        JSONObject unchanged=ObservationCodec.encode(raw,"",false,true);
        check(unchanged.getBoolean("unchanged")&&!unchanged.has("nodes")&&unchanged.getString("snapshot").equals("s.1"),"scene reuse with current snapshot");
        check(ObservationCodec.encode(new JSONObject().put("ok",false).put("error","locked"),"",false,false).getString("error").equals("locked"),"failure preserved");
        check(ObservationCodec.flags(new JSONObject().put("clickable",true).put("editable",true).put("checkable",true).put("checked",true).put("selected",true).put("enabled",false)).equals("ce*tx"),"state flags");
        JSONObject many=raw();JSONArray rows=new JSONArray();for(int i=0;i<80;i++)rows.put(row(i,"Control "+i,true,i));many.put("nodes",rows);
        JSONObject bounded=ObservationCodec.encode(many,"",false,false);
        check(bounded.getJSONArray("nodes").length()==48&&bounded.getInt("omitted")==32&&bounded.getBoolean("truncated"),"row budget");
        rows=new JSONArray();for(int i=0;i<20;i++)rows.put(row(i,"x".repeat(500),true,i).put("description","y".repeat(500)));many.put("nodes",rows);
        check(ObservationCodec.encode(many,"",true,false).toString().length()<=10000,"character cap includes details");
        raw.getJSONArray("nodes").put(row(3,"选项",false,0).put("checkable",true).put("checked",true));
        check(ObservationCodec.encode(raw,"",false,false).getJSONArray("nodes").length()==3,"child checkbox state retained");

        JSONArray messages=new JSONArray();JSONObject first=tool(messages,"a","phone_observe",compact);
        tool(messages,"b","phone_act",new JSONObject().put("ok",true).put("ui",unchanged));
        check(PhoneContext.prune(messages)==0,"unchanged base retained");
        JSONObject second=tool(messages,"c","phone_act",new JSONObject().put("ok",true).put("ui",compact));
        check(PhoneContext.prune(messages)==0,"two full views retained");
        tool(messages,"d","phone_observe",compact);int length=messages.length();
        check(PhoneContext.prune(messages)>0,"old view pruned after new full views");
        check(messages.length()==length&&first.getString("tool_call_id").equals("a"),"protocol pairs preserved");
        check(new JSONObject(first.getString("content")).optBoolean("historical_ui_pruned"),"prune explicitly marked");
        check(new JSONObject(second.getString("content")).getJSONObject("ui").has("nodes"),"recent full view retained");
        check(PhoneContext.prune(messages)==0,"prune idempotent");

        JSONObject image=image();JSONArray mixed=new JSONArray().put(new JSONObject().put("role","user").put("content","hello")).put(image);
        VisualSession.removeImageParts(mixed);
        check(!mixed.toString().contains("PRIVATE_IMAGE")&&!mixed.toString().contains("image_url"),"base64 removed");
        check(image.getString("content").contains("look at this")&&mixed.getJSONObject(0).getString("content").equals("hello"),"text and ordering retained");
        VisualSession.removeImageParts(mixed);check(mixed.length()==2,"image removal idempotent");
        check(VisualSession.coordinate(100,400,0)==100,"crop origin");
        check(VisualSession.coordinate(100,400,500)==300,"crop midpoint");
        check(VisualSession.coordinate(100,400,1000)==499,"crop far edge clamped");
        rejects(()->VisualSession.coordinate(0,10,1001),"out of bounds");
        rejects(()->VisualSession.coordinate(0,0,500),"empty crop");
        VisualSession visual=new VisualSession(null);BridgeService.Capture frame=new BridgeService.Capture();frame.jpeg=new byte[]{1,2,3};frame.pkg="example.app";
        visual.frame=frame;visual.id="v1";visual.created=System.currentTimeMillis();
        JSONObject tap=new JSONObject().put("action","tap").put("vision_id","v1").put("vx",500).put("vy",500).put("label","button");
        check(!visual.prepareTap(tap).getString("sensitive_reason").isEmpty(),"visual tap always confirmed");
        rejects(()->visual.prepareTap(new JSONObject(tap.toString()).put("vision_id","other")),"wrong image rejected");
        rejects(()->visual.prepareTap(new JSONObject(tap.toString()).put("vx",-1)),"negative visual coordinate");
        rejects(()->visual.prepareTap(new JSONObject(tap.toString()).put("action","swipe")),"visual swipe unsupported");
        rejects(()->visual.prepareTap(new JSONObject(tap.toString()).put("label","")),"owner needs target description");
        visual.created-=91000;rejects(()->visual.prepareTap(tap),"expired frame rejected");
        visual.requests=2;visual.uploaded=1;visual.pending=true;visual.reset();
        check(visual.frame==null&&visual.id.isEmpty()&&!visual.pending&&visual.requests==0&&visual.uploaded==0,"task reset");
        check(Arrays.equals(frame.jpeg,new byte[3]),"jpeg buffer cleared");

        modelTests();System.out.println(passed+" efficiency checks passed (no real model requests)");
    }
    static FakeConnection last;static int connections;
    static final class FakeConnection extends HttpURLConnection {
        final ByteArrayOutputStream body=new ByteArrayOutputStream();
        FakeConnection(URL url){super(url);}
        public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
        public OutputStream getOutputStream(){return body;}
        public int getResponseCode(){return 200;}
        public InputStream getInputStream(){return new ByteArrayInputStream((
            "data: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":1},\"choices\":[]}\n\n"+
            "data: {\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":2},\"choices\":[{\"delta\":{\"content\":\"done\"}}]}\n\n"+
            "data: [DONE]\n").getBytes(StandardCharsets.UTF_8));}
    }
    static void modelTests()throws Exception {
        URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")?new URLStreamHandler(){
            protected java.net.URLConnection openConnection(URL url){connections++;last=new FakeConnection(url);return last;}
        }:null);
        JSONObject config=new JSONObject().put("api_key","test-placeholder").put("base_url","https://api.deepseek.com").put("model","deepseek-flash");
        check(ModelClient.efficientFlash(config),"official Flash efficiency mode");
        check(!ModelClient.efficientFlash(new JSONObject(config.toString()).put("base_url","https://api.deepseek.com.example.org")),"no vendor fields to lookalike host");
        check(!ModelClient.efficientFlash(new JSONObject(config.toString()).put("model","other-model")),"other models untouched");
        JSONArray messages=new JSONArray().put(image());final int[] usageCalls={0};final long[] prompt={0};
        ModelClient.Stream sink=new ModelClient.Stream(){public void delta(String s){}public void usage(JSONObject u){usageCalls[0]++;prompt[0]=u.optLong("prompt_tokens");}};
        JSONObject result=new ModelClient().chat(config,messages,NativeTools.definitions(),sink);
        JSONObject body=new JSONObject(last.body.toString("UTF-8"));
        check(body.getJSONObject("thinking").getString("type").equals("disabled"),"non-thinking request");
        check(body.toString().contains("PRIVATE_IMAGE")&&body.getJSONArray("messages").getJSONObject(0).getString("role").equals("user"),"multimodal user message serialized");
        check(usageCalls[0]==1&&prompt[0]==12,"cumulative usage counted once");
        check(result.getString("content").equals("done"),"stream parsed");
        VisualSession.removeImageParts(messages);new ModelClient().chat(config,messages,null,sink);
        check(!last.body.toString("UTF-8").contains("PRIVATE_IMAGE"),"second request excludes image");
        new ModelClient().chat(new JSONObject(config.toString()).put("base_url","https://proxy.example.org"),messages,null,sink);
        check(!new JSONObject(last.body.toString("UTF-8")).has("thinking"),"proxy receives no vendor override");
        check(connections==3,"no hidden retry requests");
    }
}
