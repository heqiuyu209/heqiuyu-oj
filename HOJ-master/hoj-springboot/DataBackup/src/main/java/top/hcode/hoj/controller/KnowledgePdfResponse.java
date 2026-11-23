package top.hcode.hoj.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.ai.KnowledgePdfManager;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;

public final class KnowledgePdfResponse {
    private KnowledgePdfResponse() {}
    public interface Source { void send(KnowledgePdfManager.PdfWriter writer) throws IOException; }
    public static void send(HttpServletResponse response, Source source) throws IOException {
        response.setHeader("Cache-Control", "no-store"); response.setHeader("X-Content-Type-Options", "nosniff");
        try {
            source.send((name, data) -> {
                response.setContentType("application/pdf"); response.setContentLength(data.length);
                response.setHeader("Content-Disposition", "attachment; filename=\"document.pdf\"; filename*=UTF-8''"
                        + URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("*", "%2A"));
                response.getOutputStream().write(data);
            });
        } catch (ObjectiveException e) {
            response.setStatus(e.getStatus()); response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(new ObjectMapper().writeValueAsBytes(CommonResult.errorResponse(e.getMessage(), e.getStatus())));
        }
    }
}
