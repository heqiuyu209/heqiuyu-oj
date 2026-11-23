package top.hcode.hoj.controller.oj;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.pojo.dto.PaperDTO;
import top.hcode.hoj.service.oj.PaperService;
import java.util.Map;

@RestController
@RequestMapping("/api/papers")
public class PaperController {
    @Autowired private PaperService service;
    @GetMapping
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> list(@RequestParam(required = false) Integer currentPage, @RequestParam(required = false) String keyword) { return service.list(currentPage, keyword, false); }
    @GetMapping("/runs")
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> histories(@RequestParam(required = false) Integer currentPage) { return service.histories(currentPage); }
    @PostMapping("/runs")
    @RequiresAuthentication
    @RequiresPermissions("submit")
    public CommonResult<Map<String, Object>> start(@RequestBody PaperDTO.Start dto) { return service.start(dto); }
    @GetMapping("/runs/{id}")
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> run(@PathVariable Long id) { return service.run(id); }
    @PutMapping("/runs/{id}/answers")
    @RequiresAuthentication
    @RequiresPermissions("submit")
    public CommonResult<Map<String, Object>> answer(@PathVariable Long id, @RequestBody PaperDTO.Answer dto) { return service.answer(id, dto); }
    @PostMapping("/runs/{id}/program-submissions")
    @RequiresAuthentication
    @RequiresPermissions("submit")
    public CommonResult<Map<String, Object>> submit(@PathVariable Long id, @RequestBody PaperDTO.Answer dto) { return service.submit(id, dto); }
    @PostMapping("/runs/{id}/finish")
    @RequiresAuthentication
    @RequiresPermissions("submit")
    public CommonResult<Map<String, Object>> finish(@PathVariable Long id) { return service.finish(id); }
    @PostMapping("/runs/{id}/retry")
    @RequiresAuthentication
    @RequiresPermissions("submit")
    public CommonResult<Map<String, Object>> retry(@PathVariable Long id) { return service.retry(id); }
}
