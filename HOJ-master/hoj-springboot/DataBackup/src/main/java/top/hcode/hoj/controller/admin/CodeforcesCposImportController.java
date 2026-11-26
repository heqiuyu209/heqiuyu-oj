package top.hcode.hoj.controller.admin;

import org.apache.shiro.authz.annotation.Logical;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresRoles;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.external.cpos.CodeforcesCposImportManager;
import top.hcode.hoj.manager.oj.external.cpos.CodeforcesCposImportManager.Capture;

@RestController
public class CodeforcesCposImportController {
    private final CodeforcesCposImportManager importer;

    public CodeforcesCposImportController(CodeforcesCposImportManager importer) {
        this.importer = importer;
    }

    @PostMapping("/api/admin/remote-sources/codeforces/cpos/import")
    @RequiresAuthentication
    @RequiresRoles(value = {"root", "admin", "problem_admin"}, logical = Logical.OR)
    public CommonResult<Boolean> importStatement(@RequestBody Capture capture) {
        try {
            return CommonResult.successResponse(importer.importStatement(capture));
        } catch (ObjectiveException e) {
            return CommonResult.errorResponse(e.getMessage(), e.getStatus());
        }
    }
}
