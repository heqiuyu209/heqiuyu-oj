package top.hcode.hoj.controller.oj;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.external.PersonalRemoteSubmissionManager;
import top.hcode.hoj.pojo.dto.PersonalRemoteSubmissionDTO;
import top.hcode.hoj.remote.PersonalRemoteTaskStore;
import top.hcode.hoj.utils.IpUtils;
import javax.servlet.http.*;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/personal-remote")
public class PersonalRemoteSubmissionController {
    @Autowired private PersonalRemoteSubmissionManager manager;
    @ModelAttribute public void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    private CommonResult<Map<String,Object>> execute(Supplier<Map<String,Object>> action) {
        try{return CommonResult.successResponse(action.get());}
        catch(ObjectiveException e){return CommonResult.errorResponse(e.getMessage(),e.getStatus());}
        catch(PersonalRemoteTaskStore.Conflict e){return CommonResult.errorResponse("远程提交不可访问或状态已变化，请刷新后重试",409);}
        catch(Exception e){return CommonResult.errorResponse("远程提交暂时不可用；若已点击提交，请先核对请求结果",503);}
    }
    @RequiresAuthentication
    @PostMapping("/accounts") public CommonResult<Map<String,Object>> accounts(@RequestBody PersonalRemoteSubmissionDTO input){return execute(()->manager.accounts(input));}
    @RequiresAuthentication
    @PostMapping("/options") public CommonResult<Map<String,Object>> options(@RequestBody PersonalRemoteSubmissionDTO input){return execute(()->manager.options(input));}
    @RequiresAuthentication
    @PostMapping("/submissions") public CommonResult<Map<String,Object>> submit(@RequestBody PersonalRemoteSubmissionDTO input,HttpServletRequest request){return execute(()->manager.submit(input,IpUtils.getUserIpAddr(request)));}
    @RequiresAuthentication
    @GetMapping("/submissions/{id}") public CommonResult<Map<String,Object>> status(@PathVariable long id){return execute(()->manager.status(id));}
    @RequiresAuthentication
    @GetMapping("/requests/{requestId}") public CommonResult<Map<String,Object>> request(@PathVariable String requestId){return execute(()->manager.request(requestId));}
    @RequiresAuthentication
    @PostMapping("/submissions/{id}/reconcile") public CommonResult<Map<String,Object>> reconcile(@PathVariable long id){return execute(()->manager.reconcile(id));}
    @RequiresAuthentication
    @PostMapping("/submissions/{id}/cancel") public CommonResult<Map<String,Object>> cancel(@PathVariable long id){return execute(()->manager.cancel(id));}
}
