package top.hcode.hoj.manager.oj.external;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.remote.RemoteProblemRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adds the latest cached public submission for a single practice problem to an owner snapshot. */
@Component
public class CodeforcesPracticeRecords {
    private final JdbcTemplate jdbc;

    public CodeforcesPracticeRecords(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RemoteProblemRef requireProblem(String problemId) {
        try {
            if (problemId != null && problemId.startsWith("CF-")) return new RemoteProblemRef(problemId, null);
        } catch (IllegalArgumentException invalid) {
            throw new ObjectiveException(400, "Codeforces 题号无效");
        }
        throw new ObjectiveException(400, "Codeforces 题号无效");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> withLatest(Map<String, Object> ownerSnapshot, RemoteProblemRef problem) {
        String problemKey = ProblemIdentity.local(0, problem.problem, true);
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) ownerSnapshot.get("accounts");
        for (Map<String, Object> account : accounts) {
            if (!"CODEFORCES".equals(account.get("platform")) || !Boolean.TRUE.equals(account.get("verified"))) continue;
            List<Map<String, Object>> submissions = jdbc.queryForList(
                    "SELECT s.external_id,s.verdict FROM external_submission s "
                            + "JOIN external_account a ON a.id=s.account_id "
                            + "WHERE s.account_id=? AND a.uid=? AND a.platform='CODEFORCES' AND a.verified_identity=a.handle_key "
                            + "AND s.problem_key=? AND s.individual=1 ORDER BY s.external_id DESC LIMIT 1",
                    account.get("id"), ownerSnapshot.get("uid"), problemKey);
            if (submissions.isEmpty()) continue;
            Map<String, Object> submission = submissions.get(0);
            long id = ((Number) submission.get("external_id")).longValue();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", Long.toString(id));
            view.put("verdict", submission.get("verdict"));
            view.put("url", problem.origin() + problem.submissionPath(id));
            account.put("latestSubmission", view);
        }
        return ownerSnapshot;
    }
}
