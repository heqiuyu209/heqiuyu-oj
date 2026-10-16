package top.hcode.hoj.controller.oj;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.pojo.dto.ObjectiveDTO;
import top.hcode.hoj.service.oj.ObjectiveService;
import java.util.Map;

@RestController
@RequestMapping("/api/objective")
public class ObjectiveController {
    @Autowired private ObjectiveService service;

    @GetMapping("/questions")
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> questions(@RequestParam(required = false) Integer currentPage,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String questionType) {
        return service.practiceQuestions(currentPage, limit, keyword, questionType);
    }

    @GetMapping("/questions/{id}/neighbors")
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> neighbors(@PathVariable Long id,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String questionType) {
        return service.neighbors(id, keyword, questionType);
    }

    // The installed Shiro advisor only matches method-level annotations.
    @GetMapping("/sets")
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> sets(@RequestParam(required = false) Integer currentPage,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String mode) {
        return service.sets(currentPage, limit, keyword, mode, false);
    }

    @GetMapping("/sets/{id}")
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> set(@PathVariable Long id) { return service.set(id, false); }

    @PostMapping("/runs")
    @RequiresAuthentication
    @RequiresPermissions("submit")
    public CommonResult<Map<String, Object>> start(@RequestBody ObjectiveDTO.StartRun dto) {
        return service.start(dto);
    }

    @GetMapping("/runs")
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> runs(@RequestParam(required = false) Integer currentPage,
            @RequestParam(required = false) Integer limit) {
        return service.runs(currentPage, limit);
    }

    @GetMapping("/runs/{id}")
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> run(@PathVariable Long id) { return service.run(id); }

    @PutMapping("/runs/{id}/answers")
    @RequiresAuthentication
    @RequiresPermissions("submit")
    public CommonResult<Map<String, Object>> answer(@PathVariable Long id, @RequestBody ObjectiveDTO.Answer dto) {
        return service.answer(id, dto);
    }

    @PostMapping("/runs/{id}/finish")
    @RequiresAuthentication
    @RequiresPermissions("submit")
    public CommonResult<Map<String, Object>> finish(@PathVariable Long id) { return service.finish(id); }
}
