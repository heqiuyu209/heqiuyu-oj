package top.hcode.hoj.pojo.entity.objective;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

@Data
@TableName("objective_run")
public class ObjectiveRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long setId;
    private Integer setVersion;
    private String uid;
    private String requestId;
    private String mode;
    private String title;
    private String state;
    private String snapshotJson;
    private String answersJson;
    private Date gmtCreate;
    private Date gmtModified;
}
