package top.hcode.hoj.controller.oj;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.annotation.AnonApi;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.external.CodeforcesPracticeRecords;
import top.hcode.hoj.manager.oj.external.CodeforcesSyncManager;
import top.hcode.hoj.manager.oj.external.ExternalAccountManager;
import top.hcode.hoj.pojo.dto.CodeforcesSyncDTO;
import top.hcode.hoj.remote.RemoteProblemRef;
import java.util.Map;
import java.util.function.Supplier;
import javax.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/learning")
public class ExternalAccountController {
    @Autowired private ExternalAccountManager manager;
    @Autowired private CodeforcesPracticeRecords codeforcesPractices;
    @Autowired private CodeforcesSyncManager codeforcesSync;
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    private CommonResult<Map<String,Object>> execute(Supplier<Map<String,Object>> action) {
        try {return CommonResult.successResponse(action.get());}catch(ObjectiveException e){return CommonResult.errorResponse(e.getMessage(),e.getStatus());}
    }
    private Map<String,Object> withCodeforcesPractice(String problemId, Supplier<Map<String,Object>> snapshot) {
        if (problemId == null) return snapshot.get();
        RemoteProblemRef problem = codeforcesPractices.requireProblem(problemId);
        return codeforcesPractices.withLatest(snapshot.get(), problem);
    }
    @GetMapping("/accounts") @RequiresAuthentication
    public CommonResult<Map<String,Object>> mine(@RequestParam(value="cfProblem", required=false) String problemId) {
        return execute(() -> withCodeforcesPractice(problemId, manager::mine));
    }
    @PostMapping("/accounts") @RequiresAuthentication
    public CommonResult<Map<String,Object>> link(@RequestBody Map<String,String> dto) {return execute(() -> manager.link(dto.get("platform"),dto.get("handle")));}
    @DeleteMapping("/accounts/{id}") @RequiresAuthentication
    public CommonResult<Map<String,Object>> unlink(@PathVariable long id) {return execute(() -> manager.unlink(id));}
    @PostMapping("/accounts/{id}/proof") @RequiresAuthentication
    public CommonResult<Map<String,Object>> proof(@PathVariable long id) {return execute(() -> manager.proof(id));}
    @PostMapping("/accounts/{id}/sync") @RequiresAuthentication
    public CommonResult<Map<String,Object>> sync(@PathVariable long id, @RequestParam(value="cfProblem", required=false) String problemId) {
        return execute(() -> withCodeforcesPractice(problemId, () -> manager.requestSync(id,false)));
    }
    @GetMapping("/codeforces-sync") @RequiresAuthentication
    public CommonResult<Map<String,Object>> codeforcesSyncState(@RequestParam(value="cfProblem", required=false) String problemId) {
        return execute(() -> codeforcesSync.withState(manager.mine(), problemId));
    }
    @PostMapping("/accounts/{id}/codeforces-sync") @RequiresAuthentication
    public CommonResult<Map<String,Object>> requestCodeforcesSync(@PathVariable long id, @RequestBody CodeforcesSyncDTO dto) {
        return execute(() -> codeforcesSync.request(id, dto));
    }
    @PostMapping("/accounts/{id}/verify") @RequiresAuthentication
    public CommonResult<Map<String,Object>> verify(@PathVariable long id) {return execute(() -> manager.requestSync(id,true));}
    @GetMapping("/users/{uid}/external") @AnonApi
    public CommonResult<Map<String,Object>> publicView(@PathVariable String uid) {return execute(() -> manager.publicView(uid));}
}
