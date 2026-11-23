package top.hcode.hoj.manager.oj;

import org.junit.jupiter.api.*;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.TrainingPlanDTO;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearningTrainingPlanManagerTest {
    LearningTrainingPlanFixture f;
    @BeforeEach void setup() throws Exception { f = new LearningTrainingPlanFixture("jdbc:h2:mem:plans_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""); }
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(Map<String,Object> row) { return (List<Map<String,Object>>)row.get("items"); }
    void status(int status, org.junit.jupiter.api.function.Executable action) { assertEquals(status,assertThrows(ObjectiveException.class,action).getStatus()); }
    @Test void savesOnlyVerifiedSelectionAndRetriesAfterTheRecommendationHasChanged() {
        TrainingPlanDTO.Create dto=f.create("same_request"); Map<String,Object> first=f.manager.create(dto);
        when(f.recommendations.verify(anyString(),any())).thenThrow(new ObjectiveException(409,"刷新推荐"));
        assertEquals(first.get("id"),f.manager.create(dto).get("id")); assertEquals(1,f.count()); verify(f.recommendations,times(1)).verify(eq("alice"),any());
        assertEquals(Arrays.asList("PROGRAMMING:1","OBJECTIVE:2"),Arrays.asList(items(first).get(0).get("id"),items(first).get(1).get("id")));
        dto.setName("另一个名字"); status(409,()->f.manager.create(dto)); assertEquals(1,f.count());
    }
    @Test void progressUsesCanonicalRecordedEvidenceAndNeverAcceptsClientProgress() {
        Map<String,Object> row=f.manager.create(f.create("progress_request")); f.attempted.add("objective:2"); f.solved.add("cf:contest:4:A");
        row=f.manager.detail((String)row.get("id")); assertEquals("SOLVED",items(row).get(0).get("status")); assertEquals("ATTEMPTED",items(row).get(1).get("status")); assertEquals(1L,row.get("solved"));
        f.solved.add("objective:2"); assertEquals(2L,f.manager.detail((String)row.get("id")).get("solved"));
    }
    @Test void withdrawnQuestionHasNeitherSavedMetadataNorLinkAndCurrentTitleIsUsed() {
        Map<String,Object> row=f.manager.create(f.create("visibility_request")); String id=(String)row.get("id");
        f.visible.remove("OBJECTIVE:2"); f.visible.get("PROGRAMMING:1").title="Current title"; f.solved.add("objective:2");
        row=f.manager.detail(id); assertEquals("Current title",items(row).get(0).get("title")); assertEquals(new HashSet<>(Arrays.asList("id","available","status")),items(row).get(1).keySet()); assertEquals(0L,row.get("solved"));
    }
    @Test void onlyTheOwnerCanReadOrChangeAndInactiveOwnersCannotWrite() {
        Map<String,Object> row=f.manager.create(f.create("own_request")); String id=(String)row.get("id"); f.actor.set("bob");
        assertTrue(items(f.manager.list("ACTIVE",1)).isEmpty()); status(404,()->f.manager.detail(id)); status(404,()->f.manager.update(id,f.update(row,"DELETED")));
        f.actor.set("inactive"); status(403,()->f.manager.create(f.create("inactive_request"))); assertEquals(1,f.count());
    }
    @Test void revisionArchiveRenameAndDeletionDoNotResurrectOldSaveRequests() {
        TrainingPlanDTO.Create create=f.create("delete_request"); Map<String,Object> row=f.manager.create(create); String id=(String)row.get("id");
        TrainingPlanDTO.Update update=f.update(row,"ARCHIVED"); update.setName("归档后的名称"); row=f.manager.update(id,update);
        assertEquals("归档后的名称",row.get("name")); assertEquals(2L,((Number)row.get("revision")).longValue()); status(409,()->f.manager.update(id,update));
        assertTrue(items(f.manager.list("ACTIVE",1)).isEmpty()); assertEquals(1,items(f.manager.list("ARCHIVED",1)).size());
        f.manager.update(id,f.update(row,"DELETED")); assertEquals("DELETED",f.manager.create(create).get("state")); status(404,()->f.manager.detail(id));
        assertEquals("{}",f.jdbc.queryForObject("SELECT snapshot_json FROM learning_training_plan",String.class)); assertEquals("",f.jdbc.queryForObject("SELECT name FROM learning_training_plan",String.class));
    }
    @Test void activeQuotaAppliesToSavingAndRestoringAndEmptyListsNeedNoEvidenceScan() {
        assertTrue(items(f.manager.list("ACTIVE",1)).isEmpty()); verifyNoInteractions(f.candidates);
        List<Map<String,Object>> rows=new ArrayList<>(); for(int i=0;i<20;i++)rows.add(f.manager.create(f.create("quota_request_"+i)));
        status(409,()->f.manager.create(f.create("over_quota_request"))); assertEquals(20,f.count());
        Map<String,Object> archived=f.manager.update((String)rows.get(0).get("id"),f.update(rows.get(0),"ARCHIVED"));
        f.manager.create(f.create("new_quota_request")); status(409,()->f.manager.update((String)archived.get("id"),f.update(archived,"ACTIVE")));
    }
    @Test void invalidInputAndChangedRecommendationsDoNotWriteRows() {
        status(400,()->f.manager.create(null)); status(400,()->f.manager.list("DELETED",1)); status(400,()->f.manager.list("ACTIVE",0));
        TrainingPlanDTO.Create dto=f.create("invalid_request"); dto.getSelection().setCandidateIds(Collections.nCopies(7,"PROGRAMMING:1")); status(400,()->f.manager.create(dto));
        TrainingPlanDTO.Create blank=f.create("invalid_request"); blank.setName(" "); status(400,()->f.manager.create(blank));
        when(f.recommendations.verify(anyString(),any())).thenThrow(new ObjectiveException(409,"刷新推荐")); status(409,()->f.manager.create(f.create("stale_request"))); assertEquals(0,f.count());
    }
    @Test void competitionGuardBlocksEveryReadAndWriteBeforeAnySnapshotIsReturned() {
        Map<String,Object> row=f.manager.create(f.create("contest_request")); String id=(String)row.get("id");
        doThrow(new ObjectiveException(403,"正在参赛")).when(f.access).requireChat("alice");
        status(403,()->f.manager.list("ACTIVE",1)); status(403,()->f.manager.detail(id)); status(403,()->f.manager.create(f.create("blocked_request"))); status(403,()->f.manager.update(id,f.update(row,"DELETED"))); assertEquals(1,f.count());
    }
}
