package local.termux.agentbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Adaptive native interface; no WebView, terminal, or remote UI server. */
public final class MainActivity extends Activity implements AgentService.Listener {
    AgentStore store;
    Palette c;
    boolean wide;
    String page="chat",signature="",approvalId="",filePath="";
    String draft="",exportSession="";
    LinearLayout root,content,sidebar,messages,approvalSlot;
    ScrollView chatScroll;
    TextView status,modelName,title,live;
    View liveHolder;
    EditText composer;
    Button send,stop;
    int renderedCount=-1;
    static final int HISTORY_PAGE=16;
    final ExecutorService historyExecutor=Executors.newSingleThreadExecutor();
    JSONArray historyRows=new JSONArray();
    String historySession="",streamRendered="";
    long historyRevision=-1,historyBefore=0,oldestHistory=0;
    int historyGeneration=0;
    boolean historyLoading,historyReady,hasOlder;
    static volatile JSONObject historyMetrics=new JSONObject();

    static final class Palette {
        final int bg,panel,text,muted,border,accent,onAccent,soft,danger,warn;
        Palette(boolean dark) {
            bg=Color.parseColor(dark?"#111916":"#F5F7F4");panel=Color.parseColor(dark?"#1B2621":"#FFFFFF");
            text=Color.parseColor(dark?"#EDF4EF":"#172B23");muted=Color.parseColor(dark?"#AFBEB6":"#52675C");
            border=Color.parseColor(dark?"#34483D":"#DCE5DE");accent=Color.parseColor(dark?"#77DDB6":"#087F66");
            onAccent=Color.parseColor(dark?"#073D2B":"#FFFFFF");soft=Color.parseColor(dark?"#253B30":"#E7F2EA");
            danger=Color.parseColor(dark?"#FFB4A8":"#AD302B");warn=Color.parseColor(dark?"#39301C":"#FFF2D9");
        }
    }
    @Override public void onCreate(Bundle state) {
        boolean dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);c=new Palette(dark);store=AgentStore.get(this);
        getWindow().setStatusBarColor(c.bg);getWindow().setNavigationBarColor(c.bg);
        if(!dark)getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(state!=null){page=state.getString("page","chat");draft=state.getString("draft","");filePath=state.getString("filePath","");}
        build();
        // No task, credential or authorization extras are accepted from exported intents.
    }
    @Override protected void onStart(){super.onStart();AgentService.observe(this);refresh();}
    @Override protected void onStop(){if(AgentService.listener.get()==this)AgentService.observe(null);super.onStop();}
    @Override protected void onDestroy(){historyGeneration++;historyExecutor.shutdownNow();super.onDestroy();}
    @Override protected void onResume(){super.onResume();if(status!=null)refresh();}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("page",page);out.putString("draft",composer==null?draft:composer.getText().toString());out.putString("filePath",filePath);super.onSaveInstanceState(out);}
    @Override public void changed(){refresh();}
    int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    GradientDrawable background(int fill,int radius,boolean border){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(border)d.setStroke(dp(1),c.border);return d;}
    TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1.06f);return t;}
    TextView heading(String value,int size){TextView t=text(value,size,c.text);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    Button button(String label,boolean primary,View.OnClickListener click){
        Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));
        b.setPadding(dp(16),dp(8),dp(16),dp(8));b.setTextColor(primary?c.onAccent:c.text);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(c.border),background(primary?c.accent:c.panel,12,!primary),null));
        b.setOnClickListener(click);return b;
    }
    void gap(LinearLayout parent,int height){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    void divider(LinearLayout parent){View v=new View(this);v.setBackgroundColor(c.border);parent.addView(v,new LinearLayout.LayoutParams(-1,dp(1)));}
    EditText input(String hint,boolean multiline){EditText e=new EditText(this);e.setTextColor(c.text);e.setHintTextColor(c.muted);e.setTextSize(16);e.setHint(hint);
        e.setBackgroundTintList(ColorStateList.valueOf(c.accent));e.setPadding(dp(12),dp(12),dp(12),dp(12));e.setSingleLine(!multiline);if(multiline){e.setMaxLines(5);e.setGravity(Gravity.TOP);}return e;}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    void error(Exception e){new AlertDialog.Builder(this).setTitle("暂时无法完成").setMessage(String.valueOf(e.getMessage())).setPositiveButton("知道了",null).show();}
    void navigate(String target){if(composer!=null)draft=composer.getText().toString();page=target;build();}

    void build(){
        signature="";approvalId="";renderedCount=-1;live=null;liveHolder=null;composer=null;
        historyGeneration++;historyBefore=0;historyLoading=false;historyReady=false;historyRevision=-1;historySession="";historyRows=new JSONArray();streamRendered="";
        wide=getResources().getConfiguration().screenWidthDp>=840;
        root=wide?row():column();root.setBackgroundColor(c.bg);setContentView(root);
        if(wide){sidebar=column();sidebar.setPadding(dp(20),dp(24),dp(20),dp(20));sidebar.setBackgroundColor(c.panel);root.addView(sidebar,new LinearLayout.LayoutParams(dp(232),-1));buildSidebar();}
        else sidebar=null;
        LinearLayout main=column();root.addView(main,new LinearLayout.LayoutParams(wide?0:-1,wide?-1:0,1));
        LinearLayout header=row();header.setPadding(dp(20),dp(14),dp(20),dp(12));
        ImageView mark=new ImageView(this);mark.setImageResource(R.drawable.ic_agent);mark.setContentDescription("Pocket Agent");header.addView(mark,new LinearLayout.LayoutParams(dp(32),dp(32)));
        LinearLayout names=column();names.setPadding(dp(12),0,dp(8),0);title=heading("Pocket Agent",18);names.addView(title);
        modelName=text("",12,c.muted);names.addView(modelName);header.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        if(!wide)header.addView(button("会话",false,v->sessionDialog()));
        stop=button("停止",false,v->{AgentService s=AgentService.current;if(s!=null){s.cancelTask();refresh();}});stop.setTextColor(c.danger);
        LinearLayout.LayoutParams stopP=new LinearLayout.LayoutParams(-2,-2);stopP.leftMargin=dp(8);header.addView(stop,stopP);main.addView(header);
        if(!wide){LinearLayout tabs=row();tabs.setPadding(dp(16),0,dp(16),dp(8));String[] ids={"chat","files","settings"},labels={"对话","文件","设置"};
            for(int i=0;i<ids.length;i++){final String id=ids[i];Button b=button(labels[i],page.equals(id),v->navigate(id));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(i>0)p.leftMargin=dp(8);tabs.addView(b,p);}main.addView(tabs);}
        status=text("",13,c.muted);status.setPadding(dp(24),dp(4),dp(24),dp(12));main.addView(status);divider(main);
        content=column();int inset=wide?Math.max(24,(getResources().getConfiguration().screenWidthDp-232-900)/2):16;
        content.setPadding(dp(inset),0,dp(inset),0);main.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        if(page.equals("chat"))buildChat();else if(page.equals("files"))buildFiles();else buildSettings();refresh();
    }
    void buildSidebar(){
        sidebar.removeAllViews();TextView word=heading("POCKET\nAGENT",25);word.setLetterSpacing(0.07f);sidebar.addView(word);gap(sidebar,8);
        sidebar.addView(text("你的本机执行助手",13,c.muted));gap(sidebar,24);
        sidebar.addView(button("＋  新对话",true,v->{if(composer!=null)draft=composer.getText().toString();store.newSession();draft="";page="chat";build();}));gap(sidebar,16);
        String[] ids={"chat","files","settings"},labels={"对话与任务","工作区文件","模型与权限"};
        for(int i=0;i<ids.length;i++){final String id=ids[i];sidebar.addView(button(labels[i],false,v->navigate(id)));gap(sidebar,8);}
        gap(sidebar,16);sidebar.addView(text("最近会话",12,c.muted));gap(sidebar,8);
        ScrollView list=new ScrollView(this);LinearLayout items=column();list.addView(items);sidebar.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        try{JSONArray sessions=store.sessions();for(int i=0;i<Math.min(30,sessions.length());i++){
            JSONObject item=sessions.getJSONObject(i);final String id=item.getString("id");
            Button b=button(item.getString("title"),false,v->{store.prefs.edit().putString("session",id).apply();draft="";page="chat";build();});
            b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);b.setMaxLines(2);if(id.equals(store.selected()))b.setTextColor(c.accent);
            items.addView(b);gap(items,6);
        }}catch(Exception e){toast("会话列表暂不可用");}
        gap(sidebar,16);TextView local=text("独立 Android 服务\n无需 Termux · 设备操作可随时暂停",12,c.muted);sidebar.addView(local);gap(sidebar,12);
        Button emergency=button("紧急停止",false,v->emergency());emergency.setTextColor(c.danger);sidebar.addView(emergency);
    }
    void buildChat(){
        chatScroll=new ScrollView(this);chatScroll.setFillViewport(true);messages=column();messages.setPadding(dp(8),dp(24),dp(8),dp(24));chatScroll.addView(messages);
        messages.addView(text("正在读取会话…",14,c.muted));
        content.addView(chatScroll,new LinearLayout.LayoutParams(-1,0,1));
        approvalSlot=column();content.addView(approvalSlot);
        LinearLayout dock=column();dock.setPadding(0,dp(12),0,dp(12));
        LinearLayout box=row();box.setPadding(dp(8),dp(4),dp(8),dp(4));box.setBackground(background(c.panel,16,true));
        composer=input("描述你想完成的事…",true);composer.setText(draft);composer.setBackgroundColor(Color.TRANSPARENT);composer.setMinHeight(dp(48));
        box.addView(composer,new LinearLayout.LayoutParams(0,-2,1));send=button("发送",true,v->send());box.addView(send);dock.addView(box);
        TextView privacy=text("执行前可确认每一步。任务与相关界面文字会发送给模型服务。",12,c.muted);privacy.setPadding(dp(4),dp(8),dp(4),0);dock.addView(privacy);content.addView(dock);
    }
    void send(){
        String value=composer.getText().toString().trim();if(value.isEmpty()||AgentService.isBusy())return;
        try{if(store.apiKey().isEmpty()){toast("请先配置模型 API");navigate("settings");return;}}catch(Exception e){error(e);return;}
        if(!store.prefs.getBoolean("cloud_consent",false)){
            new AlertDialog.Builder(this).setTitle("允许使用模型服务？").setMessage("任务、相关历史、工具结果及完成任务所需的屏幕文字会发送到你配置的模型服务商。密码字段和本 App 界面不会通过无障碍读取。请勿提交不希望上传的信息。")
                .setNegativeButton("暂不发送",null).setPositiveButton("同意并发送",(d,w)->{store.prefs.edit().putBoolean("cloud_consent",true).commit();submit(value);}).show();return;
        }
        submit(value);
    }
    void submit(String value){
        try{String id=store.selected();JSONArray old=store.messages(id,1);if(old.length()==0)store.rename(id,value.length()>18?value.substring(0,18)+"…":value);
            composer.setText("");draft="";historyBefore=0;historyRevision=-1;((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(composer.getWindowToken(),0);
            startForegroundService(new Intent(this,AgentService.class).setAction(AgentService.SEND).putExtra("session",id).putExtra("text",value));
        }catch(Exception e){error(e);}
    }
    void welcome(){
        gap(messages,32);messages.addView(text("本机执行 / NATIVE AGENT",12,c.accent));gap(messages,16);
        messages.addView(heading("把任务交给手机。",wide?36:29));gap(messages,16);
        messages.addView(text("对话、记忆和工具都在这里。你提出目标，\n助手分步执行，每一次操作都看得见。",16,c.muted));gap(messages,28);
        suggestion("检查设备连接","检查本机无障碍服务连接状态，不要读取屏幕。");
        suggestion("打开系统设置","打开系统设置，读取当前页面，不修改任何设置。");
        suggestion("看看我的工作区","列出当前 App 工作区文件，说明哪些来自旧项目，不修改文件。");
    }
    void suggestion(String label,String prompt){Button b=button(label+"  →",false,v->{composer.setText(prompt);composer.requestFocus();});messages.addView(b);gap(messages,10);}
    TextView messageBody(String value){TextView t=text("",16,c.text);t.setTextIsSelectable(true);t.setText(markdown(value));return t;}
    CharSequence markdown(String value){
        SpannableStringBuilder out=new SpannableStringBuilder(value.replaceAll("(?m)^#{1,3} +","").replaceAll("(?m)^- +","• "));
        Pattern p=Pattern.compile("\\*\\*([^*]+)\\*\\*");int start=0;
        while(start<out.length()){Matcher m=p.matcher(out);if(!m.find(start))break;int a=m.start();String plain=m.group(1);out.replace(a,m.end(),plain);out.setSpan(new StyleSpan(Typeface.BOLD),a,a+plain.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);start=a+plain.length();}
        return out;
    }
    void message(JSONObject item)throws Exception{
        boolean user=item.optString("role").equals("user"),tool=item.optString("kind").equals("tool"),notice=item.optString("kind").equals("notice");
        LinearLayout card=column();card.setPadding(dp(16),dp(14),dp(16),dp(16));
        if(user||tool||notice)card.setBackground(background(user?c.soft:c.panel,12,tool));
        LinearLayout label=row();TextView who=text(tool?"工具执行记录":user?"你":"POCKET AGENT",11,user?c.muted:c.accent);who.setLetterSpacing(0.06f);label.addView(who,new LinearLayout.LayoutParams(0,-2,1));
        label.addView(text(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(item.optLong("created"))),11,c.muted));card.addView(label);gap(card,8);
        String value=item.optString("content");
        if(tool){String name=value.split("\n",2)[0];lazyDetails(card,name+" · 查看结果",value,true);}
        else {
            String answer=AgentPolicy.historyAnswer(value);
            if(answer.length()>1600){TextView preview=messageBody(AgentPolicy.cap(answer,1200));card.addView(preview);gap(card,8);lazyDetails(card,"展开完整消息（"+answer.length()+" 字符）",answer,false);}
            else card.addView(messageBody(answer));
            if(!answer.equals(value)){gap(card,8);lazyDetails(card,"查看旧版工具执行记录",value.substring(0,value.indexOf("[/work log]")+11),true);}
        }
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(16);if(user)params.leftMargin=dp(wide?80:24);messages.addView(card,params);
    }
    void lazyDetails(LinearLayout card,String label,String value,boolean mono){
        Button toggle=button(label,false,null);LinearLayout slot=column();card.addView(toggle);card.addView(slot);
        toggle.setOnClickListener(v->{if(slot.getChildCount()>0){slot.removeAllViews();toggle.setText(label);}else{TextView body=messageBody(value);if(mono){body.setTextSize(13);body.setTypeface(Typeface.MONOSPACE);}slot.addView(body);toggle.setText("收起详情");}});
    }
    void loadHistory(){
        String sid=store.selected();long rev=store.revision();
        if(historyLoading||(historyReady&&sid.equals(historySession)&&historyRevision==rev))return;
        historyLoading=true;final int request=historyGeneration;final long before=historyBefore;
        historyExecutor.submit(()->{
            long start=android.os.SystemClock.elapsedRealtime();
            try{
                JSONArray rows=store.messagePage(sid,before,HISTORY_PAGE+1);boolean older=rows.length()>HISTORY_PAGE;if(older)rows.remove(0);
                long queryMs=android.os.SystemClock.elapsedRealtime()-start;
                runOnUiThread(()->{
                    if(isDestroyed()||request!=historyGeneration)return;
                    historyLoading=false;if(before!=historyBefore){historyRevision=-1;refresh();return;}
                    historyRows=rows;historyReady=true;historySession=sid;historyRevision=rev;hasOlder=older;
                    oldestHistory=rows.length()==0?0:rows.optJSONObject(0).optLong("id");signature="";
                    try{historyMetrics=new JSONObject().put("session",sid).put("query_ms",queryMs).put("page_rows",rows.length()).put("before_id",before).put("query_off_main_thread",true);}catch(Exception ignored){}
                    refresh();
                });
            }catch(Exception e){runOnUiThread(()->{if(request!=historyGeneration||isDestroyed())return;historyLoading=false;toast("读取会话失败，请重试");});}
        });
    }
    void refresh(){
        if(status==null)return;
        AgentService service=AgentService.current;boolean busy=AgentService.isBusy();
        status.setText((BridgeService.current==null?"无障碍未连接":getSharedPreferences("bridge",MODE_PRIVATE).getBoolean("allowed",false)?"设备控制已就绪":"设备控制已暂停")+"  ·  "+(service==null?"独立服务待命":service.status));
        modelName.setText(store.prefs.getString("model","deepseek-flash")+" · 原生 Android");stop.setEnabled(busy);stop.setAlpha(busy?1:0.45f);
        if(!page.equals("chat"))return;
        send.setEnabled(!busy);send.setAlpha(busy?0.45f:1);send.setText(busy?"执行中":"发送");
        try{
            loadHistory();String selected=store.selected();JSONArray rows=historyRows;if(!historyReady||!historySession.equals(selected))return;
            String next=selected+":"+historyBefore+":"+rows.length()+":"+(rows.length()==0?0:rows.getJSONObject(rows.length()-1).getLong("id"));
            boolean bottom=messages.getHeight()-chatScroll.getScrollY()-chatScroll.getHeight()<dp(180);
            if(!signature.equals(next)){
                long renderStart=android.os.SystemClock.elapsedRealtime();signature=next;messages.removeAllViews();live=null;liveHolder=null;streamRendered="";
                if(hasOlder||historyBefore>0){LinearLayout paging=row();
                    if(hasOlder)paging.addView(button("查看更早消息",false,v->{historyBefore=oldestHistory;historyRevision=-1;historyReady=false;refresh();}),new LinearLayout.LayoutParams(0,-2,1));
                    if(historyBefore>0)paging.addView(button("回到最新",false,v->{historyBefore=0;historyRevision=-1;historyReady=false;refresh();}),new LinearLayout.LayoutParams(0,-2,1));messages.addView(paging);gap(messages,16);}
                if(rows.length()==0&&!busy)welcome();else for(int i=0;i<rows.length();i++)message(rows.getJSONObject(i));
                renderedCount=rows.length();if(bottom||rows.length()<3)chatScroll.post(()->chatScroll.fullScroll(View.FOCUS_DOWN));
                JSONObject metric=new JSONObject(historyMetrics.toString());metric.put("create_views_ms",android.os.SystemClock.elapsedRealtime()-renderStart);metric.put("message_cards",rows.length());historyMetrics=metric;
            }
            String streaming=historyBefore==0&&service!=null&&service.session.equals(selected)?service.liveText:"";
            if(!streaming.isEmpty()){
                if(live==null){LinearLayout holder=column();holder.setPadding(dp(16),dp(8),dp(16),dp(16));holder.addView(text("POCKET AGENT · 正在回复",11,c.accent));gap(holder,8);live=messageBody("");holder.addView(live);messages.addView(holder);liveHolder=holder;}
                if(!streamRendered.equals(streaming)){live.setText(streaming);streamRendered=streaming;if(bottom)chatScroll.post(()->chatScroll.fullScroll(View.FOCUS_DOWN));}
            }else if(liveHolder!=null){messages.removeView(liveHolder);liveHolder=null;live=null;}
            refreshApproval(service);
        }catch(Exception e){toast("暂时无法读取本机会话");}
    }
    void refreshApproval(AgentService service)throws Exception{
        AgentService.Approval pending=service==null?null:service.approval;String id=pending==null?"":pending.id;
        if(id.equals(approvalId))return;approvalId=id;approvalSlot.removeAllViews();if(pending==null)return;
        LinearLayout box=column();box.setPadding(dp(16),dp(12),dp(16),dp(12));box.setBackground(background(c.warn,12,false));
        box.addView(heading("下一步需要你的确认",17));gap(box,6);
        if(pending.taskGrant)box.addView(text("本任务授权后，剩余普通界面操作自动执行；任务结束失效，敏感动作仍需确认。",13,c.muted));
        if(pending.target!=null&&!pending.target.optString("sensitive_reason").isEmpty())box.addView(text("单独确认原因："+pending.target.optString("sensitive_reason"),13,c.danger));
        if(!service.session.equals(store.selected())){box.addView(button("前往正在执行的会话",true,v->{store.prefs.edit().putString("session",service.session).apply();build();}));approvalSlot.addView(box);return;}
        TextView details=text(pending.name+"\n"+AgentPolicy.cap(pending.args.toString(2),1400)+(pending.target==null?"":"\n确认目标："+pending.target.toString(2)),13,c.text);details.setTypeface(Typeface.MONOSPACE);
        ScrollView detailScroll=new ScrollView(this);detailScroll.addView(details);box.addView(detailScroll,new LinearLayout.LayoutParams(-1,dp(100)));
        if(pending.name.equals("phone_act"))box.addView(text("也可在通知栏直接允许/拒绝，无需切回助手。",12,c.muted));
        LinearLayout actions=row();actions.addView(button("拒绝",false,v->service.answer(id,false)),new LinearLayout.LayoutParams(0,-2,1));
        Button yes=button(pending.taskGrant?"允许本任务":"允许这一步",true,v->{
            if(pending.name.equals("phone_act")){moveTaskToBack(true);AgentService.UI.postDelayed(()->service.answer(id,true),400);}
            else service.answer(id,true);
        });LinearLayout.LayoutParams yesP=new LinearLayout.LayoutParams(0,-2,1);yesP.leftMargin=dp(8);actions.addView(yes,yesP);gap(box,8);box.addView(actions);approvalSlot.addView(box);
    }

    LinearLayout scrollPage(){ScrollView scroll=new ScrollView(this);LinearLayout body=column();body.setPadding(dp(8),dp(24),dp(8),dp(32));scroll.addView(body);content.addView(scroll,new LinearLayout.LayoutParams(-1,-1));return body;}
    void section(LinearLayout body,String title,String explanation){gap(body,24);body.addView(heading(title,21));gap(body,8);if(!explanation.isEmpty()){body.addView(text(explanation,14,c.muted));gap(body,12);}}
    void buildFiles(){
        LinearLayout body=scrollPage();body.addView(heading("工作区",28));gap(body,8);body.addView(text("App 私有文件 · 不与 Termux 共享权限",14,c.muted));gap(body,20);
        LinearLayout actions=row();actions.addView(button("导入文件",true,v->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,21);}),new LinearLayout.LayoutParams(0,-2,1));
        Button create=button("新建文件",false,v->newFile());LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);cp.leftMargin=dp(8);actions.addView(create,cp);body.addView(actions);gap(body,16);
        if(!filePath.isEmpty()){body.addView(button("← 上一级",false,v->{File f=new File(filePath).getParentFile();filePath=f==null?"":f.toString();build();}));gap(body,12);}
        body.addView(text(filePath.isEmpty()?"workspace /":"workspace / "+filePath,12,c.muted));gap(body,12);
        try{File dir=AgentPolicy.safeFile(store.workspace,filePath);File[] files=dir.listFiles();if(files==null||files.length==0){body.addView(text("这里还没有文件。你可以导入文档，或让助手创建内容。",16,c.muted));return;}
            java.util.Arrays.sort(files);int count=0;
            for(File f:files){if(count++>=300)break;String relative=store.workspace.toPath().relativize(f.toPath()).toString();Button b=button((f.isDirectory()?"目录  ":"文件  ")+f.getName()+(f.isFile()?"   · "+f.length()+" B":""),false,v->{if(f.isDirectory()){filePath=relative;build();}else editFile(relative);});b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);body.addView(b);gap(body,8);}
        }catch(Exception e){error(e);}
    }
    void newFile(){EditText name=input("例如 notes/todo.md",false);new AlertDialog.Builder(this).setTitle("新建工作区文件").setView(name).setNegativeButton("取消",null)
        .setPositiveButton("创建",(d,w)->{String rel=filePath.isEmpty()?name.getText().toString():filePath+"/"+name.getText();try{File file=AgentPolicy.safeFile(store.workspace,rel);if(file.exists())throw new IllegalArgumentException("文件已存在");editFile(rel);}catch(Exception e){error(e);}}).show();}
    void editFile(String rel){
        try{File f=AgentPolicy.safeFile(store.workspace,rel);if(f.length()>200000){toast("文件超过编辑器 200 KB 上限，可让助手分段读取。");return;}
            String value=f.exists()?new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8):"";if(value.indexOf('\0')>=0){toast("二进制文件不在文本编辑器中打开。");return;}
            EditText edit=input("文件内容",true);edit.setMaxLines(16);edit.setMinLines(5);edit.setTypeface(Typeface.MONOSPACE);edit.setTextSize(14);edit.setText(value);
            new AlertDialog.Builder(this).setTitle(rel).setView(edit).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{
                if(AgentService.isBusy()){toast("请先停止任务，避免同时修改文件。");return;}
                try{new NativeTools(store).execute("write_file",new JSONObject().put("path",rel).put("content",edit.getText().toString()));toast("文件已保存");build();}catch(Exception e){error(e);}
            }).show();
        }catch(Exception e){error(e);}
    }

    void buildSettings(){
        LinearLayout body=scrollPage();body.addView(heading("设置",28));gap(body,8);body.addView(text("模型、权限与本机运行状态",14,c.muted));
        section(body,"模型服务","API 密钥使用 Android Keystore 加密，不写入聊天记录或安装包。");
        body.addView(text("API 地址（HTTPS）",13,c.muted));EditText url=input("https://api.deepseek.com",false);url.setText(store.prefs.getString("base_url","https://api.deepseek.com"));body.addView(url);
        gap(body,8);body.addView(text("模型 ID",13,c.muted));EditText model=input("deepseek-flash",false);model.setText(store.prefs.getString("model","deepseek-flash"));body.addView(model);
        gap(body,8);body.addView(text("API 密钥",13,c.muted));EditText key=input(store.prefs.contains("key_cipher")?"已安全保存；留空保持原密钥":"粘贴 API 密钥",false);key.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);body.addView(key);gap(body,12);
        body.addView(button("保存模型配置",true,v->{try{if(AgentService.isBusy())throw new IllegalStateException("请等待当前任务结束再修改配置。");AgentPolicy.endpoint(url.getText().toString().trim());String m=model.getText().toString().trim();if(m.isEmpty())throw new IllegalArgumentException("模型 ID 不能为空");if(!key.getText().toString().trim().isEmpty())store.saveKey(key.getText().toString().trim());store.prefs.edit().putString("base_url",url.getText().toString().trim()).putString("model",m).commit();key.setText("");toast("配置已保存");refresh();}catch(Exception e){error(e);}}));
        gap(body,8);Button test=button("测试模型连接",false,null);test.setOnClickListener(v->{if(!store.prefs.getBoolean("cloud_consent",false)){toast("请先允许下方的云端模型处理。");return;}test.setEnabled(false);test.setText("正在测试…");new Thread(()->{String result;try{JSONObject reply=new ModelClient().chat(store.config(),new JSONArray().put(new JSONObject().put("role","user").put("content","只回复：连接成功")),null,new ModelClient.Stream(){public void delta(String t){}public void usage(JSONObject u){}});result=reply.optString("content","").isEmpty()?"模型未返回正文":"模型连接成功";}catch(Exception e){result=String.valueOf(e.getMessage());}final String text=result;runOnUiThread(()->{if(isFinishing())return;test.setEnabled(true);test.setText("测试模型连接");toast(text);});},"model-connection-test").start();});body.addView(test);
        Switch consent=new Switch(this);consent.setText("允许云端模型处理任务与相关界面文字");consent.setTextColor(c.text);consent.setTextSize(14);consent.setMinHeight(dp(56));consent.setChecked(store.prefs.getBoolean("cloud_consent",false));consent.setOnCheckedChangeListener((b,on)->store.prefs.edit().putBoolean("cloud_consent",on).commit());body.addView(consent);
        section(body,"视觉补盲 · 按需使用","默认只发送精简无障碍控件。目标缺失时才请求低细节截图，图片仅发送一轮，不写入聊天记录。视觉仍可能误读，不能代替操作结果验证。");
        Switch visual=new Switch(this);visual.setText("允许必要的截图发送给已配置模型");visual.setTextSize(14);visual.setTextColor(c.text);visual.setMinHeight(dp(56));visual.setChecked(store.prefs.getBoolean("visual_consent",false));
        visual.setOnCheckedChangeListener((b,on)->{
            if(!on){store.prefs.edit().putBoolean("visual_consent",false).commit();return;}
            if(!store.prefs.getBoolean("cloud_consent",false)){visual.setChecked(false);toast("请先允许云端模型处理。");return;}
            AlertDialog warning=new AlertDialog.Builder(this).setTitle("允许按需上传界面截图？")
                .setMessage("截图可能包含私人内容，将发送给你配置的模型服务商（需支持图片输入）。控件文字优先；默认每任务最多尝试 3 次截图，只发送一轮，App 不保存图片。会尝试遮蔽其他窗口，拒绝已识别的密码、支付/银行及系统授权页面；识别不能保证完整，请勿在隐私页面使用。视觉坐标点击仍逐次确认，锁屏与系统受保护窗口不会绕过。关闭后不再发送新图；已经发出的请求不能撤回。")
                .setNegativeButton("保持关闭",(d,w)->visual.setChecked(false))
                .setPositiveButton("了解并允许",(d,w)->store.prefs.edit().putBoolean("visual_consent",true).commit()).create();
            warning.setOnDismissListener(d->{if(!store.prefs.getBoolean("visual_consent",false))visual.setChecked(false);});warning.show();
        });body.addView(visual);
        Button budget=button("每任务截图尝试上限："+store.visualBudget()+" 次",false,null);budget.setOnClickListener(v->{
            String[] values={"1 次 · 最省","2 次","3 次 · 默认","4 次","5 次"};
            new AlertDialog.Builder(this).setTitle("每任务视觉预算").setSingleChoiceItems(values,store.visualBudget()-1,(dialog,index)->{store.prefs.edit().putInt("visual_budget",index+1).commit();budget.setText("每任务截图尝试上限："+(index+1)+" 次");dialog.dismiss();}).setNegativeButton("返回",null).show();
        });body.addView(budget);gap(body,8);
        body.addView(text("官方 deepseek-flash 使用非思考模式，减少逐步执行的推理开销。截图采用 low 细节，小字可请求局部裁剪；低细节不等于零图片 token。更新后如系统未授予截图能力，需手动重新启用无障碍服务。",13,c.muted));
        section(body,"设备控制",BridgeService.current==null?"系统无障碍尚未连接。请手动启用服务。":"无障碍服务已连接；无需配对或打开 Termux。");
        body.addView(button("打开系统无障碍设置",false,v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        Switch allowed=new Switch(this);allowed.setText("允许本机助手操作设备");allowed.setTextColor(c.text);allowed.setMinHeight(dp(56));allowed.setChecked(getSharedPreferences("bridge",MODE_PRIVATE).getBoolean("allowed",false));allowed.setOnCheckedChangeListener((b,on)->{getSharedPreferences("bridge",MODE_PRIVATE).edit().putBoolean("allowed",on).commit();refresh();});body.addView(allowed);
        String mode=store.prefs.getString("phone_confirmation","task");
        String[] modeIds={"step","task","auto"},modeLabels={"逐步确认","本任务授权一次（默认）","持续自动：普通界面"};
        int selected=mode.equals("step")?0:mode.equals("auto")?2:1;
        Button confirmation=button("界面确认方式："+modeLabels[selected],false,null);
        confirmation.setOnClickListener(v->{new AlertDialog.Builder(this).setTitle("界面操作确认方式").setSingleChoiceItems(modeLabels,selected,(dialog,index)->{
            dialog.dismiss();
            if(index==2)new AlertDialog.Builder(this).setTitle("持续自动执行普通界面操作？")
                .setMessage("此选项跨任务生效，模型可自动点击、滑动和输入。AI 和敏感动作识别可能误判；网页、消息也可能包含恶意指令。敏感动作仍尝试单独确认，但不能保证拦住所有风险。它不提供 root，不绕过锁屏、密码字段、本 App 保护或 Android 权限。请勿在银行/支付/隐私页面无人值守使用。")
                .setNegativeButton("取消",null).setPositiveButton("了解风险，开启",(d,w)->{store.prefs.edit().putString("phone_confirmation","auto").commit();build();}).show();
            else{store.prefs.edit().putString("phone_confirmation",modeIds[index]).commit();build();}
        }).setNegativeButton("返回",null).show();});gap(body,8);body.addView(confirmation);gap(body,12);
        body.addView(text("本任务授权只覆盖普通界面动作，不自动授权 Shell、文件修改或敏感操作。通知栏可直接允许/拒绝；任务结束、取消或进程退出后，任务授权失效。",13,c.muted));
        Switch extended=new Switch(this);extended.setText("高级：扩展免审批（本 App 工作区 / Shell）");extended.setTextSize(14);extended.setTextColor(c.text);extended.setMinHeight(dp(56));extended.setChecked(store.prefs.getBoolean("unattended_tools",false));
        extended.setOnCheckedChangeListener((b,on)->{
            if(!on){store.prefs.edit().putBoolean("unattended_tools",false).commit();return;}
            AlertDialog warning=new AlertDialog.Builder(this).setTitle("开启 App 内扩展自动执行？")
                .setMessage("只影响本 App 已拥有权限内的工作区写改和 Android Shell，不是系统全权限或 root。命令和文件修改可能造成数据丢失、网络请求或隐私泄露，风险识别不能保证完整。已识别的敏感命令及 confirm_action 仍要求确认。默认关闭，建议仅在可信任务需要时临时开启。")
                .setNegativeButton("保持关闭",(d,w)->extended.setChecked(false))
                .setPositiveButton("了解风险，开启",(d,w)->store.prefs.edit().putBoolean("unattended_tools",true).commit()).create();
            warning.setOnDismissListener(d->{if(!store.prefs.getBoolean("unattended_tools",false))extended.setChecked(false);});warning.show();
        });body.addView(extended);
        body.addView(text("App 内免审批不是安全沙箱，也不等于获得所有 Android 权限。系统授权、密码/锁屏与其他 App 私有目录的限制仍在。",13,c.muted));gap(body,12);
        Button emergency=button("紧急停止任务并暂停设备控制",false,v->emergency());emergency.setTextColor(c.danger);body.addView(emergency);
        section(body,"会话与数据","迁移后的旧会话以“旧版 · …”显示。旧 Termux 项目未删除。");
        body.addView(button("管理会话",false,v->sessionDialog()));gap(body,8);
        body.addView(button("导出当前会话",false,v->{exportSession=store.selected();Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/markdown").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"agent-session-"+System.currentTimeMillis()+".md");startActivityForResult(intent,22);}));gap(body,8);
        body.addView(button("整理当前会话记忆",false,v->{if(AgentService.isBusy()){toast("请等待当前任务结束");return;}if(!store.prefs.getBoolean("cloud_consent",false)){toast("请先允许云端模型处理");return;}startForegroundService(new Intent(this,AgentService.class).setAction(AgentService.COMPACT).putExtra("session",store.selected()));navigate("chat");}));gap(body,12);
        String migrated=store.meta("migration_state","尚未导入旧数据");body.addView(text("迁移状态："+migrated+"\n旧版消息："+store.meta("migration_messages","0")+" 条\n原生累计用量：输入 "+store.meta("native:prompt_tokens","0")+" / 输出 "+store.meta("native:completion_tokens","0")+" token",13,c.muted));
        try{JSONObject m=new JSONObject(store.meta("last_task_metrics","{}"));if(m.has("model_requests"))body.addView(text("最近任务："+m.optInt("model_requests")+" 次模型请求 · 输入 "+m.optLong("prompt_tokens")+" / 输出 "+m.optLong("completion_tokens")+" token\n控件传输 "+m.optLong("raw_ui_chars")+" → "+m.optLong("sent_ui_chars")+" 字符 · 截图尝试 "+m.optInt("visual_requests")+" 次\nToken 来自服务商 usage；未返回用量时无法统计，字符数不是 token 数。不含历史摘要请求。",13,c.muted));}catch(Exception ignored){}
        section(body,"运行边界","原生服务在本 App 内连接模型、管理记忆并执行工具，不需要 Termux 或电脑。后台任务有常驻通知；系统仍可能回收进程，重启后不会擅自续做操作。");
        body.addView(text("文件和 Android Shell 使用本 App 的独立权限。旧工作文件已迁移，但 Python、pip、Termux 软件包不随之迁移；需要它们的脚本不能直接运行。无障碍为主，独立授权的云端截图视觉补盲；不含本地 OCR。\n\n聊天与工具记录保存在本机，可能含私人内容，导出时请谨慎。截图不写入会话，但模型给出的文字描述可能保留。卸载 App 将删除本机数据。",14,c.muted));
        gap(body,24);body.addView(text("Pocket Agent 1.2 · 原生 Android",12,c.accent));
    }
    void emergency(){new AlertDialog.Builder(this).setTitle("停止所有当前任务？").setMessage("取消模型请求、停止本 App 的 Shell 子进程，并暂停设备控制。已发生的外部操作不会回滚。")
        .setNegativeButton("返回",null).setPositiveButton("紧急停止",(d,w)->{startForegroundService(new Intent(this,AgentService.class).setAction(AgentService.EMERGENCY));getSharedPreferences("bridge",MODE_PRIVATE).edit().putBoolean("allowed",false).commit();toast("已请求紧急停止");navigate("chat");}).show();}
    void sessionDialog(){
        try{JSONArray rows=store.sessions();String[] titles=new String[rows.length()+1];titles[0]="＋ 新建会话";for(int i=0;i<rows.length();i++)titles[i+1]=rows.getJSONObject(i).getString("title");
            new AlertDialog.Builder(this).setTitle("会话").setItems(titles,(d,which)->{try{if(which==0){store.newSession();draft="";navigate("chat");return;}JSONObject item=rows.getJSONObject(which-1);String id=item.getString("id");
                new AlertDialog.Builder(this).setTitle(item.getString("title")).setItems(new String[]{"打开会话","重命名","删除会话"},(dialog,action)->{
                    if(action==0){store.prefs.edit().putString("session",id).apply();draft="";navigate("chat");}
                    else if(action==1){EditText name=input("会话名称",false);name.setText(item.optString("title"));new AlertDialog.Builder(this).setTitle("重命名").setView(name).setNegativeButton("取消",null).setPositiveButton("保存",(x,y)->{store.rename(id,name.getText().toString());build();}).show();}
                    else new AlertDialog.Builder(this).setTitle("删除此会话？").setMessage("只删除 App 内的这一份会话，无法撤销；不影响 Termux 原备份。").setNegativeButton("取消",null).setPositiveButton("删除",(x,y)->{if(AgentService.isBusy()){toast("请先停止任务");return;}store.deleteSession(id);build();}).show();
                }).show();
            }catch(Exception e){error(e);}}).setNegativeButton("关闭",null).show();
        }catch(Exception e){error(e);}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==21){
            if(AgentService.isBusy()){toast("请先等待任务完成再导入文件");return;}
            String display="imported-file";try(android.database.Cursor cur=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cur!=null&&cur.moveToFirst())display=cur.getString(0);}catch(Exception ignored){}
            final String name=display.replaceAll("[/\\\\]","_");final String folder=filePath;
            new Thread(()->{String message;try(InputStream in=getContentResolver().openInputStream(uri)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[8192];for(int n;(n=in.read(bytes))!=-1;){if(out.size()+n>4_000_000)throw new IllegalArgumentException("文件超过 4 MB 上限");out.write(bytes,0,n);}File f=AgentPolicy.safeFile(store.workspace,(folder.isEmpty()?"":folder+"/")+name);if(f.exists())throw new IllegalArgumentException("同名文件已存在，请先重命名");f.getParentFile().mkdirs();Files.write(f.toPath(),out.toByteArray());message="文件已导入";}catch(Exception e){message=String.valueOf(e.getMessage());}final String done=message;runOnUiThread(()->{if(!isFinishing()){toast(done);build();}});},"file-import").start();
        }else if(request==22){final String sid=exportSession;new Thread(()->{String resultMessage;try(OutputStream out=getContentResolver().openOutputStream(uri)){StringBuilder doc=new StringBuilder("# Pocket Agent 会话\n\n");String summary=store.summary(sid);if(!summary.isEmpty())doc.append("## 记忆摘要\n\n").append(summary).append("\n\n");JSONArray rows=store.messages(sid,10000);for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);doc.append("## ").append(r.optString("kind").equals("tool")?"工具记录":r.optString("role").equals("user")?"用户":"助手").append("\n\n").append(r.optString("content")).append("\n\n");}out.write(doc.toString().getBytes(StandardCharsets.UTF_8));resultMessage="会话已导出（包含工具记录，请妥善保管）";}catch(Exception e){resultMessage=String.valueOf(e.getMessage());}final String done=resultMessage;runOnUiThread(()->toast(done));},"session-export").start();}
    }
}
