package top.hcode.hoj.controller.admin;

import org.apache.shiro.authz.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.pojo.dto.ObjectiveDTO;
import top.hcode.hoj.service.oj.ObjectiveService;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/objective")
public class AdminObjectiveController {
    @Autowired private ObjectiveService service;

    // The installed Shiro advisor only matches method-level annotations.
    @GetMapping("/questions")
    @RequiresAuthentication
    @RequiresRoles(value = {"root", "admin", "problem_admin"}, logical = Logical.OR)
    public CommonResult<Map<String, Object>> questions(@RequestParam(required = false) Integer currentPage,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String questionType) {
        return service.questions(currentPage, limit, keyword, questionType);
    }

    @GetMapping("/questions/{id}")
    @RequiresAuthentication
    @RequiresRoles(value = {"root", "admin", "problem_admin"}, logical = Logical.OR)
    public CommonResult<ObjectiveDTO.Question> question(@PathVariable Long id) { return service.question(id); }

    @RequestMapping(value = "/questions", method = {RequestMethod.POST, RequestMethod.PUT})
    @RequiresAuthentication
    @RequiresRoles(value = {"root", "admin", "problem_admin"}, logical = Logical.OR)
    public CommonResult<ObjectiveDTO.Question> saveQuestion(@RequestBody ObjectiveDTO.Question dto) {
        return service.saveQuestion(dto);
    }

    @GetMapping("/sets")
    @RequiresAuthentication
    @RequiresRoles(value = {"root", "admin", "problem_admin"}, logical = Logical.OR)
    public CommonResult<Map<String, Object>> sets(@RequestParam(required = false) Integer currentPage,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String mode) {
        return service.sets(currentPage, limit, keyword, mode, true);
    }

    @GetMapping("/sets/{id}")
    @RequiresAuthentication
    @RequiresRoles(value = {"root", "admin", "problem_admin"}, logical = Logical.OR)
    public CommonResult<Map<String, Object>> set(@PathVariable Long id) { return service.set(id, true); }

    @RequestMapping(value = "/sets", method = {RequestMethod.POST, RequestMethod.PUT})
    @RequiresAuthentication
    @RequiresRoles(value = {"root", "admin", "problem_admin"}, logical = Logical.OR)
    public CommonResult<Map<String, Object>> saveSet(@RequestBody ObjectiveDTO.ProblemSet dto) {
        return service.saveSet(dto);
    }
}
