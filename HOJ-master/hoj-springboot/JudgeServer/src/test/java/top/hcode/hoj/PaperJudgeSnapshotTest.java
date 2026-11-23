package top.hcode.hoj;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.dao.JudgeEntityService;
import top.hcode.hoj.dao.ProblemEntityService;
import top.hcode.hoj.judge.JudgeContext;
import top.hcode.hoj.judge.ProblemTestCaseUtils;
import top.hcode.hoj.mapper.PaperProgramSnapshotMapper;
import top.hcode.hoj.pojo.dto.PaperProgramSnapshotDTO;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.pojo.entity.problem.*;
import top.hcode.hoj.service.impl.JudgeServiceImpl;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaperJudgeSnapshotTest {
    @TempDir Path temporary;
    ObjectMapper json = new ObjectMapper();
    private PaperProgramSnapshotDTO payload() {
        PaperProgramSnapshotDTO dto = new PaperProgramSnapshotDTO();
        dto.setProblem(new Problem().setId(1L).setTitle("旧题版本").setJudgeMode("default").setJudgeCaseMode("default").setTimeLimit(1000));
        ProblemCase test = new ProblemCase(); test.setId(10L); test.setInput("1 2\n"); test.setOutput("3\n"); test.setScore(100);
        dto.setCases(Collections.singletonList(test)); dto.setLanguages(Collections.singletonList("C++")); return dto;
    }
    @Test void paperCasesUsePrivateImmutableCacheInsteadOfMutableProblemFiles() throws Exception {
        Path paperDir = temporary.resolve("paper_9");
        ProblemTestCaseUtils cases = new ProblemTestCaseUtils() { @Override protected String paperDirectory(Long id) { return paperDir.toString(); } };
        PaperProgramSnapshotDTO dto = payload(); assertEquals(1, cases.loadPaperTestCaseInfo(9L, dto).getInt("testCasesSize"));
        assertEquals("3\n", new String(Files.readAllBytes(paperDir.resolve("1.out")), StandardCharsets.UTF_8));
        dto.getCases().get(0).setOutput("modified"); cases.loadPaperTestCaseInfo(9L, dto);
        assertEquals("3\n", new String(Files.readAllBytes(paperDir.resolve("1.out")), StandardCharsets.UTF_8));
        assertFalse(Files.exists(temporary.resolve("problem_1")));
    }
    @Test void paperExecutionLoadsFrozenProblemAndSkipsOrdinaryAcAndRankUpdates() throws Exception {
        JudgeServiceImpl service = new JudgeServiceImpl(); JudgeEntityService judges = mock(JudgeEntityService.class); ProblemEntityService problems = mock(ProblemEntityService.class); JudgeContext context = mock(JudgeContext.class); PaperProgramSnapshotMapper mapper = mock(PaperProgramSnapshotMapper.class);
        ReflectionTestUtils.setField(service, "judgeEntityService", judges); ReflectionTestUtils.setField(service, "problemEntityService", problems); ReflectionTestUtils.setField(service, "judgeContext", context); ReflectionTestUtils.setField(service, "paperSnapshotMapper", mapper); ReflectionTestUtils.setField(service, "objectMapper", json);
        when(judges.update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(true);
        PaperProgramSnapshot snapshot = new PaperProgramSnapshot(); snapshot.setId(9L); snapshot.setRunId(8L); snapshot.setUid("alice"); snapshot.setContentJson(json.writeValueAsString(payload())); when(mapper.selectById(9L)).thenReturn(snapshot);
        Judge judge = new Judge().setSubmitId(7L).setUid("alice").setPid(1L).setCid(0L).setPaperRunId(8L).setPaperSnapshotId(9L);
        when(context.Judge(any(), same(judge))).thenAnswer(call -> { assertEquals("旧题版本", ((Problem) call.getArgument(0)).getTitle()); assertNotNull(judge.getPaperSnapshot()); return judge.setStatus(0); });
        service.judge(judge); verifyNoInteractions(problems); verify(context, never()).updateOtherTable(any(), any(), any(), any(), any(), any(), any(), any()); verify(judges).updateById(judge);
        snapshot.setUid("bob"); service.judge(judge); assertEquals(4, judge.getStatus());
        verify(context, times(1)).Judge(any(), same(judge));
    }
}
