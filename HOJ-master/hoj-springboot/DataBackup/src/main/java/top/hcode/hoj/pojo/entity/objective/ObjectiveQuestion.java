package top.hcode.hoj.pojo.entity.objective;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

@Data
@TableName("objective_question")
public class ObjectiveQuestion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String questionType;
    private Integer version;
    private Boolean published;
    private Boolean practiceEnabled;
    private String createdBy;
    private String contentJson;
    private Date gmtCreate;
    private Date gmtModified;
}
