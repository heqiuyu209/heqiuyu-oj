package top.hcode.hoj.controller.oj;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.annotation.AnonApi;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.external.RemoteStatementManager;
import java.util.Map;

@RestController
public class RemoteStatementController {
    @Autowired private RemoteStatementManager statements;
    @AnonApi @GetMapping("/api/problems/{pid}/statements")
    public CommonResult<Map<String,Object>> view(@PathVariable long pid){try{return CommonResult.successResponse(statements.publicView(pid));}catch(ObjectiveException e){return CommonResult.errorResponse(e.getMessage(),e.getStatus());}}
}
