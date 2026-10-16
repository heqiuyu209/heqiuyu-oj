package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.hcode.hoj.dao.contest.ContestEntityService;
import top.hcode.hoj.pojo.dto.ObjectiveDTO;
import top.hcode.hoj.pojo.entity.contest.Contest;
import top.hcode.hoj.pojo.vo.ACMContestRankVO;
import top.hcode.hoj.pojo.vo.OIContestRankVO;

import java.util.*;
import java.util.stream.Collectors;

/** Deterministic replay lets rejudging/recalibration repair subsequent ratings as well. */
@Component
public class SiteRatingManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private ObjectiveManager objectives;
    @Autowired private ContestEntityService contests;
    @Autowired private ContestCalculateRankManager ranks;
    @Autowired private MixedContestRankManager mixedRanks;

    private static final String ACTIVE = "SELECT u.uuid FROM user_info u WHERE u.status=0 AND NOT EXISTS (SELECT 1 FROM user_role ur JOIN role ro ON ro.id=ur.role_id WHERE ur.uid=u.uuid AND ro.role='root')";

    private static class Profile {
        int rating, peak, delta, count;
        Profile(int initial) { rating = initial; peak = initial; }
    }
    private static class Event {
        String uid, key, title;
        Date time;
        int rating;
        Event(String uid, String key, String title, Date time, int rating) {
            this.uid = uid; this.key = key; this.title = title; this.time = time; this.rating = rating;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void rebuild() throws Exception {
        // A database row lock serializes jobs on all backend replicas; readers see the previous full snapshot.
        jdbc.queryForObject("SELECT id FROM site_rating_lock WHERE id=1 FOR UPDATE", Integer.class);
        Set<String> active = new HashSet<>(jdbc.queryForList(ACTIVE, String.class));
        List<Object[]> history = new ArrayList<>();
        Map<String, Profile> practice = new HashMap<>(), competition = new HashMap<>();
        List<Event> events = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT j.uid,p.id,p.title,p.rating,MIN(j.submit_time) AS solved_at FROM judge j JOIN problem p ON p.id=j.pid JOIN user_info u ON u.uuid=j.uid WHERE j.paper_run_id IS NULL AND j.cid=0 AND j.gid IS NULL AND j.status=0 AND p.auth=1 AND p.is_group=0 AND p.rating BETWEEN 0 AND 5000 AND (p.author IS NULL OR p.author<>u.username) GROUP BY j.uid,p.id,p.title,p.rating")) {
            events.add(new Event((String) row.get("uid"), "PROGRAMMING:" + row.get("id"), (String) row.get("title"), (Date) row.get("solved_at"), ((Number) row.get("rating")).intValue()));
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT r.*,q.content_json AS current_question_json FROM objective_run r JOIN objective_question q ON q.id=r.question_id WHERE r.mode='PRACTICE' AND r.set_id IS NULL AND q.published=1 AND q.practice_enabled=1 AND q.created_by<>r.uid AND r.answers_json<>'{}' ORDER BY r.gmt_modified,r.id")) {
            Integer taskRating = json.readValue((String) row.get("current_question_json"), ObjectiveDTO.Question.class).getRating();
            if (taskRating == null || taskRating < 0 || taskRating > 5000) continue;
            List<ObjectiveDTO.Question> snapshot = json.readValue((String) row.get("snapshot_json"), new TypeReference<List<ObjectiveDTO.Question>>() {});
            Map<String, List<String>> answers = json.readValue((String) row.get("answers_json"), new TypeReference<Map<String, List<String>>>() {});
            for (ObjectiveDTO.Question q : snapshot) {
                List<String> selected = answers.get(q.getId().toString());
                if (selected != null && !selected.isEmpty() && objectives.score(q, selected) == objectives.maxScore(q))
                    events.add(new Event((String) row.get("uid"), "OBJECTIVE:" + q.getId(), q.getTitle(), (Date) row.get("gmt_modified"), taskRating));
            }
        }
        events.sort(Comparator.comparing((Event e) -> e.time).thenComparing(e -> e.key).thenComparing(e -> e.uid));
        Set<String> solved = new HashSet<>();
        for (Event event : events) if (active.contains(event.uid) && solved.add(event.uid + ":" + event.key)) {
            Profile profile = practice.computeIfAbsent(event.uid, uid -> new Profile(SiteRatingCalculator.PRACTICE_INITIAL));
            apply(profile, SiteRatingCalculator.practice(profile.rating, event.rating), event.uid, "practice", event.key, event.title, event.time, null, history);
        }

        List<Long> ids = jdbc.queryForList("SELECT id FROM contest WHERE rated=1 AND visible=1 AND is_group=0 AND gid IS NULL AND end_time<=? ORDER BY end_time,id", Long.class, new java.sql.Timestamp(System.currentTimeMillis()));
        for (Long cid : ids) {
            Contest contest = contests.getById(cid);
            // Keep the chronological prefix settled. A later contest must not use an incomplete prior rating.
            if (contest == null || !ready(contest)) break;
            List<Map<String, Object>> standings = standings(contest);
            standings.removeIf(row -> !active.contains(row.get("uid")) || Objects.equals(contest.getUid(), row.get("uid")));
            if (standings.size() < 2) continue;
            List<SiteRatingCalculator.Player> players = new ArrayList<>();
            for (int begin = 0; begin < standings.size();) {
                int end = begin + 1;
                while (end < standings.size() && Objects.equals(standings.get(begin).get("rank"), standings.get(end).get("rank"))) end++;
                // Ties occupy the average of their positions, including after excluded staff are removed.
                double place = (begin + 1 + end) / 2.0;
                for (int index = begin; index < end; index++) {
                    String uid = (String) standings.get(index).get("uid");
                    Profile profile = competition.computeIfAbsent(uid, key -> new Profile(SiteRatingCalculator.CONTEST_INITIAL));
                    players.add(new SiteRatingCalculator.Player(uid, profile.rating, place));
                }
                begin = end;
            }
            Map<String, Integer> deltas = SiteRatingCalculator.contest(players);
            for (SiteRatingCalculator.Player player : players) {
                Profile profile = competition.get(player.uid);
                apply(profile, profile.rating + deltas.get(player.uid), player.uid, "contest", "CONTEST:" + cid, contest.getTitle(), contest.getEndTime(), player.place, history);
            }
        }
        jdbc.update("DELETE FROM site_rating_profile");
        jdbc.update("DELETE FROM site_rating_history");
        saveProfiles(practice, "practice"); saveProfiles(competition, "contest");
        if (!history.isEmpty()) jdbc.batchUpdate("INSERT INTO site_rating_history(uid,type,source_key,title,occurred_at,old_rating,rating,delta,place) VALUES (?,?,?,?,?,?,?,?,?)", history);
        jdbc.update("UPDATE site_rating_lock SET updated_at=? WHERE id=1", new java.sql.Timestamp(System.currentTimeMillis()));
    }

    boolean ready(Contest contest) {
        if (Boolean.TRUE.equals(contest.getSealRank()) && !Boolean.TRUE.equals(contest.getAutoRealRank())) return false;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM judge j LEFT JOIN paper_run r ON r.id=j.paper_run_id WHERE (j.cid=? OR r.contest_id=?) AND j.submit_time>=? AND j.submit_time<? AND (j.status IS NULL OR j.status IN (4,5,6,7,9,10,15))", Integer.class, contest.getId(), contest.getId(), contest.getStartTime(), contest.getEndTime()) > 0) return false;
        return jdbc.queryForObject("SELECT COUNT(*) FROM paper_run WHERE contest_id=? AND state<>'FINISHED'", Integer.class, contest.getId()) == 0;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> standings(Contest contest) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM contest_paper WHERE contest_id=?", Integer.class, contest.getId()) > 0)
            return (List<Map<String, Object>>) mixedRanks.calculate(contest, false).get("records");
        List<Map<String, Object>> rows = new ArrayList<>();
        if (Objects.equals(contest.getType(), 0)) {
            for (ACMContestRankVO row : ranks.calcACMRank(false, true, contest, null, null, null, false)) rows.add(rankRow(row.getUid(), row.getRank()));
        } else {
            for (OIContestRankVO row : ranks.calcOIRank(false, true, contest, null, null, null, false)) rows.add(rankRow(row.getUid(), row.getRank()));
        }
        return rows;
    }
    private Map<String, Object> rankRow(String uid, Integer rank) {
        Map<String, Object> row = new HashMap<>(); row.put("uid", uid); row.put("rank", rank); return row;
    }
    private void apply(Profile profile, int rating, String uid, String type, String source, String title, Date time, Double place, List<Object[]> history) {
        int previous = profile.rating;
        profile.rating = rating; profile.delta = rating - previous; profile.peak = Math.max(profile.peak, rating); profile.count++;
        history.add(new Object[] { uid, type, source, title, time, previous, rating, profile.delta, place });
    }
    private void saveProfiles(Map<String, Profile> profiles, String type) {
        List<Object[]> values = profiles.entrySet().stream().map(entry -> { Profile p = entry.getValue(); return new Object[] { entry.getKey(), type, p.rating, p.peak, p.delta, p.count }; }).collect(Collectors.toList());
        if (!values.isEmpty()) jdbc.batchUpdate("INSERT INTO site_rating_profile(uid,type,rating,peak,delta,rated_count) VALUES (?,?,?,?,?,?)", values);
    }

    public Map<String, Object> rank(String type, Integer page, Integer limit, String keyword) {
        if (!Arrays.asList("practice", "contest").contains(type)) throw new IllegalArgumentException("未知排名类型");
        int current = page == null ? 1 : Math.max(1, page), size = limit == null ? 30 : Math.min(100, Math.max(1, limit));
        String filter = keyword == null ? "" : keyword.trim();
        String base = " FROM (SELECT p.*,DENSE_RANK() OVER (ORDER BY rating DESC) AS `rank` FROM site_rating_profile p WHERE type=?) p JOIN user_info u ON u.uuid=p.uid WHERE u.status=0 AND (u.username LIKE ? OR u.nickname LIKE ?)";
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT p.uid,u.username,u.nickname,u.avatar,p.rating,p.peak,p.delta,p.rated_count AS ratedCount,p.`rank`" + base + " ORDER BY p.rating DESC,u.username,p.uid LIMIT ? OFFSET ?", type, "%" + filter + "%", "%" + filter + "%", size, (long) (current - 1) * size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", rows); result.put("total", jdbc.queryForObject("SELECT COUNT(*)" + base, Long.class, type, "%" + filter + "%", "%" + filter + "%"));
        result.put("current", current); result.put("size", size); result.put("updatedAt", jdbc.queryForObject("SELECT updated_at FROM site_rating_lock WHERE id=1", Date.class));
        return result;
    }

    public Map<String, Object> profile(String uid) {
        return profile(uid, null);
    }

    public Map<String, Object> profile(String uid, String historyType) {
        if (historyType != null && !Arrays.asList("practice", "contest").contains(historyType))
            throw new IllegalArgumentException("未知排名类型");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("practiceRating", null); result.put("contestRating", null);
        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM site_rating_profile WHERE uid=?", uid)) {
            String type = (String) row.get("type");
            result.put(type + "Rating", row.get("rating")); result.put(type + "Count", row.get("rated_count")); result.put(type + "Peak", row.get("peak"));
        }
        String historySql = "SELECT type,source_key AS sourceKey,title,occurred_at AS occurredAt,old_rating AS oldRating,rating,delta,place FROM site_rating_history WHERE uid=?";
        String order = " ORDER BY occurred_at DESC,source_key DESC LIMIT 100";
        result.put("history", historyType == null ? jdbc.queryForList(historySql + order, uid)
                : jdbc.queryForList(historySql + " AND type=?" + order, uid, historyType));
        return result;
    }
}
