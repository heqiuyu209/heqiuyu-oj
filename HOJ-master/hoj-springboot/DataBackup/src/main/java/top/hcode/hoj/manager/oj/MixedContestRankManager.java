package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.hcode.hoj.pojo.dto.*;
import top.hcode.hoj.pojo.entity.contest.Contest;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.pojo.entity.problem.Problem;
import java.util.*;
import java.util.stream.Collectors;

/** Uses submission acceptance time, never asynchronous judge completion time, for ACM penalties. */
@Component
public class MixedContestRankManager {
    @Autowired private MixedContestManager mixed;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private ObjectiveManager objectives;
    @Autowired private top.hcode.hoj.validator.ContestValidator validator;
    protected long now() { return System.currentTimeMillis(); }

    public Map<String, Object> rank(Long cid) {
        Contest contest = mixed.access(cid, false);
        boolean sealed = validator.isSealRank(mixed.actor().getUid(), contest, false, mixed.root());
        return calculate(contest, sealed);
    }

    Map<String, Object> calculate(Contest contest, boolean sealed) {
        Long cid = contest.getId();
        long cutoff = Math.min(now(), contest.getEndTime().getTime());
        if (sealed && contest.getSealRankTime() != null) cutoff = Math.min(cutoff, contest.getSealRankTime().getTime());
        List<Map<String, Object>> runs = jdbc.queryForList("SELECT r.*,u.username,u.nickname,u.realname FROM paper_run r JOIN user_info u ON u.uuid=r.uid WHERE r.contest_id=? AND r.uid<>? AND NOT EXISTS (SELECT 1 FROM user_role ur JOIN role ro ON ro.id=ur.role_id WHERE ur.uid=r.uid AND ro.role='root')", cid, contest.getUid());
        Map<Long, List<Map<String, Object>>> judges = jdbc.queryForList("SELECT j.* FROM judge j JOIN paper_run r ON r.id=j.paper_run_id WHERE r.contest_id=? AND j.submit_time>=? AND j.submit_time<? ORDER BY j.submit_time,j.submit_id",
                cid, contest.getStartTime(), new java.sql.Timestamp(cutoff)).stream().collect(Collectors.groupingBy(j -> ((Number) j.get("paper_run_id")).longValue()));
        MixedContestDTO.Template template = mixed.read(mixed.template(cid), MixedContestDTO.Template.class);
        MixedContestDTO.Config rules = mixed.config(cid);
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> run : runs) {
            PaperDTO.Snapshot snapshot = mixed.read(run.get("snapshot_json"), PaperDTO.Snapshot.class);
            Map<String, PaperDTO.SavedAnswer> answers;
            try { answers = json.readValue((String) run.get("answers_json"), new TypeReference<Map<String, PaperDTO.SavedAnswer>>() {}); }
            catch (Exception e) { throw new IllegalStateException(e); }
            boolean finished = "FINISHED".equals(run.get("state")) && (!sealed || ((Date) run.get("gmt_modified")).getTime() <= cutoff);
            double objectiveScore = 0, programScore = 0; int ac = 0; long penalty = 0; boolean pending = false, failed = false;
            for (PaperDTO.SnapshotItem item : snapshot.getItems()) {
                if ("OBJECTIVE".equals(item.getKind())) {
                    if (finished && answers.containsKey(item.getKey())) {
                        List<String> selected = answers.get(item.getKey()).getSelectedAnswers();
                        if (selected == null) selected = Collections.emptyList();
                        double score = objectives.score(item.getObjective(), selected);
                        objectiveScore += "FILL_BLANK".equals(item.getObjective().getQuestionType()) ? score : score * item.getScore();
                    }
                } else {
                    int wrong = 0; boolean solved = false; double best = 0;
                    for (Map<String, Object> submission : judges.getOrDefault(((Number) run.get("id")).longValue(), Collections.emptyList())) {
                        if (((Number) submission.get("paper_snapshot_id")).longValue() != item.getProgramSnapshotId()) continue;
                        Integer status = submission.get("status") == null ? null : ((Number) submission.get("status")).intValue();
                        pending |= status == null || Arrays.asList(5, 6, 7, 9, 15).contains(status);
                        failed |= Arrays.asList(4, 10).contains(status);
                        Judge judge = new Judge().setStatus(status).setScore(submission.get("score") == null ? null : ((Number) submission.get("score")).intValue());
                        double score = score(judge, template.getPrograms().get(item.getKey()), item.getScore());
                        best = "BEST".equals(rules.getScoreRule()) ? Math.max(best, score) : score;
                        if (!solved && Objects.equals(status, 0)) {
                            solved = true; ac++;
                            penalty += Math.max(0, (((Date) submission.get("submit_time")).getTime() - contest.getStartTime().getTime()) / 1000) + wrong * 1200L;
                        } else if (!solved && Arrays.asList(-3, -1, 1, 2, 3).contains(status)) wrong++;
                    }
                    programScore += best;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uid", run.get("uid")); row.put("username", run.get("username"));
            String nameKey = Arrays.asList("nickname", "realname").contains(contest.getRankShowName()) ? contest.getRankShowName() : "username";
            Object displayName = run.get(nameKey); row.put("name", displayName == null || displayName.toString().isEmpty() ? run.get("username") : displayName);
            row.put("objectiveScore", objectiveScore); row.put("programScore", programScore); row.put("score", objectiveScore + programScore);
            row.put("ac", ac); row.put("penalty", penalty); row.put("finished", finished); row.put("pending", pending); row.put("failed", failed);
            records.add(row);
        }
        Comparator<Map<String, Object>> order = Objects.equals(contest.getType(), 0)
                ? Comparator.<Map<String, Object>>comparingDouble(row -> ((Number) row.get("objectiveScore")).doubleValue()).reversed()
                    .thenComparing(Comparator.<Map<String, Object>>comparingInt(row -> (Integer) row.get("ac")).reversed()).thenComparingLong(row -> (Long) row.get("penalty"))
                : Comparator.<Map<String, Object>>comparingDouble(row -> ((Number) row.get("score")).doubleValue()).reversed();
        records.sort(order.thenComparing(row -> (String) row.get("username")));
        Map<String, Object> previous = null; int position = 0, rank = 0;
        for (Map<String, Object> row : records) { position++; if (previous == null || order.compare(previous, row) != 0) rank = position; row.put("rank", rank); previous = row; }
        Map<String, Object> result = new LinkedHashMap<>(); result.put("records", records); result.put("type", contest.getType()); result.put("sealed", sealed); return result;
    }

    public static double score(Judge judge, PaperProgramSnapshotDTO snapshot, int maximum) {
        if (Objects.equals(judge.getStatus(), 0)) return maximum;
        Problem problem = snapshot.getProblem();
        if (!Objects.equals(problem.getType(), 1) || problem.getIoScore() == null || problem.getIoScore() <= 0 || judge.getScore() == null) return 0;
        return Math.round(Math.max(0, Math.min(1, judge.getScore() * 1.0 / problem.getIoScore())) * maximum * 100) / 100.0;
    }
}
