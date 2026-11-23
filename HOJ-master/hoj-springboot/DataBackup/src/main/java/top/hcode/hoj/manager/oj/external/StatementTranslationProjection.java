package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;
import java.util.*;
import java.util.regex.*;

/** Models may translate text, but cannot author markup, data, formulae or examples. */
final class StatementTranslationProjection {
    private static final List<String> FIELDS=Arrays.asList("title","description","input","output","hint");
    private static final Set<String> PROTECTED=new HashSet<>(Arrays.asList("pre","code","var","math","svg","img"));
    private static final Set<String> VOID=new HashSet<>(Arrays.asList("area","base","br","col","embed","hr","img","input","link","meta","param","source","track","wbr"));
    private static final String MATH="(?<!\\\\)\\$\\$\\$.*?(?<!\\\\)\\$\\$\\$|(?<!\\\\)\\$\\$.*?(?<!\\\\)\\$\\$|(?<!\\\\)\\$(?!\\$).*?(?<!\\\\)\\$|\\\\\\(.*?\\\\\\)|\\\\\\[.*?\\\\\\]";
    private static final String NUMBER="[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?";
    private static final String DELIMITERS=Arrays.asList("\\$","$","\\(","\\)","\\[","\\]").stream().map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
    private static final Pattern VALUES=Pattern.compile(MATH+"|"+DELIMITERS+"|"+NUMBER,Pattern.DOTALL);
    private final String prefix="⟦HOJ_"+UUID.randomUUID().toString().replace("-","").substring(0,12)+"_";
    private final Pattern token=Pattern.compile(Pattern.quote(prefix)+"[0-9]+⟧");
    private final ObjectMapper json;
    private final Content original;
    private final Map<String,Field> fields=new LinkedHashMap<>();
    private int serial,characters,markers;

