package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

class StatementTranslationProjectionTest {
    ObjectMapper json=new ObjectMapper();
    Content original(){
        Content value=new Content();value.setTitle("Given task 12");
        value.setDescription("<p>Given <var>n</var> and $x <b>+ 1</b>$, at most 1000000 items.</p><pre>SECRET CODE\nif (n &lt; 10) {}\n</pre><a href=\"https://codeforces.com/path\">Read notes</a>");
        value.setInput("<p>Given 1st value.</p>");value.setOutput("<p>Given result.</p>");value.setHint("");value.setExamples("<input> SECRET SAMPLE\n 1 2\n</input><output>3\n</output>");value.setTimeLimit(1234);value.setMemoryLimit(567);return value;
    }
    ObjectNode identity(StatementTranslationProjection projection){return json.valueToTree(projection.material());}
    List<String> markers(String text){List<String> result=new ArrayList<>();Matcher matcher=Pattern.compile("⟦HOJ_[a-f0-9]+_[0-9]+⟧").matcher(text);while(matcher.find())result.add(matcher.group());return result;}
    @Test void translatesTextWhileKeepingHtmlMathAcrossInlineTagsCodeExamplesAndLimits() throws Exception {
        Content source=original();StatementTranslationProjection projection=new StatementTranslationProjection(source,json);ObjectNode reply=identity(projection);
        String material=json.writeValueAsString(reply);assertFalse(material.contains("SECRET CODE"));assertFalse(material.contains("SECRET SAMPLE"));assertFalse(material.contains("codeforces.com"));assertFalse(material.contains("1000000"));assertFalse(material.contains("$x"));
        for(String name:Arrays.asList("title","description","input","output"))reply.put(name,reply.path(name).asText().replace("Given","给定"));
        Content translated=projection.apply(json.writeValueAsString(reply));
        assertEquals("给定 task 12",translated.getTitle());assertEquals(source.getExamples(),translated.getExamples());assertEquals(source.getTimeLimit(),translated.getTimeLimit());assertEquals(source.getMemoryLimit(),translated.getMemoryLimit());
        assertEquals(Jsoup.parse(source.getDescription()).select("pre").text(),Jsoup.parse(translated.getDescription()).select("pre").text());
        String code=((org.jsoup.nodes.TextNode)Jsoup.parse(source.getDescription()).select("pre").first().childNode(0)).getWholeText();
        assertEquals(code,((org.jsoup.nodes.TextNode)Jsoup.parse(translated.getDescription()).select("pre").first().childNode(0)).getWholeText());
        Content normalized=new RemoteStatementManager().normalize(translated,"cf:contest:4:A");
        assertEquals(code,((org.jsoup.nodes.TextNode)Jsoup.parse(normalized.getDescription()).select("pre").first().childNode(0)).getWholeText());
        assertEquals("n",Jsoup.parse(translated.getDescription()).select("var").text());assertTrue(translated.getDescription().contains("$x <b>+ 1</b>$"));assertTrue(translated.getDescription().contains("1000000"));assertEquals("https://codeforces.com/path",Jsoup.parse(translated.getDescription()).select("a").attr("href"));
        assertEquals(source.getDescription(),original().getDescription());assertTrue(projection.characters()>0);
    }
    @Test void generatedMarkupIsOnlyTextAndCannotAddElementsOrAttributes() throws Exception {
        StatementTranslationProjection projection=new StatementTranslationProjection(original(),json);ObjectNode reply=identity(projection);
        reply.put("description",reply.path("description").asText().replace("Given","<img src=x onerror=alert()>"));
        String html=projection.apply(json.writeValueAsString(reply)).getDescription();assertTrue(Jsoup.parse(html).select("img").isEmpty());assertTrue(Jsoup.parse(html).text().contains("<img src=x"));
    }
    @Test void droppedDuplicatedUnknownReorderedAndCrossFieldMarkersAreRejected() throws Exception {
        StatementTranslationProjection projection=new StatementTranslationProjection(original(),json);String field=projection.material().get("description");List<String> tags=markers(field);assertTrue(tags.size()>3);
        List<String> altered=Arrays.asList(field.replace(tags.get(0),""),field+tags.get(0),field.replace(tags.get(0),tags.get(0).replaceAll("_[0-9]+⟧$","_99999⟧")),field.replace(tags.get(0),"SWAP").replace(tags.get(1),tags.get(0)).replace("SWAP",tags.get(1)),field+markers(projection.material().get("title")).get(0));
        for(String value:altered){ObjectNode reply=identity(projection);reply.put("description",value);assertThrows(ObjectiveException.class,()->projection.apply(reply.toString()));}
    }
    @Test void incompleteFieldsAdditionalDataAndNonJsonDoNotBecomeDrafts() throws Exception {
        StatementTranslationProjection projection=new StatementTranslationProjection(original(),json);
        for(String change:Arrays.asList("missing","extra","array","number","math","empty","invent","control","surrogate","longTitle")){
            ObjectNode reply=identity(projection);
            if(change.equals("missing"))reply.remove("output");if(change.equals("extra"))reply.put("answer","hidden");if(change.equals("array"))reply.putArray("input");
            if(change.equals("number"))reply.put("description",reply.path("description").asText()+" 42");if(change.equals("math"))reply.put("description",reply.path("description").asText()+" $answer$");
            if(change.equals("empty"))reply.put("description",String.join("",markers(reply.path("description").asText())));if(change.equals("invent"))reply.put("hint","Invented explanation");
            if(change.equals("control"))reply.put("title","bad\0title");if(change.equals("surrogate"))reply.put("title","bad\ud800title");if(change.equals("longTitle"))reply.put("title",String.join("",Collections.nCopies(256,"a")));
            assertThrows(ObjectiveException.class,()->projection.apply(reply.toString()),change);
        }
        assertThrows(ObjectiveException.class,()->projection.apply("{\"title\":\"first\",\"title\":\"second\"}"));assertThrows(ObjectiveException.class,()->projection.apply(identity(projection)+" {}"));assertThrows(ObjectiveException.class,()->projection.apply("not json"));
        assertDoesNotThrow(()->projection.apply("```json\n"+identity(projection)+"\n```"));
        ObjectNode unclosed=identity(projection);unclosed.put("description","$"+unclosed.path("description").asText());assertThrows(ObjectiveException.class,()->projection.apply(unclosed.toString()));
    }
    @Test void allSupportedMathDelimitersAndScientificDataRemainLiteral() throws Exception {
        Content source=original();source.setDescription("<p>Given \\(a+b\\), \\[c+d\\], $$e+f$$, $$$g+h$$$ and 1.2e-9.</p>");
        StatementTranslationProjection projection=new StatementTranslationProjection(source,json);String rendered=projection.apply(identity(projection).toString()).getDescription();
        assertEquals(Jsoup.parse(source.getDescription()).text(),Jsoup.parse(rendered).text());
        String plain=Pattern.compile("⟦HOJ_[a-f0-9]+_[0-9]+⟧").matcher(projection.material().get("description")).replaceAll("");assertFalse(plain.contains("a+b"));assertFalse(plain.contains("1.2e-9"));
    }
    @Test void excessivePayloadAndStructuralComplexityAreRejectedBeforeProvider() {
        Content large=original();large.setDescription(String.join("",Collections.nCopies(10001,"文")));assertThrows(ObjectiveException.class,()->new StatementTranslationProjection(large,json));
        Content complex=original();complex.setDescription(String.join("",Collections.nCopies(129,"<p>word</p>")));assertThrows(ObjectiveException.class,()->new StatementTranslationProjection(complex,json));
        Content deep=original();deep.setDescription(String.join("",Collections.nCopies(70,"<div>"))+"word"+String.join("",Collections.nCopies(70,"</div>")));assertThrows(ObjectiveException.class,()->new StatementTranslationProjection(deep,json));
    }
}
