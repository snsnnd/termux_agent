package local.termux.agentbridge;

import java.io.File;
import java.net.URI;

public final class AgentPolicy {
    static File safeFile(File root,String path) throws Exception {
        File target=new File(root,path).getCanonicalFile();String base=root.getCanonicalPath();
        if(!target.getPath().equals(base)&&!target.getPath().startsWith(base+File.separator))
            throw new SecurityException("Path escapes the app workspace");
        return target;
    }
    static String endpoint(String value) throws Exception {
        URI uri=new URI(value);
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
            throw new IllegalArgumentException("Model API must use an HTTPS base URL without credentials/query/fragment");
        return value.replaceAll("/+$","");
    }
    static String cap(String text,int limit) {
        if(text.length()<=limit)return text;
        return text.substring(0,limit*2/3)+"\n[内容截断]\n"+text.substring(text.length()-limit/3);
    }
    static boolean mutates(String name,String action) {
        return name.equals("confirm_action")||name.equals("phone_act")||name.equals("shell")||name.equals("write_file")||
            name.equals("edit_file")||name.equals("undo")||(name.equals("job")&&action.equals("kill"));
    }
    static String historyAnswer(String text) {
        int end=text.indexOf("[/work log]");
        return text.startsWith("[work log")&&end>=0?text.substring(end+11).trim():text;
    }
    static boolean phoneAutomatic(String mode,boolean taskGranted,boolean sensitive) {
        return !sensitive&&(mode.equals("auto")||(mode.equals("task")&&taskGranted));
    }
    static String sensitiveReason(String pkg,String label,String action) {
        if(action.equals("back")||action.equals("home")||action.equals("recents"))return "";
        String p=pkg.toLowerCase(java.util.Locale.ROOT);
        if(p.contains("permissioncontroller")||p.contains("packageinstaller")||p.contains("wallet")||p.contains("bank")||p.contains("alipay"))return "支付、银行或系统授权相关应用";
        if(java.util.regex.Pattern.compile("支付|付款|转账|汇款|购买|下单|提交订单|删除|清空|卸载|发送|授权|允许|验证码|密码|无障碍|USB调试|设备管理|(?i)\\b(pay|purchase|transfer|delete|erase|send|authorize|password|install|uninstall)\\b").matcher(label).find())return "目标控件涉及敏感或不可逆操作";
        return "";
    }
    static boolean sensitiveCommand(String command) {
        return java.util.regex.Pattern.compile("(?i)(^|[^a-z0-9_])(rm|rmdir|reboot|recovery|su|pm|settings|dd|mkfs|am)([^a-z0-9_]|$)").matcher(command).find();
    }
}
