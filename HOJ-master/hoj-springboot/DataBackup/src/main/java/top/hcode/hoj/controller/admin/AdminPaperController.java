package top.hcode.hoj.controller.admin;

import org.apache.shiro.authz.annotation.RequiresRoles;
import org.apache.shiro.authz.annotation.Logical;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.pojo.dto.PaperDTO;
import top.hcode.hoj.service.oj.PaperService;
import java.util.*;

@RestController
@RequestMapping("/api/admin/papers")
public class AdminPaperController {
    @Autowired private PaperService service;
    @GetMapping
    @RequiresRoles(value = {"root", "admin", "problem_admin"}, logical = Logical.OR)
    public CommonResult<Map<String, Object>> list(@RequestParam(required = false) Integer currentPage, @RequestParam(required = false) String keyword) { return service.list(currentPage, keyword, true); }
    @GetMapping("/program-questions")
    @RequiresRoles(value = {"root", "admin", "problem_admin"}, logical = Logical.OR)
    public CommonResult<List<Map<String, Object>>> programs(@RequestParam(required = false) String keyword) { return service.programs(keyword); }
    @GetMapping("/{id}")
    @RequiresRoles(value = {"root", "admin", "problem_admin"}, logical = Logical.OR)
    public CommonResult<PaperDTO.Paper> detail(@PathVariable Long id) { return service.detail(id); }
    @PostMapping
    @RequiresRoles(value = {"root", "admin", "problem_admin"}, logical = Logical.OR)
    public CommonResult<PaperDTO.Paper> save(@RequestBody PaperDTO.Paper dto) { return service.save(dto); }
}
