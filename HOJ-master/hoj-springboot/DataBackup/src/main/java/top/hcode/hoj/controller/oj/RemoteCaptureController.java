package top.hcode.hoj.controller.oj;

import org.apache.shiro.authz.annotation.Logical;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresRoles;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.annotation.AnonApi;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.external.RemoteCaptureManager;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api")
public class RemoteCaptureController {
    private static final String KEY = "X-HOJ-Capture-Key";
    private static final String LEASE = "X-HOJ-Capture-Lease";
    private static final String CREDENTIAL = "/remote-captures/credential";
    private static final String ADMIN = "/admin/remote-captures";
    private final RemoteCaptureManager captures;

    public RemoteCaptureController(RemoteCaptureManager captures) {
        this.captures = captures;
    }

    @GetMapping(CREDENTIAL)
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> credentialStatus(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return call(captures::credentialStatus);
    }

    @PostMapping(CREDENTIAL)
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> issueCredential(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return call(captures::issueCredential);
    }

    @DeleteMapping(CREDENTIAL)
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> revokeCredential() {
        return call(captures::revokeCredential);
    }

    @GetMapping("/remote-captures/connection")
    @AnonApi
    public CommonResult<Map<String, Object>> connection(@RequestHeader(value = KEY, required = false) String key,
                                                          HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return call(() -> captures.connection(key));
    }

    @PostMapping("/remote-captures/reserve")
    @AnonApi
    public CommonResult<Map<String, Object>> reserve(@RequestHeader(value = KEY, required = false) String key,
                                                       @RequestParam String platform, @RequestParam String id,
                                                       @RequestParam String url, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return call(() -> captures.reserve(key, platform, id, url));
    }

    @PostMapping(value = "/remote-captures/upload", consumes = MediaType.TEXT_PLAIN_VALUE)
    @AnonApi
    public CommonResult<Map<String, Object>> upload(@RequestHeader(value = KEY, required = false) String key,
                                                      @RequestHeader(value = LEASE, required = false) String lease,
                                                      @RequestParam String platform, @RequestParam String id,
                                                      @RequestParam String url, HttpServletRequest request) {
        return call(() -> captures.upload(key, lease, platform, id, url, request));
    }

    @GetMapping(ADMIN)
    @RequiresAuthentication
    @RequiresRoles(value = {"root", "admin", "problem_admin"}, logical = Logical.OR)
    public CommonResult<Map<String, Object>> queue() {
        return call(captures::queue);
    }

    @PutMapping(ADMIN + "/policy")
    @RequiresAuthentication
    @RequiresRoles("root")
    public CommonResult<Map<String, Object>> policy(@RequestParam boolean enabled) {
        return call(() -> captures.setEnabled(enabled));
    }

    @GetMapping(ADMIN + "/statement")
    @RequiresAuthentication
    @RequiresRoles(value = {"root", "admin", "problem_admin"}, logical = Logical.OR)
    public CommonResult<Content> preview(@RequestParam String problemKey) {
        return call(() -> captures.preview(problemKey));
    }

    @DeleteMapping(ADMIN + "/statement")
    @RequiresAuthentication
    @RequiresRoles(value = {"root", "admin", "problem_admin"}, logical = Logical.OR)
    public CommonResult<Map<String, Object>> discard(@RequestParam String problemKey) {
        return call(() -> captures.discard(problemKey));
    }

    @PostMapping(ADMIN + "/import")
    @RequiresAuthentication
    @RequiresRoles(value = {"root", "admin", "problem_admin"}, logical = Logical.OR)
    public CommonResult<Map<String, Object>> approve(@RequestBody List<String> problemKeys) {
        return call(() -> captures.approveBatch(problemKeys));
    }

    private static <T> CommonResult<T> call(Supplier<T> action) {
        try {
            return CommonResult.successResponse(action.get());
        } catch (ObjectiveException error) {
            return CommonResult.errorResponse(error.getMessage(), error.getStatus());
        }
    }
}
