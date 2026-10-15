package top.hcode.hoj.manager.oj;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.mapper.*;
import top.hcode.hoj.pojo.dto.ObjectiveDTO;
import top.hcode.hoj.pojo.entity.objective.*;
import top.hcode.hoj.pojo.entity.problem.Tag;
import top.hcode.hoj.utils.ShiroUtils;

import java.util.*;
import java.util.stream.Collectors;

/** Owns objective practice; never writes programming submissions or contest ranks. */
@Component
public class ObjectiveManager {
    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
            "SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE"));
    private static final Set<String> POLICIES = new HashSet<>(Arrays.asList(
            "AFTER_ANSWER", "AFTER_FINISH", "AT_TIME", "NEVER"));
    private static final TypeReference<List<Long>> IDS = new TypeReference<List<Long>>() {};
    private static final TypeReference<List<ObjectiveDTO.Question>> QUESTIONS =
            new TypeReference<List<ObjectiveDTO.Question>>() {};
    private static final TypeReference<Map<String, List<String>>> ANSWERS =
            new TypeReference<Map<String, List<String>>>() {};

    @Autowired private ObjectiveQuestionMapper questionMapper;
    @Autowired private ObjectiveQuestionVersionMapper versionMapper;
    @Autowired private ObjectiveSetMapper setMapper;
    @Autowired private ObjectiveRunMapper runMapper;
    @Autowired private TagMapper tagMapper;
    @Autowired private ObjectMapper json;

    protected String currentUid() {
        if (ShiroUtils.getProfile() == null) {
            throw new ObjectiveException(401, "请先登录");
        }
        return ShiroUtils.getProfile().getUid();
    }

    protected boolean canManageAll() {
        return SecurityUtils.getSubject().hasRole("root")
                || SecurityUtils.getSubject().hasRole("problem_admin");
    }

    protected long now() {
        return System.currentTimeMillis();
    }

    private ObjectiveException fail(int status, String message) {
        return new ObjectiveException(status, message);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw fail(400, message);
    }

    private String text(String value, int max, String name, boolean required) {
        String result = value == null ? "" : value.trim();
        require(!required || !result.isEmpty(), name + "不能为空");
        require(result.length() <= max, name + "过长");
        return result;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("客观题数据序列化失败", e);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("客观题数据读取失败", e);
        }
    }

    private ObjectiveDTO.Question content(ObjectiveQuestion question) {
        try {
            return json.readValue(question.getContentJson(), ObjectiveDTO.Question.class);
        } catch (Exception e) {
            throw new IllegalStateException("客观题版本读取失败", e);
        }
    }

    private void owner(String createdBy) {
        if (!canManageAll() && !currentUid().equals(createdBy)) {
            throw fail(403, "只能管理自己创建的客观题或题集");
        }
    }

    private int pageSize(Integer limit) {
        return limit == null ? 20 : Math.min(100, Math.max(1, limit));
    }

    private int pageNumber(Integer page) {
        return page == null ? 1 : Math.max(1, page);
    }

    private Map<String, Object> page(List<?> records, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        return result;
    }

    public Map<String, Object> questions(Integer currentPage, Integer limit, String keyword, String type) {
        QueryWrapper<ObjectiveQuestion> query = new QueryWrapper<>();
        query.select("id", "title", "question_type", "version", "published");
        query.eq(!canManageAll(), "created_by", currentUid())
                .like(keyword != null && !keyword.trim().isEmpty(), "title", text(keyword, 100, "关键词", false))
                .eq(type != null && !type.isEmpty(), "question_type", type)
                .orderByDesc("id");
        IPage<ObjectiveQuestion> result = questionMapper.selectPage(
                new Page<>(pageNumber(currentPage), pageSize(limit)), query);
        List<Map<String, Object>> rows = result.getRecords().stream().map(q -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", q.getId());
            row.put("title", q.getTitle());
            row.put("questionType", q.getQuestionType());
            row.put("version", q.getVersion());
            row.put("published", q.getPublished());
            return row;
        }).collect(Collectors.toList());
        return page(rows, result.getTotal());
    }

    public ObjectiveDTO.Question question(Long id) {
        ObjectiveQuestion question = questionMapper.selectById(id);
        if (question == null) throw fail(404, "客观题不存在");
        owner(question.getCreatedBy());
        return content(question);
    }

    void validateQuestion(ObjectiveDTO.Question dto) {
        require(dto != null, "题目不能为空");
        dto.setTitle(text(dto.getTitle(), 200, "标题", true));
        dto.setStem(text(dto.getStem(), 30000, "题干", true));
        dto.setExplanation(text(dto.getExplanation(), 30000, "解析", false));
        require(TYPES.contains(dto.getQuestionType()), "仅支持单选、多选和判断题");
        if ("TRUE_FALSE".equals(dto.getQuestionType())) {
            List<ObjectiveDTO.Option> options = new ArrayList<>();
            for (String id : Arrays.asList("TRUE", "FALSE")) {
                ObjectiveDTO.Option option = new ObjectiveDTO.Option();
                option.setId(id);
                option.setText("TRUE".equals(id) ? "正确" : "错误");
                options.add(option);
            }
            dto.setOptions(options);
        }
        require(dto.getOptions() != null && dto.getOptions().size() >= 2
                && dto.getOptions().size() <= 10, "选项数量须为 2 到 10 个");
        Set<String> ids = new HashSet<>();
        for (ObjectiveDTO.Option option : dto.getOptions()) {
            require(option != null && option.getId() != null
                    && option.getId().matches("[A-Z]{1,5}"), "选项标识须为大写字母");
            require(ids.add(option.getId()), "选项标识不能重复");
            option.setText(text(option.getText(), 4000, "选项", true));
        }
        dto.setCorrectAnswers(normalize(dto, dto.getCorrectAnswers(), false));
        dto.setPublished(Boolean.TRUE.equals(dto.getPublished()));
        if (dto.getTagIds() == null) dto.setTagIds(new ArrayList<>());
        require(dto.getTagIds().size() <= 20
                && !dto.getTagIds().contains(null)
                && new HashSet<>(dto.getTagIds()).size() == dto.getTagIds().size(), "知识点无效或重复");
        if (!dto.getTagIds().isEmpty()) {
            List<Tag> tags = tagMapper.selectBatchIds(dto.getTagIds());
            require(tags.size() == dto.getTagIds().size()
                    && tags.stream().allMatch(tag -> tag.getGid() == null), "只能选择现有公共知识点");
        }
    }

    List<String> normalize(ObjectiveDTO.Question question, List<String> selected, boolean allowEmpty) {
        require(selected != null, "请选择答案");
        Set<String> valid = question.getOptions().stream()
                .map(ObjectiveDTO.Option::getId).collect(Collectors.toSet());
        require(!selected.contains(null) && new HashSet<>(selected).size() == selected.size()
                && valid.containsAll(selected), "答案包含非法或重复选项");
        if (allowEmpty && selected.isEmpty()) return new ArrayList<>();
        require(!selected.isEmpty(), "请选择答案");
        if (!"MULTIPLE_CHOICE".equals(question.getQuestionType())) {
            require(selected.size() == 1, "单选和判断题只能选择一个答案");
        }
        List<String> result = new ArrayList<>(selected);
        Collections.sort(result);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public ObjectiveDTO.Question saveQuestion(ObjectiveDTO.Question dto) {
        validateQuestion(dto);
        ObjectiveQuestion question;
        if (dto.getId() == null) {
            question = new ObjectiveQuestion();
            question.setCreatedBy(currentUid());
            question.setGmtCreate(new Date(now()));
            question.setVersion(1);
        } else {
            question = questionMapper.selectLocked(dto.getId());
            if (question == null) throw fail(404, "客观题不存在");
            owner(question.getCreatedBy());
            if (!question.getVersion().equals(dto.getVersion())) throw fail(409, "题目已更新，请刷新后重试");
            question.setVersion(question.getVersion() + 1);
        }
        question.setTitle(dto.getTitle());
        question.setQuestionType(dto.getQuestionType());
        question.setPublished(dto.getPublished());
        question.setGmtModified(new Date(now()));
        // Insert first to obtain the authoritative ID; the same transaction appends its immutable revision.
        if (question.getId() == null) {
            question.setContentJson("{}");
            questionMapper.insert(question);
        }
        dto.setId(question.getId());
        dto.setVersion(question.getVersion());
        question.setContentJson(write(dto));
        questionMapper.updateById(question);
        ObjectiveQuestionVersion version = new ObjectiveQuestionVersion();
        version.setQuestionId(question.getId());
        version.setVersion(question.getVersion());
        version.setContentJson(question.getContentJson());
        version.setGmtCreate(new Date(now()));
        versionMapper.insert(version);
        return dto;
    }

    public Map<String, Object> sets(Integer currentPage, Integer limit, String keyword, String mode, boolean admin) {
        QueryWrapper<ObjectiveSet> query = new QueryWrapper<>();
        if (admin) query.eq(!canManageAll(), "created_by", currentUid());
        else query.eq("published", true);
        query.like(keyword != null && !keyword.trim().isEmpty(), "title", text(keyword, 100, "关键词", false))
                .eq(mode != null && !mode.isEmpty(), "mode", mode).orderByDesc("id");
        IPage<ObjectiveSet> result = setMapper.selectPage(
                new Page<>(pageNumber(currentPage), pageSize(limit)), query);
        return page(result.getRecords().stream().map(this::setView).collect(Collectors.toList()), result.getTotal());
    }

    private Map<String, Object> setView(ObjectiveSet set) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", set.getId());
        result.put("title", set.getTitle());
        result.put("description", set.getDescription());
        result.put("mode", set.getMode());
        result.put("version", set.getVersion());
        result.put("published", set.getPublished());
        List<Long> ids = read(set.getQuestionIdsJson(), IDS);
        result.put("questionIds", ids);
        result.put("questionCount", ids.size());
        result.put("answerPolicy", set.getAnswerPolicy());
        result.put("explanationPolicy", set.getExplanationPolicy());
        result.put("answerReleaseAt", set.getAnswerReleaseAt());
        result.put("explanationReleaseAt", set.getExplanationReleaseAt());
        return result;
    }

    public Map<String, Object> set(Long id, boolean admin) {
        ObjectiveSet set = setMapper.selectById(id);
        if (set == null) throw fail(404, "题集不存在");
        if (admin) owner(set.getCreatedBy());
        else requirePublished(set);
        return setView(set);
    }

    private void requirePublished(ObjectiveSet set) {
        if (!Boolean.TRUE.equals(set.getPublished())) throw fail(403, "该题集暂未开放");
    }

    private void validatePolicy(String policy, Long time) {
        require(POLICIES.contains(policy), "开放条件无效");
        require(!"AT_TIME".equals(policy) || time != null && time > 0, "请设置有效的开放时间");
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveSet(ObjectiveDTO.ProblemSet dto) {
        require(dto != null, "题集不能为空");
        dto.setTitle(text(dto.getTitle(), 200, "标题", true));
        dto.setDescription(text(dto.getDescription(), 5000, "说明", false));
        require("PRACTICE".equals(dto.getMode()) || "PAPER".equals(dto.getMode()),
                "仅开放练习和整卷答题；比赛使用独立规则");
        validatePolicy(dto.getAnswerPolicy(), dto.getAnswerReleaseAt());
        validatePolicy(dto.getExplanationPolicy(), dto.getExplanationReleaseAt());
        require(dto.getQuestionIds() != null && !dto.getQuestionIds().isEmpty()
                && dto.getQuestionIds().size() <= 50 && !dto.getQuestionIds().contains(null)
                && new HashSet<>(dto.getQuestionIds()).size() == dto.getQuestionIds().size(),
                "题集须包含 1 到 50 道不重复的题目");
        List<ObjectiveQuestion> questions = questionMapper.selectBatchIds(dto.getQuestionIds());
        require(questions.size() == dto.getQuestionIds().size(), "部分题目已不存在");
        for (ObjectiveQuestion question : questions) {
            owner(question.getCreatedBy());
            require(!Boolean.TRUE.equals(dto.getPublished()) || Boolean.TRUE.equals(question.getPublished()),
                    "发布题集前，请先发布其中所有题目");
        }
        ObjectiveSet set;
        if (dto.getId() == null) {
            set = new ObjectiveSet();
            set.setCreatedBy(currentUid());
            set.setGmtCreate(new Date(now()));
            set.setVersion(1);
        } else {
            set = setMapper.selectLocked(dto.getId());
            if (set == null) throw fail(404, "题集不存在");
            owner(set.getCreatedBy());
            if (!set.getVersion().equals(dto.getVersion())) throw fail(409, "题集已更新，请刷新后重试");
            require(set.getMode().equals(dto.getMode())
                    || runMapper.selectCount(new QueryWrapper<ObjectiveRun>().eq("set_id", set.getId())) == 0,
                    "已有作答记录的题集不能更改模式，请另建题集");
            set.setVersion(set.getVersion() + 1);
        }
        set.setTitle(dto.getTitle());
        set.setDescription(dto.getDescription());
        set.setMode(dto.getMode());
        set.setQuestionIdsJson(write(dto.getQuestionIds()));
        set.setPublished(Boolean.TRUE.equals(dto.getPublished()));
        set.setAnswerPolicy(dto.getAnswerPolicy());
        set.setExplanationPolicy(dto.getExplanationPolicy());
        set.setAnswerReleaseAt("AT_TIME".equals(dto.getAnswerPolicy()) ? dto.getAnswerReleaseAt() : null);
        set.setExplanationReleaseAt("AT_TIME".equals(dto.getExplanationPolicy()) ? dto.getExplanationReleaseAt() : null);
        set.setGmtModified(new Date(now()));
        if (set.getId() == null) setMapper.insert(set);
        else setMapper.updateById(set);
        return setView(set);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> start(ObjectiveDTO.StartRun dto) {
        require(dto != null && dto.getSetId() != null, "请选择题集");
        require(dto.getRequestId() != null && dto.getRequestId().matches("[A-Za-z0-9_-]{8,64}"),
                "开卷请求标识无效");
        String uid = currentUid();
        // Serialize starts with changes to set mode/publishing and make retries idempotent.
        ObjectiveSet set = setMapper.selectLocked(dto.getSetId());
        if (set == null) throw fail(404, "题集不存在");
        requirePublished(set);
        QueryWrapper<ObjectiveRun> duplicate = new QueryWrapper<ObjectiveRun>()
                .eq("uid", uid).eq("request_id", dto.getRequestId());
        ObjectiveRun existing = runMapper.selectOne(duplicate);
        if (existing != null) {
            if (!existing.getSetId().equals(dto.getSetId())) throw fail(409, "请求标识已用于其他题集");
            return runView(existing, set);
        }
        List<Long> ids = read(set.getQuestionIdsJson(), IDS);
        List<ObjectiveDTO.Question> snapshot = new ArrayList<>();
        for (Long id : ids) {
            ObjectiveQuestion question = questionMapper.selectById(id);
            require(question != null && Boolean.TRUE.equals(question.getPublished()), "题集中有题目暂未开放");
            snapshot.add(content(question));
        }
        ObjectiveRun run = new ObjectiveRun();
        run.setSetId(set.getId());
        run.setSetVersion(set.getVersion());
        run.setUid(uid);
        run.setRequestId(dto.getRequestId());
        run.setMode(set.getMode());
        run.setTitle(set.getTitle());
        run.setState("IN_PROGRESS");
        run.setSnapshotJson(write(snapshot));
        run.setAnswersJson("{}");
        run.setGmtCreate(new Date(now()));
        run.setGmtModified(new Date(now()));
        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException e) {
            ObjectiveRun retry = runMapper.selectOne(duplicate);
            if (retry == null || !retry.getSetId().equals(dto.getSetId())) throw fail(409, "请求标识已使用");
            return runView(retry, set);
        }
        return runView(run, set);
    }

    private ObjectiveRun ownedRun(Long id, boolean locked) {
        ObjectiveRun run = locked ? runMapper.selectLocked(id) : runMapper.selectById(id);
        if (run == null || !currentUid().equals(run.getUid())) throw fail(404, "答题记录不存在");
        return run;
    }

    private ObjectiveSet publishedSet(ObjectiveRun run) {
        ObjectiveSet set = setMapper.selectById(run.getSetId());
        if (set == null) throw fail(404, "题集不存在");
        requirePublished(set);
        return set;
    }

    public Map<String, Object> run(Long id) {
        ObjectiveRun run = ownedRun(id, false);
        return runView(run, publishedSet(run));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> answer(Long id, ObjectiveDTO.Answer dto) {
        ObjectiveRun run = ownedRun(id, true);
        ObjectiveSet set = publishedSet(run);
        require(dto != null && dto.getQuestionId() != null, "请选择题目");
        List<ObjectiveDTO.Question> questions = read(run.getSnapshotJson(), QUESTIONS);
        ObjectiveDTO.Question question = questions.stream().filter(q -> q.getId().equals(dto.getQuestionId()))
                .findFirst().orElseThrow(() -> fail(400, "题目不属于本次作答"));
        boolean paper = "PAPER".equals(run.getMode());
        List<String> selected = normalize(question, dto.getSelectedAnswers(), paper);
        Map<String, List<String>> answers = read(run.getAnswersJson(), ANSWERS);
        String key = question.getId().toString();
        if (!paper && answers.containsKey(key)) {
            if (!answers.get(key).equals(selected)) throw fail(409, "本题已提交，请新建练习后重答");
            return runView(run, set);
        }
        if (!"IN_PROGRESS".equals(run.getState())) throw fail(409, "已交卷，不能修改答案");
        if (selected.isEmpty()) answers.remove(key);
        else answers.put(key, selected);
        run.setAnswersJson(write(answers));
        run.setGmtModified(new Date(now()));
        runMapper.updateById(run);
        return runView(run, set);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> finish(Long id) {
        ObjectiveRun run = ownedRun(id, true);
        ObjectiveSet set = publishedSet(run);
        if (!"FINISHED".equals(run.getState())) {
            run.setState("FINISHED");
            run.setGmtModified(new Date(now()));
            runMapper.updateById(run);
        }
        return runView(run, set);
    }

    public Map<String, Object> runs(Integer currentPage, Integer limit) {
        IPage<ObjectiveRun> result = runMapper.selectPage(
                new Page<>(pageNumber(currentPage), pageSize(limit)),
                new QueryWrapper<ObjectiveRun>().select("id", "set_id", "title", "mode", "state", "gmt_create")
                        .eq("uid", currentUid()).orderByDesc("id"));
        List<Map<String, Object>> records = result.getRecords().stream().map(run -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", run.getId());
            row.put("setId", run.getSetId());
            row.put("title", run.getTitle());
            row.put("mode", run.getMode());
            row.put("state", run.getState());
            row.put("createdAt", run.getGmtCreate().getTime());
            // History summaries do not reveal correctness through counts or filters.
            return row;
        }).collect(Collectors.toList());
        return page(records, result.getTotal());
    }

    boolean released(String policy, Long at, boolean submitted, boolean finished, long time) {
        if (!submitted) return false;
        if ("AFTER_ANSWER".equals(policy)) return true;
        if ("AFTER_FINISH".equals(policy)) return finished;
        return "AT_TIME".equals(policy) && at != null && time >= at;
    }

    Map<String, Object> runView(ObjectiveRun run, ObjectiveSet set) {
        List<ObjectiveDTO.Question> questions = read(run.getSnapshotJson(), QUESTIONS);
        Map<String, List<String>> answers = read(run.getAnswersJson(), ANSWERS);
        boolean finished = "FINISHED".equals(run.getState());
        long time = now();
        int correctCount = 0;
        boolean allResultsVisible = finished;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ObjectiveDTO.Question question : questions) {
            List<String> selected = answers.getOrDefault(question.getId().toString(), Collections.emptyList());
            boolean submitted = finished || "PRACTICE".equals(run.getMode()) && !selected.isEmpty();
            boolean answerVisible = released(set.getAnswerPolicy(), set.getAnswerReleaseAt(), submitted, finished, time);
            boolean explanationVisible = released(set.getExplanationPolicy(), set.getExplanationReleaseAt(),
                    submitted, finished, time);
            boolean correct = !selected.isEmpty() && selected.equals(question.getCorrectAnswers());
            if (correct) correctCount++;
            allResultsVisible &= answerVisible;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", question.getId());
            row.put("version", question.getVersion());
            row.put("title", question.getTitle());
            row.put("questionType", question.getQuestionType());
            row.put("stem", question.getStem());
            row.put("options", question.getOptions());
            row.put("tagIds", question.getTagIds());
            row.put("selectedAnswers", selected);
            row.put("submitted", submitted);
            row.put("answerVisible", answerVisible);
            row.put("explanationVisible", explanationVisible);
            if (answerVisible) {
                row.put("correctAnswers", question.getCorrectAnswers());
                row.put("correct", correct);
                row.put("score", correct ? 1 : 0);
            }
            if (explanationVisible) row.put("explanation", question.getExplanation());
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", run.getId());
        result.put("setId", run.getSetId());
        result.put("setVersion", run.getSetVersion());
        result.put("title", run.getTitle());
        result.put("mode", run.getMode());
        result.put("state", run.getState());
        result.put("questions", rows);
        result.put("answeredCount", answers.size());
        result.put("createdAt", run.getGmtCreate().getTime());
        result.put("answerPolicy", set.getAnswerPolicy());
        result.put("explanationPolicy", set.getExplanationPolicy());
        result.put("answerReleaseAt", set.getAnswerReleaseAt());
        result.put("explanationReleaseAt", set.getExplanationReleaseAt());
        if (allResultsVisible) {
            result.put("correctCount", correctCount);
            result.put("score", correctCount);
            result.put("maxScore", questions.size());
        }
        return result;
    }
}
