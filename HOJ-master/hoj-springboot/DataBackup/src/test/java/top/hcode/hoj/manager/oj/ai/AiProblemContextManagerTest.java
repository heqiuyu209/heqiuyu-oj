package top.hcode.hoj.manager.oj.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.*;
import top.hcode.hoj.manager.oj.*;
import top.hcode.hoj.pojo.dto.AiDTO;
import top.hcode.hoj.pojo.entity.problem.Problem;
import top.hcode.hoj.pojo.vo.ProblemInfoVO;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiProblemContextManagerTest {
    AiProblemContextManager manager; ObjectMapper json=new ObjectMapper();
    ProblemManager problems;ContestManager contests;ObjectiveManager objectives;PaperManager papers;AiAccessGuard access;
    @BeforeEach void setup() {
        manager=new AiProblemContextManager();problems=mock(ProblemManager.class);contests=mock(ContestManager.class);objectives=mock(ObjectiveManager.class);papers=mock(PaperManager.class);access=mock(AiAccessGuard.class);
        ReflectionTestUtils.setField(manager,"problems",problems);ReflectionTestUtils.setField(manager,"contests",contests);ReflectionTestUtils.setField(manager,"objectives",objectives);ReflectionTestUtils.setField(manager,"papers",papers);ReflectionTestUtils.setField(manager,"access",access);ReflectionTestUtils.setField(manager,"json",json);
    }
    Map<String,Object> map(Object... values) { Map<String,Object> row=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)row.put((String)values[i],values[i+1]);return row; }
    AiDTO.ProblemReference program() { AiDTO.ProblemReference ref=new AiDTO.ProblemReference();ref.setKind("PROGRAMMING");ref.setProblemId("P1");return ref; }
    AiDTO.ProblemReference run(String kind,String key) { AiDTO.ProblemReference ref=new AiDTO.ProblemReference();ref.setKind(kind);ref.setRunId(1L);ref.setQuestionKey(key);return ref; }
    Map<String,Object> question() {
        return map("id",7L,"key","OBJECTIVE:7","kind","OBJECTIVE","title","Frozen task","source","Internal course","stem","Select a value","questionType","FILL_BLANK","version",2,
                "options",Collections.singletonList(map("id","A","text","Visible option","correct",true)),
                "blanks",Collections.singletonList(map("id","x","label","Value","answers",Collections.singletonList("SECRET_BLANK"))),
                "correctAnswers",Collections.singletonList("SECRET_ANSWER"),"selectedAnswers",Collections.singletonList("PERSONAL_ANSWER"),"explanation","SECRET_SOLUTION","code","PERSONAL_CODE","score",100);
    }
    void status(int code,org.junit.jupiter.api.function.Executable action) { assertEquals(code,assertThrows(ObjectiveException.class,action).getStatus()); }
    @Test void programProjectionKeepsLiteralStatementAndSamplesButNeverHintsTestsOrCode() throws Exception {
        Problem p=new Problem();p.setId(1L);p.setTitle("Task");p.setDescription("```cpp\nvector<int> a;\n```\n<img src=x>");p.setInput("n\n");p.setOutput("sum\n");p.setExamples("<input>1 2</input><output>3</output>");p.setHint("SECRET_HINT");p.setSpjCode("SECRET_SPJ");
        when(problems.getProblemInfo("P1",null)).thenReturn(new ProblemInfoVO(p,Collections.emptyList(),Collections.emptyList(),null,new HashMap<>()));
        AiProblemContextManager.Context context=manager.read("alice",program());assertEquals(p.getDescription(),context.material.get("stem"));assertEquals(p.getExamples(),context.material.get("examples"));
        String body=json.writeValueAsString(context.view());assertFalse(body.contains("SECRET_"));assertFalse(body.contains("codeTemplate"));assertTrue(context.fingerprint.matches("[a-f0-9]{64}"));verify(access,times(2)).requireChat("alice");
    }
    @Test void objectiveAndPaperRowsUseOnlyFrozenPublicFieldsEvenWhenGradesAreOpen() throws Exception {
        Map<String,Object> row=question();when(objectives.run(1L)).thenReturn(map("questions",Collections.singletonList(row)));when(papers.run(1L)).thenReturn(map("items",Collections.singletonList(row)));
        for(AiDTO.ProblemReference ref:Arrays.asList(run("OBJECTIVE_RUN","7"),run("PAPER_RUN","OBJECTIVE:7"))) {
            AiProblemContextManager.Context context=manager.read("alice",ref);String body=json.writeValueAsString(context.view());
            assertEquals("Frozen task",context.material.get("title"));assertEquals(2,context.material.get("version"));assertFalse(body.contains("SECRET_"));assertFalse(body.contains("PERSONAL_"));assertFalse(body.contains("selectedAnswers"));assertFalse(body.contains("correctAnswers"));assertFalse(body.contains("score"));
        }
        AiProblemContextManager.Context old=manager.read("alice",run("OBJECTIVE_RUN","7"));row.put("score",0);row.put("correctAnswers",Collections.singletonList("OTHER_SECRET"));
        assertEquals(old.fingerprint,manager.read("alice",old.reference).fingerprint);verifyNoInteractions(problems,contests);
    }
    @Test void paperProgrammingUsesItsAuthorizedSnapshotWithoutReopeningPrivateLibraryProblem() {
        when(papers.run(1L)).thenReturn(map("items",Collections.singletonList(map("key","PROGRAMMING:7","kind","PROGRAMMING","title","Private snapshot task","questionType","PROGRAMMING","stem","Frozen original","input","n","output","n*n","examples","1 -> 1","explanation","SECRET","code","PRIVATE_CODE"))));
        AiProblemContextManager.Context result=manager.read("alice",run("PAPER_RUN","PROGRAMMING:7"));assertEquals("Frozen original",result.material.get("stem"));assertFalse(result.material.containsKey("explanation"));assertFalse(result.material.containsKey("code"));verifyNoInteractions(problems,contests,objectives);
    }
    @Test void existingReadersStillDecideGroupContestAndRunOwnership() throws Exception {
        when(problems.getProblemInfo("P1",9L)).thenThrow(new StatusForbiddenException("not a member"));AiDTO.ProblemReference group=program();group.setGroupId(9L);status(403,()->manager.read("bob",group));
        AiDTO.ProblemReference contest=program();contest.setContestId(4L);when(contests.getContestProblemDetails(4L,"P1",true)).thenThrow(new StatusNotFoundException("missing"));status(404,()->manager.read("bob",contest));
        when(objectives.run(1L)).thenThrow(new ObjectiveException(404,"not owner"));status(404,()->manager.read("bob",run("OBJECTIVE_RUN","7")));
        when(papers.run(1L)).thenThrow(new ObjectiveException(403,"withdrawn"));status(403,()->manager.read("alice",run("PAPER_RUN","OBJECTIVE:7")));
    }
    @Test void mismatchedRowsInvalidReferencesAndOversizedContentAreRejected() {
        when(objectives.run(1L)).thenReturn(map("questions",Collections.singletonList(question())));status(404,()->manager.read("alice",run("OBJECTIVE_RUN","8")));
        status(400,()->manager.read("alice",null));status(400,()->manager.read("alice",run("OBJECTIVE_RUN","7 OR 1=1")));
        AiDTO.ProblemReference wrong=program();wrong.setRunId(1L);status(400,()->manager.read("alice",wrong));
        Map<String,Object> row=question();row.put("stem",String.join("",Collections.nCopies(24001,"x")));when(objectives.run(1L)).thenReturn(map("questions",Collections.singletonList(row)));status(400,()->manager.read("alice",run("OBJECTIVE_RUN","7")));
    }
    @Test void changedOrRevokedStatementCannotReusePreviewFingerprint() {
        Map<String,Object> row=question();when(objectives.run(1L)).thenReturn(map("questions",Collections.singletonList(row)));AiProblemContextManager.Context before=manager.read("alice",run("OBJECTIVE_RUN","7"));
        AiDTO.ProblemContext dto=new AiDTO.ProblemContext();dto.setReference(before.reference);dto.setFingerprint(before.fingerprint);assertEquals(before.fingerprint,manager.confirmed("alice",dto).fingerprint);
        row.put("stem","Changed statement");status(409,()->manager.confirmed("alice",dto));status(409,()->manager.verify("alice",before));
        when(objectives.run(1L)).thenThrow(new ObjectiveException(403,"withdrawn"));status(403,()->manager.verify("alice",before));
    }
    @Test void competitionDenialPrecedesEveryReaderAndIsRepeatedAfterReading() {
        doThrow(new ObjectiveException(403,"participant")).when(access).requireChat("alice");status(403,()->manager.read("alice",program()));verifyNoInteractions(problems,contests,objectives,papers);
        doNothing().doThrow(new ObjectiveException(403,"just started")).when(access).requireChat("bob");when(objectives.run(1L)).thenReturn(map("questions",Collections.singletonList(question())));status(403,()->manager.read("bob",run("OBJECTIVE_RUN","7")));
    }
    @Test void sourceIdentityUsesTheAuthorizedOriginIdAndRebindingInvalidatesThePreview() throws Exception {
        Problem p=new Problem();p.setId(1L);p.setProblemId("A");p.setTitle("Task");p.setDescription("Same statement");when(problems.getProblemInfo("P1",null)).thenReturn(new ProblemInfoVO(p,Collections.emptyList(),Collections.emptyList(),null,new HashMap<>()));
        AiProblemContextManager.Context before=manager.read("alice",program());AiDTO.ProblemContext dto=new AiDTO.ProblemContext();dto.setReference(before.reference);dto.setFingerprint(before.fingerprint);p.setId(2L);status(409,()->manager.confirmed("alice",dto));assertNotEquals(before.fingerprint,manager.read("alice",program()).fingerprint);
    }
}
