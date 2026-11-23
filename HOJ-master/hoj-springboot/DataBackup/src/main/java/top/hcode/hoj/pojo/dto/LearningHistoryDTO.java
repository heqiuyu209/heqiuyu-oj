package top.hcode.hoj.pojo.dto;

import lombok.Getter;
import lombok.Setter;

public final class LearningHistoryDTO {
    private LearningHistoryDTO() {}
    @Getter @Setter public static class Query {
        private String source = "ALL";
        private String status = "ALL";
        private String groupCode;
        private String keyword;
        private boolean onlyDuplicates;
        private Integer page = 1;
    }
}
