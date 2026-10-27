package top.hcode.hoj.judge.remote;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Personal journal IDs must never enter the legacy account-pool re-submit path. */
@Component
public class PersonalRemoteSubmissionGuard {
    private final JdbcTemplate jdbc;
    public PersonalRemoteSubmissionGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean managed(Long submitId) {
        return submitId != null && jdbc.queryForObject("SELECT COUNT(*) FROM personal_remote_task WHERE submit_id=?", Integer.class, submitId) != 0;
    }
}
