package top.hcode.hoj.service.oj;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.PaperManager;
import top.hcode.hoj.pojo.dto.PaperDTO;
import java.util.*;
import java.util.function.Supplier;

@Service
public class PaperService {
    @Autowired private PaperManager manager;
    private <T> CommonResult<T> execute(Supplier<T> action) {
        try { return CommonResult.successResponse(action.get()); }
        catch (ObjectiveException e) { return CommonResult.errorResponse(e.getMessage(), e.getStatus()); }
    }
    public CommonResult<Map<String, Object>> list(Integer page, String keyword, boolean admin) { return execute(() -> manager.list(page, keyword, admin)); }
    public CommonResult<PaperDTO.Paper> detail(Long id) { return execute(() -> manager.detail(id)); }
    public CommonResult<PaperDTO.Paper> save(PaperDTO.Paper dto) { return execute(() -> manager.save(dto)); }
    public CommonResult<List<Map<String, Object>>> programs(String keyword) { return execute(() -> manager.programChoices(keyword)); }
    public CommonResult<Map<String, Object>> start(PaperDTO.Start dto) { return execute(() -> manager.start(dto)); }
    public CommonResult<Map<String, Object>> run(Long id) { return execute(() -> manager.run(id)); }
    public CommonResult<Map<String, Object>> histories(Integer page) { return execute(() -> manager.histories(page)); }
    public CommonResult<Map<String, Object>> answer(Long id, PaperDTO.Answer dto) { return execute(() -> manager.answer(id, dto)); }
    public CommonResult<Map<String, Object>> submit(Long id, PaperDTO.Answer dto) { return execute(() -> manager.submit(id, dto)); }
    public CommonResult<Map<String, Object>> finish(Long id) { return execute(() -> manager.finish(id)); }
    public CommonResult<Map<String, Object>> retry(Long id) { return execute(() -> manager.retry(id)); }
}
