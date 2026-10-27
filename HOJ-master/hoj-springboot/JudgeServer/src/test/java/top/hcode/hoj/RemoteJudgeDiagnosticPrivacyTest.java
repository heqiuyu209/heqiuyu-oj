package top.hcode.hoj;

import org.junit.jupiter.api.Test;
import top.hcode.hoj.remoteJudge.entity.RemoteJudgeDTO;
import java.net.HttpCookie;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class RemoteJudgeDiagnosticPrivacyTest {
    @Test void remoteTaskDiagnosticsAreAnAllowlistOfJobIdentifiers(){RemoteJudgeDTO task=RemoteJudgeDTO.builder().oj("AC").judgeId(42L).submitId(100L).password("synthetic-password").csrfToken("synthetic-csrf").userCode("synthetic-private-code").cookies(Collections.singletonList(new HttpCookie("session","synthetic-cookie"))).build();String diagnostic=task.toString();assertTrue(diagnostic.contains("judgeId=42"));assertTrue(diagnostic.contains("submitId=100"));assertFalse(diagnostic.contains("synthetic-"));}
}
