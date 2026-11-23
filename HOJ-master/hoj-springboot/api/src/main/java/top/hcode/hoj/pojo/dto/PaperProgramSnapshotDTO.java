package top.hcode.hoj.pojo.dto;

import lombok.Data;
import top.hcode.hoj.pojo.entity.problem.Problem;
import top.hcode.hoj.pojo.entity.problem.ProblemCase;
import java.util.List;

/** Private immutable execution data, never part of student responses. */
@Data
public class PaperProgramSnapshotDTO {
    private Problem problem;
    private List<ProblemCase> cases;
    private List<String> languages;
}

