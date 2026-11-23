package top.hcode.hoj.controller.oj;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.LearningTrainingPlanManager;
import top.hcode.hoj.pojo.dto.TrainingPlanDTO;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/learning/training-plans")
public class LearningTrainingPlanController {
    @Autowired private LearningTrainingPlanManager manager;
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    private CommonResult<Map<String,Object>> execute(Supplier<Map<String,Object>> action) {
        try { return CommonResult.successResponse(action.get()); }
        catch (ObjectiveException e) { return CommonResult.errorResponse(e.getMessage(), e.getStatus()); }
    }
    @GetMapping @RequiresAuthentication @RequiresPermissions("submit")
    public CommonResult<Map<String,Object>> list(@RequestParam(defaultValue="ACTIVE") String state, @RequestParam(defaultValue="1") Integer page) { return execute(() -> manager.list(state, page)); }
    @PostMapping @RequiresAuthentication @RequiresPermissions("submit")
    public CommonResult<Map<String,Object>> create(@RequestBody TrainingPlanDTO.Create dto) { return execute(() -> manager.create(dto)); }
    @GetMapping("/{id}") @RequiresAuthentication @RequiresPermissions("submit")
    public CommonResult<Map<String,Object>> detail(@PathVariable String id) { return execute(() -> manager.detail(id)); }
    @PutMapping("/{id}") @RequiresAuthentication @RequiresPermissions("submit")
    public CommonResult<Map<String,Object>> update(@PathVariable String id, @RequestBody TrainingPlanDTO.Update dto) { return execute(() -> manager.update(id, dto)); }
}
