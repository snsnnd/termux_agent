package local.termux.agentbridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;

/** Prune superseded UI payloads without removing tool-call/result protocol pairs. */
final class PhoneContext {
    static int prune(JSONArray messages)throws Exception {
        HashMap<String,String> names=new HashMap<>();int latest=-1,previous=-1,saved=0;
        for(int i=0;i<messages.length();i++) {
            JSONObject m=messages.getJSONObject(i);JSONArray calls=m.optJSONArray("tool_calls");
            if(calls!=null)for(int j=0;j<calls.length();j++){JSONObject c=calls.getJSONObject(j);names.put(c.getString("id"),c.getJSONObject("function").getString("name"));}
            String name=names.get(m.optString("tool_call_id"));if(!"phone_observe".equals(name)&&!"phone_act".equals(name))continue;
            JSONObject result=parse(m.optString("content"));if(result==null)continue;
            JSONObject ui="phone_act".equals(name)?result.optJSONObject("ui"):result;
            if(ui!=null&&ui.has("nodes")){previous=latest;latest=i;}
        }
        // Keep two full views and every following unchanged reply, so reused IDs
        // always have their base, including after a filtered/query observation.
        if(previous<0)return 0;
        for(int i=0;i<previous;i++) {
            JSONObject m=messages.getJSONObject(i);String name=names.get(m.optString("tool_call_id"));
            if(!"phone_observe".equals(name)&&!"phone_act".equals(name))continue;
            String old=m.optString("content");JSONObject result=parse(old);if(result==null)continue;
            JSONObject ui="phone_act".equals(name)?result.optJSONObject("ui"):result;if(ui==null)continue;
            if(!ui.has("nodes")&&!ui.has("details"))continue;
            ui.remove("nodes");ui.remove("details");ui.remove("format");ui.remove("vision_hint");
            ui.put("historical_ui_pruned",true);String next=result.toString();m.put("content",next);saved+=Math.max(0,old.length()-next.length());
        }
        return saved;
    }
    private static JSONObject parse(String text){try{return new JSONObject(text);}catch(Exception e){return null;}}
}
