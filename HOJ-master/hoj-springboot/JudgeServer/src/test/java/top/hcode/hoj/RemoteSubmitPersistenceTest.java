package top.hcode.hoj;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.dao.JudgeEntityService;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.remoteJudge.RemoteJudgeToSubmit;
import top.hcode.hoj.remoteJudge.entity.RemoteJudgeDTO;
import top.hcode.hoj.remoteJudge.task.RemoteJudgeStrategy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteSubmitPersistenceTest {
    @Test void legacyRemoteSuccessPersistsResultIdentityWithoutPersistingTheLoginPassword() {
        RemoteJudgeDTO dto=RemoteJudgeDTO.builder().judgeId(42L).submitId(100L).oj("AC").username("synthetic-user").password("synthetic-secret").build();
        RemoteJudgeStrategy strategy=mock(RemoteJudgeStrategy.class);when(strategy.getRemoteJudgeDTO()).thenReturn(dto);
        JudgeEntityService judges=mock(JudgeEntityService.class);RemoteJudgeToSubmit submit=new RemoteJudgeToSubmit();ReflectionTestUtils.setField(submit,"judgeEntityService",judges);
        assertTrue(submit.process(strategy));ArgumentCaptor<Judge> changed=ArgumentCaptor.forClass(Judge.class);verify(judges).updateById(changed.capture());
        assertEquals(42L,changed.getValue().getSubmitId());assertEquals(100L,changed.getValue().getVjudgeSubmitId());assertEquals("synthetic-user",changed.getValue().getVjudgeUsername());assertNull(changed.getValue().getVjudgePassword());
    }
}
