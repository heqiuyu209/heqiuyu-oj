package top.hcode.hoj.manager.oj.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable decoded speech. Citations are checked against text and its exact clock bounds. */
public final class VideoTranscript {
    public static final long MAX_BYTES = 1073741824L, MAX_DURATION = 7200000L, SLICE = 60000L;
    public static final int MAX_CHARACTERS = 200000, MAX_SEGMENTS = 5000;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static final class Speech {
        public final long startMs, endMs;
        public final String text;
        private Speech(long start, long end, String text) { this.startMs=start;this.endMs=end;this.text=text; }
    }
    public final long durationMs;
    public final List<Speech> segments;
    private VideoTranscript(long duration, List<Speech> segments) { this.durationMs=duration;this.segments=Collections.unmodifiableList(segments); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid video transcript"); }
    private static int length(String value) { return value.codePointCount(0,value.length()); }
    private static long integer(JsonNode node,long minimum,long maximum) {
        if(!node.isIntegralNumber()||!node.canConvertToLong()||node.asLong()<minimum||node.asLong()>maximum)throw invalid();
        return node.asLong();
    }
    private static boolean text(String value) {
        if(value==null||value.trim().isEmpty()||length(value)>4000||!StandardCharsets.UTF_8.newEncoder().canEncode(value))return false;
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c<32&&c!='\n'&&c!='\t')return false;}
        return true;
    }
    public static VideoTranscript parse(String source) {
        try {
            if(source==null||length(source)<1||length(source)>MAX_CHARACTERS)throw invalid();
            int depth=0;boolean quoted=false,escaped=false;
            for(int i=0;i<source.length();i++){
                char c=source.charAt(i);
                if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}
                else if(c=='"')quoted=true;
                else if(c=='{'||c=='['){if(++depth>4)throw invalid();}
                else if(c=='}'||c==']'){if(--depth<0)throw invalid();}
            }
            JsonNode root=JSON.readTree(source);
            if(root==null||!root.isObject()||root.size()!=3)throw invalid();
            integer(root.path("schema"),1,1);long duration=integer(root.path("durationMs"),1,MAX_DURATION);
            JsonNode values=root.path("segments");if(!values.isArray()||values.size()<1||values.size()>MAX_SEGMENTS)throw invalid();
            List<Speech> segments=new ArrayList<>();long previous=0;
            for(JsonNode value:values){
                if(!value.isObject()||value.size()!=3||!value.path("text").isTextual()||!text(value.path("text").asText()))throw invalid();
                long start=integer(value.path("startMs"),previous,duration-1),end=integer(value.path("endMs"),start+1,duration);
                segments.add(new Speech(start,end,value.path("text").asText()));previous=end;
            }
            return new VideoTranscript(duration,segments);
        } catch(Exception error) { throw invalid(); }
    }
    public String canonical() {
        Map<String,Object> root=new LinkedHashMap<>();root.put("schema",1);root.put("durationMs",durationMs);
        List<Map<String,Object>> values=new ArrayList<>();
        for(Speech speech:segments){Map<String,Object> value=new LinkedHashMap<>();value.put("startMs",speech.startMs);value.put("endMs",speech.endMs);value.put("text",speech.text);values.add(value);}
        root.put("segments",values);
        try{return JSON.writeValueAsString(root);}catch(Exception error){throw new IllegalStateException("Video transcript serialization failed");}
    }
    public static String clock(long milliseconds) {
        return String.format(Locale.ROOT,"%02d:%02d:%02d.%03d",milliseconds/3600000,(milliseconds/60000)%60,(milliseconds/1000)%60,milliseconds%1000);
    }
    private static long milliseconds(String value) {
        if(value==null||!value.matches("[0-9]{2}:[0-5][0-9]:[0-5][0-9]\\.[0-9]{3}"))throw invalid();
        return Long.parseLong(value.substring(0,2))*3600000+Long.parseLong(value.substring(3,5))*60000+Long.parseLong(value.substring(6,8))*1000+Long.parseLong(value.substring(9));
    }
    public long[] citation(String heading,String excerpt) {
        if(heading==null||heading.length()!=25||heading.charAt(12)!='–'||excerpt==null||excerpt.trim().isEmpty())throw invalid();
        long start=milliseconds(heading.substring(0,12)),end=milliseconds(heading.substring(13));
        List<Speech> selected=new ArrayList<>();for(Speech value:segments)if(value.startMs>=start&&value.endMs<=end)selected.add(value);
        if(selected.isEmpty()||selected.get(0).startMs!=start||selected.get(selected.size()-1).endMs!=end)throw invalid();
        StringBuilder body=new StringBuilder();for(Speech speech:selected){if(body.length()>0)body.append('\n');body.append(speech.text);}
        if(!body.toString().contains(excerpt))throw invalid();
        return new long[]{start,end};
    }
}
