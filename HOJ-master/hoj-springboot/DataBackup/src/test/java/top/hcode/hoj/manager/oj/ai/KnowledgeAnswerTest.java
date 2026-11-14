package top.hcode.hoj.manager.oj.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeAnswerTest {
    final ObjectMapper json = new ObjectMapper();
    Map<String, Object> citation(String id, String text) {
        Map<String, Object> value = new LinkedHashMap<>();value.put("citationId",id);value.put("excerpt",text);
        value.put("url","/knowledge/server-owned-id?version=3&page=2");value.put("page",2);value.put("startMs",65500);return value;
    }
    Map<String, String> quote(String id, String quote) {Map<String,String> value=new LinkedHashMap<>();value.put("citationId",id);value.put("quote",quote);return value;}
    Map<String, Object> claim(String content, Object... evidence) {Map<String,Object> value=new LinkedHashMap<>();value.put("content",content);value.put("evidence",Arrays.asList(evidence));return value;}
    String reply(boolean answerable,Object... claims)throws Exception {return withUnanswered(answerable,answerable?Collections.emptyList():Collections.singletonList("当前问题缺少哪份资料？"),claims);}
    String withUnanswered(boolean answerable,Object unanswered,Object... claims)throws Exception {Map<String,Object> value=new LinkedHashMap<>();value.put("answerable",answerable);value.put("claims",Arrays.asList(claims));value.put("unansweredQuestions",unanswered);return json.writeValueAsString(value);}
    List<Map<String,Object>> sources(){return Arrays.asList(citation("K1","建立前缀和。区间查询使用两个前缀值相减。"),citation("K2","正文保持原有的物理页号。"));}
    Map<String,Object> parse(String text){return KnowledgeAnswer.parse(text,sources(),json);}
    void rejected(String text){ObjectiveException error=assertThrows(ObjectiveException.class,()->parse(text));assertEquals(502,error.getStatus());}
    String repeat(String text,int count){return String.join("",Collections.nCopies(count,text));}

    @Test void paragraphsRetainTheirOwnQuotesAndTheGlobalCitationListIsDeduplicated()throws Exception{
        String text=reply(true,claim("先预处理。",quote("K1","建立前缀和。")),claim("再查询。",quote("K1","两个前缀值相减"),quote("K2","物理页号")));
        Map<String,Object> result=parse(text);assertEquals("先预处理。\n\n再查询。",result.get("content"));assertEquals(true,result.get("answerable"));
        assertEquals(2,((List<?>)result.get("claims")).size());assertEquals(2,((List<?>)result.get("citations")).size());
        assertEquals(sources().get(1),((List<?>)result.get("citations")).get(1));
        assertEquals(json.readTree(text).path("claims"),json.valueToTree(result.get("claims")));
    }
    @Test void refusalReturnsOnlyTheServerMessageAndNoUsedCitations()throws Exception{
        Map<String,Object> result=parse(reply(false));assertEquals(KnowledgeAnswer.INSUFFICIENT,result.get("content"));
        assertEquals(false,result.get("answerable"));assertTrue(((List<?>)result.get("claims")).isEmpty());assertTrue(((List<?>)result.get("citations")).isEmpty());
        assertEquals("insufficient",result.get("answerCoverage"));assertEquals(1,((List<?>)result.get("unansweredQuestions")).size());
    }
    @Test void partialAnswerKeepsBackedClaimsAndListsOnlyTheUnansweredQuestions()throws Exception{
        List<String> missing=Arrays.asList("修改数组后如何更新？","动态查询的时间复杂度是多少？");
        Map<String,Object> result=parse(withUnanswered(true,missing,claim("静态查询使用两个前缀值相减。",quote("K1","两个前缀值相减"))));
        assertEquals("partial",result.get("answerCoverage"));assertEquals(true,result.get("answerable"));
        assertEquals(missing,result.get("unansweredQuestions"));assertEquals(1,((List<?>)result.get("claims")).size());
        assertEquals(Collections.singletonList(sources().get(0)),result.get("citations"));
        assertFalse(((String)result.get("content")).contains("动态查询"));
        Map<String,Object> complete=parse(reply(true,claim("先预处理。",quote("K1","建立前缀和。"))));
        assertEquals("complete",complete.get("answerCoverage"));assertEquals(Collections.emptyList(),complete.get("unansweredQuestions"));
    }
    @Test void unansweredQuestionsCannotBeOmittedMalformedBlankDuplicateOrContradictory()throws Exception{
        rejected("{\"answerable\":true,\"claims\":[{\"content\":\"说明\",\"evidence\":[{\"citationId\":\"K1\",\"quote\":\"建立前缀和。\"}]}]}");
        for(Object missing:Arrays.asList(null,"缺资料",Arrays.asList(1),Arrays.asList("Q"," Q "),Arrays.asList("\u3000"),Arrays.asList("\u00a0"))){
            rejected(withUnanswered(true,missing,claim("说明",quote("K1","建立前缀和。"))));
        }
        rejected(withUnanswered(false,Collections.emptyList()));
        rejected(withUnanswered(false,Collections.singletonList("未答"),claim("矛盾",quote("K1","建立前缀和。"))));
    }
    @Test void unansweredQuestionCountAndIndividualAndTotalLengthsAreBounded()throws Exception{
        Object paragraph=claim("说明",quote("K1","建立前缀和。"));
        rejected(withUnanswered(true,Arrays.asList("1","2","3","4","5","6","7","8","9"),paragraph));
        rejected(withUnanswered(true,Collections.singletonList(repeat("x",2001)),paragraph));
        rejected(withUnanswered(true,Arrays.asList(repeat("x",2000),repeat("y",2000),"z"),paragraph));
        Map<String,Object> accepted=parse(withUnanswered(true,Arrays.asList(repeat("x",2000),repeat("y",2000)),paragraph));
        assertEquals(2,((List<?>)accepted.get("unansweredQuestions")).size());
    }
    @Test void knownCitationIdCannotBorrowAQuoteFromAnotherSource()throws Exception{
        rejected(reply(true,claim("内容",quote("K1","物理页号"))));rejected(reply(true,claim("内容",quote("K3","建立前缀和。"))));
    }
    @Test void everyParagraphRequiresAtLeastOneExactNonblankQuote()throws Exception{
        rejected(reply(true,claim("没有依据")));rejected(reply(true,claim("没有依据",quote("K1"," "))));
        rejected(reply(true,claim("先预处理",quote("K1","建立前缀和。")),claim("追加的无依据结论")));
    }
    @Test void unicodeWhitespaceCannotBecomeAnApparentlyEmptyParagraphOrProof()throws Exception{
        for (String empty : Arrays.asList("\u3000", "\u00a0", "\u2003", "\ufeff", "\t\n\u3000")) {
            rejected(reply(true,claim(empty,quote("K1","建立前缀和。"))));
            assertThrows(ObjectiveException.class,()->KnowledgeAnswer.parse(reply(true,claim("说明",quote("K1",empty))),Collections.singletonList(citation("K1","before"+empty+"after")),json));
        }
    }
    @Test void quotesCannotBeParaphrasedOrSplicedAcrossTheSource()throws Exception{
        rejected(reply(true,claim("说明",quote("K1","先建立数组，再相减"))));
        rejected(reply(true,claim("说明",quote("K1","建立前缀和。……两个前缀值相减"))));
    }
    @Test void extraModelSuppliedLinksPagesAndFieldsAreRejectedAtEveryLevel()throws Exception{
        Map<String,Object> paragraph=claim("说明",quote("K1","建立前缀和。"));paragraph.put("url","https://invented.invalid");rejected(reply(true,paragraph));
        Map<String,Object> proof=new LinkedHashMap<>(quote("K1","建立前缀和。"));proof.put("page",99);rejected(reply(true,claim("说明",proof)));
        rejected("{\"answerable\":false,\"claims\":[],\"content\":\"unsupported text\"}");
    }
    @Test void contradictoryAnswerabilityIsNotAccepted()throws Exception{
        rejected(reply(true));rejected(reply(false,claim("猜测",quote("K1","建立前缀和。"))));
        rejected("{\"answerable\":\"true\",\"claims\":[]}");
    }
    @Test void duplicateJsonKeysAndTrailingValuesNeverPublishAnAnswer()throws Exception{
        rejected("{\"answerable\":true,\"answerable\":false,\"claims\":[]}");
        rejected(reply(false)+reply(false));rejected(reply(false)+" trailing");
    }
    @Test void deeplyNestedProviderDataIsRejectedBeforeRecursiveTreeConstruction(){rejected(repeat("[",1000)+"0"+repeat("]",1000));}
    @Test void duplicateQuotePairsAreRejectedButSeparateQuotesFromOneSourceAreAllowed()throws Exception{
        rejected(reply(true,claim("说明",quote("K1","建立前缀和。"),quote("K1","建立前缀和。"))));
        Map<String,Object> result=parse(reply(true,claim("说明",quote("K1","建立前缀和。"),quote("K1","两个前缀值相减"))));assertEquals(1,((List<?>)result.get("citations")).size());
    }
    @Test void paragraphAndReplyLengthLimitsAreEnforced()throws Exception{
        rejected(reply(true,claim(repeat("x",2001),quote("K1","建立前缀和。"))));rejected(repeat("x",24001));rejected(null);
        Object[] claims=new Object[9];Arrays.fill(claims,claim("说明",quote("K1","建立前缀和。")));rejected(reply(true,claims));
    }
    @Test void totalParagraphBudgetIsCheckedAcrossOtherwiseValidParagraphs()throws Exception{
        Object[] claims=new Object[5];Arrays.fill(claims,claim(repeat("x",1800),quote("K1","建立前缀和。")));rejected(reply(true,claims));
    }
    @Test void individualAndTotalQuoteBudgetsAreBounded()throws Exception{
        List<Map<String,Object>> source=Collections.singletonList(citation("K1",repeat("x",1000)));
        assertThrows(ObjectiveException.class,()->KnowledgeAnswer.parse(reply(true,claim("说明",quote("K1",repeat("x",801)))),source,json));
        Object[] claims=new Object[8];Arrays.fill(claims,claim("说明",quote("K1",repeat("x",800))));
        assertThrows(ObjectiveException.class,()->KnowledgeAnswer.parse(reply(true,claims),source,json));
    }
    @Test void markdownCodeFenceIsSupportedAndQuoteWhitespaceIsPreserved()throws Exception{
        String text=reply(true,claim("说明",quote("K1","  x\n y  ")));
        Map<String,Object> result=KnowledgeAnswer.parse("```json\n"+text+"\n```",Collections.singletonList(citation("K1","before  x\n y  after")),json);
        assertEquals(json.readTree(text).path("claims"),json.valueToTree(result.get("claims")));
    }
    @Test void metadataIsTakenOnlyFromTheVerifiedServerCitation()throws Exception{
        Map<String,Object> result=parse(reply(true,claim("说明",quote("K2","物理页号"))));
        assertEquals(sources().get(1),((List<?>)result.get("citations")).get(0));
    }
}
