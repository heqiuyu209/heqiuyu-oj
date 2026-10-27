package top.hcode.hoj.controller.oj;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.annotation.AnonApi;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.external.ExternalAccountManager;
import java.util.Map;
import java.util.function.Supplier;
import javax.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/learning")
public class ExternalAccountController {
    @Autowired private ExternalAccountManager manager;
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    private CommonResult<Map<String,Object>> execute(Supplier<Map<String,Object>> action) {
        try {return CommonResult.successResponse(action.get());}catch(ObjectiveException e){return CommonResult.errorResponse(e.getMessage(),e.getStatus());}
    }
    @GetMapping("/accounts") @RequiresAuthentication
    public CommonResult<Map<String,Object>> mine() {return execute(manager::mine);}
    @PostMapping("/accounts") @RequiresAuthentication
    public CommonResult<Map<String,Object>> link(@RequestBody Map<String,String> dto) {return execute(() -> manager.link(dto.get("platform"),dto.get("handle")));}
    @DeleteMapping("/accounts/{id}") @RequiresAuthentication
    public CommonResult<Map<String,Object>> unlink(@PathVariable long id) {return execute(() -> manager.unlink(id));}
    @PostMapping("/accounts/{id}/proof") @RequiresAuthentication
    public CommonResult<Map<String,Object>> proof(@PathVariable long id) {return execute(() -> manager.proof(id));}
    @PostMapping("/accounts/{id}/sync") @RequiresAuthentication
    public CommonResult<Map<String,Object>> sync(@PathVariable long id) {return execute(() -> manager.requestSync(id,false));}
    @PostMapping("/accounts/{id}/verify") @RequiresAuthentication
    public CommonResult<Map<String,Object>> verify(@PathVariable long id) {return execute(() -> manager.requestSync(id,true));}
    @GetMapping("/users/{uid}/external") @AnonApi
    public CommonResult<Map<String,Object>> publicView(@PathVariable String uid) {return execute(() -> manager.publicView(uid));}
}
