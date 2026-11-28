package top.hcode.hoj.controller.oj;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.annotation.AnonApi;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.external.CodeforcesBrowserSubmitManager;
import top.hcode.hoj.manager.oj.external.CodeforcesBrowserSubmitManager.BrowserEvent;
import top.hcode.hoj.manager.oj.external.CodeforcesBrowserSubmitManager.NewSubmission;

import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/codeforces-browser-submit")
public class CodeforcesBrowserSubmitController {
    private static final String TASK = "X-HOJ-Browser-Task";
    private final CodeforcesBrowserSubmitManager submissions;

    public CodeforcesBrowserSubmitController(CodeforcesBrowserSubmitManager submissions) {
        this.submissions = submissions;
    }

    @PostMapping
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> create(@RequestBody NewSubmission submission, HttpServletResponse response) {
        return call(response, () -> submissions.create(submission));
    }

    @GetMapping("/latest")
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> latest(@RequestParam String problem, HttpServletResponse response) {
        return call(response, () -> submissions.latest(problem));
    }

    @GetMapping("/{id}")
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> status(@PathVariable long id, HttpServletResponse response) {
        return call(response, () -> submissions.status(id));
    }

    @PostMapping("/{id}/claim")
    @AnonApi
    public CommonResult<Map<String, Object>> claim(@PathVariable long id,
                                                    @RequestHeader(value = TASK, required = false) String credential,
                                                    HttpServletResponse response) {
        return call(response, () -> submissions.claim(id, credential));
    }

    @PutMapping("/{id}/event")
    @AnonApi
    public CommonResult<Map<String, Object>> event(@PathVariable long id,
                                                    @RequestHeader(value = TASK, required = false) String credential,
                                                    @RequestBody BrowserEvent event, HttpServletResponse response) {
        return call(response, () -> submissions.browserEvent(id, credential, event));
    }

    private static <T> CommonResult<T> call(HttpServletResponse response, Supplier<T> action) {
        response.setHeader("Cache-Control", "no-store");
        try {
            return CommonResult.successResponse(action.get());
        } catch (ObjectiveException error) {
            return CommonResult.errorResponse(error.getMessage(), error.getStatus());
        }
    }
}
