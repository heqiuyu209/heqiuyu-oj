package top.hcode.hoj.manager.oj.external;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.hcode.hoj.common.exception.ObjectiveException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CodeforcesPracticeRecordsTest {
    @Test
    void selectsOnlyTheLatestIndividualSubmissionForTheOwnedVerifiedAccountAndProblem() {
        SingleConnectionDataSource source = new SingleConnectionDataSource(
                "jdbc:h2:mem:cf_practice;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE external_account (id BIGINT PRIMARY KEY, uid VARCHAR(32), platform VARCHAR(16), handle_key VARCHAR(64), verified_identity VARCHAR(64))");
            jdbc.execute("CREATE TABLE external_submission (account_id BIGINT, external_id BIGINT, problem_key VARCHAR(120), verdict VARCHAR(32), submitted_at BIGINT, individual TINYINT)");
            jdbc.update("INSERT INTO external_account VALUES (1,'alice','CODEFORCES','alice','alice'), (2,'bob','CODEFORCES','bob','bob'), (3,'alice','CODEFORCES','guest',NULL), (4,'alice','ATCODER','other','other'), (5,'alice','CODEFORCES','empty','empty')");
            jdbc.update("INSERT INTO external_submission VALUES "
                    + "(1,100,'cf:contest:4:A','WRONG_ANSWER',10,1), (1,101,'cf:contest:4:A','OK',20,1), "
                    + "(1,102,'cf:contest:4:A','WRONG_ANSWER',30,0), (1,103,'cf:contest:4:B','WRONG_ANSWER',30,1), "
                    + "(2,104,'cf:contest:4:A','WRONG_ANSWER',30,1), (3,105,'cf:contest:4:A','OK',30,1), (4,106,'cf:contest:4:A','OK',30,1)");
            Map<String, Object> account = account(1, "CODEFORCES", true);
            Map<String, Object> otherOwner = account(2, "CODEFORCES", true);
            Map<String, Object> unverified = account(3, "CODEFORCES", false);
            Map<String, Object> otherPlatform = account(4, "ATCODER", true);
            Map<String, Object> noSubmissions = account(5, "CODEFORCES", true);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("uid", "alice");
            snapshot.put("accounts", Arrays.asList(account, otherOwner, unverified, otherPlatform, noSubmissions));

            CodeforcesPracticeRecords records = new CodeforcesPracticeRecords(jdbc);
            records.withLatest(snapshot, records.requireProblem("CF-4a"));

            Map<?, ?> latest = (Map<?, ?>) account.get("latestSubmission");
            assertEquals("101", latest.get("id"));
            assertEquals("OK", latest.get("verdict"));
            assertEquals("https://codeforces.com/contest/4/submission/101", latest.get("url"));
            for (Map<String, Object> excluded : Arrays.asList(otherOwner, unverified, otherPlatform, noSubmissions)) {
                assertFalse(excluded.containsKey("latestSubmission"));
            }
        } finally {
            source.destroy();
        }
    }

    @Test
    void acceptsOnlyOrdinaryCodeforcesProblemIds() {
        CodeforcesPracticeRecords records = new CodeforcesPracticeRecords(null);
        for (String problem : Arrays.asList(null, "", "AC-abc001_a", "GYM-123456A", "CF-4A?handle=other")) {
            assertEquals(400, assertThrows(ObjectiveException.class, () -> records.requireProblem(problem)).getStatus());
        }
    }

    private Map<String, Object> account(long id, String platform, boolean verified) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", id);
        account.put("platform", platform);
        account.put("verified", verified);
        return account;
    }
}
