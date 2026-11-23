package top.hcode.hoj.pojo.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

/** Internal index contracts. Browser input must never supply allowed versions. */
public final class KnowledgeDTO {
    private KnowledgeDTO() {}
    @Getter @Setter public static class Upload {
        private String requestId;
        private Long expectedRevision;
        private String title;
        private String format;
        private String content;
        private String visibility = "ADMIN";
        private Integer sourceVersion;
        private boolean ocr;
        private boolean reviewedOcr;
        private boolean reviewedPdf;
        private boolean reviewedVideo;
    }
    @Getter @Setter public static class Change {
        private Long expectedRevision;
        private Integer version;
        private String visibility;
    }
    @Getter @Setter public static class Search {
        private String query;
        private List<String> documentIds;
        private Integer topK = 5;
    }
    @Getter @Setter public static class Question {
        private Search search;
        private boolean allowModelExcerpts;
        private String modelFingerprint;
    }
    @Getter @Setter public static class VersionRef {
        private String documentId;
        private Integer version;
        private String contentHash;
    }
    @Getter @Setter public static class Ingest extends VersionRef {
        private String title;
        private String format;
        private String content;
    }
    @Getter @Setter public static class Hit extends VersionRef {
        private String chunkId;
        private String chunkHash;
        private Integer chunkIndex;
        private String title;
        private String excerpt;
        private String headingPath;
        private Double score;
        private Integer page;
        private Integer sourceStart;
        private Integer sourceEnd;
        private Long startMs;
        private Long endMs;
    }
    @Getter @Setter public static class PdfText {
        private String sourceHash;
        private Integer pages;
        private String content;
        private List<Integer> emptyPages;
        private String parser;
    }
}
