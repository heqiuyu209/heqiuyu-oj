package top.hcode.hoj.controller.admin;

import org.apache.shiro.authz.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.external.*;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO;
import java.util.Map;
import java.util.function.Supplier;
import javax.servlet.http.HttpServletResponse;

@RestController @RequestMapping("/api/admin/remote-sources/codeforces")
public class RemoteSourceController {
    @Autowired private RemoteCatalogManager catalogue;
    @Autowired private RemoteStatementManager statements;
    @ModelAttribute public void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    private CommonResult<Map<String,Object>> call(Supplier<Map<String,Object>> action){try{return CommonResult.successResponse(action.get());}catch(ObjectiveException e){return CommonResult.errorResponse(e.getMessage(),e.getStatus());}}
    @GetMapping("") @RequiresAuthentication @RequiresRoles(value={"root","admin","problem_admin"},logical=Logical.OR)
    public CommonResult<Map<String,Object>> list(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) String keyword,@RequestParam(required=false) Integer ratingMin,@RequestParam(required=false) Integer ratingMax){return call(()->catalogue.list(page,keyword,ratingMin,ratingMax));}
    @PostMapping("/sync") @RequiresAuthentication @RequiresRoles(value={"root","admin","problem_admin"},logical=Logical.OR)
    public CommonResult<Map<String,Object>> sync(){return call(catalogue::requestSync);}
    @PostMapping("/imports") @RequiresAuthentication @RequiresRoles(value={"root","admin","problem_admin"},logical=Logical.OR)
    public CommonResult<Map<String,Object>> request(@RequestBody RemoteStatementDTO dto){return call(()->statements.request(dto.getProblemKey()));}
    @GetMapping("/statements") @RequiresAuthentication @RequiresRoles(value={"root","admin","problem_admin"},logical=Logical.OR)
    public CommonResult<Map<String,Object>> statement(@RequestParam String problemKey){return call(()->statements.adminView(problemKey));}
    @PostMapping("/statements/en") @RequiresAuthentication @RequiresRoles(value={"root","admin","problem_admin"},logical=Logical.OR)
    public CommonResult<Map<String,Object>> english(@RequestBody RemoteStatementDTO dto){return call(()->statements.save(dto,false));}
    @PostMapping("/statements/zh") @RequiresAuthentication @RequiresRoles(value={"root","admin","problem_admin"},logical=Logical.OR)
    public CommonResult<Map<String,Object>> chinese(@RequestBody RemoteStatementDTO dto){return call(()->statements.save(dto,true));}
    @PostMapping("/refresh") @RequiresAuthentication @RequiresRoles(value={"root","admin","problem_admin"},logical=Logical.OR)
    public CommonResult<Map<String,Object>> refresh(@RequestBody RemoteStatementDTO dto){return call(()->statements.refresh(dto.getProblemKey(),dto.getAutoRefresh()));}
}
