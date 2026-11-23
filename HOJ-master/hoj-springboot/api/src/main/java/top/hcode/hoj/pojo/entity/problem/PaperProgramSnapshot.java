package top.hcode.hoj.pojo.entity.problem;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
@TableName("paper_program_snapshot")
public class PaperProgramSnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private String itemKey;
    private String uid;
    @JsonIgnore
    private String contentJson;
}

