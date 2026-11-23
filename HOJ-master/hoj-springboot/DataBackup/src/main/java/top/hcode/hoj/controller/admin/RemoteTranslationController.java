package top.hcode.hoj.controller.admin;

import org.apache.shiro.authz.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.external.RemoteTranslationManager;
import top.hcode.hoj.pojo.dto.RemoteTranslationDTO;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

@RestController @RequestMapping("/api/admin/remote-sources")
public class RemoteTranslationController {
    @Autowired private RemoteTranslationManager translations;
    @PostMapping("/translation") @RequiresAuthentication @RequiresRoles(value={"root","admin","problem_admin"},logical=Logical.OR)
    public CommonResult<Map<String,Object>> generate(@RequestBody RemoteTranslationDTO dto,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");
        try{return CommonResult.successResponse(translations.generate(dto));}catch(ObjectiveException e){return CommonResult.errorResponse(e.getMessage(),e.getStatus());}
    }
}
