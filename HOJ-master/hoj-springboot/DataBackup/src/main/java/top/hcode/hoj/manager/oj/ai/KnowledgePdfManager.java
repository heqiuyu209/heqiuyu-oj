package top.hcode.hoj.manager.oj.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;
import java.io.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Binary sources never have public paths; document/version ACL remains authoritative. */
@Component
public class KnowledgePdfManager {
    @Autowired private KnowledgeManager knowledge;
    @Autowired private RagServiceClient parser;
    @Autowired private JdbcTemplate jdbc;
    private final Semaphore uploads = new Semaphore(1), downloads = new Semaphore(1);

    public interface PdfWriter { void write(String name, byte[] data) throws IOException; }

    private void sourceName(String name) {
        if (name == null || name.trim().isEmpty() || name.codePointCount(0, name.length()) > 200
                || !name.toLowerCase(Locale.ROOT).endsWith(".pdf") || name.matches("(?s).*[\\p{Cntrl}/\\\\].*"))
            throw new ObjectiveException(400, "PDF 文件名无效，最多 200 字且不能包含路径或控制字符");
    }
    private byte[] bytes(InputStream input, long length) {
        if (length > KnowledgeLimits.PDF_BYTES) throw new ObjectiveException(413, "PDF 文件最多 32 MiB");
        if (input == null) throw new ObjectiveException(400, "请选择 PDF 文件");
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (output.size() + count > KnowledgeLimits.PDF_BYTES) throw new ObjectiveException(413, "PDF 文件最多 32 MiB");
                output.write(buffer, 0, count);
            }
            byte[] data = output.toByteArray();
            if (data.length < 5 || data[0] != '%' || data[1] != 'P' || data[2] != 'D' || data[3] != 'F' || data[4] != '-')
                throw new ObjectiveException(400, "请选择有效 PDF 文件");
            return data;
        } catch (IOException e) { throw new ObjectiveException(400, "PDF 文件读取失败，请重新上传"); }
    }
    private KnowledgeDTO.PdfText unparsed() {
        KnowledgeDTO.PdfText value = new KnowledgeDTO.PdfText(); value.setPages(1); value.setContent(""); value.setEmptyPages(Collections.emptyList()); return value;
    }
    public Map<String,Object> upload(String id, KnowledgeDTO.Upload metadata, String name, InputStream input, long length) {
        String actor = knowledge.admin(); knowledge.validateMetadata(metadata); sourceName(name);
        if (!uploads.tryAcquire()) throw new ObjectiveException(429, "PDF 正在处理，请稍后再上传");
        try {
            Map<String,Object> detail = id == null ? null : knowledge.detail(id);
            if (detail != null && !"ACTIVE".equals(detail.get("state"))) throw new ObjectiveException(404, "资料不存在或已撤回");
            byte[] data = bytes(input, length);
            KnowledgePdfUpload identity = new KnowledgePdfUpload(metadata, data, name, unparsed());
            String hash = knowledge.fingerprint(identity);
            List<Map<String,Object>> duplicate = id == null
                    ? jdbc.queryForList("SELECT id,creation_hash AS input_hash,1 AS version FROM knowledge_document WHERE created_by=? AND request_id=?", actor, metadata.getRequestId())
                    : jdbc.queryForList("SELECT document_id AS id,input_hash,version FROM knowledge_command WHERE document_id=? AND uid=? AND request_id=?", id, actor, metadata.getRequestId());
            if (!duplicate.isEmpty()) {
                if (!hash.equals(duplicate.get(0).get("input_hash"))) throw new ObjectiveException(409, "请求标识已用于其他资料内容");
                Map<String,Object> result = knowledge.detail((String)duplicate.get(0).get("id"));
                if (id != null) result.put("requestedVersion", duplicate.get(0).get("version")); return result;
            }
            if (detail != null && (metadata.getExpectedRevision() == null
                    || metadata.getExpectedRevision().longValue() != ((Number)detail.get("revision")).longValue()))
                throw new ObjectiveException(409, "资料已更新，请刷新后重试");
            KnowledgePdfUpload upload = new KnowledgePdfUpload(metadata, data, name, metadata.isOcr() ? parser.extractPdfOcr(data) : parser.extractPdf(data));
            return id == null ? knowledge.create(upload) : knowledge.upload(id, upload);
        } finally { uploads.release(); }
    }

    /** Metadata edits reuse the source; explicit PDF corrections preserve its physical pages. */
    public Map<String,Object> reuse(String id, KnowledgeDTO.Upload metadata) {
        knowledge.admin(); knowledge.validateMetadata(metadata);
        if (metadata.getSourceVersion() == null || metadata.getSourceVersion() < 1) throw new ObjectiveException(400, "请选择 PDF 来源版本或上传新文件");
        Map<String,Object> detail = knowledge.detail(id);
        if (!"ACTIVE".equals(detail.get("state"))) throw new ObjectiveException(404, "资料不存在或已撤回");
        Map<String,Object> source = load(id, metadata.getSourceVersion());
        String mode = (String)source.get("extraction_mode");
        boolean ocr = "OCR".equals(mode) || "OCR_REVIEWED".equals(mode);
        boolean correction = metadata.isReviewedPdf() || metadata.isReviewedOcr();
        if (metadata.isReviewedOcr() && !ocr) throw new ObjectiveException(400, "原有文字 PDF 校订请使用 PDF 校订选项");
        String targetMode = correction ? (ocr ? "OCR_REVIEWED" : "NATIVE_REVIEWED") : mode;
        KnowledgeDTO.PdfText parsed = new KnowledgeDTO.PdfText();
        parsed.setContent((String)source.get("content")); parsed.setPages(((Number)source.get("pages")).intValue());
        parsed.setEmptyPages(emptyPages((String)source.get("empty_pages"), parsed.getPages()));
        if (correction) {
            String text = metadata.getContent();
            if (text == null) throw new ObjectiveException(400, "请填写校订文字");
            text = text.replace("\r\n", "\n").replace('\r', '\n');
            if (text.trim().isEmpty() || text.codePointCount(0,text.length()) > KnowledgeLimits.PDF_CHARACTERS || text.indexOf('\0') >= 0
                    || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(text)) throw new ObjectiveException(400, "校订文字最多 30 万字，不能为空或包含无效字符");
            String[] pages = text.split("\n\f\n", -1); List<Integer> empty = new ArrayList<>();
            if (pages.length != parsed.getPages()) throw new ObjectiveException(400, "校订必须保留原 PDF 的物理页数");
            for (int i=0; i<pages.length; i++) {
                if (pages[i].indexOf('\f') >= 0) throw new ObjectiveException(400, "单页校订文字不能包含换页符");
                if (pages[i].trim().isEmpty()) empty.add(i+1);
            }
            parsed.setContent(text); parsed.setEmptyPages(empty);
        }
        return knowledge.upload(id, new KnowledgePdfUpload(metadata, (byte[])source.get("source_data"), (String)source.get("source_name"), parsed, targetMode));
    }
    static List<Integer> emptyPages(String value, int pages) {
        try {
            if (pages < 1 || pages > 200 || value == null || value.length() > 1024) throw new IllegalArgumentException();
            List<Integer> result = new ArrayList<>(); int previous = 0;
            if (!value.isEmpty()) for (String item : value.split(",", -1)) {
                if (!item.matches("[1-9][0-9]{0,2}")) throw new IllegalArgumentException();
                int page = Integer.parseInt(item); if (page <= previous || page > pages) throw new IllegalArgumentException();
                result.add(page); previous = page;
            }
            if (result.size() == pages) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException e) { throw new ObjectiveException(503, "PDF 原件信息校验失败，请联系管理员"); }
    }
    private Map<String,Object> load(String id, int version) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT s.*,v.content,v.format FROM knowledge_source s JOIN knowledge_version v ON v.document_id=s.document_id AND v.version=s.version WHERE s.document_id=? AND s.version=?", id, version);
        if (rows.isEmpty() || !"pdf".equals(rows.get(0).get("format"))) throw new ObjectiveException(404, "PDF 原件不存在或已撤回");
        Map<String,Object> row = rows.get(0); Object bytes = row.get("source_data");
        if (!Arrays.asList("NATIVE","NATIVE_REVIEWED","OCR","OCR_REVIEWED").contains(row.get("extraction_mode"))) throw new ObjectiveException(503, "PDF 提取方式无效，请联系管理员");
        if (!(bytes instanceof byte[]) || ((byte[])bytes).length < 5 || ((byte[])bytes).length > KnowledgeLimits.PDF_BYTES
                || ((byte[])bytes).length != ((Number)row.get("byte_length")).intValue()
                || !RagServiceClient.hash((byte[])bytes).equals(row.get("source_hash"))) throw new ObjectiveException(503, "PDF 原件校验失败，请联系管理员");
        sourceName((String)row.get("source_name"));
        String content = (String)row.get("content"); int count = ((Number)row.get("pages")).intValue();
        List<Integer> declared = emptyPages((String)row.get("empty_pages"), count), actual = new ArrayList<>();
        if (content == null || content.codePointCount(0, content.length()) > KnowledgeLimits.PDF_CHARACTERS || content.indexOf('\0') >= 0 || content.indexOf('\r') >= 0)
            throw new ObjectiveException(503, "PDF 正文校验失败，请联系管理员");
        String[] pages = content.split("\n\f\n", -1);
        if (pages.length != count) throw new ObjectiveException(503, "PDF 页码校验失败，请联系管理员");
        for (int i=0; i<pages.length; i++) {
            if (pages[i].indexOf('\f') >= 0) throw new ObjectiveException(503, "PDF 页码校验失败，请联系管理员");
            if (pages[i].trim().isEmpty()) actual.add(i+1);
        }
        if (!actual.equals(declared)) throw new ObjectiveException(503, "PDF 页码校验失败，请联系管理员");
        return row;
    }
    public void userOriginal(String id, Integer version, PdfWriter writer) throws IOException {
        if (!downloads.tryAcquire()) throw new ObjectiveException(429, "原件读取繁忙，请稍后再试");
        try {
            Map<String,Object> visible = knowledge.read(id, version);
            int current = ((Number)visible.get("version")).intValue();
            if (!"pdf".equals(visible.get("format"))) throw new ObjectiveException(404, "该版本没有 PDF 原件");
            Map<String,Object> row = load(id, current);
            knowledge.read(id, current); // Recheck current publication/role/contest after reading the binary.
            writer.write((String)row.get("source_name"), (byte[])row.get("source_data"));
        } finally { downloads.release(); }
    }
    public void adminOriginal(String id, int version, PdfWriter writer) throws IOException {
        knowledge.admin();
        if (!downloads.tryAcquire()) throw new ObjectiveException(429, "原件读取繁忙，请稍后再试");
        try {
            if (!"ACTIVE".equals(knowledge.detail(id).get("state"))) throw new ObjectiveException(404, "资料不存在或已撤回");
            Map<String,Object> row = load(id, version);
            knowledge.admin(); if (!"ACTIVE".equals(knowledge.detail(id).get("state"))) throw new ObjectiveException(404, "资料不存在或已撤回");
            writer.write((String)row.get("source_name"), (byte[])row.get("source_data"));
        } finally { downloads.release(); }
    }
}
