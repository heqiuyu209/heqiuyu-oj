package top.hcode.hoj.pojo.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

/** Internal source identifiers are created by HOJ; no browser file paths. */
public final class KnowledgeVideoDTO {
    private KnowledgeVideoDTO() {}
    @Getter @Setter public static class Save extends KnowledgeDTO.Upload {
        private Long importRevision;
        private boolean asNew;
    }
    @Getter @Setter public static class Change {
        private Long expectedRevision;
        private boolean restart;
    }
    @Getter @Setter public static class Asset {
        private String assetId;
        private String sourceHash;
        private Long byteLength;
    }
    @Getter @Setter public static class Slice extends Asset {
        private Long startMs;
        private Long endMs;
        private String language = "auto";
        private String expectedModelHash;
    }
    @Getter @Setter public static class Probe extends Asset {
        private Long durationMs;
        private String mime;
        private Integer width;
        private Integer height;
        private String videoCodec;
        private String audioCodec;
        private Integer audioIndex;
    }
    @Getter @Setter public static class Speech {
        private Long startMs;
        private Long endMs;
        private String text;
    }
    @Getter @Setter public static class Transcribed extends Asset {
        private Long startMs;
        private Long endMs;
        private Long durationMs;
        private String language;
        private List<Speech> segments;
        private String engine;
        private String modelHash;
    }
}
