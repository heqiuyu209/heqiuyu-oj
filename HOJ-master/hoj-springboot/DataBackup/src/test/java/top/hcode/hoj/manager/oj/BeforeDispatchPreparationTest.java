package top.hcode.hoj.manager.oj;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.*;
import org.mockito.*;
import top.hcode.hoj.common.exception.*;
import top.hcode.hoj.dao.contest.*;
import top.hcode.hoj.dao.judge.JudgeEntityService;
import top.hcode.hoj.dao.problem.ProblemEntityService;
import top.hcode.hoj.dao.training.*;
import top.hcode.hoj.pojo.entity.contest.*;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.pojo.entity.problem.Problem;
import top.hcode.hoj.pojo.entity.training.*;
import top.hcode.hoj.shiro.AccountProfile;
import top.hcode.hoj.utils.Constants;
import top.hcode.hoj.validator.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Form reads must preserve all old submission permissions without creating judge/score records. */
class BeforeDispatchPreparationTest {
    @InjectMocks BeforeDispatchInitManager manager;
    @Mock MixedContestManager mixed;
    @Mock ContestEntityService contests;
    @Mock ContestRecordEntityService contestRecords;
    @Mock ContestProblemEntityService contestProblems;
    @Mock JudgeEntityService judges;
    @Mock ProblemEntityService problems;
    @Mock TrainingEntityService trainings;
    @Mock TrainingProblemEntityService trainingProblems;
    @Mock TrainingRecordEntityService trainingRecords;
    @Mock TrainingValidator trainingValidator;
    @Mock ContestValidator contestValidator;
    @Mock TrainingManager trainingManager;
    @Mock GroupValidator groups;
    AccountProfile actor;Problem problem;Subject subject;
    @BeforeEach void setup(){MockitoAnnotations.initMocks(this);when(mixed.configuration(anyLong())).thenReturn(null);actor=new AccountProfile();actor.setUid("alice");actor.setUsername("alice");subject=mock(Subject.class);when(subject.getPrincipal()).thenReturn(actor);ThreadContext.bind(subject);problem=new Problem().setId(7L).setProblemId("CF-4A").setAuth(1).setIsGroup(false);}
    @AfterEach void close() throws Exception {ThreadContext.remove();}
    void noWrites(){verifyNoInteractions(judges,contestRecords,trainingRecords,trainingManager);}
    @Test void publicAndGroupPreparationResolveSourceButDoNotPersist() throws Exception {
        when(problems.getOne(any(),eq(false))).thenReturn(problem);Judge judge=new Judge();manager.prepareCommonSubmission("CF-4A",null,judge);assertEquals(7L,judge.getPid());assertEquals("CF-4A",judge.getDisplayPid());noWrites();
        problem.setIsGroup(true).setGid(9L);assertThrows(StatusForbiddenException.class,()->manager.prepareCommonSubmission("CF-4A",null,new Judge()));assertThrows(StatusForbiddenException.class,()->manager.prepareCommonSubmission("CF-4A",9L,new Judge()));when(groups.isGroupMember("alice",9L)).thenReturn(true);manager.prepareCommonSubmission("CF-4A",9L,judge);assertEquals(9L,judge.getGid());noWrites();
    }
    @Test void contestPreparationStillChecksScheduleAndParticipationAndMissingMapping() throws Exception {
        Contest contest=new Contest().setId(3L).setUid("teacher").setStatus(Constants.Contest.STATUS_SCHEDULED.getCode()).setAllowEndSubmit(false).setIsGroup(false).setOpenAccountLimit(false);
        when(contests.getById(3L)).thenReturn(contest);assertThrows(StatusForbiddenException.class,()->manager.prepareContestSubmission(3L,"A",actor,new Judge()));
        contest.setStatus(Constants.Contest.STATUS_RUNNING.getCode());when(contestProblems.getOne(any(),eq(false))).thenReturn(new ContestProblem().setId(11L).setPid(7L));when(problems.getById(7L)).thenReturn(problem);Judge judge=new Judge();manager.prepareContestSubmission(3L,"A",actor,judge);verify(contestValidator).validateJudgeAuth(contest,"alice");assertEquals(11L,judge.getCpid());assertEquals("CF-4A",judge.getDisplayPid());noWrites();
        when(contestProblems.getOne(any(),eq(false))).thenReturn(null);assertThrows(StatusNotFoundException.class,()->manager.prepareContestSubmission(3L,"A",actor,new Judge()));noWrites();
    }
    @Test void trainingPreparationUsesExistingValidatorAndNeverRecordsProgress() throws Exception {
        Training training=new Training().setId(4L).setStatus(true).setGid(8L);when(trainings.getById(4L)).thenReturn(training);when(trainingProblems.getOne(any())).thenReturn(new TrainingProblem().setId(12L).setPid(7L));when(problems.getById(7L)).thenReturn(problem);Judge judge=new Judge();manager.prepareTrainingSubmission(4L,"A",actor,judge);verify(trainingValidator).validateTrainingAuth(training,actor);assertEquals(7L,judge.getPid());assertEquals(8L,judge.getGid());noWrites();
        doThrow(new StatusAccessDeniedException("private training")).when(trainingValidator).validateTrainingAuth(training,actor);assertThrows(StatusAccessDeniedException.class,()->manager.prepareTrainingSubmission(4L,"A",actor,new Judge()));noWrites();
    }
}
