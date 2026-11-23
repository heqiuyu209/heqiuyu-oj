package top.hcode.hoj.controller.oj;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.*;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/contests/{cid}/mixed")
public class MixedContestController {
    @Autowired private PaperManager papers;
    @Autowired private MixedContestRankManager ranks;
    private CommonResult<Map<String, Object>> execute(Supplier<Map<String, Object>> action) {
        try { return CommonResult.successResponse(action.get()); }
        catch (ObjectiveException e) { return CommonResult.errorResponse(e.getMessage(), e.getStatus()); }
    }
    @PostMapping("/run")
    @RequiresAuthentication
    @RequiresPermissions("submit")
    public CommonResult<Map<String, Object>> start(@PathVariable Long cid) { return execute(() -> papers.startContest(cid)); }
    @GetMapping("/rank")
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> rank(@PathVariable Long cid) { return execute(() -> ranks.rank(cid)); }
}
