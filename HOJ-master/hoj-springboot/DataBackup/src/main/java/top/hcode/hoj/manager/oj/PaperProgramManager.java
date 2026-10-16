package top.hcode.hoj.manager.oj;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.judge.self.JudgeDispatcher;
import top.hcode.hoj.mapper.*;
import top.hcode.hoj.pojo.dto.PaperProgramSnapshotDTO;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.pojo.entity.problem.*;
import top.hcode.hoj.utils.Constants;
import top.hcode.hoj.utils.ShiroUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/** Bridges mixed papers to the local judge without creating ordinary practice submissions. */
@Component
public class PaperProgramManager {
    @Autowired private ProblemMapper problemMapper;
    @Autowired private ProblemCaseMapper caseMapper;
    @Autowired private PaperProgramSnapshotMapper snapshotMapper;
    @Autowired private JudgeMapper judgeMapper;
    @Autowired private JudgeDispatcher dispatcher;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private top.hcode.hoj.validator.AccessValidator accessValidator;
    @Autowired private top.hcode.hoj.config.NacosSwitchConfig switchConfig;
    @Autowired private top.hcode.hoj.utils.RedisUtils redis;

    private void require(boolean ok, String message) { if (!ok) throw new ObjectiveException(400, message); }

    public Problem available(Long id) {
        Problem problem = problemMapper.selectById(id);
        require(problem != null && Objects.equals(problem.getAuth(), 1) && !Boolean.TRUE.equals(problem.getIsGroup()),
                "只能引用已公开的主题库编程题");
        require(!Boolean.TRUE.equals(problem.getIsRemote()) && "default".equals(problem.getJudgeMode()),
                "混合试卷首版支持本地标准判题，请选择本地非特判编程题");
        return problem;
    }

    public List<Map<String, Object>> choices(String keyword) {
        QueryWrapper<Problem> query = new QueryWrapper<>();
        query.select("id", "problem_id", "title", "source").eq("auth", 1).eq("is_remote", false)
                .eq("judge_mode", "default").and(q -> q.eq("is_group", false).or().isNull("is_group"));
        if (keyword != null && !keyword.trim().isEmpty()) {
            require(keyword.length() <= 100, "搜索词过长"); query.like("title", keyword.trim());
        }
        return problemMapper.selectList(query.orderByDesc("id").last("LIMIT 100")).stream().map(p -> {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("id", p.getId());
            row.put("problemId", p.getProblemId()); row.put("title", p.getTitle()); row.put("source", p.getSource()); return row;
        }).collect(Collectors.toList());
    }

    public Long freeze(Long runId, String itemKey, String uid, Long problemId) {
        return copy(runId, itemKey, uid, capture(problemId));
    }

    public PaperProgramSnapshotDTO capture(Long problemId) {
        jdbc.queryForList("SELECT id FROM problem WHERE id=? FOR UPDATE", problemId);
        Problem problem = available(problemId);
        List<ProblemCase> cases = caseMapper.selectList(new QueryWrapper<ProblemCase>().eq("pid", problemId).eq("status", 0).orderByAsc("id"));
        require(!cases.isEmpty(), "编程题缺少可用测试数据");
        long size = 0;
        for (ProblemCase test : cases) {
            if (Boolean.TRUE.equals(problem.getIsUploadCase())) {
                test.setInput(readCase(problemId, test.getInput()));
                test.setOutput(readCase(problemId, test.getOutput()).replace("\r\n", "\n").replace("\r", "\n"));
            }
            require(test.getInput() != null && test.getOutput() != null, "测试数据不完整");
            size += test.getInput().getBytes(StandardCharsets.UTF_8).length + test.getOutput().getBytes(StandardCharsets.UTF_8).length;
            require(size <= 16 * 1024 * 1024, "单题测试数据超过混合卷首版 16 MiB 快照限制");
        }
        List<String> languages = jdbc.queryForList("SELECT l.name FROM language l JOIN problem_language pl ON pl.lid=l.id WHERE pl.pid=? AND l.oj='ME' ORDER BY l.seq DESC, l.id", String.class, problemId);
        require(!languages.isEmpty(), "编程题尚未配置本地可用语言");
        PaperProgramSnapshotDTO payload = new PaperProgramSnapshotDTO();
        payload.setProblem(problem); payload.setCases(cases); payload.setLanguages(languages);
        return payload;
    }

    public Long copy(Long runId, String itemKey, String uid, PaperProgramSnapshotDTO payload) {
        PaperProgramSnapshot snapshot = new PaperProgramSnapshot();
        snapshot.setRunId(runId); snapshot.setItemKey(itemKey); snapshot.setUid(uid);
        try { snapshot.setContentJson(json.writeValueAsString(payload)); }
        catch (IOException e) { throw new IllegalStateException(e); }
        snapshotMapper.insert(snapshot);
        return snapshot.getId();
    }

