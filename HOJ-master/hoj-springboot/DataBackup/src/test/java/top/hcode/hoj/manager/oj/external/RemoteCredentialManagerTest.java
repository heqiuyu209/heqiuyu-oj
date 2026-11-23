package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.controller.oj.RemoteCredentialController;
import top.hcode.hoj.pojo.dto.ToJudgeDTO;
import top.hcode.hoj.pojo.entity.judge.*;
import top.hcode.hoj.utils.RemoteCredentialCipher;
import top.hcode.hoj.remote.RemoteSession;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteCredentialManagerTest {
    @Test void nowcoderSessionsAreEncryptedOwnedAndExposeEnabledSourceCapability() throws Exception {
        jdbc.update("INSERT INTO external_account(id,uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(5,'alice','NOWCODER','12345','12345','12345',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        ReflectionTestUtils.setField(manager,"submissionEnabled",true);
        Map<String,Object> saved=manager.saveSession(5,"t=fake-nc-login; csrf_token=fake-csrf; tracking=discard",0L,true);
        assertEquals(true,saved.get("configured"));assertEquals(true,saved.get("submissionSupported"));assertEquals(true,saved.get("submissionAvailable"));
        String cipher=jdbc.queryForObject("SELECT credential_ciphertext FROM remote_user_credential WHERE account_id=5",String.class);assertTrue(cipher.startsWith("s1:"));assertFalse(cipher.contains("fake-nc"));assertFalse(new ObjectMapper().writeValueAsString(saved).contains("fake-"));
        assertEquals("t=fake-nc-login; csrf_token=fake-csrf",manager.sessionForDispatch(5,"alice",1).cookieHeader());
        assertThrows(ObjectiveException.class,()->manager.sessionForDispatch(5,"bob",1));
        doThrow(new ObjectiveException(403,"会话账号不一致")).when(sessions).verify(eq(5L),eq("NOWCODER"),eq("12345"),any(RemoteSession.class));
        assertThrows(ObjectiveException.class,()->manager.saveSession(5,"t=fake-other-account",1L,true));assertEquals(cipher,jdbc.queryForObject("SELECT credential_ciphertext FROM remote_user_credential WHERE account_id=5",String.class));
        assertThrows(Exception.class,()->new RemoteCredentialCipher(secret).decryptSession(5,"alice","CODEFORCES","12345",cipher));manager.delete(5,1);assertNull(jdbc.queryForObject("SELECT credential_ciphertext FROM remote_user_credential WHERE account_id=5",String.class));
    }
    @Test void cookieRotationCannotOverwriteANewerSessionOrResurrectRevocation(){
        save(1,"first-session",0L);
        RemoteSession first=RemoteSession.parse("CODEFORCES","JSESSIONID=first-session"),next=RemoteSession.parse("CODEFORCES","JSESSIONID=rotated-session");
        manager.rotateSession(1,"alice",1,first,next);assertEquals("rotated-session",dispatch(1,"alice"));assertEquals(1L,((Number)manager.settings(1).get("version")).longValue());
        assertEquals(409,assertThrows(ObjectiveException.class,()->manager.rotateSession(1,"alice",1,first,next)).getStatus());
        ReflectionTestUtils.setField(manager,"submissionEnabled",true);assertEquals(true,manager.settings(1).get("submissionAvailable"));
        manager.delete(1,1);assertThrows(ObjectiveException.class,()->manager.rotateSession(1,"alice",1,next,first));assertEquals(false,manager.settings(1).get("submissionAvailable"));
    }
    @TempDir Path secret;JdbcTemplate jdbc;RemoteCredentialManager manager;RemoteSessionVerifier sessions;String actor="alice";
    Map<String,Object> save(long id,String value,Long version){return manager.saveSession(id,value.isEmpty()?"":(id==3?"REVEL_SESSION=":"JSESSIONID=")+value,version,true);}
    String dispatch(long id,String owner){long version=jdbc.queryForObject("SELECT credential_version FROM remote_user_credential WHERE account_id=?",Long.class,id);String header=manager.sessionForDispatch(id,owner,version).cookieHeader();return header.substring(header.indexOf('=')+1);}
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:remote_credentials;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(source);jdbc.execute("DROP ALL OBJECTS");jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");jdbc.update("INSERT INTO user_info VALUES('alice',0),('bob',0)");
        for(String file:Arrays.asList("external-learning.sql","remote-credentials.sql")){String ddl=StreamUtils.copyToString(new ClassPathResource(file).getInputStream(),StandardCharsets.UTF_8).replace(" COLLATE=utf8mb4_unicode_ci","");try(Connection connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));}}
        jdbc.update("INSERT INTO external_account(id,uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(1,'alice','CODEFORCES','Tourist','tourist','tourist',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'bob','CODEFORCES','Another','another','another',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(3,'alice','ATCODER','Tourist','tourist','tourist',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(4,'alice','CODEFORCES','Unverified','unverified',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        manager=new RemoteCredentialManager(){@Override protected String uid(){return actor;}};ReflectionTestUtils.setField(manager,"jdbc",jdbc);ReflectionTestUtils.setField(manager,"transactions",new DataSourceTransactionManager(source));ReflectionTestUtils.setField(manager,"secretDirectory",secret.toString());sessions=mock(RemoteSessionVerifier.class);ReflectionTestUtils.setField(manager,"sessions",sessions);
    }
    @Test void verifiedOwnerSavesEncryptedSecretAndOnlyMetadataCanBeRead() throws Exception {
        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return null;}).when(sessions).verify(anyLong(),anyString(),anyString(),any());
        assertEquals(false,manager.settings(1).get("configured"));String password="synthetic-session-123";Map<String,Object> saved=save(1,password,0L);
        assertEquals(true,saved.get("configured"));assertEquals(false,saved.get("submissionAvailable"));assertEquals(password,dispatch(1,"alice"));
        assertEquals("BROWSER_SESSION",saved.get("credentialType"));assertEquals(false,saved.get("reauthorizationRequired"));assertNotNull(saved.get("sessionCheckedAt"));verify(sessions).verify(eq(1L),eq("CODEFORCES"),eq("tourist"),any(RemoteSession.class));
        String stored=jdbc.queryForObject("SELECT credential_ciphertext FROM remote_user_credential WHERE account_id=1",String.class);assertTrue(stored.startsWith("s1:"));assertFalse(stored.contains(password));assertFalse(new ObjectMapper().writeValueAsString(saved).contains(password));assertFalse(saved.containsKey("password"));
        save(1,password,1L);assertNotEquals(stored,jdbc.queryForObject("SELECT credential_ciphertext FROM remote_user_credential WHERE account_id=1",String.class));
    }
    @Test void ownerStatusAndVerificationAreCheckedForEveryReadSaveDeleteAndDispatch(){
        assertThrows(ObjectiveException.class,()->manager.settings(2));assertThrows(ObjectiveException.class,()->save(2,"secret",0L));assertThrows(ObjectiveException.class,()->manager.delete(2,0));assertThrows(ObjectiveException.class,()->save(4,"secret",0L));
        save(1,"synthetic",0L);assertThrows(ObjectiveException.class,()->dispatch(1,"bob"));jdbc.update("UPDATE external_account SET verified_identity=NULL WHERE id=1");assertThrows(ObjectiveException.class,()->dispatch(1,"alice"));assertThrows(ObjectiveException.class,()->save(1,"replacement",1L));
        manager.delete(1,1);assertEquals(false,manager.settings(1).get("configured"));jdbc.update("UPDATE user_info SET status=1 WHERE uuid='alice'");assertThrows(ObjectiveException.class,()->manager.settings(1));
    }
    @Test void authenticatedContextRejectsCrossAccountUserPlatformHandleAndCiphertextTampering() throws Exception {
        RemoteCredentialCipher cipher=new RemoteCredentialCipher(secret);String value=cipher.encrypt(1,"alice","CODEFORCES","tourist","synthetic",true);
        assertThrows(Exception.class,()->cipher.decrypt(2,"alice","CODEFORCES","tourist",value));assertThrows(Exception.class,()->cipher.decrypt(1,"bob","CODEFORCES","tourist",value));assertThrows(Exception.class,()->cipher.decrypt(1,"alice","ATCODER","tourist",value));assertThrows(Exception.class,()->cipher.decrypt(1,"alice","CODEFORCES","another",value));
        byte[] bytes=Base64.getDecoder().decode(value.substring(3));bytes[bytes.length-1]^=1;String corrupt="v1:"+Base64.getEncoder().encodeToString(bytes);assertThrows(Exception.class,()->cipher.decrypt(1,"alice","CODEFORCES","tourist",corrupt));
    }
    @Test void missingOrCorruptWrappingKeyCannotSilentlyOverwriteAnyExistingCredential() throws Exception {
        save(1,"synthetic",0L);String stored=jdbc.queryForObject("SELECT credential_ciphertext FROM remote_user_credential WHERE account_id=1",String.class);Files.delete(secret.resolve("master.key"));assertThrows(ObjectiveException.class,()->save(1,"replacement",1L));assertThrows(ObjectiveException.class,()->save(3,"other",0L));assertFalse(Files.exists(secret.resolve("master.key")));assertEquals(stored,jdbc.queryForObject("SELECT credential_ciphertext FROM remote_user_credential WHERE account_id=1",String.class));
        Files.write(secret.resolve("master.key"),new byte[]{1});assertThrows(ObjectiveException.class,()->dispatch(1,"alice"));
    }
    @Test void monotonicVersionsProtectSaveAndDeleteEvenAcrossDeletionAndRecreation(){
        save(1,"first",0L);assertThrows(ObjectiveException.class,()->save(1,"stale",0L));manager.delete(1,1);assertEquals(2L,manager.settings(1).get("version"));assertThrows(ObjectiveException.class,()->save(1,"stale-absent",0L));save(1,"second",2L);assertThrows(ObjectiveException.class,()->manager.delete(1,1));assertEquals("second",dispatch(1,"alice"));
    }
    @Test void unlinkCascadesOnlySelectedCredentialAndInvalidInputNeverTouchesStorage(){
        assertThrows(ObjectiveException.class,()->save(1,"",0L));assertThrows(ObjectiveException.class,()->save(1,String.join("",Collections.nCopies(8193,"x")),0L));assertThrows(ObjectiveException.class,()->save(1,"secret",null));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM remote_user_credential",Integer.class));
        save(1,"first",0L);save(3,"second",0L);jdbc.update("DELETE FROM external_account WHERE id=1");assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM remote_user_credential",Integer.class));assertEquals("second",dispatch(3,"alice"));
    }
    @Test void publicSerializationAndDiagnosticStringsDoNotExposePasswordsCodeOrJudgeToken() throws Exception {
        ObjectMapper json=new ObjectMapper();RemoteCredentialController.Input input=json.readValue("{\"password\":\"synthetic-password\",\"version\":0}",RemoteCredentialController.Input.class);assertEquals("synthetic-password",input.password);assertFalse(json.writeValueAsString(input).contains("synthetic-password"));
        Judge judge=new Judge().setSubmitId(42L).setCode("synthetic-private-code").setVjudgePassword("synthetic-password");assertFalse(json.writeValueAsString(judge).contains("synthetic-password"));assertFalse(judge.toString().contains("synthetic-private-code"));assertFalse(judge.toString().contains("synthetic-password"));
        ToJudgeDTO task=new ToJudgeDTO().setJudge(judge).setPassword("synthetic-password").setToken("synthetic-token");assertFalse(task.toString().contains("synthetic-"));RemoteJudgeAccount account=new RemoteJudgeAccount().setPassword("synthetic-password");assertFalse(account.toString().contains("synthetic-password"));
    }
    @Test void failedIdentityCheckNeverReplacesThePriorSession(){
        save(1,"first",0L);String original=jdbc.queryForObject("SELECT credential_ciphertext FROM remote_user_credential WHERE account_id=1",String.class);
        doThrow(new ObjectiveException(403,"会话登录账号与绑定账号不一致")).when(sessions).verify(anyLong(),anyString(),anyString(),any());
        assertEquals(403,assertThrows(ObjectiveException.class,()->save(1,"wrong-account",1L)).getStatus());assertEquals(original,jdbc.queryForObject("SELECT credential_ciphertext FROM remote_user_credential WHERE account_id=1",String.class));assertEquals(1L,manager.settings(1).get("version"));
    }
    @Test void deletionOrAccountRevocationDuringNetworkCheckCannotBeOverwritten(){
        save(1,"first",0L);doAnswer(call->{manager.delete(1,1);return null;}).when(sessions).verify(anyLong(),anyString(),anyString(),any());
        assertEquals(409,assertThrows(ObjectiveException.class,()->save(1,"delayed",1L)).getStatus());assertEquals(false,manager.settings(1).get("configured"));
        doAnswer(call->{jdbc.update("UPDATE external_account SET verified_identity=NULL WHERE id=1");return null;}).when(sessions).verify(anyLong(),anyString(),anyString(),any());
        assertEquals(403,assertThrows(ObjectiveException.class,()->save(1,"revoked",2L)).getStatus());assertNull(jdbc.queryForObject("SELECT credential_ciphertext FROM remote_user_credential WHERE account_id=1",String.class));
    }
    @Test void legacyPasswordRequiresCheckedSessionReplacementAndCannotBeDispatched() throws Exception {
        String legacy=new RemoteCredentialCipher(secret).encrypt(1,"alice","CODEFORCES","tourist","synthetic-old-password",true);jdbc.update("INSERT INTO remote_user_credential VALUES(1,?,1,CURRENT_TIMESTAMP)",legacy);
        assertEquals("LEGACY_PASSWORD",manager.settings(1).get("credentialType"));assertEquals(true,manager.settings(1).get("reauthorizationRequired"));assertNull(manager.settings(1).get("sessionCheckedAt"));assertThrows(ObjectiveException.class,()->manager.sessionForDispatch(1,"alice",1));
        save(1,"replacement",1L);assertEquals("replacement",dispatch(1,"alice"));assertThrows(ObjectiveException.class,()->manager.sessionForDispatch(1,"alice",1));
    }
    @Test void sessionKindIsAuthenticatedAndReadersNeedNoWriteLock() throws Exception {
        save(1,"first",0L);Files.delete(secret.resolve("master.lock"));assertEquals("first",dispatch(1,"alice"));assertFalse(Files.exists(secret.resolve("master.lock")));
        String value=jdbc.queryForObject("SELECT credential_ciphertext FROM remote_user_credential WHERE account_id=1",String.class);RemoteCredentialCipher cipher=new RemoteCredentialCipher(secret);
        assertThrows(Exception.class,()->cipher.decrypt(1,"alice","CODEFORCES","tourist","v1:"+value.substring(3)));assertThrows(Exception.class,()->cipher.decryptSession(2,"alice","CODEFORCES","tourist",value));
        RemoteCredentialController.Input input=new RemoteCredentialController.Input();input.cookieHeader="JSESSIONID=synthetic-session";assertFalse(new ObjectMapper().writeValueAsString(input).contains("synthetic-session"));assertFalse(RemoteSession.parse("CODEFORCES",input.cookieHeader).toString().contains("synthetic-session"));
    }
    @Test void noConsentOrInvalidCookieCannotCauseASourceRequest(){
        assertThrows(ObjectiveException.class,()->manager.saveSession(1,"JSESSIONID=synthetic",0L,false));assertThrows(ObjectiveException.class,()->manager.saveSession(1,"JSESSIONID=bad\r\nInjected: value",0L,true));verifyNoInteractions(sessions);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM remote_user_credential",Integer.class));
    }
}
