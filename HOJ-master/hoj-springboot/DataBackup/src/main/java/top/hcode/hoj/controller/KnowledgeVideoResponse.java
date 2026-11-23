package top.hcode.hoj.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.manager.oj.ai.KnowledgeVideoSources;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Single byte-range streaming with bounded memory; no public file or access token URL. */
public final class KnowledgeVideoResponse {
    private KnowledgeVideoResponse() {}
    public interface Source { void send(KnowledgeVideoSources.Writer writer) throws IOException; }

    private static long[] range(String value, long length) {
        if (value == null || !value.startsWith("bytes=")) return null;
        // Multiple ranges may be ignored (RFC 9110 section 14); browser media uses one.
        if (value.length() <= 512 && value.indexOf(',') >= 0) return null;
        if (value.length() > 128 || !value.matches("bytes=([0-9]+-[0-9]*|-[0-9]+)")) throw new IllegalArgumentException();
        String[] parts = value.substring(6).split("-", -1);
        long start, end;
        if (parts[0].isEmpty()) {
            long suffix = Long.parseLong(parts[1]); if (suffix < 1) throw new IllegalArgumentException();
            start = Math.max(0, length - suffix); end = length - 1;
        } else {
            start = Long.parseLong(parts[0]); end = parts[1].isEmpty() ? length - 1 : Math.min(length - 1, Long.parseLong(parts[1]));
        }
        if (start < 0 || start >= length || end < start) throw new IllegalArgumentException();
        return new long[] { start, end };
    }

    private static void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        response.setHeader("X-Accel-Buffering", "no");
    }

    public static void send(HttpServletRequest request, HttpServletResponse response, Runnable session, Source source) throws IOException {
        headers(response);
        try {
            if (!"GET".equals(request.getMethod())) throw new ObjectiveException(405, "请使用视频播放请求");
            session.run();
            source.send((name, mime, hash, length, channel, resource) -> {
                session.run(); resource.run();
                String etag = "\"" + hash + "\"";
                String rawRange = request.getHeader("Range"), ifRange = request.getHeader("If-Range");
                long[] selected;
                try { selected = ifRange != null && !ifRange.equals(etag) ? null : range(rawRange, length); }
                catch (IllegalArgumentException error) {
                    response.setStatus(416); response.setHeader("Content-Range", "bytes */" + length); response.setContentLengthLong(0); return;
                }
                long start = selected == null ? 0 : selected[0], end = selected == null ? length - 1 : selected[1];
                response.setStatus(selected == null ? 200 : 206);
                response.setHeader("Accept-Ranges", "bytes"); response.setHeader("ETag", etag); response.setContentType(mime);
                response.setHeader("Content-Disposition", "inline; filename=\"video" + ("video/webm".equals(mime) ? ".webm" : ".mp4")
                        + "\"; filename*=UTF-8''" + URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("*", "%2A"));
                response.setContentLengthLong(end - start + 1);
                if (selected != null) response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
                stream(channel, response, start, end, () -> { session.run(); resource.run(); });
            });
        } catch (ObjectiveException error) {
            if (response.isCommitted()) throw new IOException("Video access ended");
            response.reset(); headers(response); response.setStatus(error.getStatus()); response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(new ObjectMapper().writeValueAsBytes(CommonResult.errorResponse(error.getMessage(), error.getStatus())));
        }
    }

    private static void stream(FileChannel channel, HttpServletResponse response, long start, long end, Runnable recheck) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(65536); long position = start, checkedAt = System.nanoTime(), checkedPosition = start;
        recheck.run();
        while (position <= end) {
            if (position - checkedPosition >= 1048576 || System.nanoTime() - checkedAt >= 1000000000L) {
                recheck.run(); checkedAt = System.nanoTime(); checkedPosition = position;
            }
            buffer.clear(); buffer.limit((int) Math.min(buffer.capacity(), end - position + 1));
            int read = channel.read(buffer, position); if (read < 1) throw new IOException("Video source read failed");
            response.getOutputStream().write(buffer.array(), 0, read); position += read;
        }
    }
}