    private class Field {
        boolean html;String template;Map<String,String> literals=new LinkedHashMap<>();List<String> order;
        String protect(String value){String key=prefix+(serial++)+"⟧";literals.put(key,value);return key;}
        String escaped(String value){return html?new TextNode(value).outerHtml():value;}
        String restore(String text,boolean response){
            StringBuilder out=new StringBuilder();Matcher matcher=token.matcher(text);int from=0;List<String> seen=new ArrayList<>();
            while(matcher.find()){
                String plain=text.substring(from,matcher.start());
                if(plain.contains(prefix) || !literals.containsKey(matcher.group()))throw new IllegalArgumentException("Unknown protection marker");
                if(response && VALUES.matcher(plain).find())throw new IllegalArgumentException("New formula or data");
                out.append(escaped(plain)).append(literals.get(matcher.group()));seen.add(matcher.group());from=matcher.end();
            }
            String tail=text.substring(from);if(tail.contains(prefix))throw new IllegalArgumentException("Invalid protection marker");
            if(response && (VALUES.matcher(tail).find() || !seen.equals(order)))throw new IllegalArgumentException("Protection markers changed");
            return out.append(escaped(tail)).toString();
        }
        void node(Node node,StringBuilder template,int depth){
            if(depth>64)throw new ObjectiveException(400,"题面格式嵌套过深，请使用手工翻译编辑");
            if(node instanceof TextNode){template.append(((TextNode)node).getWholeText());return;}
            if(!(node instanceof Element)){template.append(protect(node.outerHtml()));return;}
            Element element=(Element)node;String tag=element.normalName(),classes=element.className().toLowerCase(Locale.ROOT);
            if(PROTECTED.contains(tag) || classes.contains("math") || classes.contains("katex")){template.append(protect(node.outerHtml()));return;}
            String attrs=element.attributes().html();if(!attrs.isEmpty() && !Character.isWhitespace(attrs.charAt(0)))attrs=" "+attrs;
            template.append(protect("<"+tag+attrs+">"));
            for(Node child:element.childNodes())node(child,template,depth+1);
            if(!VOID.contains(tag))template.append(protect("</"+tag+">"));
        }
        Field(String text,boolean html){
            this.html=html;StringBuilder markup=new StringBuilder();
            if(html){Document document=Jsoup.parseBodyFragment(text);document.outputSettings().prettyPrint(false);for(Node node:document.body().childNodes())node(node,markup,0);}
            else markup.append(text);
            // Match math over the whole projected field, including intervening
            // protected tags. This also preserves formulae split by inline HTML.
            String value=markup.toString();Matcher matcher=Pattern.compile(token.pattern()+"|"+MATH+"|"+DELIMITERS+"|"+NUMBER,Pattern.DOTALL).matcher(value);
            StringBuilder projected=new StringBuilder();int from=0;
            while(matcher.find()){
                projected.append(value,from,matcher.start());String part=matcher.group();
                if(literals.containsKey(part))projected.append(part);
                else{
                    String restored=restore(part,false);Matcher nested=token.matcher(part);while(nested.find())literals.remove(nested.group());
                    projected.append(protect(restored));
                }
                from=matcher.end();
            }
            template=projected.append(value.substring(from)).toString();order=new ArrayList<>();Matcher tokens=token.matcher(template);while(tokens.find())order.add(tokens.group());
            characters+=token.matcher(template).replaceAll("").length();markers+=order.size();
        }
    }
    StatementTranslationProjection(Content source,ObjectMapper json){
        this.json=json;
        if(source==null)throw new ObjectiveException(400,"请先保存来源原文");
        original=json.convertValue(source,Content.class);
        Map<String,String> text=new LinkedHashMap<>();text.put("title",source.getTitle());text.put("description",source.getDescription());text.put("input",source.getInput());text.put("output",source.getOutput());text.put("hint",source.getHint());
        int raw=0;for(String value:text.values()){raw+=value==null?0:value.length();if(raw>150000)throw new ObjectiveException(400,"题面过长，请使用手工翻译编辑");}
        for(String name:FIELDS)fields.put(name,new Field(text.get(name)==null?"":text.get(name),!"title".equals(name)));
        if(characters>10000 || markers>256 || payloadLength()>24000)throw new ObjectiveException(400,"题面文本或格式过多，暂时无法一次生成完整译文，请使用手工翻译编辑");
    }
    Map<String,String> material(){Map<String,String> result=new LinkedHashMap<>();fields.forEach((name,value)->result.put(name,value.template));return result;}
    int characters(){return characters;}
    private int payloadLength(){try{return json.writeValueAsString(material()).length();}catch(Exception e){throw new IllegalStateException(e);}}
    Content apply(String reply){
        try{
            if(reply==null || reply.length()>24000)throw new IllegalArgumentException();String text=reply.trim();
            if(text.startsWith("```json") && text.endsWith("```"))text=text.substring(7,text.length()-3).trim();
            ObjectMapper strict=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode result=strict.readTree(text);if(!result.isObject() || result.size()!=FIELDS.size())throw new IllegalArgumentException();
            Map<String,String> restored=new LinkedHashMap<>();
            for(String name:FIELDS){
                JsonNode value=result.get(name);if(value==null || !value.isTextual() || value.asText().indexOf('\0')>=0)throw new IllegalArgumentException();
                Field field=fields.get(name);String translated=value.asText();
                if(field.template.isEmpty()? !translated.isEmpty():translated.trim().isEmpty())throw new IllegalArgumentException();
                boolean empty=token.matcher(field.template).replaceAll("").trim().isEmpty();
                if(empty!=token.matcher(translated).replaceAll("").trim().isEmpty())throw new IllegalArgumentException();
                for(int i=0;i<translated.length();i++){
                    char c=translated.charAt(i);
                    if(Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t')throw new IllegalArgumentException();
                    if(Character.isHighSurrogate(c)){if(i+1>=translated.length() || !Character.isLowSurrogate(translated.charAt(++i)))throw new IllegalArgumentException();}
                    else if(Character.isLowSurrogate(c))throw new IllegalArgumentException();
                }
                restored.put(name,field.restore(translated,true));
            }
            if(restored.get("title").trim().isEmpty() || restored.get("title").length()>255)throw new IllegalArgumentException();
            Content content=json.convertValue(original,Content.class);content.setTitle(restored.get("title"));content.setDescription(restored.get("description"));content.setInput(restored.get("input"));content.setOutput(restored.get("output"));content.setHint(restored.get("hint"));return content;
        }catch(Exception e){throw new ObjectiveException(502,"模型译文不完整或改变了格式、公式、数据，请重试或继续手工编辑");}
    }
}
