package top.hcode.hoj.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import top.hcode.hoj.pojo.entity.objective.ObjectiveRun;

@Mapper
public interface ObjectiveRunMapper extends BaseMapper<ObjectiveRun> {
    @Select("SELECT * FROM objective_run WHERE id = #{id} FOR UPDATE")
    ObjectiveRun selectLocked(@Param("id") Long id);

}
