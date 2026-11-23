package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.judge.self.JudgeDispatcher;
import top.hcode.hoj.mapper.*;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.pojo.entity.problem.*;
import top.hcode.hoj.shiro.AccountProfile;
import top.hcode.hoj.validator.AccessValidator;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaperProgramManagerTest {
    PaperProgramManager manager;
    ProblemMapper problems;
    ProblemCaseMapper cases;
    PaperProgramSnapshotMapper snapshots;
    JudgeMapper judges;
    JudgeDispatcher dispatcher;
    JdbcTemplate jdbc;
    Map<Long, PaperProgramSnapshot> stored;
    @BeforeEach void setup() {
        manager = new PaperProgramManager(); problems = mock(ProblemMapper.class); cases = mock(ProblemCaseMapper.class); snapshots = mock(PaperProgramSnapshotMapper.class); judges = mock(JudgeMapper.class); dispatcher = mock(JudgeDispatcher.class); jdbc = mock(JdbcTemplate.class); stored = new HashMap<>();
        ReflectionTestUtils.setField(manager, "problemMapper", problems); ReflectionTestUtils.setField(manager, "caseMapper", cases); ReflectionTestUtils.setField(manager, "snapshotMapper", snapshots); ReflectionTestUtils.setField(manager, "judgeMapper", judges); ReflectionTestUtils.setField(manager, "dispatcher", dispatcher); ReflectionTestUtils.setField(manager, "jdbc", jdbc); ReflectionTestUtils.setField(manager, "json", new ObjectMapper()); ReflectionTestUtils.setField(manager, "accessValidator", mock(AccessValidator.class));
        AccountProfile profile = new AccountProfile(); profile.setUid("alice"); profile.setUsername("alice"); Subject subject = mock(Subject.class); when(subject.getPrincipal()).thenReturn(profile); ThreadContext.bind(subject);
        when(snapshots.insert(any())).thenAnswer(call -> { PaperProgramSnapshot snapshot = call.getArgument(0); snapshot.setId(11L); stored.put(11L, snapshot); return 1; }); when(snapshots.selectById(any())).thenAnswer(call -> stored.get(call.getArgument(0)));
        when(jdbc.queryForList(anyString(), eq(String.class), anyLong())).thenReturn(Collections.singletonList("C++"));
        when(judges.insert(any())).thenAnswer(call -> { ((Judge) call.getArgument(0)).setSubmitId(19L); return 1; });
    }
    @AfterEach void cleanup() { ThreadContext.remove(); if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization(); }
    private Long freeze() {
        when(problems.selectById(1L)).thenReturn(new Problem().setId(1L).setProblemId("P1000").setTitle("原题").setAuth(1).setIsRemote(false).setIsGroup(false).setJudgeMode("default").setIsUploadCase(false));
        ProblemCase test = new ProblemCase(); test.setId(5L); test.setInput("1 2"); test.setOutput("3"); when(cases.selectList(any())).thenReturn(Collections.singletonList(test));
        return manager.freeze(7L, "PROGRAMMING:1", "alice", 1L);
    }
    @Test void statementCasesAndLanguagesAreFrozenBeforeDispatchAndSubmissionIsRunScoped() {
        Long id = freeze(); problems.selectById(1L).setTitle("管理员改题后"); assertEquals("原题", manager.snapshot(id).getProblem().getTitle()); assertEquals("3", manager.snapshot(id).getCases().get(0).getOutput());
        TransactionSynchronizationManager.initSynchronization(); manager.submit(7L, id, "alice", "C++", "int main(){}");
        verify(dispatcher, never()).sendTask(anyLong(), anyLong(), anyBoolean());
        org.mockito.ArgumentCaptor<Judge> captured = org.mockito.ArgumentCaptor.forClass(Judge.class); verify(judges).insert(captured.capture());
        assertEquals(7L, captured.getValue().getPaperRunId()); assertEquals(11L, captured.getValue().getPaperSnapshotId()); assertEquals(0L, captured.getValue().getCid()); assertFalse(captured.getValue().getShare());
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit()); verify(dispatcher).sendTask(19L, 1L, false);
    }
    @Test void foreignSnapshotUnsupportedLanguageAndInvalidCodeAreRejected() {
        Long id = freeze();
        assertThrows(ObjectiveException.class, () -> manager.submit(8L, id, "alice", "C++", "code"));
        assertThrows(ObjectiveException.class, () -> manager.submit(7L, id, "bob", "C++", "code"));
        assertThrows(ObjectiveException.class, () -> manager.validate(id, "Python3", "print(3)"));
        assertThrows(ObjectiveException.class, () -> manager.validate(id, "C++", " "));
        verify(judges, never()).insert(any());
    }
    @Test void remoteSpecialJudgeAndPrivateProblemAreNotOfferedAsSupportedPaperItems() {
        freeze(); problems.selectById(1L).setIsRemote(true); assertThrows(ObjectiveException.class, () -> manager.available(1L));
        problems.selectById(1L).setIsRemote(false).setJudgeMode("spj"); assertThrows(ObjectiveException.class, () -> manager.available(1L));
        problems.selectById(1L).setJudgeMode("default").setAuth(2); assertThrows(ObjectiveException.class, () -> manager.available(1L));
    }
    @Test void privateReferencesRequireSourcePermissionButAuthorizedCollectionCanFreezeThemForStudents() {
        freeze();
        Problem privateProblem = problems.selectById(1L).setAuth(2).setAuthor("alice");
        assertSame(privateProblem, manager.availableForCollection(1L));
        privateProblem.setAuthor("bob");
        assertEquals(403, assertThrows(ObjectiveException.class, () -> manager.availableForCollection(1L)).getStatus());
        when(org.apache.shiro.SecurityUtils.getSubject().hasRole("problem_admin")).thenReturn(true);
        assertSame(privateProblem, manager.availableForCollection(1L));
        when(org.apache.shiro.SecurityUtils.getSubject().hasRole("problem_admin")).thenReturn(false);
        assertNotNull(manager.freezeForCollection(9L, "PROGRAMMING:1", "student", 1L));
        assertEquals(2, manager.snapshot(11L).getProblem().getAuth());
        assertThrows(ObjectiveException.class, () -> manager.capture(1L));
        privateProblem.setIsGroup(true);
        assertThrows(ObjectiveException.class, () -> manager.freezeForCollection(9L, "PROGRAMMING:1", "student", 1L));
    }
}
