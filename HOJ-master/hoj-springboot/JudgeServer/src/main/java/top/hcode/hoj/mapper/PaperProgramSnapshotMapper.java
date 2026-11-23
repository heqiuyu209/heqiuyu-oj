package top.hcode.hoj.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.hcode.hoj.pojo.entity.problem.PaperProgramSnapshot;

@Mapper
public interface PaperProgramSnapshotMapper extends BaseMapper<PaperProgramSnapshot> {}
