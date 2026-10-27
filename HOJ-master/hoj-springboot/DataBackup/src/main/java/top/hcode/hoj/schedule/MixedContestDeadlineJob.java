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
        java.sql.Timestamp time = new java.sql.Timestamp(System.currentTimeMillis());
        List<Long> ids = jdbc.queryForList("SELECT r.id FROM paper_run r LEFT JOIN contest c ON c.id=r.contest_id LEFT JOIN contest_virtual_run v ON v.id=r.virtual_run_id WHERE r.state='IN_PROGRESS' AND ((r.virtual_run_id IS NULL AND c.end_time<=?) OR v.ends_at<=?) ORDER BY r.id LIMIT 100", Long.class, time, time);
        for (Long id : ids) {
            try { papers.finishExpired(id); }
            catch (Exception e) { log.warn("Mixed contest deadline failed for run {}", id, e); }
        }
    }
}
