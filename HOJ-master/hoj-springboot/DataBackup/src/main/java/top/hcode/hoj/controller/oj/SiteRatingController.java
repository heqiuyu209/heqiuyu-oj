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
    public CommonResult<Map<String, Object>> rank(@RequestParam(defaultValue = "contest") String type,
            @RequestParam(required = false) Integer currentPage, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String searchUser) {
        if (!"contest".equals(type)) return CommonResult.errorResponse("平时 Rating 已取消，仅支持比赛 Rating");
        return CommonResult.successResponse(ratings.rank(type, currentPage, limit, searchUser));
    }
    @GetMapping("/users/{uid}")
    public CommonResult<Map<String, Object>> profile(@PathVariable String uid, @RequestParam(required = false) String type) {
        if (type != null && !"contest".equals(type)) return CommonResult.errorResponse("平时 Rating 已取消，仅支持比赛 Rating");
        return CommonResult.successResponse(ratings.profile(uid, type));
    }
}
