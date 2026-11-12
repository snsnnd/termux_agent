package local.termux.agentbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.Rect;
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
        // Window transitions invalidate all previously observed node handles.
        clearSnapshot();
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
            .put("root_required", false).put("api", android.os.Build.VERSION.SDK_INT);
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
        return string(node.getPackageName()) + "|" + node.getViewIdResourceName() + "|" +
            string(node.getClassName()) + "|" + r.toShortString() + "|" +
            string(node.getText()) + "|" + string(node.getContentDescription());
    }

    private JSONObject observe() throws Exception {
        clearSnapshot();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return error("No accessible active window. Open the target app first.");
        try {
            snapshotPackage = string(root.getPackageName());
            // Never expose this app's pairing screen to the model.
            if (snapshotPackage.equals(getPackageName())) return error("Pairing screen is private. Open another app.");
            JSONArray items = new JSONArray();
            visited = 0;
            walk(root, items, 0);
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
                            walk(other, items, 0);
                            windowsRead++;
                        }
                    } finally { other.recycle(); }
                } finally { window.recycle(); }
            }
            snapshot = UUID.randomUUID().toString();
            android.util.DisplayMetrics dm = displayMetrics();
            return ok().put("snapshot", snapshot).put("package", snapshotPackage)
                .put("width", dm.widthPixels).put("height", dm.heightPixels)
                .put("app_windows", windowsRead)
                .put("nodes", items).put("truncated", nodes.size() >= 220 || visited >= 2000);
        } finally { root.recycle(); }
    }

    private void walk(AccessibilityNodeInfo node, JSONArray items, int depth) throws Exception {
        if (node == null || visited++ >= 2000 || nodes.size() >= 220 || depth > 40) return;
        if (node.isVisibleToUser() && !node.isPassword() &&
                (node.getText() != null || node.getContentDescription() != null || node.isClickable() ||
                 node.isLongClickable() || node.isEditable() || node.isScrollable())) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            int id = nodes.size();
            nodes.add(AccessibilityNodeInfo.obtain(node));
            fingerprints.add(fingerprint(node));
            items.put(new JSONObject().put("node_id", id).put("text", string(node.getText()))
                .put("window_id", node.getWindowId())
                .put("description", string(node.getContentDescription()))
                .put("view_id", node.getViewIdResourceName()).put("class", string(node.getClassName()))
                .put("bounds", new JSONArray(new int[]{r.left, r.top, r.right, r.bottom}))
                .put("clickable", node.isClickable()).put("editable", node.isEditable())
                .put("scrollable", node.isScrollable()).put("enabled", node.isEnabled()));
        }
        // Password subtrees are not inspected.
        if (node.isPassword()) return;
        for (int i = 0; i < node.getChildCount() && nodes.size() < 220 && visited < 2000; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try { walk(child, items, depth + 1); } finally { child.recycle(); }
            }
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
