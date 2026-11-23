package top.hcode.hoj.controller.oj;

import org.apache.shiro.authz.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.ai.KnowledgeManager;
import top.hcode.hoj.manager.oj.ai.AiAssistantManager;
import top.hcode.hoj.manager.oj.ai.KnowledgePdfManager;
import top.hcode.hoj.controller.KnowledgePdfResponse;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

@RestController @RequestMapping("/api/knowledge")
public class KnowledgeController {
    @Autowired private KnowledgeManager manager;
    @Autowired private AiAssistantManager assistant;
    @Autowired private KnowledgePdfManager pdf;
    @ModelAttribute public void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    private CommonResult<Map<String,Object>> call(Supplier<Map<String,Object>> action){try{return CommonResult.successResponse(action.get());}catch(ObjectiveException e){return CommonResult.errorResponse(e.getMessage(),e.getStatus());}}
    @RequiresAuthentication @RequiresPermissions("submit")
    @GetMapping("/documents") public CommonResult<Map<String,Object>> directory(@RequestParam(defaultValue="1")int page,@RequestParam(required=false)String keyword){return call(()->manager.directory(page,keyword));}
    @RequiresAuthentication @RequiresPermissions("submit")
    @PostMapping("/search") public CommonResult<Map<String,Object>> search(@RequestBody KnowledgeDTO.Search dto){return call(()->manager.search(dto));}
    @RequiresAuthentication @RequiresPermissions("submit")
    @PostMapping("/answer") public CommonResult<Map<String,Object>> answer(@RequestBody KnowledgeDTO.Question dto){return call(()->assistant.answerKnowledge(dto));}
    @RequiresAuthentication @RequiresPermissions("submit")
    @GetMapping("/documents/{id}") public CommonResult<Map<String,Object>> read(@PathVariable String id,@RequestParam(required=false)Integer version){return call(()->manager.read(id,version));}
    @RequiresAuthentication @RequiresPermissions("submit")
    @GetMapping("/documents/{id}/original") public void original(@PathVariable String id,@RequestParam(required=false)Integer version,HttpServletResponse response) throws IOException {KnowledgePdfResponse.send(response,writer->pdf.userOriginal(id,version,writer));}
}
