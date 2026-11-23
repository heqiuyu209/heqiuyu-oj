package top.hcode.hoj.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import top.hcode.hoj.common.exception.ObjectiveException;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeVideoResponseTest {
    @TempDir Path temporary;

    private MockHttpServletResponse send(String range, String ifRange) throws Exception {
        byte[] bytes = "0123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Path file = Files.write(temporary.resolve("media.bin"), bytes);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/knowledge/documents/a/video");
        if (range != null) request.addHeader("Range", range); if (ifRange != null) request.addHeader("If-Range", ifRange);
        MockHttpServletResponse response = new MockHttpServletResponse();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            KnowledgeVideoResponse.send(request, response, () -> {}, writer -> writer.write("讲义.mp4", "video/mp4", "abc", bytes.length, channel, () -> {}));
        }
        return response;
    }

    @Test void completeOpenEndedSuffixAndBoundedRangesReturnExactBytes() throws Exception {
        MockHttpServletResponse full = send(null, null); assertEquals(200, full.getStatus()); assertEquals("0123456789", full.getContentAsString());
        MockHttpServletResponse bounded = send("bytes=2-5", null); assertEquals(206, bounded.getStatus()); assertEquals("2345", bounded.getContentAsString());
        assertEquals("bytes 2-5/10", bounded.getHeader("Content-Range")); assertEquals("4", bounded.getHeader("Content-Length"));
        assertEquals("789", send("bytes=7-", null).getContentAsString()); assertEquals("789", send("bytes=-3", null).getContentAsString());
        assertEquals("0123456789", send("bytes=-99", null).getContentAsString()); assertEquals("89", send("bytes=8-99", null).getContentAsString());
        assertEquals("no-store", full.getHeader("Cache-Control")); assertEquals("same-origin", full.getHeader("Cross-Origin-Resource-Policy"));
        assertTrue(full.getHeader("Content-Disposition").contains("filename*=UTF-8''"));
    }

    @Test void invalidAndUnsatisfiableRangesReturn416WithoutData() throws Exception {
        for (String range : new String[] { "bytes=10-", "bytes=4-2", "bytes=-0", "bytes=999999999999999999999999-", "bytes=x-y" }) {
            MockHttpServletResponse result = send(range, null); assertEquals(416, result.getStatus(), range);
            assertEquals("bytes */10", result.getHeader("Content-Range")); assertEquals(0, result.getContentAsByteArray().length);
        }
    }

    @Test void ifRangeRequiresExactStrongValidatorAndUnsupportedMultipleRangesAreIgnored() throws Exception {
        assertEquals("23", send("bytes=2-3", "\"abc\"").getContentAsString());
        for (String ifRange : new String[] { "W/\"abc\"", "\"old\"", "Wed, 21 Oct 2015 07:28:00 GMT" }) {
            MockHttpServletResponse result = send("bytes=2-3", ifRange); assertEquals(200, result.getStatus()); assertEquals("0123456789", result.getContentAsString());
        }
        assertEquals(200, send("bytes=0-1,3-4", null).getStatus()); assertEquals(200, send("items=0-1", null).getStatus());
    }

    @Test void noBodyCanBeWrittenBeforeSessionAndResourceChecks() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/video"); MockHttpServletResponse response = new MockHttpServletResponse();
        KnowledgeVideoResponse.send(request, response, () -> { throw new ObjectiveException(401, "expired"); }, writer -> fail("must not open media"));
        assertEquals(401, response.getStatus()); assertTrue(response.getContentType().startsWith("application/json"));
        MockHttpServletResponse forbidden = new MockHttpServletResponse();
        KnowledgeVideoResponse.send(request, forbidden, () -> {}, writer -> writer.write("v.mp4", "video/mp4", "abc", 10, null, () -> { throw new ObjectiveException(403, "contest"); }));
        assertEquals(403, forbidden.getStatus()); assertFalse(forbidden.getContentAsString().contains("Content-Range"));
    }

    @Test void longPlaybackRechecksAccessAndDoesNotAppendAnErrorToCommittedVideo() throws Exception {
        Path file = temporary.resolve("long.bin"); byte[] bytes = new byte[2 * 1048576]; Files.write(file, bytes);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/video"); MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger checks = new AtomicInteger();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertThrows(java.io.IOException.class, () -> KnowledgeVideoResponse.send(request, response, () -> {}, writer ->
                writer.write("v.mp4", "video/mp4", "abc", bytes.length, channel, () -> {
                    if (checks.incrementAndGet() >= 3) throw new ObjectiveException(403, "revoked");
                })));
        }
        assertEquals(1048576, response.getContentAsByteArray().length); assertEquals(200, response.getStatus());
        assertEquals("video/mp4", response.getContentType());
    }
}
