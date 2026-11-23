package top.hcode.hoj.judge.remote;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.config.NacosSwitchConfig;
import top.hcode.hoj.config.SwitchConfig;
import top.hcode.hoj.dao.judge.JudgeEntityService;
import top.hcode.hoj.judge.Dispatcher;
import top.hcode.hoj.pojo.dto.ToJudgeDTO;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.utils.Constants;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteResultCredentialTest {
    @Test void resultLookupUsesCurrentConfiguredPasswordAndNeverOldSubmissionPassword() {
        RemoteJudgeReceiver receiver=new RemoteJudgeReceiver();SwitchConfig config=new SwitchConfig();config.setCfUsernameList(Collections.singletonList("pool-user"));config.setCfPasswordList(Collections.singletonList("synthetic-current-secret"));
        NacosSwitchConfig source=mock(NacosSwitchConfig.class);when(source.getSwitchConfig()).thenReturn(config);ReflectionTestUtils.setField(receiver,"nacosSwitchConfig",source);
        Dispatcher dispatcher=mock(Dispatcher.class);ReflectionTestUtils.setField(receiver,"dispatcher",dispatcher);
        Judge judge=new Judge().setSubmitId(42L).setVjudgeUsername("pool-user").setVjudgePassword("synthetic-legacy-secret").setVjudgeSubmitId(100L);ToJudgeDTO task=new ToJudgeDTO().setJudge(judge);
        ReflectionTestUtils.invokeMethod(receiver,"commonJudge","CF",true,task,judge);
        assertEquals("synthetic-current-secret",task.getPassword());assertEquals(true,task.getIsHasSubmitIdRemoteReJudge());verify(dispatcher).dispatch(Constants.TaskType.REMOTE_JUDGE,task);
        assertFalse(config.toString().contains("synthetic-current-secret"));assertFalse(config.toString().contains("pool-user"));
    }
    @Test void missingOrMismatchedConfigurationNeverFallsBackToTheOldPasswordOrPostsCode() {
        RemoteJudgeReceiver receiver=new RemoteJudgeReceiver();SwitchConfig config=new SwitchConfig();config.setCfUsernameList(Arrays.asList("another-user","removed-password"));config.setCfPasswordList(Collections.singletonList("synthetic-other-secret"));
        NacosSwitchConfig source=mock(NacosSwitchConfig.class);when(source.getSwitchConfig()).thenReturn(config);ReflectionTestUtils.setField(receiver,"nacosSwitchConfig",source);
        Dispatcher dispatcher=mock(Dispatcher.class);JudgeEntityService judges=mock(JudgeEntityService.class);ReflectionTestUtils.setField(receiver,"dispatcher",dispatcher);ReflectionTestUtils.setField(receiver,"judgeEntityService",judges);
        Judge judge=new Judge().setSubmitId(42L).setVjudgeUsername("removed-password").setVjudgePassword("synthetic-legacy-secret");ToJudgeDTO task=new ToJudgeDTO().setJudge(judge);
        ReflectionTestUtils.invokeMethod(receiver,"commonJudge","CF",true,task,judge);
        verifyZeroInteractions(dispatcher);verify(judges).updateById(argThat((Judge changed)->changed.getStatus()==10 && changed.getVjudgePassword()==null));assertNull(task.getPassword());
    }
}
