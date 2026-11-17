package top.hcode.hoj.manager.oj.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeQuestionPlanTest {
    final ObjectMapper json = new ObjectMapper();
    final String query = "1. 如何计算区间和？\n2. 数组修改后怎样维护？";
    KnowledgeQuestionPlan plan() { return KnowledgeQuestionPlan.from(query); }
    List<Map<String,Object>> sources() {
        Map<String,Object> source = new LinkedHashMap<>();source.put("citationId","K1");
        source.put("excerpt","区间和是两个前缀和之差。数组修改后可用树状数组维护。");
        return Collections.singletonList(source);
    }
    Map<String,Object> claim(Object ids) {
        Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("citationId","K1");evidence.put("quote","区间和是两个前缀和之差。");
        Map<String,Object> result=new LinkedHashMap<>();result.put("content","使用前缀和之差计算区间和。");
        result.put("questionIds",ids);result.put("evidence",Collections.singletonList(evidence));return result;
    }
    String reply(boolean answerable,Object missing,Object... claims) throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();result.put("answerable",answerable);result.put("claims",Arrays.asList(claims));result.put("unansweredQuestions",missing);
        return json.writeValueAsString(result);
    }
    Map<String,Object> parse(String text) { return KnowledgeAnswer.parse(text,sources(),json,plan()); }
    void rejected(String text) { assertEquals(502,assertThrows(ObjectiveException.class,()->parse(text)).getStatus()); }

    @Test void topLevelNumberedQuestionsKeepTheirOwnOriginalText() {
        KnowledgeQuestionPlan plan=plan();assertTrue(plan.structured());assertEquals(new LinkedHashSet<>(Arrays.asList("Q1","Q2")),plan.ids());
        assertEquals("1. 如何计算区间和？",plan.text("Q1"));assertEquals("2. 数组修改后怎样维护？",plan.text("Q2"));
        assertEquals("Q2",plan.material().get(1).get("id"));assertEquals(plan.text("Q2"),plan.material().get(1).get("text"));
    }
    @Test void chineseMarkersCrLfAndContinuationLinesKeepCanonicalSourceSegments() {
        KnowledgeQuestionPlan plan=KnowledgeQuestionPlan.from("\r\n（1）第一问\r\n补充条件\r\n（2）第二问\r\n");
        assertTrue(plan.structured());assertEquals("（1）第一问\r\n补充条件",plan.text("Q1"));assertEquals("（2）第二问",plan.text("Q2"));
        assertTrue(KnowledgeQuestionPlan.from("1、问题甲\n2、问题乙").structured());
    }
    @Test void standaloneAndInlineFencedCodeDoesNotCreateQuestions() {
        KnowledgeQuestionPlan plan=KnowledgeQuestionPlan.from("1. 解释示例\n```python\n2. sample input\n```\n2. 解释复杂度");
        assertTrue(plan.structured());assertTrue(plan.text("Q1").contains("2. sample input"));assertEquals("2. 解释复杂度",plan.text("Q2"));
        plan=KnowledgeQuestionPlan.from("1. ```text\n2. sample input\n```\n2. 真正的问题");
        assertTrue(plan.structured());assertTrue(plan.text("Q1").contains("sample input"));assertEquals("2. 真正的问题",plan.text("Q2"));
        assertFalse(KnowledgeQuestionPlan.from("```text\n1. example\n2. example\n```").structured());
    }
    @Test void nestedListsRemainInsideTheOriginalQuestion() {
        KnowledgeQuestionPlan plan=KnowledgeQuestionPlan.from("1. 解释图\n  1. 顶点条件\n  2. 边的条件\n2. 解释复杂度");
        assertTrue(plan.structured());assertEquals(2,plan.ids().size());assertTrue(plan.text("Q1").contains("  2. 边的条件"));
    }
    @Test void ambiguousOrNonNumberedQueriesKeepTheWholeExistingFlow() {
        for(String text:Arrays.asList("如何计算区间和？数组修改后呢？","前提说明\n1. 问题甲\n2. 问题乙",
                "1. 问题甲\n3. 问题乙","1. 问题甲\n1. 问题乙","1.23与2.56的区别？",
                "1. 问题甲\n```text\n2. 尚未闭合","    1. code\n    2. code","1.\u3000\n2. 问题乙")) {
            KnowledgeQuestionPlan plan=KnowledgeQuestionPlan.from(text);assertFalse(plan.structured(),text);assertEquals(Collections.singletonList(text),plan.unanswered());
        }
    }
    @Test void eightQuestionsFitTheExistingBoundAndTheNinthIsNotSilentlyOmitted() {
        StringBuilder text=new StringBuilder();for(int i=1;i<=8;i++)text.append(i).append(". 问题").append(i).append('\n');
        assertEquals(8,KnowledgeQuestionPlan.from(text.toString()).ids().size());
        text.append("9. 第九问");assertFalse(KnowledgeQuestionPlan.from(text.toString()).structured());
    }
    @Test void structuredPartialAnswerRestoresMissingTextWithoutExposingInternalIds() throws Exception {
        Map<String,Object> result=parse(reply(true,Collections.singletonList("Q2"),claim(Collections.singletonList("Q1"))));
        assertEquals("partial",result.get("answerCoverage"));assertEquals(Collections.singletonList(plan().text("Q2")),result.get("unansweredQuestions"));
        assertFalse(((Map<?,?>)((List<?>)result.get("claims")).get(0)).containsKey("questionIds"));assertEquals(1,((List<?>)result.get("citations")).size());
    }
    @Test void completeAndFullyInsufficientAnswersAccountForEveryNumberedQuestion() throws Exception {
        Map<String,Object> all=claim(Arrays.asList("Q1","Q2"));all.put("content","区间和取前缀和之差，修改时可使用树状数组维护。");
        ((Map<String,Object>)((List<?>)all.get("evidence")).get(0)).put("quote","区间和是两个前缀和之差。数组修改后可用树状数组维护。");
        assertEquals("complete",parse(reply(true,Collections.emptyList(),all)).get("answerCoverage"));
        Map<String,Object> none=parse(reply(false,Arrays.asList("Q1","Q2")));
        assertEquals("insufficient",none.get("answerCoverage"));assertEquals(plan().unanswered(),none.get("unansweredQuestions"));assertEquals(Collections.emptyList(),none.get("citations"));
    }
    @Test void anOmittedNumberedQuestionCannotBeReportedAsComplete() throws Exception {
        rejected(reply(true,Collections.emptyList(),claim(Collections.singletonList("Q1"))));
        rejected(reply(false,Collections.singletonList("Q1")));
    }
    @Test void unknownIdsInventedTextAndAnsweredMissingOverlapAreRejected() throws Exception {
        rejected(reply(true,Collections.singletonList("Q3"),claim(Collections.singletonList("Q1"))));
        rejected(reply(true,Collections.singletonList("其他未问过的问题"),claim(Collections.singletonList("Q1"))));
        rejected(reply(true,Arrays.asList("Q1","Q2"),claim(Collections.singletonList("Q1"))));
        rejected(reply(true,Collections.singletonList("Q2"),claim(Collections.singletonList("Q9"))));
    }
    @Test void duplicateOrMalformedCoverageArraysAreRejected() throws Exception {
        for(Object ids:Arrays.asList(Collections.emptyList(),Arrays.asList("Q1","Q1"),Arrays.asList(1),"Q1",null))
            rejected(reply(true,Collections.singletonList("Q2"),claim(ids)));
        rejected(reply(false,Arrays.asList("Q1"," Q1 ","Q2")));
    }
    @Test void coverageIdsDoNotRelaxExactSourceQuoteChecks() throws Exception {
        Map<String,Object> paragraph=claim(Collections.singletonList("Q1"));
        ((Map<String,Object>)((List<?>)paragraph.get("evidence")).get(0)).put("quote","原文中不存在这句话");
        rejected(reply(true,Collections.singletonList("Q2"),paragraph));
    }
    @Test void ordinaryQuestionsRetainTheExistingPartialAnswerContract() throws Exception {
        Map<String,Object> paragraph=claim(Collections.emptyList());paragraph.remove("questionIds");
        Map<String,Object> result=KnowledgeAnswer.parse(reply(true,Collections.singletonList("数组修改后呢？"),paragraph),sources(),json,
                KnowledgeQuestionPlan.from("如何计算区间和？数组修改后呢？"));
        assertEquals("partial",result.get("answerCoverage"));assertEquals(Collections.singletonList("数组修改后呢？"),result.get("unansweredQuestions"));
    }
}
