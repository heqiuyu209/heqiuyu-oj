package top.hcode.hoj.controller.oj;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.external.RemoteCredentialManager;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/learning/accounts/{id}/submission-credentials")
public class RemoteCredentialController {
    @Autowired private RemoteCredentialManager manager;
    @ModelAttribute public void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    public static class Input {
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) public String password;
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) public String cookieHeader;
        public boolean authorized;
        public Long version;
    }
    private CommonResult<Map<String,Object>> execute(Supplier<Map<String,Object>> action){try{return CommonResult.successResponse(action.get());}catch(ObjectiveException e){return CommonResult.errorResponse(e.getMessage(),e.getStatus());}}
    @GetMapping @RequiresAuthentication
    public CommonResult<Map<String,Object>> settings(@PathVariable long id){return execute(()->manager.settings(id));}
    @PutMapping @RequiresAuthentication
    public CommonResult<Map<String,Object>> save(@PathVariable long id,@RequestBody Input input){return execute(()->{
        if(input.password!=null)throw new ObjectiveException(400,"请使用浏览器登录会话授权，不再接收平台密码");
        return manager.saveSession(id,input.cookieHeader,input.version,input.authorized);
    });}
    @DeleteMapping @RequiresAuthentication
    public CommonResult<Map<String,Object>> delete(@PathVariable long id,@RequestParam long version){return execute(()->manager.delete(id,version));}
}
