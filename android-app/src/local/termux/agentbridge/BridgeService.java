package local.termux.agentbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BridgeService extends AccessibilityService {
    public static volatile BridgeService current;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();
    private final ArrayList<String> fingerprints = new ArrayList<>();
    private String snapshot = "";
    private String snapshotPackage = "";
    private volatile ServerSocket server;
    private volatile boolean running;
    private SharedPreferences prefs;
    private int visited;
    private final ExecutorService captureExecutor=Executors.newSingleThreadExecutor();
    private volatile long lastUiEvent;
    private long windowSerial,snapshotCounter;
    private final String snapshotPrefix=UUID.randomUUID().toString().substring(0,8);

    private android.util.DisplayMetrics displayMetrics() {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        android.view.WindowManager manager = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        manager.getDefaultDisplay().getRealMetrics(metrics);
        return metrics;
    }

    @Override protected void onServiceConnected() {
        current = this;
        prefs = getSharedPreferences("bridge", MODE_PRIVATE);
        // Native agent uses in-process calls. Legacy loopback API is opt-in.
        if (!prefs.getBoolean("legacy_http", false)) return;
        if (running) return;
        running = true;
        new Thread(this::serve, "agent-loopback").start();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        lastUiEvent=android.os.SystemClock.uptimeMillis();
        if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED||event.getEventType()==AccessibilityEvent.TYPE_WINDOWS_CHANGED){windowSerial++;clearSnapshot();}
    }

    @Override public void onInterrupt() {
        prefs.edit().putBoolean("allowed", false).apply();
        clearSnapshot();
    }

    @Override public void onDestroy() {
        running = false;
        current = null;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        clearSnapshot();
        captureExecutor.shutdownNow();
        super.onDestroy();
    }

    private JSONObject ok() throws Exception { return new JSONObject().put("ok", true); }
    private JSONObject error(String message) {
        JSONObject out = new JSONObject();
        try { out.put("ok", false).put("error", message); } catch (Exception ignored) { }
        return out;
    }

    private boolean locked() {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    private <T> T onMain(Callable<T> callable) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())return callable.call();
        FutureTask<T> task = new FutureTask<>(callable);
        main.post(task);
        try { return task.get(5, TimeUnit.SECONDS); }
        finally { task.cancel(false); }
    }

    private void serve() {
        try (ServerSocket listener = new ServerSocket(8766, 8, InetAddress.getByName("127.0.0.1"))) {
            server = listener;
            while (running) {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    handle(socket);
                } catch (Exception ignored) {
                    // No request bodies, UI text or credentials are written to logs.
                }
            }
        } catch (Exception ignored) {
            running = false;
        }
    }

    private String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1;) {
            if (c == '\n') return line.toString("US-ASCII").replace("\r", "");
            if (line.size() >= 4096) throw new IllegalArgumentException("header too long");
            line.write(c);
        }
        throw new IllegalArgumentException("incomplete request");
    }

    private void handle(Socket socket) throws Exception {
        InputStream in = new BufferedInputStream(socket.getInputStream());
        String[] request = readLine(in).split(" ");
        if (request.length != 3 || !request[0].equals("POST")) {
            reply(socket, 400, error("POST required")); return;
        }
        String auth = "";
        int length = -1, headerBytes = 0;
        boolean transferEncoding = false;
        for (String line; !(line = readLine(in)).isEmpty();) {
            headerBytes += line.length();
            if (headerBytes > 16384) { reply(socket, 400, error("headers too long")); return; }
            int colon = line.indexOf(':');
            if (colon < 0) { reply(socket, 400, error("invalid header")); return; }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Authorization")) auth = value;
            if (name.equalsIgnoreCase("Transfer-Encoding")) transferEncoding = true;
            if (name.equalsIgnoreCase("Content-Length")) {
                if (length != -1) { reply(socket, 400, error("duplicate length")); return; }
                length = Integer.parseInt(value);
            }
        }
        String token = prefs.getString("token", "");
        if (token.length() != 64 || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                auth.getBytes(StandardCharsets.UTF_8))) {
            reply(socket, 401, error("Unauthorized")); return;
        }
        if (transferEncoding || length < 0 || length > 65536) {
            reply(socket, 400, error("invalid body length")); return;
        }
        byte[] bytes = new byte[length];
        for (int n = 0; n < length;) {
            int count = in.read(bytes, n, length - n);
            if (count < 0) throw new IllegalArgumentException("incomplete body");
            n += count;
        }
        JSONObject response;
        try {
            JSONObject body = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (request[1].equals("/act") &&
                    (body.optString("action").equals("tap") || body.optString("action").equals("swipe"))) {
                response = gesture(body);
            } else {
                response = onMain(() -> route(request[1], body));
            }
        } catch (Exception e) {
            response = error("Request failed or timed out; observe state before retrying.");
        }
        reply(socket, 200, response);
    }

    private void reply(Socket socket, int status, JSONObject body) throws Exception {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n" +
            "Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private JSONObject route(String path, JSONObject body) throws Exception {
        if (path.equals("/status")) return ok().put("service", "connected")
            .put("control_allowed", prefs.getBoolean("allowed", false)).put("locked", locked())
            .put("root_required", false).put("api", android.os.Build.VERSION.SDK_INT)
            .put("screenshot_capable",android.os.Build.VERSION.SDK_INT>=30&&getServiceInfo()!=null&&
                (getServiceInfo().getCapabilities()&android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT)!=0);
        if (!prefs.getBoolean("allowed", false)) return error("Control paused in Agent Bridge app.");
        if (locked()) return error("Screen locked. Ask the owner to unlock it manually.");
        if (path.equals("/apps")) return apps();
        if (path.equals("/observe")) return observe();
        if (path.equals("/act")) return act(body);
        return error("Unknown endpoint");
    }

    public JSONObject nativeCall(String path, JSONObject args) throws Exception {
        if (path.equals("/act") && (args.optString("action").equals("tap") ||
                args.optString("action").equals("swipe"))) return gesture(args);
        return onMain(() -> route(path, args));
    }

    public JSONObject prepareNode(JSONObject args) throws Exception {
        return onMain(() -> {
            int id = args.getInt("node_id");
            if (snapshot.isEmpty() || !snapshot.equals(args.optString("snapshot")) || id < 0 || id >= nodes.size())
                throw new IllegalStateException("Stale target; observe again before confirmation");
            AccessibilityNodeInfo node = nodes.get(id);
            return new JSONObject().put("fingerprint", fingerprints.get(id)).put("package", snapshotPackage)
                .put("text", string(node.getText())).put("description", string(node.getContentDescription()))
                .put("view_id", node.getViewIdResourceName()).put("target_label", targetLabel(node,new int[]{40},0));
        });
    }

    private String targetLabel(AccessibilityNodeInfo node,int[] budget,int depth) {
        if(node==null||node.isPassword()||depth>3||budget[0]--<=0)return "";
        StringBuilder text=new StringBuilder(string(node.getText())).append(' ').append(string(node.getContentDescription()));
        for(int i=0;i<node.getChildCount()&&budget[0]>0&&text.length()<600;i++) {
            AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try{text.append(' ').append(targetLabel(child,budget,depth+1));}finally{child.recycle();}
        }
        return text.length()>600?text.substring(0,600):text.toString();
    }
    public JSONObject assessNativeAction(JSONObject args)throws Exception {
        return onMain(()->{
            String action=args.optString("action");
            if(action.equals("launch"))return new JSONObject().put("package",args.optString("package")).put("target_label","");
            AccessibilityNodeInfo root=getRootInActiveWindow();
            if(root==null)return new JSONObject().put("sensitive_reason","无法核对当前目标窗口");
            try{
                JSONObject target=new JSONObject().put("package",string(root.getPackageName())).put("target_label","");
                if(action.equals("tap")) {
                    int x=args.optInt("x",-1),y=args.optInt("y",-1);AccessibilityNodeInfo best=null;int area=Integer.MAX_VALUE;
                    for(AccessibilityNodeInfo n:nodes){Rect bounds=new Rect();n.getBoundsInScreen(bounds);int a=bounds.width()*bounds.height();if(bounds.contains(x,y)&&a<area){best=n;area=a;}}
                    if(best==null)target.put("sensitive_reason","坐标点击无法核对控件含义");
                    else target.put("target_label",targetLabel(best,new int[]{40},0));
                }
                return target;
            }finally{root.recycle();}
        });
    }
    public void verifyNativePackage(String expected)throws Exception {
        onMain(()->{AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)throw new IllegalStateException("Target window unavailable");
            try{if(!expected.equals(string(root.getPackageName())))throw new IllegalStateException("Target app changed; observe again");}finally{root.recycle();}return true;});
    }
    /** Called on main after an explicit notification approval; does not open the agent. */
    public void dismissApprovalShade() {
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return;
        try{if("com.android.systemui".equals(string(root.getPackageName())))performGlobalAction(android.os.Build.VERSION.SDK_INT>=31?GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE:GLOBAL_ACTION_BACK);}
        finally{root.recycle();}
    }

    public JSONObject resolveConfirmedNode(JSONObject args, JSONObject target) throws Exception {
        return onMain(() -> {
            if (!prefs.getBoolean("allowed", false) || locked()) throw new IllegalStateException("Control paused or screen locked");
            JSONObject fresh = observe();
            if (!fresh.optBoolean("ok") || !snapshotPackage.equals(target.getString("package")))
                throw new IllegalStateException("Target app changed during confirmation; observe again");
            int match = -1;
            for (int i = 0; i < fingerprints.size(); i++) if (fingerprints.get(i).equals(target.getString("fingerprint"))) {
                if (match != -1) throw new IllegalStateException("Ambiguous target; observe again");
                match = i;
            }
            if (match == -1) throw new IllegalStateException("Target changed during confirmation; observe again");
            return new JSONObject(args.toString()).put("snapshot", snapshot).put("node_id", match);
        });
    }

    private JSONObject apps() throws Exception {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> launchers = getPackageManager().queryIntentActivities(query, 0);
        JSONArray items = new JSONArray();
        for (ResolveInfo item : launchers) {
            if (items.length() >= 300) break;
            items.put(new JSONObject().put("label", item.loadLabel(getPackageManager()).toString())
                .put("package", item.activityInfo.packageName));
        }
        return ok().put("apps", items);
    }

    private void clearSnapshot() {
        snapshot = "";
        for (AccessibilityNodeInfo node : nodes) node.recycle();
        nodes.clear();
        fingerprints.clear();
    }

    private String string(CharSequence value) {
        if (value == null) return "";
        String text = value.toString();
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private String fingerprint(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        return string(node.getPackageName()) + "|" +node.getWindowId()+"|"+ node.getViewIdResourceName() + "|" +
            string(node.getClassName()) + "|" + r.toShortString() + "|" +
            string(node.getText()) + "|" + string(node.getContentDescription())+"|"+node.isEnabled()+"|"+node.isSelected()+"|"+node.isChecked()+"|"+node.isCheckable()+"|"+node.isClickable()+"|"+node.isEditable()+"|"+node.isScrollable()+"|"+node.isLongClickable();
    }

    private JSONObject observe() throws Exception {
        clearSnapshot();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return error("No accessible active window. Open the target app first.");
        try {
            snapshotPackage = string(root.getPackageName());
            // Never expose this app's pairing screen to the model.
            if (snapshotPackage.equals(getPackageName())) return error("Agent app screen is private. Open the target app first.");
            JSONArray items = new JSONArray();
            visited = 0;
            walk(root, items, 0,-1);
            // Huawei tablet Settings uses separate left/right app windows.
            // Include only application windows belonging to this foreground app,
            // never an unrelated split-screen app, keyboard, or notification pane.
            int windowsRead = 1;
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo window : windows) {
                try {
                    if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION ||
                            window.getId() == root.getWindowId()) continue;
                    AccessibilityNodeInfo other = window.getRoot();
                    if (other == null) continue;
                    try {
                        if (snapshotPackage.equals(string(other.getPackageName()))) {
                            walk(other, items, 0,-1);
                            windowsRead++;
                        }
                    } finally { other.recycle(); }
                } finally { window.recycle(); }
            }
            snapshot = snapshotPrefix+"."+Long.toString(++snapshotCounter,36);
            android.util.DisplayMetrics dm = displayMetrics();
            String scene=hash((snapshotPackage+"|"+dm.widthPixels+"x"+dm.heightPixels+"|"+fingerprints.toString()).getBytes(StandardCharsets.UTF_8)).substring(0,16);
            return ok().put("snapshot", snapshot).put("package", snapshotPackage)
                .put("scene",scene)
                .put("width", dm.widthPixels).put("height", dm.heightPixels)
                .put("app_windows", windowsRead)
                .put("nodes", items).put("truncated", nodes.size() >= 220 || visited >= 2000);
        } finally { root.recycle(); }
    }

    private void walk(AccessibilityNodeInfo node, JSONArray items, int depth,int clickParent) throws Exception {
        if (node == null || visited++ >= 2000 || nodes.size() >= 220 || depth > 40) return;
        if (node.isVisibleToUser() && !node.isPassword() &&
                (node.getText() != null || node.getContentDescription() != null || node.isClickable() ||
                 node.isLongClickable() || node.isEditable() || node.isScrollable())) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            int id = nodes.size();
            if(node.isClickable())clickParent=id;
            nodes.add(AccessibilityNodeInfo.obtain(node));
            fingerprints.add(fingerprint(node));
            items.put(new JSONObject().put("node_id", id).put("text", string(node.getText()))
                .put("window_id", node.getWindowId())
                .put("description", string(node.getContentDescription()))
                .put("view_id", node.getViewIdResourceName()).put("class", string(node.getClassName()))
                .put("bounds", new JSONArray(new int[]{r.left, r.top, r.right, r.bottom}))
                .put("clickable", node.isClickable()).put("editable", node.isEditable())
                .put("click_id",clickParent).put("long_clickable",node.isLongClickable())
                .put("selected",node.isSelected()).put("checkable",node.isCheckable()).put("checked",node.isChecked())
                .put("scrollable", node.isScrollable()).put("enabled", node.isEnabled()));
        }
        // Password subtrees are not inspected.
        if (node.isPassword()) return;
        for (int i = 0; i < node.getChildCount() && nodes.size() < 220 && visited < 2000; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try { walk(child, items, depth + 1,clickParent); } finally { child.recycle(); }
            }
        }
    }

    void awaitUiStable()throws InterruptedException {
        long start=android.os.SystemClock.uptimeMillis();
        do{Thread.sleep(80);}while((android.os.SystemClock.uptimeMillis()-start<300||android.os.SystemClock.uptimeMillis()-lastUiEvent<220)&&android.os.SystemClock.uptimeMillis()-start<1000);
    }
    static String hash(byte[] bytes)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();
        for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    static final class Capture {
        byte[] jpeg;String pkg,hash;Rect crop;int imageWidth,imageHeight;
        ArrayList<Rect> allowed,masked;
    }
    private static final class CapturePlan {
        String pkg;Rect crop;long serial;
        final ArrayList<Rect> allowed=new ArrayList<>(),masked=new ArrayList<>();
    }
    private boolean protectedInput(AccessibilityNodeInfo node,int[] budget,int depth) {
        if(node==null||!node.isVisibleToUser())return false;
        if(budget[0]--<=0||depth>40)return true;
        if(node.isPassword())return true;
        String label=string(node.getText())+" "+string(node.getContentDescription());
        if(label.trim().matches("^(请输入)?(登录|支付|交易|短信|手机|图形)?(密码|验证码).*$"))return true;
        if(java.util.regex.Pattern.compile("付款码|收款码|收付款|确认支付|立即付款|支付密码|银行卡|银行账户|(?i)\\b(password|captcha|verification code|pay now|payment code|bank account)\\b").matcher(label).find())return true;
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try{if(protectedInput(child,budget,depth+1))return true;}finally{child.recycle();}
        }
        return false;
    }
    private CapturePlan capturePlan(Rect region)throws Exception {
        SharedPreferences agent=getSharedPreferences("agent",MODE_PRIVATE);
        if(!agent.getBoolean("visual_consent",false)||!agent.getBoolean("cloud_consent",false))throw new SecurityException("截图和云端处理需要用户单独授权");
        if(!prefs.getBoolean("allowed",false)||locked())throw new SecurityException("设备控制已暂停或屏幕锁定，拒绝截图");
        android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        if(power!=null&&!power.isInteractive())throw new SecurityException("请先由用户亮屏并解锁");
        if(android.os.Build.VERSION.SDK_INT<30||getServiceInfo()==null||
            (getServiceInfo().getCapabilities()&android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT)==0)
            throw new IllegalStateException("系统截图能力未启用，请在系统设置重新开启本 App 无障碍服务");
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)throw new IllegalStateException("无法确定前台 App，拒绝截图");
        CapturePlan p=new CapturePlan();p.serial=windowSerial;
        try {
            p.pkg=string(root.getPackageName());if(p.pkg.equals(getPackageName()))throw new SecurityException("助手自身界面禁止截图");
            if(!AgentPolicy.sensitiveReason(p.pkg,"","click").isEmpty())throw new SecurityException("支付、银行或系统授权应用禁止视觉上传");
            if(protectedInput(root,new int[]{2000},0))throw new SecurityException("检测到密码或验证码区域，拒绝视觉上传");
            Rect r=new Rect();root.getBoundsInScreen(r);if(!r.isEmpty())p.allowed.add(r);
        }finally{root.recycle();}
        boolean foundWindow=false;
        for(AccessibilityWindowInfo w:getWindows()) {
            try {
                Rect bounds=new Rect();w.getBoundsInScreen(bounds);if(bounds.isEmpty())continue;
                AccessibilityNodeInfo other=w.getRoot();String pkg="";
                try {
                    if(other!=null)pkg=string(other.getPackageName());
                    if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&p.pkg.equals(pkg)) {
                        foundWindow=true;
                        if(protectedInput(other,new int[]{2000},0))throw new SecurityException("检测到敏感输入窗口，拒绝视觉上传");
                        p.allowed.add(new Rect(bounds));
                    }else p.masked.add(new Rect(bounds));
                }finally{if(other!=null)other.recycle();}
            }finally{w.recycle();}
        }
        if(!foundWindow)throw new SecurityException("无法核对目标 App 的窗口范围，拒绝截图");
        android.util.DisplayMetrics dm=displayMetrics();Rect screen=new Rect(0,0,dm.widthPixels,dm.heightPixels),union=new Rect();
        for(Rect r:p.allowed){if(!r.intersect(screen))throw new SecurityException("窗口范围超出屏幕");union.union(r);}
        p.crop=region==null?union:new Rect(region);
        if(!p.crop.intersect(screen)||p.crop.width()<32||p.crop.height()<32)throw new IllegalArgumentException("截图裁剪区域无效或过小");
        boolean overlap=false;for(Rect r:p.allowed)if(Rect.intersects(r,p.crop)){overlap=true;break;}
        if(!overlap)throw new SecurityException("裁剪区域不属于目标 App");
        return p;
    }
    Capture captureForVision(Rect region)throws Exception {
        awaitUiStable();CapturePlan plan=onMain(()->capturePlan(region));
        CompletableFuture<Bitmap> result=new CompletableFuture<>();
        main.post(()->{
            try{takeScreenshot(android.view.Display.DEFAULT_DISPLAY,captureExecutor,new TakeScreenshotCallback(){
                @Override public void onSuccess(ScreenshotResult shot) {
                    HardwareBuffer buffer=shot.getHardwareBuffer();Bitmap hardware=null,copy=null;
                    try{hardware=Bitmap.wrapHardwareBuffer(buffer,shot.getColorSpace());if(hardware==null)throw new IllegalStateException("截图转换失败");copy=hardware.copy(Bitmap.Config.ARGB_8888,false);if(!result.complete(copy))copy.recycle();}
                    catch(Exception e){if(copy!=null&&!copy.isRecycled())copy.recycle();result.completeExceptionally(e);}
                    finally{if(hardware!=null)hardware.recycle();buffer.close();}
                }
                @Override public void onFailure(int code){result.completeExceptionally(new SecurityException("Android拒绝截图（"+code+"）；不会尝试绕过受保护窗口"));}
            });}catch(Exception e){result.completeExceptionally(e);}
        });
        Bitmap raw=null,cropped=null,scaled=null;
        try {
            raw=result.get(6,TimeUnit.SECONDS);
            if(raw==null||(long)raw.getWidth()*raw.getHeight()>12_000_000)throw new IllegalStateException("截图尺寸超出本地处理预算");
            onMain(()->{CapturePlan now=capturePlan(plan.crop);if(!now.pkg.equals(plan.pkg)||now.serial!=plan.serial||!now.allowed.equals(plan.allowed)||!now.masked.equals(plan.masked))throw new IllegalStateException("截图期间窗口已变化，请重新观察");return true;});
            if(!new Rect(0,0,raw.getWidth(),raw.getHeight()).contains(plan.crop))throw new IllegalStateException("屏幕方向或尺寸已改变，请重新观察");
            cropped=Bitmap.createBitmap(plan.crop.width(),plan.crop.height(),Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(cropped);canvas.drawColor(Color.BLACK);Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
            for(Rect allowed:plan.allowed){Rect r=new Rect(allowed);if(!r.intersect(plan.crop))continue;Rect dest=new Rect(r);dest.offset(-plan.crop.left,-plan.crop.top);canvas.drawBitmap(raw,r,dest,paint);}
            paint.setColor(Color.BLACK);for(Rect mask:plan.masked){Rect r=new Rect(mask);if(r.intersect(plan.crop)){r.offset(-plan.crop.left,-plan.crop.top);canvas.drawRect(r,paint);}}
            float factor=Math.min(1f,1024f/Math.max(cropped.getWidth(),cropped.getHeight()));
            scaled=Bitmap.createScaledBitmap(cropped,Math.max(1,Math.round(cropped.getWidth()*factor)),Math.max(1,Math.round(cropped.getHeight()*factor)),true);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();scaled.compress(Bitmap.CompressFormat.JPEG,65,bytes);
            Capture out=new Capture();out.jpeg=bytes.toByteArray();out.hash=hash(out.jpeg);out.pkg=plan.pkg;out.crop=new Rect(plan.crop);
            out.allowed=plan.allowed;out.masked=plan.masked;out.imageWidth=scaled.getWidth();out.imageHeight=scaled.getHeight();return out;
        }finally{
            result.cancel(false);if(scaled!=null&&scaled!=cropped)scaled.recycle();if(cropped!=null)cropped.recycle();if(raw!=null)raw.recycle();
        }
    }

    private JSONObject act(JSONObject args) throws Exception {
        String action = args.getString("action");
        int global = 0;
        switch (action) {
            case "back": global = GLOBAL_ACTION_BACK; break;
            case "home": global = GLOBAL_ACTION_HOME; break;
            case "recents": global = GLOBAL_ACTION_RECENTS; break;
            case "notifications": global = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": global = GLOBAL_ACTION_QUICK_SETTINGS; break;
        }
        if (global != 0) {
            boolean done = performGlobalAction(global);
            clearSnapshot();
            return done ? ok().put("action", action) : error("Global action rejected");
        }
        if (action.equals("launch")) {
            String pkg = args.getString("package");
            if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) return error("Invalid package");
            if (pkg.equals(getPackageName())) return error("Pairing app cannot be controlled by agent");
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) return error("App is not installed or has no launcher activity");
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            clearSnapshot();
            return ok().put("launched", pkg);
        }
        int nodeAction;
        switch (action) {
            case "click": nodeAction = AccessibilityNodeInfo.ACTION_CLICK; break;
            case "long_click": nodeAction = AccessibilityNodeInfo.ACTION_LONG_CLICK; break;
            case "set_text": nodeAction = AccessibilityNodeInfo.ACTION_SET_TEXT; break;
            case "scroll_forward": nodeAction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD; break;
            case "scroll_backward": nodeAction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; break;
            default: return error("Unsupported action");
        }
        if (snapshot.isEmpty() || !snapshot.equals(args.optString("snapshot"))) return error("Stale snapshot; observe again");
        int id = args.getInt("node_id");
        if (id < 0 || id >= nodes.size()) return error("Invalid node_id");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return error("Active window unavailable; observe again");
        try {
            if (!snapshotPackage.equals(string(root.getPackageName()))) return error("App changed; observe again");
        } finally { root.recycle(); }
        AccessibilityNodeInfo node = nodes.get(id);
        if (!node.refresh() || !node.isVisibleToUser() || !node.isEnabled() || node.isPassword() ||
                !fingerprints.get(id).equals(fingerprint(node))) {
            clearSnapshot();
            return error("Node changed or protected; observe again");
        }
        Bundle data = new Bundle();
        if (action.equals("set_text")) {
            if (!node.isEditable()) return error("Node is not editable");
            String text = args.getString("text");
            if (text.length() > 8000) return error("Input text too long");
            data.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        }
        boolean done = node.performAction(nodeAction, data);
        clearSnapshot();
        return done ? ok().put("action", action) : error("Node action rejected. Observe to find the actual clickable node.");
    }

    private JSONObject gesture(JSONObject args) throws Exception {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        main.post(() -> {
            try {
                if (!prefs.getBoolean("allowed", false) || locked()) {
                    result.complete(error("Control paused or screen locked")); return;
                }
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) { result.complete(error("No active window")); return; }
                try {
                    if(args.has("_vision_package")&&!args.getString("_vision_package").equals(string(root.getPackageName()))){result.complete(error("Visual target app changed"));return;}
                    if (getPackageName().equals(string(root.getPackageName()))) {
                        result.complete(error("Pairing app is protected")); return;
                    }
                } finally { root.recycle(); }
                android.util.DisplayMetrics dm = displayMetrics();
                int x = args.getInt("x"), y = args.getInt("y");
                int x2 = x, y2 = y, duration = 80;
                if (args.getString("action").equals("swipe")) {
                    x2 = args.getInt("x2"); y2 = args.getInt("y2");
                    duration = args.optInt("duration_ms", 400);
                    if (duration < 100 || duration > 2000) {
                        result.complete(error("duration_ms must be 100-2000")); return;
                    }
                }
                if (x < 0 || y < 0 || x2 < 0 || y2 < 0 || x >= dm.widthPixels ||
                        x2 >= dm.widthPixels || y >= dm.heightPixels || y2 >= dm.heightPixels) {
                    result.complete(error("Coordinates outside screen")); return;
                }
                Path path = new Path();
                path.moveTo(x, y);
                if (x != x2 || y != y2) path.lineTo(x2, y2);
                GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
                clearSnapshot();
                boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription description) {
                        try { result.complete(ok().put("gesture", "completed")); }
                        catch (Exception e) { result.complete(error("Gesture result unavailable")); }
                    }
                    @Override public void onCancelled(GestureDescription description) {
                        result.complete(error("Gesture cancelled; observe before retrying"));
                    }
                }, main);
                if (!accepted) result.complete(error("Gesture rejected"));
            } catch (Exception e) { result.complete(error("Invalid gesture arguments")); }
        });
        return result.get(5, TimeUnit.SECONDS);
    }
}
