package top.hcode.hoj.schedule;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.hcode.hoj.manager.oj.PaperManager;
import java.util.List;

/** Recoverable and idempotent after restarts; no browser or logged-in principal is required. */
@Component
@Slf4j
public class MixedContestDeadlineJob {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaperManager papers;
    @Scheduled(fixedDelay = 5000)
    public void finishOverdueRuns() {
        List<Long> ids = jdbc.queryForList("SELECT r.id FROM paper_run r JOIN contest c ON c.id=r.contest_id WHERE r.state='IN_PROGRESS' AND c.end_time<=? ORDER BY c.end_time,r.id LIMIT 100", Long.class, new java.sql.Timestamp(System.currentTimeMillis()));
        for (Long id : ids) {
            try { papers.finishExpired(id); }
            catch (Exception e) { log.warn("Mixed contest deadline failed for run {}", id, e); }
        }
    }
}
