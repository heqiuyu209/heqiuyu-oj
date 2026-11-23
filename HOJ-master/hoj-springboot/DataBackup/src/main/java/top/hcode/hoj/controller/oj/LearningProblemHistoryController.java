package top.hcode.hoj.controller.oj;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.LearningProblemHistoryManager;
import top.hcode.hoj.pojo.dto.LearningHistoryDTO;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

@RestController
@RequestMapping("/api/learning/problem-history")
public class LearningProblemHistoryController {
    @Autowired private LearningProblemHistoryManager manager;
    @GetMapping @RequiresAuthentication @RequiresPermissions("submit")
    public CommonResult<Map<String,Object>> list(@ModelAttribute LearningHistoryDTO.Query query,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        try{return CommonResult.successResponse(manager.list(query));}catch(ObjectiveException e){return CommonResult.errorResponse(e.getMessage(),e.getStatus());}
    }
}
