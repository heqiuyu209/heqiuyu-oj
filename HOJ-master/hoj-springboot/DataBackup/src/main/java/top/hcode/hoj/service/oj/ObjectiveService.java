package top.hcode.hoj.service.oj;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.ObjectiveManager;
import top.hcode.hoj.pojo.dto.ObjectiveDTO;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class ObjectiveService {
    @Autowired private ObjectiveManager manager;

    private <T> CommonResult<T> execute(Supplier<T> action) {
        try {
            return CommonResult.successResponse(action.get());
        } catch (ObjectiveException e) {
            return CommonResult.errorResponse(e.getMessage(), e.getStatus());
        }
    }

    public CommonResult<Map<String, Object>> questions(Integer page, Integer limit, String keyword, String type) {
        return execute(() -> manager.questions(page, limit, keyword, type));
    }
    public CommonResult<Map<String, Object>> practiceQuestions(Integer page, Integer limit, String keyword, String type) {
        return execute(() -> manager.practiceQuestions(page, limit, keyword, type));
    }
    public CommonResult<ObjectiveDTO.Question> question(Long id) { return execute(() -> manager.question(id)); }
    public CommonResult<Map<String, Object>> neighbors(Long id, String keyword, String type) {
        return execute(() -> manager.neighbors(id, keyword, type));
    }
    public CommonResult<ObjectiveDTO.Question> saveQuestion(ObjectiveDTO.Question dto) {
        return execute(() -> manager.saveQuestion(dto));
    }
    public CommonResult<Map<String, Object>> sets(Integer page, Integer limit, String keyword, String mode, boolean admin) {
        return execute(() -> manager.sets(page, limit, keyword, mode, admin));
    }
    public CommonResult<Map<String, Object>> set(Long id, boolean admin) { return execute(() -> manager.set(id, admin)); }
    public CommonResult<Map<String, Object>> saveSet(ObjectiveDTO.ProblemSet dto) {
        return execute(() -> manager.saveSet(dto));
    }
    public CommonResult<Map<String, Object>> start(ObjectiveDTO.StartRun dto) { return execute(() -> manager.start(dto)); }
    public CommonResult<Map<String, Object>> run(Long id) { return execute(() -> manager.run(id)); }
    public CommonResult<Map<String, Object>> answer(Long id, ObjectiveDTO.Answer dto) {
        return execute(() -> manager.answer(id, dto));
    }
    public CommonResult<Map<String, Object>> finish(Long id) { return execute(() -> manager.finish(id)); }
    public CommonResult<Map<String, Object>> runs(Integer page, Integer limit) {
        return execute(() -> manager.runs(page, limit));
    }
}
