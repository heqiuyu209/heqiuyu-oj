package top.hcode.hoj.pojo.entity.objective;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

@Data
@TableName("objective_question_version")
public class ObjectiveQuestionVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long questionId;
    private Integer version;
    private String contentJson;
    private Date gmtCreate;
}
