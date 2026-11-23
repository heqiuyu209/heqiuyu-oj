package top.hcode.hoj.manager.oj;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.*;
import top.hcode.hoj.dao.judge.JudgeEntityService;
import top.hcode.hoj.pojo.dto.SubmitIdListDTO;
import top.hcode.hoj.pojo.entity.judge.Judge;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaperSubmissionIsolationTest {
    @Test void genericSubmissionDetailsSharingRejudgeAndCaseResultsCannotBypassPaperReleaseRules() {
        JudgeManager manager = new JudgeManager(); JudgeEntityService judges = mock(JudgeEntityService.class);
        ReflectionTestUtils.setField(manager, "judgeEntityService", judges);
        Judge paper = new Judge().setSubmitId(1L).setPaperRunId(9L).setUid("alice"); when(judges.getById(1L)).thenReturn(paper); when(judges.getOne(any(Wrapper.class))).thenReturn(paper);
        assertThrows(StatusAccessDeniedException.class, () -> manager.getSubmission(1L));
        assertThrows(StatusForbiddenException.class, () -> manager.getALLCaseResult(1L));
        assertThrows(StatusForbiddenException.class, () -> manager.resubmit(1L));
        assertThrows(StatusForbiddenException.class, () -> manager.updateSubmission(new Judge().setSubmitId(1L).setShare(true)));
        verify(judges, never()).updateById(any());
    }
    @Test void publicStatusPollingExplicitlyExcludesPaperSubmissions() {
        JudgeManager manager = new JudgeManager(); JudgeEntityService judges = mock(JudgeEntityService.class); ReflectionTestUtils.setField(manager, "judgeEntityService", judges);
        when(judges.list(any(Wrapper.class))).thenAnswer(call -> { assertTrue(((Wrapper<?>) call.getArgument(0)).getSqlSegment().contains("paper_run_id IS NULL")); return Collections.emptyList(); });
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "isolated"), Judge.class);
        SubmitIdListDTO dto = new SubmitIdListDTO(); dto.setSubmitIds(Arrays.asList(1L, 2L)); assertTrue(manager.checkCommonJudgeResult(dto).isEmpty());
    }
}
