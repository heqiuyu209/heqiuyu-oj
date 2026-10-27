package top.hcode.hoj.controller.oj;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.annotation.AnonApi;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.AlgorithmCatalog;
import top.hcode.hoj.manager.oj.LearningProfileManager;
import top.hcode.hoj.manager.oj.LearningEvidenceManager;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/learning")
@AnonApi
public class LearningProfileController {
    @Autowired private AlgorithmCatalog catalog;
    @Autowired private LearningProfileManager profiles;
    @Autowired private LearningEvidenceManager evidence;
    @GetMapping("/topics")
    public CommonResult<Map<String, Object>> topics() { return CommonResult.successResponse(catalog.view()); }
    @GetMapping("/users/{uid}/profile")
    public CommonResult<Map<String, Object>> profile(@PathVariable String uid) {
        try { return CommonResult.successResponse(profiles.profile(uid)); }
        catch (ObjectiveException e) { return CommonResult.errorResponse(e.getMessage(), e.getStatus()); }
    }
    @GetMapping("/users/{uid}/evidence")
    public CommonResult<Map<String, Object>> evidence(@PathVariable String uid, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        try { return CommonResult.successResponse(evidence.profile(uid)); }
        catch (ObjectiveException e) { return CommonResult.errorResponse(e.getMessage(), e.getStatus()); }
    }
}
