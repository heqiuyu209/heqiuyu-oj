package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.manager.oj.external.CodeforcesSyncManager;
import top.hcode.hoj.pojo.dto.*;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.pojo.entity.problem.Problem;
import top.hcode.hoj.utils.ShiroUtils;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/** Mixed paper configuration, frozen runs and server-authoritative grading. */
@Component
public class PaperManager {
    private static final TypeReference<Map<String, PaperDTO.SavedAnswer>> ANSWERS = new TypeReference<Map<String, PaperDTO.SavedAnswer>>() {};
    private static final Set<String> POLICIES = new HashSet<>(Arrays.asList("AFTER_ANSWER", "AFTER_FINISH", "AT_TIME", "NEVER"));
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private ObjectiveManager objectives;
    @Autowired private PaperProgramManager programs;
    @Autowired(required = false) private MixedContestManager mixed;

    protected String uid() {
        if (ShiroUtils.getProfile() == null) throw fail(401, "请先登录");
        return ShiroUtils.getProfile().getUid();
    }
    protected boolean manageAll() { return SecurityUtils.getSubject().hasRole("root") || SecurityUtils.getSubject().hasRole("problem_admin"); }
    protected long now() { return System.currentTimeMillis(); }
    private ObjectiveException fail(int status, String message) { return new ObjectiveException(status, message); }
    private void require(boolean ok, String message) { if (!ok) throw fail(400, message); }
    private void owner(Map<String, Object> row) { if (!manageAll() && !uid().equals(row.get("created_by"))) throw fail(403, "只能管理自己的试卷"); }
    private long number(Object value) { return ((Number) value).longValue(); }
    private boolean enabled(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number && ((Number) value).intValue() == 1; }
    private String text(String value, int max, boolean required) {
        String result = value == null ? "" : value.trim();
        require(result.length() <= max && (!required || !result.isEmpty()), "必填内容为空或长度超限"); return result;
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private <T> T read(Object value, Class<T> type) {
        try { return json.readValue((String) value, type); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private Map<String, PaperDTO.SavedAnswer> answers(Map<String, Object> row) {
        try { return json.readValue((String) row.get("answers_json"), ANSWERS); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private long insert(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
    private Map<String, Object> row(String table, Long id, boolean locked) {
        require(id != null && id > 0, "记录 ID 无效");
        List<Map<String, Object>> result = jdbc.queryForList("SELECT * FROM " + table + " WHERE id=?" + (locked ? " FOR UPDATE" : ""), id);
        if (result.isEmpty()) throw fail(404, "试卷或答卷不存在"); return result.get(0);
    }
    private Map<String, Object> paper(Long id, boolean locked, boolean admin) {
        Map<String, Object> row = row("paper", id, locked);
        if (admin) owner(row); else if (!enabled(row.get("published"))) throw fail(403, "试卷尚未开放");
        return row;
    }
    private Map<String, Object> ownedRun(Long id, boolean locked) {
        Map<String, Object> row = row("paper_run", id, locked);
        if (!uid().equals(row.get("uid"))) throw fail(404, "答卷不存在");
        if (row.get("virtual_run_id") != null) mixed.virtualAccess(number(virtualRow(row).get("cid")));
        else if (row.get("contest_id") != null) mixed.access(number(row.get("contest_id")), false);
        else paper(number(row.get("paper_id")), false, false);
        return row;
    }

    public void requireCodeforcesSyncItem(long runId, String key, String problemId) {
        Map<String, Object> run = ownedRun(runId, false);
        PaperDTO.SnapshotItem item = item(snapshot(run), key);
        if (!"PROGRAMMING".equals(item.getKind())) throw fail(404, "此题不支持 Codeforces 结果同步");
        Problem problem = programs.snapshot(item.getProgramSnapshotId()).getProblem();
        if (!CodeforcesSyncManager.isCodeforcesProblem(problem) || !problemId.equals(problem.getProblemId()))
            throw fail(404, "此题不支持 Codeforces 结果同步");
    }

    public List<CodeforcesSyncManager.Target> codeforcesSyncTargets(long runId, String user) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.*,c.start_time,c.end_time,v.started_at,v.ends_at FROM paper_run r "
                        + "LEFT JOIN contest c ON c.id=r.contest_id LEFT JOIN contest_virtual_run v ON v.id=r.virtual_run_id AND v.uid=r.uid "
                        + "WHERE r.id=? AND r.uid=?", runId, user);
        if (rows.isEmpty()) return Collections.emptyList();
        Map<String, Object> run = rows.get(0);
        Object start = run.get("contest_id") != null ? run.get("start_time")
                : run.get("virtual_run_id") != null ? run.get("started_at") : run.get("gmt_create");
        Object end = run.get("contest_id") != null ? run.get("end_time") : run.get("ends_at");
        if (!(start instanceof java.util.Date) || competition(run) && !(end instanceof java.util.Date)) return Collections.emptyList();
        long from = ((java.util.Date) start).getTime();
        long until = end == null ? Long.MAX_VALUE : ((java.util.Date) end).getTime();
        if ("FINISHED".equals(run.get("state"))) until = Math.min(until, ((java.util.Date) run.get("gmt_modified")).getTime());
        List<CodeforcesSyncManager.Target> targets = new ArrayList<>();
        for (PaperDTO.SnapshotItem item : snapshot(run).getItems()) {
            if (!"PROGRAMMING".equals(item.getKind())) continue;
            Problem problem = programs.snapshot(item.getProgramSnapshotId()).getProblem();
            if (CodeforcesSyncManager.isCodeforcesProblem(problem))
                targets.add(CodeforcesSyncManager.Target.paper(runId, item.getProgramSnapshotId(), problem, from, until));
        }
        return targets;
    }
    private Map<String, Object> virtualRow(Map<String, Object> run) {
        if (run.get("virtual_run_id") == null) return null;
        Map<String, Object> window = row("contest_virtual_run", number(run.get("virtual_run_id")), false);
        if (!Objects.equals(run.get("uid"), window.get("uid"))) throw fail(404, "答卷不存在");
        return window;
    }
    private boolean competition(Map<String, Object> run) { return run.get("contest_id") != null || run.get("virtual_run_id") != null; }
    private PaperDTO.Paper content(Map<String, Object> row) { return read(row.get("content_json"), PaperDTO.Paper.class); }
    private Map<String, Object> metadata(Map<String, Object> row) {
        PaperDTO.Paper paper = content(row);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", paper.getId()); view.put("title", paper.getTitle()); view.put("description", paper.getDescription());
        view.put("version", paper.getVersion()); view.put("published", paper.getPublished()); view.put("questionCount", paper.getItems().size());
        view.put("programMode", paper.getProgramMode()); view.put("scoreRule", paper.getScoreRule()); return view;
    }

    public Map<String, Object> list(Integer page, String keyword, boolean admin) {
        List<Object> args = new ArrayList<>(); String where = " WHERE 1=1";
        if (!admin) where += " AND published=1";
        else if (!manageAll()) { where += " AND created_by=?"; args.add(uid()); }
        if (keyword != null && !keyword.trim().isEmpty()) { where += " AND title LIKE ?"; args.add("%" + text(keyword, 100, false) + "%"); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM paper" + where, Long.class, args.toArray());
        args.add(20); args.add((Math.max(1, Math.min(100000, page == null ? 1 : page)) - 1) * 20);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM paper" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray());
        Map<String, Object> result = new LinkedHashMap<>(); result.put("total", total);
        result.put("records", rows.stream().map(this::metadata).collect(Collectors.toList())); return result;
    }
    public PaperDTO.Paper detail(Long id) { return content(paper(id, false, true)); }
    public List<Map<String, Object>> programChoices(String keyword) { uid(); return programs.choices(keyword); }

    /** One paginated catalogue; old objective-only sets keep their IDs and student URLs. */
    public Map<String, Object> collections(Integer page, String keyword, String kind) {
        uid();
        require(kind == null || kind.isEmpty() || Arrays.asList("PRACTICE", "PAPER").contains(kind), "集合类型无效");
        String catalogue = "(SELECT id,title,published,created_by,gmt_modified,'PAPER' AS source,'PAPER' AS mode,content_json AS config FROM paper"
                + " UNION ALL SELECT id,title,published,created_by,gmt_modified,'OBJECTIVE' AS source,mode,question_ids_json AS config FROM objective_set) catalogue";
        String where = " WHERE 1=1";
        List<Object> args = new ArrayList<>();
        if (!manageAll()) { where += " AND created_by=?"; args.add(uid()); }
        if (keyword != null && !keyword.trim().isEmpty()) { where += " AND title LIKE ?"; args.add("%" + text(keyword, 100, false) + "%"); }
        if (kind != null && !kind.isEmpty()) { where += " AND mode=?"; args.add(kind); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + catalogue + where, Long.class, args.toArray());
        args.add(20); args.add((Math.max(1, Math.min(100000, page == null ? 1 : page)) - 1) * 20);
        List<Map<String, Object>> records = jdbc.queryForList("SELECT * FROM " + catalogue + where
                + " ORDER BY gmt_modified DESC,source,id DESC LIMIT ? OFFSET ?", args.toArray()).stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", row.get("id")); result.put("title", row.get("title"));
            result.put("published", enabled(row.get("published"))); result.put("source", row.get("source")); result.put("mode", row.get("mode"));
            if ("PAPER".equals(row.get("source"))) {
                PaperDTO.Paper value = read(row.get("config"), PaperDTO.Paper.class);
                result.put("questionCount", value.getItems().size()); result.put("programMode", value.getProgramMode());
            } else result.put("questionCount", read(row.get("config"), List.class).size());
            return result;
        }).collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>(); result.put("records", records); result.put("total", total); return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public PaperDTO.Paper save(PaperDTO.Paper dto) {
        require(dto != null, "试卷不能为空");
        dto.setTitle(text(dto.getTitle(), 200, true)); dto.setDescription(text(dto.getDescription(), 5000, false));
        require(Arrays.asList("LIVE", "AFTER_FINISH").contains(dto.getProgramMode()), "请选择编程题判题方式");
        require(Arrays.asList("BEST", "LAST").contains(dto.getScoreRule()), "请选择最高分或最后一次提交计分");
        for (String policy : Arrays.asList(dto.getAnswerPolicy(), dto.getExplanationPolicy())) require(POLICIES.contains(policy), "开放条件无效");
        require(!"AT_TIME".equals(dto.getAnswerPolicy()) || dto.getAnswerReleaseAt() != null && dto.getAnswerReleaseAt() > 0, "请设置答案开放时间");
        require(!"AT_TIME".equals(dto.getExplanationPolicy()) || dto.getExplanationReleaseAt() != null && dto.getExplanationReleaseAt() > 0, "请设置解析开放时间");
        if (!"AT_TIME".equals(dto.getAnswerPolicy())) dto.setAnswerReleaseAt(null);
        if (!"AT_TIME".equals(dto.getExplanationPolicy())) dto.setExplanationReleaseAt(null);
        require(dto.getItems() != null && !dto.getItems().isEmpty() && dto.getItems().size() <= 50, "试卷须包含 1 到 50 道题");
        Set<String> keys = new HashSet<>();
        for (PaperDTO.Item item : dto.getItems()) {
            require(item != null && Arrays.asList("OBJECTIVE", "PROGRAMMING").contains(item.getKind()) && item.getReferenceId() != null && item.getReferenceId() > 0, "题目引用无效");
            require(keys.add(item.getKind() + ":" + item.getReferenceId()), "同一试卷不能重复引用同一道题");
            if ("OBJECTIVE".equals(item.getKind())) {
                ObjectiveDTO.Question question = objectives.question(item.getReferenceId());
                if ("FILL_BLANK".equals(question.getQuestionType())) item.setScore(objectives.maxScore(question));
            } else programs.availableForCollection(item.getReferenceId());
            require(item.getScore() != null && item.getScore() > 0 && item.getScore() <= 20000, "每题分值须为 1 到 20000");
        }
        dto.setPublished(Boolean.TRUE.equals(dto.getPublished()));
        Timestamp time = new Timestamp(now());
        if (dto.getId() == null) {
            dto.setVersion(1);
            dto.setId(insert("INSERT INTO paper(title,content_json,version,published,created_by,gmt_create,gmt_modified) VALUES(?,?,?,?,?,?,?)",
                    dto.getTitle(), "{}", 1, dto.getPublished(), uid(), time, time));
        } else {
            Map<String, Object> old = paper(dto.getId(), true, true);
            if (dto.getVersion() == null || number(old.get("version")) != dto.getVersion()) throw fail(409, "试卷已更新，请刷新后重试");
            dto.setVersion(dto.getVersion() + 1);
        }
        jdbc.update("UPDATE paper SET title=?,content_json=?,version=?,published=?,gmt_modified=? WHERE id=?",
                dto.getTitle(), write(dto), dto.getVersion(), dto.getPublished(), time, dto.getId());
        return dto;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> start(PaperDTO.Start dto) {
        require(dto != null, "请选择试卷"); nonce(dto.getRequestId());
        Map<String, Object> source = paper(dto.getPaperId(), true, false);
        String uid = uid();
        List<Map<String, Object>> duplicate = jdbc.queryForList("SELECT * FROM paper_run WHERE uid=? AND request_id=?", uid, dto.getRequestId());
        if (!duplicate.isEmpty()) {
            if (number(duplicate.get(0).get("paper_id")) != dto.getPaperId()) throw fail(409, "请求标识已用于其他试卷");
            return view(duplicate.get(0));
        }
        PaperDTO.Paper paper = content(source); Timestamp time = new Timestamp(now());
        long id = insert("INSERT INTO paper_run(paper_id,paper_version,uid,request_id,state,snapshot_json,answers_json,gmt_create,gmt_modified) VALUES(?,?,?,?,?,?,?,?,?)",
                paper.getId(), paper.getVersion(), uid, dto.getRequestId(), "IN_PROGRESS", "{}", "{}", time, time);
        PaperDTO.Snapshot snapshot = new PaperDTO.Snapshot(); snapshot.setPaper(paper); snapshot.setItems(new ArrayList<>());
        for (PaperDTO.Item reference : paper.getItems()) {
            PaperDTO.SnapshotItem item = new PaperDTO.SnapshotItem();
            item.setKey(reference.getKind() + ":" + reference.getReferenceId()); item.setKind(reference.getKind());
            item.setReferenceId(reference.getReferenceId()); item.setScore(reference.getScore());
            if ("OBJECTIVE".equals(item.getKind())) {
                item.setObjective(objectives.collectionQuestionSnapshot(item.getReferenceId()));
                if ("FILL_BLANK".equals(item.getObjective().getQuestionType())) item.setScore(objectives.maxScore(item.getObjective()));
            } else item.setProgramSnapshotId(programs.freezeForCollection(id, item.getKey(), uid, item.getReferenceId()));
            snapshot.getItems().add(item);
        }
        jdbc.update("UPDATE paper_run SET snapshot_json=? WHERE id=?", write(snapshot), id);
        return view(row("paper_run", id, false));
    }

    private void nonce(String value) { require(value != null && value.matches("[A-Za-z0-9_-]{8,64}"), "请求标识无效"); }
    private PaperDTO.Snapshot snapshot(Map<String, Object> run) { return read(run.get("snapshot_json"), PaperDTO.Snapshot.class); }
    private PaperDTO.SnapshotItem item(PaperDTO.Snapshot snapshot, String key) {
        return snapshot.getItems().stream().filter(item -> item.getKey().equals(key)).findFirst().orElseThrow(() -> fail(400, "题目不属于本次答卷"));
    }
    private void editable(Map<String, Object> run) {
        Map<String, Object> window = virtualRow(run);
        if (window != null) {
            mixed.virtualAccess(number(window.get("cid")));
            if (now() >= ((java.util.Date) window.get("ends_at")).getTime()) throw fail(409, "虚拟计时已截止，答卷不能修改");
        } else if (run.get("contest_id") != null) mixed.access(number(run.get("contest_id")), true);
        if (!"IN_PROGRESS".equals(run.get("state"))) throw fail(409, "答卷已交卷，不能修改");
    }
    private void storeAnswers(Long id, Map<String, PaperDTO.SavedAnswer> answers) {
        jdbc.update("UPDATE paper_run SET answers_json=?,gmt_modified=? WHERE id=?", write(answers), new Timestamp(now()), id);
    }
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> run(Long id) {
        Map<String, Object> run = ownedRun(id, true); closeExpired(run); return view(run);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> startContest(Long cid) {
        mixed.contest(cid, true);
        top.hcode.hoj.pojo.entity.contest.Contest contest = mixed.access(cid, false);
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT * FROM paper_run WHERE contest_id=? AND uid=? FOR UPDATE", cid, uid());
        if (!existing.isEmpty()) { closeExpired(existing.get(0)); return view(existing.get(0)); }
        mixed.access(cid, true);
        MixedContestDTO.Template template = mixed.read(mixed.template(cid), MixedContestDTO.Template.class);
        PaperDTO.Snapshot snapshot = template.getSnapshot(); PaperDTO.Paper paper = snapshot.getPaper();
        MixedContestDTO.Config rules = mixed.config(cid);
        paper.setTitle(contest.getTitle()); paper.setProgramMode("LIVE"); paper.setScoreRule(rules.getScoreRule());
        Timestamp time = new Timestamp(now()); String actor = uid();
        long id = insert("INSERT INTO paper_run(paper_id,paper_version,contest_id,uid,request_id,state,snapshot_json,answers_json,gmt_create,gmt_modified) VALUES(?,?,?,?,?,?,?,?,?,?)",
                paper.getId(), paper.getVersion(), cid, actor, "contest_" + UUID.randomUUID().toString(), "IN_PROGRESS", "{}", "{}", time, time);
        for (PaperDTO.SnapshotItem item : snapshot.getItems()) if ("PROGRAMMING".equals(item.getKind()))
            item.setProgramSnapshotId(programs.copy(id, item.getKey(), actor, template.getPrograms().get(item.getKey())));
        jdbc.update("UPDATE paper_run SET snapshot_json=? WHERE id=?", write(snapshot), id);
        return view(row("paper_run", id, false));
    }

    /** Called inside the same transaction that creates the personal participation window. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> startVirtual(Long virtualId) {
        Map<String, Object> window = row("contest_virtual_run", virtualId, true);
        if (!uid().equals(window.get("uid"))) throw fail(404, "虚拟练习不存在");
        Long cid = number(window.get("cid"));
        top.hcode.hoj.pojo.entity.contest.Contest contest = mixed.virtualAccess(cid);
        List<Map<String, Object>> previous = jdbc.queryForList("SELECT * FROM paper_run WHERE virtual_run_id=?", virtualId);
        if (!previous.isEmpty()) { closeExpired(previous.get(0)); return view(previous.get(0)); }
        require(now() < ((java.util.Date) window.get("ends_at")).getTime(), "虚拟练习已截止");
        Map<String, Object> configuration = jdbc.queryForMap("SELECT * FROM contest_paper WHERE contest_id=? FOR UPDATE", cid);
        MixedContestDTO.Template template = mixed.read(configuration.get("template_json"), MixedContestDTO.Template.class);
        MixedContestDTO.Config config = mixed.read(configuration.get("rules_json"), MixedContestDTO.Config.class);
        PaperDTO.Snapshot snapshot = template.getSnapshot(); PaperDTO.Paper paper = snapshot.getPaper();
        paper.setTitle(contest.getTitle()); paper.setProgramMode("LIVE"); paper.setScoreRule(config.getScoreRule());
        long end = ((java.util.Date) window.get("ends_at")).getTime();
        applyVirtualRelease(paper, config, end);
        snapshot.setVirtualContestType(contest.getType());
        Timestamp time = new Timestamp(now()); String actor = uid();
        long id = insert("INSERT INTO paper_run(paper_id,paper_version,virtual_run_id,uid,request_id,state,snapshot_json,answers_json,gmt_create,gmt_modified) VALUES(?,?,?,?,?,?,?,?,?,?)",
                paper.getId(), paper.getVersion(), virtualId, actor, "virtual_" + UUID.randomUUID().toString(), "IN_PROGRESS", "{}", "{}", time, time);
        for (PaperDTO.SnapshotItem item : snapshot.getItems()) if ("PROGRAMMING".equals(item.getKind()))
            item.setProgramSnapshotId(programs.copy(id, item.getKey(), actor, template.getPrograms().get(item.getKey())));
        jdbc.update("UPDATE paper_run SET snapshot_json=? WHERE id=?", write(snapshot), id);
        return view(row("paper_run", id, false));
    }
    private void applyVirtualRelease(PaperDTO.Paper paper, MixedContestDTO.Config config, long end) {
        paper.setAnswerPolicy("NEVER".equals(config.getAnswerPolicy()) ? "NEVER" : "AT_TIME");
        paper.setExplanationPolicy("NEVER".equals(config.getExplanationPolicy()) ? "NEVER" : "AT_TIME");
        paper.setAnswerReleaseAt(Math.max(end, "AT_TIME".equals(config.getAnswerPolicy()) && config.getAnswerReleaseAt() != null ? config.getAnswerReleaseAt() : end));
        paper.setExplanationReleaseAt(Math.max(end, "AT_TIME".equals(config.getExplanationPolicy()) && config.getExplanationReleaseAt() != null ? config.getExplanationReleaseAt() : end));
    }

    private void closeExpired(Map<String, Object> run) {
        if (competition(run) && "IN_PROGRESS".equals(run.get("state"))) {
            Map<String, Object> window = virtualRow(run);
            long end = window != null ? ((java.util.Date) window.get("ends_at")).getTime() : mixed.contest(number(run.get("contest_id")), false).getEndTime().getTime();
            if (now() >= end) {
                jdbc.update("UPDATE paper_run SET state='FINISHED',gmt_modified=? WHERE id=?", new Timestamp(end), run.get("id"));
                run.put("state", "FINISHED"); run.put("gmt_modified", new Timestamp(end));
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void finishExpired(Long id) { closeExpired(row("paper_run", id, true)); }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> answer(Long id, PaperDTO.Answer dto) {
        Map<String, Object> run = ownedRun(id, true); editable(run); require(dto != null, "答案不能为空");
        PaperDTO.SnapshotItem item = item(snapshot(run), dto.getKey());
        Map<String, PaperDTO.SavedAnswer> answers = answers(run);
        PaperDTO.SavedAnswer answer = answers.computeIfAbsent(item.getKey(), key -> new PaperDTO.SavedAnswer());
        if ("OBJECTIVE".equals(item.getKind())) answer.setSelectedAnswers(objectives.normalize(item.getObjective(), dto.getSelectedAnswers(), true));
        else {
            require(dto.getCode() != null && dto.getCode().length() <= 65535, "代码过长或无效");
            if (!dto.getCode().trim().isEmpty()) programs.validate(item.getProgramSnapshotId(), dto.getLanguage(), dto.getCode());
            answer.setCode(dto.getCode()); answer.setLanguage(dto.getLanguage());
        }
        storeAnswers(id, answers); return view(row("paper_run", id, false));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(Long id, PaperDTO.Answer dto) {
        Map<String, Object> run = ownedRun(id, true); editable(run); require(dto != null, "提交内容不能为空"); nonce(dto.getRequestId());
        PaperDTO.Snapshot snapshot = snapshot(run); PaperDTO.SnapshotItem item = item(snapshot, dto.getKey());
        require("PROGRAMMING".equals(item.getKind()) && "LIVE".equals(snapshot.getPaper().getProgramMode()), "本卷只允许交卷后统一判题");
        Map<String, PaperDTO.SavedAnswer> answers = answers(run);
        PaperDTO.SavedAnswer answer = answers.computeIfAbsent(item.getKey(), key -> new PaperDTO.SavedAnswer());
        if (answer.getRequests() == null) answer.setRequests(new LinkedHashMap<>());
        if (answer.getRequests().containsKey(dto.getRequestId())) return view(run);
        require(answer.getRequests().size() < 100, "本题已达到 100 次提交上限");
        programs.rateLimit(uid());
        Long submission;
        if (!competition(run)) submission = programs.submit(id, item.getProgramSnapshotId(), uid(), dto.getLanguage(), dto.getCode());
        else {
            programs.contestAccess();
            submission = programs.submitAs(id, item.getProgramSnapshotId(), uid(), ShiroUtils.getProfile().getUsername(), dto.getLanguage(), dto.getCode());
        }
        answer.setCode(dto.getCode()); answer.setLanguage(dto.getLanguage()); answer.getRequests().put(dto.getRequestId(), submission);
        storeAnswers(id, answers); return view(row("paper_run", id, false));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> finish(Long id) {
        Map<String, Object> run = ownedRun(id, true);
        closeExpired(run);
        if ("FINISHED".equals(run.get("state"))) return view(run);
        editable(run);
        PaperDTO.Snapshot snapshot = snapshot(run);
        if ("AFTER_FINISH".equals(snapshot.getPaper().getProgramMode())) {
            Map<String, PaperDTO.SavedAnswer> answers = answers(run);
            boolean limited = false;
            for (PaperDTO.SnapshotItem item : snapshot.getItems()) {
                PaperDTO.SavedAnswer answer = answers.get(item.getKey());
                if ("PROGRAMMING".equals(item.getKind()) && answer != null && answer.getCode() != null && !answer.getCode().trim().isEmpty()
                        && !CodeforcesSyncManager.isCodeforcesProblem(programs.snapshot(item.getProgramSnapshotId()).getProblem())) {
                    if (!limited) { programs.rateLimit(uid()); limited = true; }
                    programs.submit(id, item.getProgramSnapshotId(), uid(), answer.getLanguage(), answer.getCode());
                }
            }
        }
        jdbc.update("UPDATE paper_run SET state='FINISHED',gmt_modified=? WHERE id=?", new Timestamp(now()), id);
        return view(row("paper_run", id, false));
    }

    public Map<String, Object> histories(Integer page) {
        String uid = uid(); int offset = (Math.max(1, Math.min(100000, page == null ? 1 : page)) - 1) * 20;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,paper_id,paper_version,state,gmt_create,snapshot_json FROM paper_run WHERE uid=? AND contest_id IS NULL AND virtual_run_id IS NULL ORDER BY id DESC LIMIT 20 OFFSET ?", uid, offset);
        List<Map<String, Object>> records = rows.stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>(); result.put("id", row.get("id")); result.put("paperId", row.get("paper_id"));
            result.put("title", snapshot(row).getPaper().getTitle()); result.put("state", row.get("state")); result.put("createdAt", ((java.util.Date) row.get("gmt_create")).getTime()); return result;
        }).collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>(); result.put("records", records);
        result.put("total", jdbc.queryForObject("SELECT COUNT(*) FROM paper_run WHERE uid=? AND contest_id IS NULL AND virtual_run_id IS NULL", Long.class, uid)); return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> retry(Long id) {
        Map<String, Object> run = ownedRun(id, true);
        if (!competition(run)) programs.retry(id, uid()); else programs.retry(id, uid(), true);
        return view(run);
    }

    private boolean released(String policy, Long at, boolean finished) {
        return finished && (!"NEVER".equals(policy)) && (!"AT_TIME".equals(policy) || at != null && now() >= at);
    }
    private boolean pending(Judge judge) { return judge.getStatus() == null || Arrays.asList(5, 6, 7, 9, 15).contains(judge.getStatus()); }
    private double programScore(Judge judge, PaperProgramSnapshotDTO snapshot, int maximum) {
        return MixedContestRankManager.score(judge, snapshot, maximum);
    }
    private Map<String, Object> view(Map<String, Object> run) {
        PaperDTO.Snapshot snapshot = snapshot(run); PaperDTO.Paper frozen = snapshot.getPaper();
        Map<String, Object> window = virtualRow(run);
        boolean competition = competition(run);
        top.hcode.hoj.pojo.entity.contest.Contest contest = competition ? mixed.contest(number(window != null ? window.get("cid") : run.get("contest_id")), false) : null;
        PaperDTO.Paper rules = competition ? frozen : content(paper(number(run.get("paper_id")), false, false));
        MixedContestDTO.Config currentConfig = competition ? mixed.config(contest.getId()) : null;
        if (window != null) applyVirtualRelease(rules, currentConfig, ((java.util.Date) window.get("ends_at")).getTime());
        boolean finished = "FINISHED".equals(run.get("state"));
        boolean answerVisible = released(rules.getAnswerPolicy(), rules.getAnswerReleaseAt(), finished);
        boolean explanationVisible = released(rules.getExplanationPolicy(), rules.getExplanationReleaseAt(), finished);
        MixedContestDTO.Config contestRules = window == null ? currentConfig : null;
        if (contestRules != null) {
            answerVisible = finished && mixed.released(contestRules.getAnswerPolicy(), contestRules.getAnswerReleaseAt(), contest);
            explanationVisible = finished && mixed.released(contestRules.getExplanationPolicy(), contestRules.getExplanationReleaseAt(), contest);
        }
        Map<String, PaperDTO.SavedAnswer> answers = answers(run);
        List<Map<String, Object>> rows = new ArrayList<>(); double totalScore = 0; int maximum = 0, answered = 0; boolean complete = true, gradingFailed = false;
        for (PaperDTO.SnapshotItem item : snapshot.getItems()) {
            maximum += item.getScore(); Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", item.getKey()); row.put("kind", item.getKind()); row.put("referenceId", item.getReferenceId()); row.put("maxScore", item.getScore());
            PaperDTO.SavedAnswer saved = answers.getOrDefault(item.getKey(), new PaperDTO.SavedAnswer());
            if ("OBJECTIVE".equals(item.getKind())) {
                ObjectiveDTO.Question q = item.getObjective();
                List<String> selected = saved.getSelectedAnswers() == null ? Collections.emptyList() : saved.getSelectedAnswers();
                if (selected.stream().anyMatch(value -> !value.trim().isEmpty())) answered++;
                double score = "FILL_BLANK".equals(q.getQuestionType()) ? objectives.score(q, selected) : objectives.score(q, selected) * item.getScore();
                totalScore += score;
                row.put("rating", q.getRating()); row.put("title", q.getTitle()); row.put("version", q.getVersion()); row.put("source", q.getSource()); row.put("stem", q.getStem());
                row.put("questionType", q.getQuestionType()); row.put("options", q.getOptions()); row.put("blanks", objectives.publicBlanks(q)); row.put("selectedAnswers", selected);
                row.put("submitted", finished); row.put("answerVisible", answerVisible); row.put("explanationVisible", explanationVisible);
                if (answerVisible || competition && finished) row.put("score", score);
                if (answerVisible) { row.put("correct", score == item.getScore()); row.put("correctAnswers", objectives.correctAnswers(q)); }
                if (explanationVisible) row.put("explanation", q.getExplanation());
            } else {
                PaperProgramSnapshotDTO program = programs.snapshot(item.getProgramSnapshotId()); Problem p = program.getProblem();
                row.put("rating", p.getRating()); row.put("title", p.getTitle()); row.put("questionType", "PROGRAMMING"); row.put("source", p.getSource()); row.put("problemId", p.getProblemId());
                if (CodeforcesSyncManager.isCodeforcesProblem(p)) row.put("codeforcesProblem", p.getProblemId());
                row.put("stem", p.getDescription()); row.put("input", p.getInput()); row.put("output", p.getOutput()); row.put("examples", p.getExamples());
                row.put("timeLimit", p.getTimeLimit()); row.put("memoryLimit", p.getMemoryLimit()); row.put("languages", program.getLanguages());
                row.put("language", saved.getLanguage()); row.put("code", saved.getCode() == null ? "" : saved.getCode());
                List<Judge> submissions = programs.submissions(number(run.get("id")), item.getProgramSnapshotId(), (String) run.get("uid"));
                if (!submissions.isEmpty() || saved.getCode() != null && !saved.getCode().trim().isEmpty()) answered++;
                boolean failed = submissions.stream().anyMatch(j -> Arrays.asList(4, 10).contains(j.getStatus()));
                boolean ready = submissions.stream().noneMatch(this::pending); complete &= ready && !failed; gradingFailed |= failed;
                double score = 0;
                for (Judge submission : submissions) {
                    double current = programScore(submission, program, item.getScore());
                    score = "BEST".equals(frozen.getScoreRule()) ? Math.max(score, current) : current;
                }
                totalScore += score; row.put("grading", !ready); row.put("submissionCount", submissions.size());
                row.put("gradingFailed", failed);
                boolean feedback = "LIVE".equals(frozen.getProgramMode()) || answerVisible;
                row.put("answerVisible", feedback); row.put("explanationVisible", explanationVisible);
                if (feedback) {
                    List<Map<String, Object>> results = new ArrayList<>();
                    for (Judge submission : submissions) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("id", submission.getSubmitId()); result.put("status", submission.getStatus());
                        if (submission.getVjudgeSubmitId() != null) result.put("codeforcesSubmissionId", Long.toString(submission.getVjudgeSubmitId()));
                        result.put("time", submission.getTime()); result.put("memory", submission.getMemory());
                        if (!pending(submission)) result.put("score", programScore(submission, program, item.getScore()));
                        if (Objects.equals(submission.getStatus(), -2)) {
                            String message = submission.getErrorMessage();
                            result.put("message", message == null ? "" : message.substring(0, Math.min(8000, message.length())));
                        }
                        results.add(result);
                    }
                    row.put("submissions", results);
                    if (ready && !failed) { row.put("score", score); row.put("correct", score == item.getScore()); }
                }
                if (explanationVisible) row.put("explanation", p.getHint());
            }
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", run.get("id")); result.put("paperId", frozen.getId()); result.put("paperVersion", frozen.getVersion());
        result.put("title", frozen.getTitle()); result.put("description", frozen.getDescription()); result.put("state", run.get("state"));
        result.put("programMode", frozen.getProgramMode()); result.put("scoreRule", frozen.getScoreRule()); result.put("items", rows);
        result.put("answeredCount", answered); result.put("maxScore", maximum); result.put("gradingComplete", complete);
        result.put("gradingFailed", gradingFailed);
        result.put("answerPolicy", rules.getAnswerPolicy()); result.put("explanationPolicy", rules.getExplanationPolicy());
        result.put("answerReleaseAt", rules.getAnswerReleaseAt()); result.put("explanationReleaseAt", rules.getExplanationReleaseAt());
        if ((answerVisible || competition && finished) && complete) result.put("score", Math.round(totalScore * 100) / 100.0);
        if (competition) {
            result.put("contestId", contest.getId()); result.put("contestType", contest.getType());
            result.put("endTime", window == null ? contest.getEndTime().getTime() : ((java.util.Date) window.get("ends_at")).getTime()); result.put("serverNow", now());
            if (contestRules != null) {
                result.put("answerPolicy", contestRules.getAnswerPolicy()); result.put("explanationPolicy", contestRules.getExplanationPolicy());
                result.put("answerReleaseAt", contestRules.getAnswerReleaseAt()); result.put("explanationReleaseAt", contestRules.getExplanationReleaseAt());
            }
            if (window != null) { result.put("virtual", true); result.put("virtualRunId", window.get("id")); result.put("contestType", snapshot.getVirtualContestType()); }
        }
        return result;
    }
}

