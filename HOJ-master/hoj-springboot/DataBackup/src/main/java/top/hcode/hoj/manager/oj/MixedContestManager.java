package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.dao.contest.ContestEntityService;
import top.hcode.hoj.pojo.dto.*;
import top.hcode.hoj.pojo.entity.contest.Contest;
import top.hcode.hoj.shiro.AccountProfile;
import top.hcode.hoj.utils.ShiroUtils;
import top.hcode.hoj.validator.ContestValidator;
import java.util.*;

/** Authoritative contest access and a common frozen paper for all entrants. */
@Component
public class MixedContestManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private ContestEntityService contests;
    @Autowired private ContestValidator validator;
    @Autowired private ObjectiveManager objectives;
    @Autowired private PaperProgramManager programs;

    protected long now() { return System.currentTimeMillis(); }
    protected AccountProfile actor() { return ShiroUtils.getProfile(); }
    protected boolean root() { return SecurityUtils.getSubject().hasRole("root"); }
    private void require(boolean ok, String message) { if (!ok) throw new ObjectiveException(400, message); }
    public <T> T read(Object value, Class<T> type) {
        try { return json.readValue((String) value, type); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public Map<String, Object> configuration(Long cid) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT contest_id,paper_id,rules_json FROM contest_paper WHERE contest_id=?", cid);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public MixedContestDTO.Config config(Long cid) {
        Map<String, Object> row = configuration(cid);
        return row == null ? null : read(row.get("rules_json"), MixedContestDTO.Config.class);
    }
    public String template(Long cid) { return jdbc.queryForObject("SELECT template_json FROM contest_paper WHERE contest_id=?", String.class, cid); }
    public void cloneConfiguration(Long source, Long target) {
        jdbc.update("INSERT INTO contest_paper(contest_id,paper_id,rules_json,template_json) SELECT ?,paper_id,rules_json,template_json FROM contest_paper WHERE contest_id=?", target, source);
    }
    public Contest contest(Long cid, boolean lock) {
        if (lock) jdbc.queryForList("SELECT id FROM contest WHERE id=? FOR UPDATE", cid);
        Contest contest = contests.getById(cid);
        if (contest == null) throw new ObjectiveException(404, "比赛不存在");
        return contest;
    }
    public Contest access(Long cid, boolean submitting) {
        return access(cid, submitting, true);
    }
    public Contest virtualAccess(Long cid) {
        return access(cid, true, false);
    }
    private Contest access(Long cid, boolean submitting, boolean originalDeadline) {
        Contest contest = contest(cid, false);
        AccountProfile user = actor();
        if (user == null) throw new ObjectiveException(401, "请先登录");
        if (configuration(cid) == null) throw new ObjectiveException(404, "该比赛不是混合比赛");
        require(now() >= contest.getStartTime().getTime(), "比赛尚未开始");
        // Existing validators also enforce visibility, private registration and group membership.
        contest.setStatus(now() < contest.getEndTime().getTime() ? 0 : 1);
        try {
            validator.validateContestAuth(contest, user, root());
            if (!root() && !Objects.equals(contest.getUid(), user.getUid())) {
                if (submitting) validator.validateJudgeAuth(contest, user.getUid());
                if (Boolean.TRUE.equals(contest.getOpenAccountLimit()))
                    require(validator.validateAccountRule(contest.getAccountLimitRule(), user.getUsername()), "账号不符合比赛参赛规则");
            }
        } catch (top.hcode.hoj.common.exception.StatusFailException | top.hcode.hoj.common.exception.StatusForbiddenException e) {
            throw new ObjectiveException(403, e.getMessage());
        }
        if (submitting && originalDeadline) require(now() < contest.getEndTime().getTime(), "比赛已截止，答卷不能修改");
        return contest;
    }
    public void configure(Contest old, Contest next, MixedContestDTO.Config config) {
        Map<String, Object> existing = configuration(next.getId());
        boolean locked = old != null && (now() >= old.getStartTime().getTime()
                || jdbc.queryForObject("SELECT COUNT(*) FROM paper_run WHERE contest_id=?", Integer.class, old.getId()) > 0);
        if (locked && (existing != null || config != null)) {
            require(existing != null && config != null, "比赛开始后不能更换题目模式");
            MixedContestDTO.Config previous = read(existing.get("rules_json"), MixedContestDTO.Config.class);
            require(Objects.equals(previous.getPaperId(), config.getPaperId()) && Objects.equals(previous.getScoreRule(), config.getScoreRule())
                    && Objects.equals(old.getType(), next.getType()) && Objects.equals(old.getStartTime(), next.getStartTime())
                    && Objects.equals(old.getEndTime(), next.getEndTime()), "比赛开始后不能更换试卷、计分方式、赛制或起止时间");
        }
        if (config == null) { jdbc.update("DELETE FROM contest_paper WHERE contest_id=?", next.getId()); return; }
        require(!Boolean.TRUE.equals(next.getIsGroup()) && next.getGid() == null, "首版混合比赛请在主站比赛中创建");
        require(next.getStartTime() != null && next.getEndTime() != null && next.getStartTime().before(next.getEndTime()), "比赛起止时间无效");
        require(Arrays.asList("BEST", "LAST").contains(config.getScoreRule()), "编程计分规则无效");
        for (String policy : Arrays.asList(config.getAnswerPolicy(), config.getExplanationPolicy()))
            require(Arrays.asList("AFTER_END", "AT_TIME", "NEVER").contains(policy), "比赛答案与解析开放条件无效");
        require(!"AT_TIME".equals(config.getAnswerPolicy()) || config.getAnswerReleaseAt() != null && config.getAnswerReleaseAt() >= next.getEndTime().getTime(), "答案开放时间不能早于比赛结束");
        require(!"AT_TIME".equals(config.getExplanationPolicy()) || config.getExplanationReleaseAt() != null && config.getExplanationReleaseAt() >= next.getEndTime().getTime(), "解析开放时间不能早于比赛结束");
        String template;
        if (locked) template = template(next.getId());
        else {
            require(config.getPaperId() != null, "请选择混合试卷");
            List<Map<String, Object>> papers = jdbc.queryForList("SELECT * FROM paper WHERE id=? FOR UPDATE", config.getPaperId());
            require(!papers.isEmpty(), "试卷不存在");
            Map<String, Object> paperRow = papers.get(0);
            require(root() || Objects.equals(actor().getUid(), paperRow.get("created_by")) || SecurityUtils.getSubject().hasRole("problem_admin"), "不能使用他人的私有试卷");
            PaperDTO.Paper paper = read(paperRow.get("content_json"), PaperDTO.Paper.class);
            MixedContestDTO.Template frozen = new MixedContestDTO.Template();
            PaperDTO.Snapshot snapshot = new PaperDTO.Snapshot(); snapshot.setPaper(paper); snapshot.setItems(new ArrayList<>());
            frozen.setSnapshot(snapshot); frozen.setPrograms(new LinkedHashMap<>());
            for (PaperDTO.Item reference : paper.getItems()) {
                PaperDTO.SnapshotItem item = new PaperDTO.SnapshotItem();
                item.setKey(reference.getKind() + ":" + reference.getReferenceId()); item.setKind(reference.getKind());
                item.setReferenceId(reference.getReferenceId()); item.setScore(reference.getScore());
                if ("OBJECTIVE".equals(item.getKind())) {
                    objectives.question(item.getReferenceId());
                    item.setObjective(objectives.collectionQuestionSnapshot(item.getReferenceId()));
                    if ("FILL_BLANK".equals(item.getObjective().getQuestionType())) item.setScore(objectives.maxScore(item.getObjective()));
                } else {
                    programs.availableForCollection(item.getReferenceId());
                    frozen.getPrograms().put(item.getKey(), programs.captureForCollection(item.getReferenceId()));
                }
                snapshot.getItems().add(item);
            }
            template = write(frozen);
            require(template.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 32 * 1024 * 1024, "混合比赛的全部题目与测试数据快照须小于 32 MiB");
        }
        // Serialize with the contest row lock held by the administrative transaction.
        jdbc.update("DELETE FROM contest_paper WHERE contest_id=?", next.getId());
        jdbc.update("INSERT INTO contest_paper(contest_id,paper_id,rules_json,template_json) VALUES(?,?,?,?)", next.getId(), config.getPaperId(), write(config), template);
    }
    public boolean released(String policy, Long at, Contest contest) {
        return now() >= contest.getEndTime().getTime() && ("AFTER_END".equals(policy) || "AT_TIME".equals(policy) && at != null && now() >= at);
    }
}
