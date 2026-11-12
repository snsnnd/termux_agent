package local.termux.agentbridge;

import java.io.File;
import java.nio.file.Files;

public class PolicyTest {
    static int count;
    static void check(boolean pass,String label){if(!pass)throw new AssertionError(label);count++;}
    public static void main(String[] args)throws Exception {
        File root=Files.createTempDirectory("native-agent-policy-").toFile();
        check(AgentPolicy.safeFile(root,"notes/a.md").getPath().startsWith(root.getCanonicalPath()+File.separator),"normal workspace path");
        try{AgentPolicy.safeFile(root,"../outside");throw new AssertionError("traversal");}catch(SecurityException good){count++;}
        File outside=Files.createTempDirectory("native-agent-outside-").toFile();
        Files.createSymbolicLink(new File(root,"link").toPath(),outside.toPath());
        try{AgentPolicy.safeFile(root,"link/secret");throw new AssertionError("symlink traversal");}catch(SecurityException good){count++;}
        check(AgentPolicy.endpoint("https://api.deepseek.com/").equals("https://api.deepseek.com"),"HTTPS normalization");
        for(String url:new String[]{"http://api.deepseek.com","https://secret@example.com","https://api.deepseek.com?key=x","https://api.deepseek.com#key","not a URL"}) {
            try{AgentPolicy.endpoint(url);throw new AssertionError("bad URL "+url);}catch(Exception good){count++;}
        }
        check(AgentPolicy.mutates("phone_act","click"),"phone mutation");
        check(AgentPolicy.mutates("shell",""),"shell mutation");
        check(AgentPolicy.mutates("write_file",""),"file mutation");
        check(AgentPolicy.mutates("job","kill"),"job kill mutation");
        check(!AgentPolicy.mutates("job","tail"),"job read");
        check(!AgentPolicy.mutates("phone_observe",""),"observe read");
        check(AgentPolicy.cap("short",50).equals("short"),"cap preserves short text");
        check(AgentPolicy.cap(new String(new char[1000]).replace('\0','a'),100).length()<150,"cap limits output");
        check(AgentPolicy.historyAnswer("[work log - tools]\nx\n[/work log]\n\nanswer").equals("answer"),"legacy tool records separated");
        check(AgentPolicy.historyAnswer("ordinary answer").equals("ordinary answer"),"plain history unchanged");
        check(!AgentPolicy.phoneAutomatic("task",false,false),"task requires first grant");
        check(AgentPolicy.phoneAutomatic("task",true,false),"granted task automates normal UI");
        check(!AgentPolicy.phoneAutomatic("task",true,true),"sensitive action ignores task grant");
        check(AgentPolicy.phoneAutomatic("auto",false,false),"persistent UI mode");
        check(!AgentPolicy.phoneAutomatic("auto",true,true),"sensitive action ignores persistent mode");
        check(!AgentPolicy.phoneAutomatic("step",true,false),"step mode never bypasses");
        check(!AgentPolicy.sensitiveReason("com.example.chat","发送","click").isEmpty(),"send requires explicit approval");
        check(!AgentPolicy.sensitiveReason("com.example.bank","余额","click").isEmpty(),"bank requires explicit approval");
        check(AgentPolicy.sensitiveReason("com.android.settings","显示和亮度","click").isEmpty(),"normal settings navigation");
        check(!AgentPolicy.sensitiveReason("com.example.store","Purchase","click").isEmpty(),"English purchase sensitive");
        check(AgentPolicy.sensitiveCommand("rm -rf ./notes"),"destructive shell approval");
        check(!AgentPolicy.sensitiveCommand("ls -la"),"ordinary shell classification");
        check(AgentPolicy.mutates("confirm_action",""),"explicit confirmation cannot bypass");
        new File(root,"link").delete();root.delete();outside.delete();
        System.out.println(count+" native policy checks passed");
    }
}
