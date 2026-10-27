package top.hcode.hoj.controller.admin;

import org.apache.shiro.authz.annotation.Logical;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresRoles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.controller.KnowledgeVideoResponse;
import top.hcode.hoj.manager.oj.ai.KnowledgeVideoImports;
import top.hcode.hoj.manager.oj.ai.KnowledgeVideoSources;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;
import top.hcode.hoj.pojo.dto.KnowledgeVideoDTO;
import top.hcode.hoj.shiro.KnowledgeVideoSession;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/admin/knowledge")
public class KnowledgeVideoAdminController {
    @Autowired private KnowledgeVideoImports imports;
    @Autowired private KnowledgeVideoSources sources;
    @Autowired private KnowledgeVideoSession sessions;
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    private CommonResult<Map<String,Object>> call(Supplier<Map<String,Object>> action) {
        try { return CommonResult.successResponse(action.get()); }
        catch (ObjectiveException error) { return CommonResult.errorResponse(error.getMessage(), error.getStatus()); }
    }

    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @GetMapping("/video-imports")
    public CommonResult<Map<String,Object>> list() { return call(imports::list); }

    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @PostMapping(value="/video-imports",consumes={"video/mp4","video/webm","application/octet-stream"})
    public CommonResult<Map<String,Object>> upload(@RequestParam String title, @RequestParam String visibility,
            @RequestParam String requestId, @RequestParam String fileName, @RequestParam(defaultValue="auto") String language,
            @RequestParam(required=false) String targetDocumentId, @RequestParam(required=false) Long expectedRevision,
            HttpServletRequest request) {
        return call(() -> {
            KnowledgeDTO.Upload metadata = new KnowledgeDTO.Upload(); metadata.setTitle(title); metadata.setVisibility(visibility);
            metadata.setRequestId(requestId); metadata.setExpectedRevision(expectedRevision);
            try { return imports.upload(targetDocumentId, metadata, fileName, language, request.getInputStream(), request.getContentLengthLong()); }
            catch (IOException error) { throw new ObjectiveException(400, "视频读取失败，请重新上传"); }
        });
    }

    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @GetMapping("/video-imports/{id}")
    public CommonResult<Map<String,Object>> detail(@PathVariable String id) { return call(() -> imports.detail(id)); }

    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @PostMapping("/video-imports/{id}/cancel")
    public CommonResult<Map<String,Object>> cancel(@PathVariable String id, @RequestBody KnowledgeVideoDTO.Change change) { return call(() -> imports.cancel(id, change)); }

    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @PostMapping("/video-imports/{id}/retry")
    public CommonResult<Map<String,Object>> retry(@PathVariable String id, @RequestBody KnowledgeVideoDTO.Change change) { return call(() -> imports.retry(id, change)); }

    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @PostMapping("/video-imports/{id}/save")
    public CommonResult<Map<String,Object>> save(@PathVariable String id, @RequestBody KnowledgeVideoDTO.Save metadata) { return call(() -> imports.save(id, metadata)); }

    private String draftPath(String id) { return "/api/admin/knowledge/video-imports/" + id + "/video"; }
    private String versionPath(String id, int version) { return "/api/admin/knowledge/documents/" + id + "/versions/" + version + "/video"; }

    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @PostMapping("/video-imports/{id}/video/access")
    public CommonResult<Map<String,Object>> previewAccess(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        return call(() -> sessions.grant(request, response, draftPath(id), sources.importAccess(id)));
    }

    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @DeleteMapping("/video-imports/{id}/video/access")
    public CommonResult<Map<String,Object>> clearPreview(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        return call(() -> { sessions.clear(request, response, draftPath(id)); return java.util.Collections.emptyMap(); });
    }

    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @GetMapping("/video-imports/{id}/video")
    public void preview(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        KnowledgeVideoResponse.send(request, response, () -> sessions.requireAuthenticated(request), writer -> sources.importOriginal(id, writer));
    }

    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @PostMapping("/documents/{id}/versions/{version}/video/access")
    public CommonResult<Map<String,Object>> originalAccess(@PathVariable String id, @PathVariable int version, HttpServletRequest request, HttpServletResponse response) {
        return call(() -> sessions.grant(request, response, versionPath(id, version), sources.adminAccess(id, version)));
    }

    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @DeleteMapping("/documents/{id}/versions/{version}/video/access")
    public CommonResult<Map<String,Object>> clearOriginal(@PathVariable String id, @PathVariable int version, HttpServletRequest request, HttpServletResponse response) {
        return call(() -> { sessions.clear(request, response, versionPath(id, version)); return java.util.Collections.emptyMap(); });
    }

    @RequiresAuthentication @RequiresRoles(value={"root","admin"},logical=Logical.OR)
    @GetMapping("/documents/{id}/versions/{version}/video")
    public void original(@PathVariable String id, @PathVariable int version, HttpServletRequest request, HttpServletResponse response) throws IOException {
        KnowledgeVideoResponse.send(request, response, () -> sessions.requireAuthenticated(request), writer -> sources.adminOriginal(id, version, writer));
    }
}
