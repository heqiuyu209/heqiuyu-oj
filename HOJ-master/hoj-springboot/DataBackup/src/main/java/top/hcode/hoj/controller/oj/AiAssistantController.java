package top.hcode.hoj.controller.oj;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.ai.AiAssistantManager;
import top.hcode.hoj.pojo.dto.AiDTO;
import java.util.Map;
import java.util.function.Supplier;
import javax.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/ai")
public class AiAssistantController {
    @Autowired private AiAssistantManager manager;
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    private CommonResult<Map<String, Object>> execute(Supplier<Map<String, Object>> action) {
        try { return CommonResult.successResponse(action.get()); }
        catch (ObjectiveException e) { return CommonResult.errorResponse(e.getMessage(), e.getStatus()); }
    }
    @GetMapping("/settings") @RequiresAuthentication
    public CommonResult<Map<String, Object>> settings() { return execute(manager::settings); }
    @PutMapping("/settings") @RequiresAuthentication
    public CommonResult<Map<String, Object>> save(@RequestBody AiDTO.Settings dto) { return execute(() -> manager.save(dto)); }
    @DeleteMapping("/settings") @RequiresAuthentication
    public CommonResult<Map<String, Object>> remove() { return execute(manager::remove); }
    @PostMapping("/chat") @RequiresAuthentication @RequiresPermissions("submit")
    public CommonResult<Map<String, Object>> chat(@RequestBody AiDTO.Chat dto) { return execute(() -> manager.chat(dto)); }
    @PostMapping("/problem-context") @RequiresAuthentication @RequiresPermissions("submit")
    public CommonResult<Map<String,Object>> problemContext(@RequestBody AiDTO.ProblemReference dto) { return execute(() -> manager.problemContext(dto)); }
    @GetMapping("/recommendations") @RequiresAuthentication @RequiresPermissions("submit")
    public CommonResult<Map<String, Object>> recommendations(@ModelAttribute AiDTO.RecommendationFilter filters) { return execute(() -> manager.recommendations(filters)); }
    @PostMapping("/recommendations/explain") @RequiresAuthentication @RequiresPermissions("submit")
    public CommonResult<Map<String, Object>> explain(@RequestBody AiDTO.RecommendationExplanation dto) { return execute(() -> manager.explain(dto)); }
}
