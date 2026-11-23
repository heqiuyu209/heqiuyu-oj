package top.hcode.hoj.pojo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

public final class AiDTO {
    private AiDTO() {}
    @Getter @Setter public static class Settings {
        private String baseUrl;
        private String model;
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) private String apiKey;
    }
    @Getter @Setter public static class Message {
        private String role;
        private String content;
    }
    @Getter @Setter public static class Chat {
        private List<Message> messages;
        private String modelFingerprint;
        private ProblemContext problemContext;
        private boolean includeProfile;
        private boolean includeExternalProfile;
    }
    @Getter @Setter public static class ProblemReference {
        private String kind;
        private String problemId;
        private Long groupId;
        private Long contestId;
        private Long runId;
        private String questionKey;
    }
    @Getter @Setter public static class ProblemContext {
        private ProblemReference reference;
        private String fingerprint;
    }
    @Getter @Setter public static class RecommendationFilter {
        private String source = "HOJ";
        private String groupCode;
        private Integer minRating;
        private Integer maxRating;
        private boolean includeUnrated = true;
    }
    @Getter @Setter public static class RecommendationExplanation {
        private String modelFingerprint;
        private RecommendationFilter filters;
        private String fingerprint;
        private List<String> candidateIds;
        private boolean includeProfile;
        private boolean includeExternalProfile;
    }
}
