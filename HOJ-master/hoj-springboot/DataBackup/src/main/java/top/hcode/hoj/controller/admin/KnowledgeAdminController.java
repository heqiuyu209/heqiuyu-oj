package top.hcode.hoj.controller.admin;

import org.apache.shiro.authz.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.ai.KnowledgeManager;
import top.hcode.hoj.manager.oj.ai.KnowledgePdfManager;
import top.hcode.hoj.manager.oj.ai.KnowledgeVideoSources;
import top.hcode.hoj.controller.KnowledgePdfResponse;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

@RestController @RequestMapping("/api/admin/knowledge")
public class KnowledgeAdminController {
    @Autowired private KnowledgeManager manager;
    @Autowired private KnowledgePdfManager pdf;
    @Autowired private KnowledgeVideoSources videos;
    @ModelAttribute public void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    private CommonResult<Map<String,Object>> call(Supplier<Map<String,Object>> action){try{return CommonResult.successResponse(action.get());}catch(ObjectiveException e){return CommonResult.errorResponse(e.getMessage(),e.getStatus());}}
    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @GetMapping("") public CommonResult<Map<String,Object>> list(@RequestParam(defaultValue="1")int page,@RequestParam(required=false)String keyword){return call(()->manager.list(page,keyword));}
    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @PostMapping("/documents") public CommonResult<Map<String,Object>> create(@RequestBody KnowledgeDTO.Upload dto){return call(()->manager.create(dto));}
    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @GetMapping("/documents/{id}") public CommonResult<Map<String,Object>> detail(@PathVariable String id){return call(()->manager.detail(id));}
    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @GetMapping("/documents/{id}/versions/{version}") public CommonResult<Map<String,Object>> version(@PathVariable String id,@PathVariable int version){return call(()->manager.adminVersion(id,version));}
    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @PostMapping("/documents/{id}/versions") public CommonResult<Map<String,Object>> upload(@PathVariable String id,@RequestBody KnowledgeDTO.Upload dto){return call(()->dto!=null&&"pdf".equals(dto.getFormat())?pdf.reuse(id,dto):dto!=null&&"video".equals(dto.getFormat())?videos.reuse(id,dto):manager.upload(id,dto));}
    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @PostMapping(value="/documents/pdf",consumes="application/pdf")
    public CommonResult<Map<String,Object>> createPdf(@RequestParam String title,@RequestParam String visibility,@RequestParam String requestId,@RequestParam String fileName,@RequestParam(defaultValue="false") boolean ocr,HttpServletRequest request) throws IOException {
        KnowledgeDTO.Upload dto=pdfMetadata(title,visibility,requestId,null);
        dto.setOcr(ocr);
        return call(()->pdf.upload(null,dto,fileName,input(request),request.getContentLengthLong()));
    }
    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @PostMapping(value="/documents/{id}/pdf-versions",consumes="application/pdf")
    public CommonResult<Map<String,Object>> uploadPdf(@PathVariable String id,@RequestParam String title,@RequestParam String visibility,@RequestParam String requestId,@RequestParam String fileName,@RequestParam Long expectedRevision,@RequestParam(defaultValue="false") boolean ocr,HttpServletRequest request) throws IOException {
        KnowledgeDTO.Upload dto=pdfMetadata(title,visibility,requestId,expectedRevision);
        dto.setOcr(ocr);
        return call(()->pdf.upload(id,dto,fileName,input(request),request.getContentLengthLong()));
    }
    private KnowledgeDTO.Upload pdfMetadata(String title,String visibility,String requestId,Long expectedRevision){KnowledgeDTO.Upload dto=new KnowledgeDTO.Upload();dto.setTitle(title);dto.setVisibility(visibility);dto.setRequestId(requestId);dto.setExpectedRevision(expectedRevision);return dto;}
    private java.io.InputStream input(HttpServletRequest request){try{return request.getInputStream();}catch(IOException e){throw new ObjectiveException(400,"PDF 文件读取失败");}}
    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @GetMapping("/documents/{id}/versions/{version}/original")
    public void original(@PathVariable String id,@PathVariable int version,HttpServletResponse response) throws IOException {KnowledgePdfResponse.send(response,writer->pdf.adminOriginal(id,version,writer));}
    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @PostMapping("/documents/{id}/publish") public CommonResult<Map<String,Object>> publish(@PathVariable String id,@RequestBody KnowledgeDTO.Change dto){return call(()->manager.publish(id,dto));}
    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @PostMapping("/documents/{id}/unpublish") public CommonResult<Map<String,Object>> unpublish(@PathVariable String id,@RequestBody KnowledgeDTO.Change dto){return call(()->manager.unpublish(id,dto));}
    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @PutMapping("/documents/{id}/visibility") public CommonResult<Map<String,Object>> visibility(@PathVariable String id,@RequestBody KnowledgeDTO.Change dto){return call(()->manager.changeVisibility(id,dto));}
    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @PostMapping("/documents/{id}/delete") public CommonResult<Map<String,Object>> delete(@PathVariable String id,@RequestBody KnowledgeDTO.Change dto){return call(()->manager.remove(id,dto));}
    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @PostMapping("/documents/{id}/versions/{version}/retry") public CommonResult<Map<String,Object>> retry(@PathVariable String id,@PathVariable int version,@RequestBody KnowledgeDTO.Change dto){return call(()->manager.retry(id,version,dto));}
}
