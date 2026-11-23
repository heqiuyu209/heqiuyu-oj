package top.hcode.hoj.remote;

import org.jsoup.nodes.*;
import java.util.*;
import java.util.regex.*;

/** Reads bounded literal fields from official page assignments. Source JavaScript is never executed. */
public final class NowCoderPage {
    private NowCoderPage(){}
    private static RemoteSourceFailure invalid(){return new RemoteSourceFailure(RemoteSourceFailure.Kind.FORMAT_CHANGED);}
    private static String assignment(Document document,String name,boolean object){
        return assignment(document,name,object,true);
    }
    private static String assignment(Document document,String name,boolean object,boolean required){
        Pattern start=Pattern.compile("(?m)^\\s*window\\."+Pattern.quote(name)+"\\s*=\\s*");String found=null;
        for(Element script:document.select("script:not([src])")){
            String data=script.data();if(data.length()>200000)continue;
            Matcher match=start.matcher(data);
            while(match.find()){
                if(found!=null)throw invalid();int offset=match.end();
                if(object){
                    if(offset>=data.length() || data.charAt(offset)!='{')throw invalid();
                    int end=closing(data,offset);found=data.substring(offset+1,end);
                    if(!data.substring(end+1).trim().startsWith(";"))throw invalid();
                }else{
                    int end=data.indexOf(';',offset);if(end<0 || end-offset>20)throw invalid();found=data.substring(offset,end).trim();
                }
            }
        }
        if(found==null && required)throw invalid();return found;
    }
    private static int closing(String value,int start){
        int depth=0;char quote=0;boolean escaped=false,line=false,block=false;
        for(int n=start;n<value.length();n++){
            char c=value.charAt(n),next=n+1<value.length()?value.charAt(n+1):0;
            if(line){if(c=='\n')line=false;continue;}
            if(block){if(c=='*' && next=='/'){block=false;n++;}continue;}
            if(quote!=0){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c==quote)quote=0;continue;}
            if(c=='/' && next=='/'){line=true;n++;continue;}if(c=='/' && next=='*'){block=true;n++;continue;}
            if(c=='\'' || c=='"'){quote=c;continue;}
            if(c=='{')depth++;else if(c=='}' && --depth==0)return n;
        }
        throw invalid();
    }
    public static Map<String,String> fields(Document document,String object){
        String source=assignment(document,object,true);StringBuilder clean=new StringBuilder();
        char quote=0;boolean escaped=false,line=false,block=false;
        for(int n=0;n<source.length();n++){
            char c=source.charAt(n),next=n+1<source.length()?source.charAt(n+1):0;
            if(line){if(c=='\n'){line=false;clean.append(' ');}continue;}
            if(block){if(c=='*' && next=='/'){block=false;n++;clean.append(' ');}continue;}
            if(quote==0 && c=='/' && next=='/'){line=true;n++;continue;}if(quote==0 && c=='/' && next=='*'){block=true;n++;continue;}
            clean.append(c);if(quote!=0){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c==quote)quote=0;}else if(c=='\'' || c=='"')quote=c;
        }
        if(quote!=0 || block)throw invalid();
        Map<String,String> fields=new LinkedHashMap<>();String value=clean.toString();int start=0,depth=0;quote=0;escaped=false;
        for(int n=0;n<=value.length();n++){
            char c=n==value.length()?',':value.charAt(n);
            if(quote!=0){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c==quote)quote=0;continue;}
            if(c=='\'' || c=='"'){quote=c;continue;}
            if(c=='{' || c=='[' || c=='(')depth++;else if(c=='}' || c==']' || c==')')depth--;
            if(depth<0)throw invalid();
            if(c==',' && depth==0){
                String field=value.substring(start,n).trim();start=n+1;if(field.isEmpty())continue;
                Matcher match=Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*|\"[A-Za-z_][A-Za-z0-9_]*\"|'[A-Za-z_][A-Za-z0-9_]*')\\s*:\\s*([\\s\\S]*)$").matcher(field);
                if(!match.matches())throw invalid();String key=match.group(1).replace("\"","").replace("'","");
                if(fields.put(key,match.group(2).trim())!=null || fields.size()>200)throw invalid();
            }
        }
        if(depth!=0 || quote!=0)throw invalid();return Collections.unmodifiableMap(fields);
    }
    public static String positiveId(Map<String,String> fields,String name,int digits){
        String value=fields.get(name);if(value==null)throw invalid();
        Matcher match=Pattern.compile("^(?:'([1-9][0-9]{0,"+(digits-1)+"})'|\"([1-9][0-9]{0,"+(digits-1)+"})\"|([1-9][0-9]{0,"+(digits-1)+"}))$").matcher(value);
        if(!match.matches())throw invalid();return match.group(1)!=null?match.group(1):match.group(2)!=null?match.group(2):match.group(3);
    }
    public static String authenticatedOwner(Document document){
        String login;
        try{login=assignment(document,"isLogin",false);}catch(RemoteSourceFailure e){throw new RemoteSourceFailure(RemoteSourceFailure.Kind.AUTH_REQUIRED);}
        if(!"true".equals(login))throw new RemoteSourceFailure(RemoteSourceFailure.Kind.AUTH_REQUIRED);
        Map<String,String> fields=new LinkedHashMap<>(fields(document,"globalInfo"));
        String assigned=assignment(document,"globalInfo.ownerId",false,false);
        if(assigned!=null){
            String original=fields.get("ownerId");fields.put("ownerId",assigned);String owner=positiveId(fields,"ownerId",12);
            if(original!=null){fields.put("ownerId",original);if(!owner.equals(positiveId(fields,"ownerId",12)))throw invalid();}
            return owner;
        }
        return positiveId(fields,"ownerId",12);
    }
}
