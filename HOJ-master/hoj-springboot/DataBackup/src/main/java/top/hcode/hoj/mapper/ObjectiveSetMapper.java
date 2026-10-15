package top.hcode.hoj.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import top.hcode.hoj.pojo.entity.objective.ObjectiveSet;

@Mapper
public interface ObjectiveSetMapper extends BaseMapper<ObjectiveSet> {
    @Select("SELECT * FROM objective_set WHERE id = #{id} FOR UPDATE")
    ObjectiveSet selectLocked(@Param("id") Long id);

}
