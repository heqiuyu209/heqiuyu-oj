package top.hcode.hoj.judge.remote;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.*;
import top.hcode.hoj.dao.judge.JudgeEntityService;
import top.hcode.hoj.judge.Dispatcher;
import top.hcode.hoj.manager.admin.rejudge.RejudgeManager;
import top.hcode.hoj.manager.oj.JudgeManager;
import top.hcode.hoj.pojo.entity.judge.Judge;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalRemoteIsolationTest {
    PersonalRemoteSubmissionGuard guard() {
        PersonalRemoteSubmissionGuard guard = mock(PersonalRemoteSubmissionGuard.class);
        when(guard.managed(42L)).thenReturn(true); return guard;
    }
    @Test void legacyDispatcherAndAlreadyQueuedMessagesCannotPostAPersonalTask() {
        RemoteJudgeDispatcher dispatcher = new RemoteJudgeDispatcher();
        ReflectionTestUtils.setField(dispatcher, "personalRemoteSubmissionGuard", guard());
        assertThrows(IllegalStateException.class, () -> dispatcher.sendTask(42L, 1L, "CF-4A", false, false));
        RemoteJudgeReceiver receiver = new RemoteJudgeReceiver(); JudgeEntityService judges = mock(JudgeEntityService.class); Dispatcher client = mock(Dispatcher.class);
        when(judges.getById(42L)).thenReturn(new Judge().setSubmitId(42L).setCid(0L));
        ReflectionTestUtils.setField(receiver, "personalRemoteSubmissionGuard", guard()); ReflectionTestUtils.setField(receiver, "judgeEntityService", judges); ReflectionTestUtils.setField(receiver, "dispatcher", client);
        receiver.handleJudgeMsg("{\"judgeId\":42,\"remoteJudgeProblem\":\"CF-4A\",\"isHasSubmitIdRemoteReJudge\":false}", "test");
        verifyZeroInteractions(client); verify(judges, never()).updateById(any());
    }
    @Test void publicResubmitCannotMutateOrDispatchAPersonalJudge() {
        JudgeManager manager = new JudgeManager(); JudgeEntityService judges = mock(JudgeEntityService.class);
        when(judges.getById(42L)).thenReturn(new Judge().setSubmitId(42L).setUid("alice").setCid(0L));
        ReflectionTestUtils.setField(manager, "judgeEntityService", judges); ReflectionTestUtils.setField(manager, "personalRemoteSubmissionGuard", guard());
        assertThrows(StatusForbiddenException.class, () -> manager.resubmit(42L)); verify(judges, never()).updateById(any());
    }
    @Test void administrativeRejudgeManualVerdictAndCancellationUseThePersonalJournalInstead() {
        RejudgeManager manager = new RejudgeManager(); JudgeEntityService judges = mock(JudgeEntityService.class);
        ReflectionTestUtils.setField(manager, "judgeEntityService", judges); ReflectionTestUtils.setField(manager, "personalRemoteSubmissionGuard", guard());
        assertThrows(StatusFailException.class, () -> manager.rejudge(42L));
        assertThrows(StatusFailException.class, () -> manager.manualJudge(42L, 0, 100));
        assertThrows(StatusFailException.class, () -> manager.cancelJudge(42L));
        assertThrows(StatusFailException.class, () -> manager.checkAndUpdateJudge(false, new Judge().setSubmitId(42L), 42L));
        assertThrows(StatusFailException.class, () -> manager.checkAndUpdateJudgeBatch(Collections.singletonList(new Judge().setSubmitId(42L)), new HashMap<>()));
        verifyZeroInteractions(judges);
    }
}
