package top.hcode.hoj.controller.oj;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.VirtualContestManager;
import top.hcode.hoj.pojo.dto.VirtualContestDTO;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/contests/{cid}/virtual-run")
public class VirtualContestController {
    @Autowired private VirtualContestManager manager;
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    private CommonResult<Map<String, Object>> execute(Supplier<Map<String, Object>> action) {
        try { return CommonResult.successResponse(action.get()); }
        catch (ObjectiveException error) { return CommonResult.errorResponse(error.getMessage(), error.getStatus()); }
    }
    @GetMapping @RequiresAuthentication @RequiresPermissions("submit")
    public CommonResult<Map<String, Object>> current(@PathVariable Long cid) { return execute(() -> manager.current(cid)); }
    @PostMapping @RequiresAuthentication @RequiresPermissions("submit")
    public CommonResult<Map<String, Object>> start(@PathVariable Long cid, @RequestBody VirtualContestDTO request) { return execute(() -> manager.start(cid, request)); }
}
