package top.hcode.hoj.pojo.dto;

import lombok.Data;
import java.util.List;

/** Objective practice contracts. Scores and user identity are never accepted from clients. */
public final class ObjectiveDTO {
    private ObjectiveDTO() {}

    @Data
    public static class Option {
        private String id;
        private String text;
    }

    @Data
    public static class Question {
        private Long id;
        private Integer version;
        private String title;
        private String questionType;
        private String stem;
        private List<Option> options;
        private List<String> correctAnswers;
        private String explanation;
        private List<Long> tagIds;
        private Boolean published;
    }

    @Data
    public static class ProblemSet {
        private Long id;
        private Integer version;
        private String title;
        private String description;
        private String mode;
        private List<Long> questionIds;
        private String answerPolicy;
        private String explanationPolicy;
        private Long answerReleaseAt;
        private Long explanationReleaseAt;
        private Boolean published;
    }

    @Data
    public static class StartRun {
        private Long setId;
        private String requestId;
    }

    @Data
    public static class Answer {
        private Long questionId;
        private List<String> selectedAnswers;
    }
}