    private String readCase(Long pid, String name) {
        try {
            Path base = Paths.get(Constants.File.TESTCASE_BASE_FOLDER.getPath(), "problem_" + pid).toAbsolutePath().normalize();
            require(name != null && !name.isEmpty(), "测试文件名为空");
            Path file = base.resolve(name).normalize();
            require(file.startsWith(base) && Files.isRegularFile(file) && file.toRealPath().startsWith(base.toRealPath())
                    && Files.size(file) <= 16 * 1024 * 1024, "测试文件无效或过大");
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new ObjectiveException(400, "无法读取测试数据，请检查题目数据文件"); }
    }

    public PaperProgramSnapshotDTO snapshot(Long snapshotId) {
        PaperProgramSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) throw new ObjectiveException(404, "编程题快照不存在");
        try { return json.readValue(snapshot.getContentJson(), PaperProgramSnapshotDTO.class); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    public void validate(Long snapshotId, String language, String code) {
        require(code != null && !code.trim().isEmpty() && code.length() <= 65535, "请填写不超过 65535 字的代码");
        require(snapshot(snapshotId).getLanguages().contains(language), "请选择本题支持的语言");
    }

    public void rateLimit(String uid) {
        int interval = switchConfig.getSwitchConfig().getDefaultSubmitInterval();
        if (interval > 0 && !redis.isWithinRateLimit(Constants.Account.SUBMIT_NON_CONTEST_LOCK.getCode() + uid, interval))
            throw new ObjectiveException(403, "提交过于频繁，请稍后重试");
    }

    public Long submit(Long runId, Long snapshotId, String uid, String language, String code) {
        checkAccess();
        return submitAs(runId, snapshotId, uid, ShiroUtils.getProfile().getUsername(), language, code);
    }

    /** Deadline jobs have no Shiro principal; the actor always comes from the persisted run. */
    public Long submitAs(Long runId, Long snapshotId, String uid, String username, String language, String code) {
        validate(snapshotId, language, code);
        PaperProgramSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        require(snapshot.getRunId().equals(runId) && snapshot.getUid().equals(uid), "编程题快照不属于本次作答");
        Problem problem = snapshot(snapshotId).getProblem();
        Judge judge = new Judge().setUid(uid).setUsername(username)
                .setPid(problem.getId()).setDisplayPid(problem.getProblemId()).setCid(0L).setCpid(0L)
                .setPaperRunId(runId).setPaperSnapshotId(snapshotId).setCode(code).setLanguage(language)
                .setShare(false).setLength(code.length()).setVersion(0).setSubmitTime(new Date())
                .setStatus(Constants.Judge.STATUS_PENDING.getStatus());
        judgeMapper.insert(judge);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { dispatcher.sendTask(judge.getSubmitId(), judge.getPid(), false); }
        });
        return judge.getSubmitId();
    }

    public void contestAccess() {
        try { accessValidator.validateAccess(top.hcode.hoj.annotation.HOJAccessEnum.CONTEST_JUDGE); }
        catch (top.hcode.hoj.exception.AccessException e) { throw new ObjectiveException(403, e.getMessage()); }
    }

    private void checkAccess() {
        try { accessValidator.validateAccess(top.hcode.hoj.annotation.HOJAccessEnum.PUBLIC_JUDGE); }
        catch (top.hcode.hoj.exception.AccessException e) { throw new ObjectiveException(403, e.getMessage()); }
    }

    public void retry(Long runId, String uid) {
        retry(runId, uid, false);
    }

    public void retry(Long runId, String uid, boolean contest) {
        List<Judge> failed = judgeMapper.selectList(new QueryWrapper<Judge>()
                .eq("paper_run_id", runId).eq("uid", uid).in("status", Arrays.asList(4, 10)));
        if (failed.isEmpty()) return;
        if (contest) contestAccess(); else checkAccess();
        rateLimit(uid);
        for (Judge judge : failed) {
            judge.setStatus(5).setVersion(judge.getVersion() + 1).setScore(null).setTime(null).setMemory(null).setErrorMessage(null);
            judgeMapper.updateById(judge);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatcher.sendTask(judge.getSubmitId(), judge.getPid(), false); }
            });
        }
    }

    public List<Judge> submissions(Long runId, Long snapshotId, String uid) {
        return judgeMapper.selectList(new QueryWrapper<Judge>().select("submit_id", "status", "score", "time", "memory", "error_message", "submit_time")
                .eq("paper_run_id", runId).eq("paper_snapshot_id", snapshotId).eq("uid", uid).orderByAsc("submit_id"));
    }
}

