package top.hcode.hoj.manager.oj.ai;

import top.hcode.hoj.pojo.dto.KnowledgeDTO;
import java.util.List;

/** Server-created only; HTTP controllers bind the plain Upload DTO. */
final class KnowledgePdfUpload extends KnowledgeDTO.Upload {
    final byte[] source;
    final String sourceName, sourceHash;
    private final String mode;
    final int pages;
    final List<Integer> emptyPages;

    KnowledgePdfUpload(KnowledgeDTO.Upload metadata, byte[] source, String name, KnowledgeDTO.PdfText parsed) {
        this(metadata, source, name, parsed, metadata.isOcr() ? "OCR" : "NATIVE");
    }

    KnowledgePdfUpload(KnowledgeDTO.Upload metadata, byte[] source, String name, KnowledgeDTO.PdfText parsed, String mode) {
        this.source = source; this.sourceName = name; this.sourceHash = RagServiceClient.hash(source);
        this.mode = mode;
        this.pages = parsed.getPages(); this.emptyPages = parsed.getEmptyPages();
        setRequestId(metadata.getRequestId()); setExpectedRevision(metadata.getExpectedRevision());
        setTitle(metadata.getTitle()); setVisibility(metadata.getVisibility()); setFormat("pdf"); setContent(parsed.getContent());
        setOcr("OCR".equals(mode) || "OCR_REVIEWED".equals(mode));
        setReviewedOcr("OCR_REVIEWED".equals(mode));
        setReviewedPdf("NATIVE_REVIEWED".equals(mode) || "OCR_REVIEWED".equals(mode));
    }

    String emptyPagesValue() {
        StringBuilder value = new StringBuilder();
        for (Integer page : emptyPages) { if (value.length() > 0) value.append(','); value.append(page); }
        return value.toString();
    }
    String extractionMode() { return mode; }
}
