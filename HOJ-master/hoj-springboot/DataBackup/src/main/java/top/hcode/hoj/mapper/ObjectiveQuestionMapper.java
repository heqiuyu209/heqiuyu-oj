package top.hcode.hoj.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import top.hcode.hoj.pojo.entity.objective.ObjectiveQuestion;

@Mapper
public interface ObjectiveQuestionMapper extends BaseMapper<ObjectiveQuestion> {
    @Select("SELECT * FROM objective_question WHERE id = #{id} FOR UPDATE")
    ObjectiveQuestion selectLocked(@Param("id") Long id);

}
