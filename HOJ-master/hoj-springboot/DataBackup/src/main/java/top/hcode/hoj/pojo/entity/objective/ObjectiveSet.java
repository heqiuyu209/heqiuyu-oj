package top.hcode.hoj.pojo.entity.objective;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

@Data
@TableName("objective_set")
public class ObjectiveSet {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String description;
    private String mode;
    private String questionIdsJson;
    private Integer version;
    private Boolean published;
    private String answerPolicy;
    private String explanationPolicy;
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Long answerReleaseAt;
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Long explanationReleaseAt;
    private String createdBy;
    private Date gmtCreate;
    private Date gmtModified;
}
