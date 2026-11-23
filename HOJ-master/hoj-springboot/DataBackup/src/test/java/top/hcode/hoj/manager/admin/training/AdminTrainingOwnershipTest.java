package top.hcode.hoj.manager.admin.training;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.StatusFailException;
import top.hcode.hoj.common.exception.StatusForbiddenException;
import top.hcode.hoj.dao.training.MappingTrainingCategoryEntityService;
import top.hcode.hoj.dao.training.TrainingCategoryEntityService;
import top.hcode.hoj.dao.training.TrainingEntityService;
import top.hcode.hoj.dao.training.TrainingRegisterEntityService;
import top.hcode.hoj.pojo.dto.TrainingDTO;
import top.hcode.hoj.pojo.entity.training.MappingTrainingCategory;
import top.hcode.hoj.pojo.entity.training.Training;
import top.hcode.hoj.pojo.entity.training.TrainingCategory;
import top.hcode.hoj.shiro.AccountProfile;
import top.hcode.hoj.validator.TrainingValidator;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminTrainingOwnershipTest {
    private AdminTrainingManager manager;
    private TrainingEntityService trainings;
    private MappingTrainingCategoryEntityService mappings;
    private TrainingCategoryEntityService categories;
    private TrainingRegisterEntityService registrations;
    private AdminTrainingRecordManager records;
    private Subject subject;
    private Training stored;

    @BeforeEach
    void setup() {
        manager = new AdminTrainingManager();
        trainings = mock(TrainingEntityService.class);
        mappings = mock(MappingTrainingCategoryEntityService.class);
        categories = mock(TrainingCategoryEntityService.class);
        registrations = mock(TrainingRegisterEntityService.class);
        records = mock(AdminTrainingRecordManager.class);
        ReflectionTestUtils.setField(manager, "trainingEntityService", trainings);
        ReflectionTestUtils.setField(manager, "mappingTrainingCategoryEntityService", mappings);
        ReflectionTestUtils.setField(manager, "trainingCategoryEntityService", categories);
        ReflectionTestUtils.setField(manager, "trainingRegisterEntityService", registrations);
        ReflectionTestUtils.setField(manager, "adminTrainingRecordManager", records);
        ReflectionTestUtils.setField(manager, "trainingValidator", mock(TrainingValidator.class));
        subject = mock(Subject.class);
        AccountProfile actor = new AccountProfile();
        actor.setUid("alice-id");
        actor.setUsername("alice");
        when(subject.getPrincipal()).thenReturn(actor);
        ThreadContext.bind(subject);
        stored = new Training().setId(7L).setAuthor("bob").setAuth("Private").setPrivatePwd("before");
        when(trainings.getById(7L)).thenReturn(stored);
        when(trainings.updateById(any(Training.class))).thenReturn(true);
        when(mappings.getOne(any(Wrapper.class), eq(false)))
                .thenReturn(new MappingTrainingCategory().setTid(7L).setCid(3L));
    }

    @AfterEach
    void cleanup() {
        ThreadContext.remove();
    }

    private TrainingDTO edit(Long id, String author) {
        TrainingDTO dto = new TrainingDTO();
        dto.setTraining(new Training().setId(id).setTitle("Updated").setAuthor(author)
                .setAuth("Private").setPrivatePwd("after"));
        dto.setTrainingCategory(new TrainingCategory().setId(3L));
        return dto;
    }

    private void noWrites() {
        verify(trainings, never()).updateById(any(Training.class));
        verify(trainings, never()).saveOrUpdate(any(Training.class));
        verifyNoInteractions(mappings, categories, registrations, records);
    }

    @Test
    void spoofedBodyAuthorCannotEditAnotherOwnersTraining() {
        assertThrows(StatusForbiddenException.class, () -> manager.updateTraining(edit(7L, "alice")));
        noWrites();
    }

    @Test
    void spoofedQueryAuthorCannotChangeAnotherOwnersStatus() {
        assertThrows(StatusForbiddenException.class, () -> manager.changeTrainingStatus(7L, "alice", true));
        noWrites();
    }

    @Test
    void existingOwnerCanEditWithoutRepeatingTheAuthorInTheBody() throws Exception {
        stored.setAuthor("alice");
        TrainingDTO dto = edit(7L, null);
        manager.updateTraining(dto);
        verify(trainings).updateById(dto.getTraining());
        verify(registrations).remove(any(Wrapper.class));
    }

    @Test
    void unchangedPrivatePasswordPreservesRegistrations() throws Exception {
        stored.setAuthor("alice");
        TrainingDTO dto = edit(7L, "alice");
        dto.getTraining().setPrivatePwd("before");
        manager.updateTraining(dto);
        verifyNoInteractions(registrations);
    }

    @Test
    void existingOwnerCanChangeStatusRegardlessOfQueryAuthor() throws Exception {
        stored.setAuthor("alice");
        manager.changeTrainingStatus(7L, "bob", true);
        verify(trainings).updateById(new Training().setId(7L).setStatus(true));
        verify(trainings, never()).saveOrUpdate(any(Training.class));
    }

    @Test
    void rootCanEditAnotherOwnersTraining() throws Exception {
        when(subject.hasRole("root")).thenReturn(true);
        TrainingDTO dto = edit(7L, "bob");
        manager.updateTraining(dto);
        verify(trainings).updateById(dto.getTraining());
    }

    @Test
    void rootCanChangeAnotherOwnersStatus() throws Exception {
        when(subject.hasRole("root")).thenReturn(true);
        manager.changeTrainingStatus(7L, "irrelevant", false);
        verify(trainings).updateById(new Training().setId(7L).setStatus(false));
    }

    @Test
    void missingTrainingIsRejectedBeforeAnyEditSideEffects() {
        assertThrows(StatusFailException.class, () -> manager.updateTraining(edit(99L, "alice")));
        noWrites();
    }

    @Test
    void missingTrainingStatusCannotUpsertAnIncompleteTraining() {
        when(subject.hasRole("root")).thenReturn(true);
        assertThrows(StatusFailException.class, () -> manager.changeTrainingStatus(99L, "alice", true));
        noWrites();
    }

    @Test
    void missingTrainingIdIsRejectedBeforeQueryOrMutation() {
        assertThrows(StatusFailException.class, () -> manager.updateTraining(edit(null, "alice")));
        verifyNoInteractions(trainings, mappings, categories, registrations, records);
    }

    @Test
    void failedTrainingUpdateDoesNotContinueToCategoryOrRegistrationWrites() {
        stored.setAuthor("alice");
        when(trainings.updateById(any(Training.class))).thenReturn(false);
        assertThrows(StatusFailException.class, () -> manager.updateTraining(edit(7L, "alice")));
        verifyNoInteractions(mappings, categories, registrations, records);
    }
}
