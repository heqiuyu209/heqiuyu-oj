package top.hcode.hoj.pojo.dto;

import lombok.Data;

@Data
public class CodeforcesSyncDTO {
    private String problem;
    private String displayId;
    private Long contestId;
    private Long trainingId;
    private Long groupId;
    private Long runId;
    private String itemKey;
}
