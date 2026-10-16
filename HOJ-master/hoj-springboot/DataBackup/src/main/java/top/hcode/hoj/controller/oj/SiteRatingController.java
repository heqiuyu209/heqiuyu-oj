package top.hcode.hoj.controller.oj;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.annotation.AnonApi;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.SiteRatingManager;
import java.util.Map;

@RestController
@RequestMapping("/api/rating")
@AnonApi
public class SiteRatingController {
    @Autowired private SiteRatingManager ratings;
    @GetMapping("/rank")
    public CommonResult<Map<String, Object>> rank(@RequestParam(defaultValue = "practice") String type,
            @RequestParam(required = false) Integer currentPage, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String searchUser) {
        if (!"practice".equals(type) && !"contest".equals(type)) return CommonResult.errorResponse("未知排名类型");
        return CommonResult.successResponse(ratings.rank(type, currentPage, limit, searchUser));
    }
    @GetMapping("/users/{uid}")
    public CommonResult<Map<String, Object>> profile(@PathVariable String uid) { return CommonResult.successResponse(ratings.profile(uid)); }
}
