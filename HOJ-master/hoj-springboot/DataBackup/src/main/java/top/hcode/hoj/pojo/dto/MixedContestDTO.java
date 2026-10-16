package top.hcode.hoj.pojo.dto;

import lombok.Data;
import java.util.Map;

/** Contest rules are independent from practice paper release rules. */
public final class MixedContestDTO {
    private MixedContestDTO() {}
    @Data public static class Config {
        private Long paperId;
        private String scoreRule = "BEST";
        private String answerPolicy = "AFTER_END";
        private String explanationPolicy = "AFTER_END";
        private Long answerReleaseAt;
        private Long explanationReleaseAt;
    }
    @Data public static class Template {
        private PaperDTO.Snapshot snapshot;
        private Map<String, PaperProgramSnapshotDTO> programs;
    }
}
