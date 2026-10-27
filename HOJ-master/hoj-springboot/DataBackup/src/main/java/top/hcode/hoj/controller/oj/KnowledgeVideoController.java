package top.hcode.hoj.controller.oj;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.controller.KnowledgeVideoResponse;
import top.hcode.hoj.manager.oj.ai.KnowledgeVideoSources;
import top.hcode.hoj.shiro.KnowledgeVideoSession;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge/documents/{id}")
public class KnowledgeVideoController {
    @Autowired private KnowledgeVideoSources sources;
    @Autowired private KnowledgeVideoSession sessions;

    @RequiresAuthentication @RequiresPermissions("submit")
    @PostMapping("/video/access")
    public CommonResult<Map<String,Object>> access(@PathVariable String id, @RequestParam int version, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        try {
            Map<String,Object> result = sessions.grant(request, response, "/api/knowledge/documents/" + id + "/video", sources.userAccess(id, version));
            result.put("url", result.get("url") + "?version=" + version); return CommonResult.successResponse(result);
        } catch (ObjectiveException error) { return CommonResult.errorResponse(error.getMessage(), error.getStatus()); }
    }

    @RequiresAuthentication @RequiresPermissions("submit")
    @DeleteMapping("/video/access")
    public CommonResult<Void> clear(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        try { sessions.clear(request, response, "/api/knowledge/documents/" + id + "/video"); return CommonResult.successResponse(); }
        catch (ObjectiveException error) { return CommonResult.errorResponse(error.getMessage(), error.getStatus()); }
    }

    @RequiresAuthentication @RequiresPermissions("submit")
    @GetMapping("/video")
    public void original(@PathVariable String id, @RequestParam int version, HttpServletRequest request, HttpServletResponse response) throws IOException {
        KnowledgeVideoResponse.send(request, response, () -> sessions.requireAuthenticated(request), writer -> sources.userOriginal(id, version, writer));
    }
}
