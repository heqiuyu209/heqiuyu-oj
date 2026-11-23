package top.hcode.hoj.pojo.dto;

import lombok.Getter;
import lombok.Setter;

public final class TrainingPlanDTO {
    private TrainingPlanDTO() {}
    @Getter @Setter public static class Create {
        private String requestId;
        private String name;
        private AiDTO.RecommendationExplanation selection;
    }
    @Getter @Setter public static class Update {
        private Long expectedRevision;
        private String state;
        private String name;
    }
}
