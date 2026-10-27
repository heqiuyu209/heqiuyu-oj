package top.hcode.hoj.pojo.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/** Mixed papers reference existing banks; execution belongs to a separate paper run. */
public final class PaperDTO {
    private PaperDTO() {}
    @Data public static class Item {
        private String kind;
        private Long referenceId;
        private Integer score;
    }
    @Data public static class Paper {
        private Long id;
        private Integer version;
        private String title;
        private String description;
        private List<Item> items;
        private String programMode;
        private String scoreRule;
        private String answerPolicy;
        private String explanationPolicy;
        private Long answerReleaseAt;
        private Long explanationReleaseAt;
        private Boolean published;
    }
    @Data public static class Start {
        private Long paperId;
        private String requestId;
    }
    @Data public static class Answer {
        private String key;
        private List<String> selectedAnswers;
        private String language;
        private String code;
        private String requestId;
    }
    @Data public static class SnapshotItem {
        private String key;
        private String kind;
        private Long referenceId;
        private Integer score;
        private ObjectiveDTO.Question objective;
        private Long programSnapshotId;
    }
    @Data public static class Snapshot {
        private Paper paper;
        private List<SnapshotItem> items;
        private Integer virtualContestType;
    }
    @Data public static class SavedAnswer {
        private List<String> selectedAnswers;
        private String language;
        private String code;
        private List<Long> submissionIds;
        private Map<String, Long> requests;
    }
}

