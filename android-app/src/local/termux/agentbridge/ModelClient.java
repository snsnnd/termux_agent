package local.termux.agentbridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

/** Streaming OpenAI-compatible HTTPS client. Never logs headers or credentials. */
final class ModelClient {
    interface Stream { void delta(String text); void usage(JSONObject usage); }
    volatile HttpURLConnection connection;
    volatile boolean cancelled;
    void cancel() {cancelled=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    JSONObject chat(JSONObject config,JSONArray messages,JSONArray tools,Stream sink) throws Exception {
        if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException();
        JSONObject body=new JSONObject().put("model",config.getString("model")).put("messages",messages)
            .put("stream",true).put("temperature",0.4).put("stream_options",new JSONObject().put("include_usage",true));
        if(tools!=null&&tools.length()>0)body.put("tools",tools).put("tool_choice","auto");
        StringBuilder content=new StringBuilder();TreeMap<Integer,JSONObject> slots=new TreeMap<>();
        String endpoint=AgentPolicy.endpoint(config.getString("base_url"))+"/chat/completions";
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();connection=c;
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(90000);
        c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer "+config.getString("api_key"));
        c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","text/event-stream");
        try {
            try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
            int status=c.getResponseCode();
            if(status!=200)throw new IllegalStateException("模型服务 HTTP "+status+"；请检查网络、余额、模型和 API 配置。未自动重复执行工具。");
            try(BufferedReader in=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))) {
                for(String line;(line=in.readLine())!=null;) {
                    if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException();
                    if(!line.startsWith("data:"))continue;
                    String data=line.substring(5).trim();if(data.equals("[DONE]"))break;
                    JSONObject chunk;try{chunk=new JSONObject(data);}catch(Exception bad){continue;}
                    if(chunk.has("error"))throw new IllegalStateException("模型返回流式错误；请稍后重试。");
                    if(chunk.optJSONObject("usage")!=null)sink.usage(chunk.getJSONObject("usage"));
                    JSONArray choices=chunk.optJSONArray("choices");if(choices==null||choices.length()==0)continue;
                    JSONObject delta=choices.getJSONObject(0).optJSONObject("delta");if(delta==null)continue;
                    String text=delta.optString("content","");
                    if(!text.equals("null")&&!text.isEmpty()){content.append(text);sink.delta(text);}
                    JSONArray calls=delta.optJSONArray("tool_calls");if(calls==null)continue;
                    for(int i=0;i<calls.length();i++) {
                        JSONObject part=calls.getJSONObject(i);int index=part.optInt("index",0);
                        JSONObject slot=slots.get(index);
                        if(slot==null){slot=new JSONObject().put("id","").put("name","").put("arguments","");slots.put(index,slot);}
                        if(part.has("id"))slot.put("id",part.getString("id"));
                        JSONObject fn=part.optJSONObject("function");if(fn==null)continue;
                        if(fn.has("name"))slot.put("name",fn.getString("name"));
                        if(fn.has("arguments"))slot.put("arguments",slot.getString("arguments")+fn.getString("arguments"));
                    }
                }
            }
            JSONObject result=new JSONObject().put("role","assistant").put("content",content.toString());
            if(!slots.isEmpty()) {
                JSONArray calls=new JSONArray();
                for(int index:slots.keySet()) {
                    JSONObject s=slots.get(index);calls.put(new JSONObject().put("id",s.optString("id").isEmpty()?"call_"+index:s.getString("id"))
                        .put("type","function").put("function",new JSONObject().put("name",s.getString("name"))
                        .put("arguments",s.getString("arguments").isEmpty()?"{}":s.getString("arguments"))));
                }
                result.put("tool_calls",calls);if(content.length()==0)result.put("content",JSONObject.NULL);
            }
            return result;
        } catch(Exception e) {
            if(content.length()>0&&!cancelled&&slots.isEmpty())return new JSONObject().put("role","assistant")
                .put("content",content+"\n\n[连接中断，以上为部分回答；未自动重试。]");
            throw e;
        } finally {c.disconnect();connection=null;}
    }
}
