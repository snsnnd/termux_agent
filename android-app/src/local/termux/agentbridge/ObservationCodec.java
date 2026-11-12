package local.termux.agentbridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Locale;

/** Keep the full actionable tree on device; transmit a bounded semantic view. */
final class ObservationCodec {
    static String value(JSONObject row,String key) {
        String s=row.optString(key,"");return s.equals("null")?"":s.trim();
    }
    static String label(JSONObject row) {
        String a=value(row,"text"),b=value(row,"description");
        return a.isEmpty()?b:b.isEmpty()||a.equals(b)?a:a+" · "+b;
    }
    static String clip(String s,int n){return s.length()>n?s.substring(0,n)+"…":s;}
    static String flags(JSONObject n) {
        StringBuilder f=new StringBuilder();
        if(n.optBoolean("clickable"))f.append('c');if(n.optBoolean("long_clickable"))f.append('l');
        if(n.optBoolean("editable"))f.append('e');if(n.optBoolean("scrollable"))f.append('s');
        if(n.optBoolean("selected"))f.append('*');if(n.optBoolean("checkable"))f.append(n.optBoolean("checked")?'t':'f');
        if(!n.optBoolean("enabled",true))f.append('x');return f.toString();
    }
    static JSONObject encode(JSONObject raw,String query,boolean detail,boolean unchanged)throws Exception {
        if(!raw.optBoolean("ok"))return new JSONObject(raw.toString());
        JSONObject out=new JSONObject().put("ok",true).put("snapshot",raw.getString("snapshot"))
            .put("package",raw.optString("package")).put("screen",new JSONArray(new int[]{raw.optInt("width"),raw.optInt("height")}))
            .put("scene",raw.optString("scene"));
        if(unchanged&&!detail)return out.put("unchanged",true).put("reuse","Reuse previous node IDs with this NEW snapshot; the observed tree and bounds are unchanged.");
        JSONArray source=raw.optJSONArray("nodes");if(source==null)source=new JSONArray();
        LinkedHashMap<Integer,JSONObject> rows=new LinkedHashMap<>();
        LinkedHashMap<Integer,String> labels=new LinkedHashMap<>();
        for(int i=0;i<source.length();i++){JSONObject n=source.getJSONObject(i);int id=n.getInt("node_id");rows.put(id,n);labels.put(id,label(n));}
        for(JSONObject n:rows.values()) {
            int parent=n.optInt("click_id",-1),id=n.getInt("node_id");String text=label(n);
            if(parent>=0&&parent!=id&&labels.containsKey(parent)&&!text.isEmpty()) {
                String old=labels.get(parent);if(!old.contains(text))labels.put(parent,clip(old.isEmpty()?text:old+" · "+text,300));
            }
        }
        String needle=query.trim().toLowerCase(Locale.ROOT);JSONArray items=new JSONArray(),details=new JSONArray();
        int useful=0,matches=0,omitted=0;
        for(JSONObject n:rows.values()) {
            int id=n.getInt("node_id"),parent=n.optInt("click_id",-1);String text=labels.get(id),f=flags(n);
            boolean actionable=n.optBoolean("clickable")||n.optBoolean("editable")||n.optBoolean("long_clickable")||n.optBoolean("checkable");
            if(parent>=0&&parent!=id&&!actionable&&!n.optBoolean("scrollable"))continue;
            if(!text.isEmpty())useful++;
            boolean matched=needle.isEmpty()||text.toLowerCase(Locale.ROOT).contains(needle);
            if(matched)matches++;
            if(!matched&&!n.optBoolean("scrollable"))continue;
            if(items.length()>=48){omitted++;continue;}
            items.put(new JSONArray().put(id).put(f).put(clip(text,100)));
            if(detail&&matched&&details.length()<12)details.put(n);
        }
        out.put("format","nodes=[id,flags,label]; c=click,l=long,e=edit,s=scroll,*=selected,t/f=checked/unchecked,x=disabled")
            .put("nodes",items).put("matches",matches).put("useful_labels",useful)
            .put("truncated",raw.optBoolean("truncated")||omitted>0).put("omitted",omitted);
        if(!needle.isEmpty())out.put("query",clip(query,100));
        if(useful<3)out.put("vision_hint","Few accessible labels; use phone_vision only if the task needs visible content missing here.");
        if(detail)out.put("details",details);
        while(out.toString().length()>10000&&details.length()>0){details.remove(details.length()-1);out.put("truncated",true);}
        while(out.toString().length()>10000&&items.length()>1){items.remove(items.length()-1);out.put("truncated",true).put("omitted",++omitted);}
        return out;
    }
}
