package top.hcode.hoj.manager.oj.external;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalSyncWorkerTest {
    ExternalSyncWorker worker;
    ExternalAccountManager accounts;
    RemoteCatalogManager catalogue;
    RemoteStatementManager statements;
    CodeforcesDataClient client;
    CodeforcesStatementClient statementClient;
    AtCoderStatementClient atcoderStatementClient;
    NowCoderCatalogManager nowcoderCatalogue;NowCoderStatementClient nowcoderStatements;NowCoderSourceManager nowcoderSources;
    AtCoderDataClient atcoderClient;
    AtCoderCatalogManager atcoderCatalogue;
    NowCoderContextManager nowcoderContexts;NowCoderContestClient nowcoderContestClient;
    NowCoderPracticeManager nowcoderPractice;NowCoderPracticeClient nowcoderPracticeClient;
    ValueOperations<String,String> values;
    Map<String,Object> account,source,statement;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        worker=new ExternalSyncWorker();accounts=mock(ExternalAccountManager.class);catalogue=mock(RemoteCatalogManager.class);statements=mock(RemoteStatementManager.class);client=mock(CodeforcesDataClient.class);statementClient=mock(CodeforcesStatementClient.class);
        nowcoderCatalogue=mock(NowCoderCatalogManager.class);when(nowcoderCatalogue.claim()).thenReturn(null);nowcoderStatements=mock(NowCoderStatementClient.class);nowcoderSources=mock(NowCoderSourceManager.class);ReflectionTestUtils.setField(worker,"nowcoderCatalogue",nowcoderCatalogue);ReflectionTestUtils.setField(worker,"nowcoderStatements",nowcoderStatements);ReflectionTestUtils.setField(worker,"nowcoderSources",nowcoderSources);
        atcoderStatementClient=mock(AtCoderStatementClient.class);ReflectionTestUtils.setField(worker,"atcoderStatementClient",atcoderStatementClient);
        atcoderClient=mock(AtCoderDataClient.class);atcoderCatalogue=mock(AtCoderCatalogManager.class);when(atcoderCatalogue.claim()).thenReturn(null);ReflectionTestUtils.setField(worker,"atcoderClient",atcoderClient);ReflectionTestUtils.setField(worker,"atcoderCatalogue",atcoderCatalogue);
        nowcoderContexts=mock(NowCoderContextManager.class);when(nowcoderContexts.claim()).thenReturn(null);nowcoderContestClient=mock(NowCoderContestClient.class);ReflectionTestUtils.setField(worker,"nowcoderContexts",nowcoderContexts);ReflectionTestUtils.setField(worker,"nowcoderContestClient",nowcoderContestClient);
        nowcoderPractice=mock(NowCoderPracticeManager.class);when(nowcoderPractice.claim()).thenReturn(null);nowcoderPracticeClient=mock(NowCoderPracticeClient.class);ReflectionTestUtils.setField(worker,"nowcoderPractice",nowcoderPractice);ReflectionTestUtils.setField(worker,"nowcoderPracticeClient",nowcoderPracticeClient);
        StringRedisTemplate redis=mock(StringRedisTemplate.class);values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);when(values.setIfAbsent(anyString(),anyString(),eq(3L),eq(TimeUnit.SECONDS))).thenReturn(true);
        ReflectionTestUtils.setField(worker,"accounts",accounts);ReflectionTestUtils.setField(worker,"catalogue",catalogue);ReflectionTestUtils.setField(worker,"statements",statements);ReflectionTestUtils.setField(worker,"client",client);ReflectionTestUtils.setField(worker,"statementClient",statementClient);ReflectionTestUtils.setField(worker,"redis",redis);
        source=Collections.singletonMap("lease_token","catalog");statement=Collections.singletonMap("problem_key","cf:contest:4:A");account=new HashMap<>();account.put("stage","STATUS");account.put("handle","tourist");account.put("from_index",501);
        when(catalogue.claim()).thenReturn(source);when(statements.claim()).thenReturn(statement);when(accounts.claim()).thenReturn(account);
    }
    @AfterEach void close(){worker.close();}
    @Test void catalogImportsAndAccountStatisticsEachGetATurn(){
        worker.tick();worker.tick();worker.tick();
        verify(catalogue).apply(eq(source),any());verify(statements).apply(eq(statement),any());verify(accounts).apply(eq(account),any());
        verify(client).fetch("CATALOG",null,1);verify(client).fetch("STATUS","tourist",501);verify(statementClient).fetch("cf:contest:4:A");
    }
    @Test void emptyCategoriesFallThroughToExistingAccountSync(){
        when(catalogue.claim()).thenReturn(null);when(statements.claim()).thenReturn(null);worker.tick();verify(accounts).apply(eq(account),any());verifyNoInteractions(statementClient);
    }
    @Test void atcoderMetadataAndTimestampAccountJobsUseTheirOwnClient(){
        Map<String,Object> models=new HashMap<>();models.put("source_name","ATCODER_MODELS");models.put("lease_token","atcoder-models");
        when(atcoderCatalogue.claim()).thenReturn(models);
        worker.tick();worker.tick();worker.tick();worker.tick();
        verify(atcoderClient).fetch("MODELS",null,0);verify(atcoderCatalogue).apply(eq(models),any());
        account.put("platform","ATCODER");account.put("from_second",2100000000L);when(catalogue.claim()).thenReturn(null);when(statements.claim()).thenReturn(null);when(atcoderCatalogue.claim()).thenReturn(null);
        worker.tick();verify(atcoderClient).fetch("STATUS","tourist",2100000000L);
    }
    @Test void failedSourceRoutesToItsOwnLeaseAndKeepsPublicErrorSanitized(){
        when(catalogue.claim()).thenReturn(null);when(statementClient.fetch(anyString())).thenThrow(new ObjectiveException(502,"HTTP 403"));worker.tick();
        verify(statements).failed(statement,"HTTP 403");verify(accounts,never()).failed(anyMap(),anyString());verify(catalogue,never()).failed(anyMap(),anyString());
    }
    @Test void codeforcesAccessRejectionStopsTheCurrentStatementAttempt(){
        CodeforcesStatementClient.AccessRejected rejection = new CodeforcesStatementClient.AccessRejected();
        when(catalogue.claim()).thenReturn(null);
        when(statementClient.fetch(anyString())).thenThrow(rejection);
        worker.tick();
        verify(statements).failed(statement, rejection.getMessage(), true);
    }
    @Test void redisFailureOrBusyGateNeverClaimsOrFetchesWork(){
        when(values.setIfAbsent(anyString(),anyString(),eq(3L),eq(TimeUnit.SECONDS))).thenReturn(false);worker.tick();
        when(values.setIfAbsent(anyString(),anyString(),eq(3L),eq(TimeUnit.SECONDS))).thenThrow(new RuntimeException("offline"));worker.tick();
        verifyNoInteractions(accounts,catalogue,statements,client,statementClient);
    }
    @Test void nowcoderAccountUsesNumericIdentityAndItsOwnSourceGate(){
        NowCoderDataClient nowcoder=mock(NowCoderDataClient.class);ReflectionTestUtils.setField(worker,"nowcoderClient",nowcoder);
        when(catalogue.claim()).thenReturn(null);when(statements.claim()).thenReturn(null);account.put("platform","NOWCODER");account.put("handle","12345");account.put("from_index",2);
        worker.tick();verify(nowcoder).fetch("STATUS","12345",2);verify(accounts).apply(eq(account),any());verifyNoInteractions(client,atcoderClient);
    }
    @Test void busyNowcoderGateDefersWithoutFetchingOrCountingAFailure(){
        NowCoderDataClient nowcoder=mock(NowCoderDataClient.class);ReflectionTestUtils.setField(worker,"nowcoderClient",nowcoder);
        when(catalogue.claim()).thenReturn(null);when(statements.claim()).thenReturn(null);account.put("platform","NOWCODER");
        when(values.setIfAbsent(eq("external:nowcoder:public-http"),anyString(),eq(3L),eq(TimeUnit.SECONDS))).thenReturn(false);
        worker.tick();verify(accounts).defer(account);verify(accounts,never()).failed(anyMap(),anyString());verifyNoInteractions(nowcoder);
    }

    @Test void atcoderStatementsUsePinnedCatalogIdentityAndTheSharedOfficialHttpGate(){
        when(catalogue.claim()).thenReturn(null);statement=new HashMap<>();statement.put("problem_key","atcoder:task:abc086_a");when(statements.claim()).thenReturn(statement);
        Map<String,Object> source=new HashMap<>();source.put("contest_id","abc086");source.put("task_id","abc086_a");when(statements.atcoderSource(statement)).thenReturn(source);worker.tick();
        verify(atcoderStatementClient).fetch("abc086","abc086_a");verifyNoInteractions(statementClient);verify(statements).apply(eq(statement),any());
    }
    @Test void aBusyAtcoderSourceDefersImportWithoutConsumingFailureBudget(){
        when(catalogue.claim()).thenReturn(null);statement=Collections.singletonMap("problem_key","atcoder:task:abc086_a");when(statements.claim()).thenReturn(statement);
        when(values.setIfAbsent(eq("external:at:personal-http"),anyString(),eq(3L),eq(TimeUnit.SECONDS))).thenReturn(false);worker.tick();verify(statements).defer(statement);verifyNoInteractions(atcoderStatementClient);verify(statements,never()).failed(any(),any());
    }

    @Test void nowcoderCataloguesGetAFairTurnWithoutUsingAnyAccountSession(){
        when(atcoderCatalogue.claim()).thenReturn(Collections.singletonMap("source_name","ATCODER_MODELS"));
        Map<String,Object> nc=new HashMap<>();nc.put("next_page",2);when(nowcoderCatalogue.claim()).thenReturn(nc);for(int n=0;n<5;n++)worker.tick();verify(nowcoderStatements).fetchPage(eq(2),any());verify(nowcoderCatalogue).apply(eq(nc),any());
    }
    @Test void nowcoderSourceGateDefersAnImportWithoutConsumingItsFailureBudget(){
        when(catalogue.claim()).thenReturn(null);Map<String,Object> nc=Collections.singletonMap("problem_key","nowcoder:acm:6");when(statements.claim()).thenReturn(nc);when(nowcoderStatements.fetch(eq("nowcoder:acm:6"),any())).thenThrow(new ObjectiveException(429,"busy"));worker.tick();verify(statements).defer(nc);verify(statements,never()).failed(any(),any());
    }
    @Test void contestContextGetsAFairTurnAndSharesPublicRateGate(){
        when(atcoderCatalogue.claim()).thenReturn(Collections.singletonMap("source_name","ATCODER_MODELS"));when(nowcoderCatalogue.claim()).thenReturn(Collections.singletonMap("next_page",1));
        Map<String,Object> job=Collections.singletonMap("stage","LIST");when(nowcoderContexts.claim()).thenReturn(job);
        for(int i=0;i<6;i++)worker.tick();verify(nowcoderContestClient).fetch(job);verify(nowcoderContexts).apply(eq(job),any());
    }
    @Test void busyContestContextDefersWithoutNetworkOrFailure(){
        when(catalogue.claim()).thenReturn(null);when(statements.claim()).thenReturn(null);when(accounts.claim()).thenReturn(null);
        Map<String,Object> job=Collections.singletonMap("stage","LIST");when(nowcoderContexts.claim()).thenReturn(job);
        when(values.setIfAbsent(eq("external:nowcoder:public-http"),anyString(),eq(3L),eq(TimeUnit.SECONDS))).thenReturn(false);
        worker.tick();verify(nowcoderContexts).defer(job);verify(nowcoderContexts,never()).failed(any(),anyString());verifyNoInteractions(nowcoderContestClient);
    }
    @Test void publicPracticeGetsAFairTurnAndItsOwnResultDispatch(){
        when(atcoderCatalogue.claim()).thenReturn(Collections.singletonMap("source_name","ATCODER_MODELS"));when(nowcoderCatalogue.claim()).thenReturn(Collections.singletonMap("next_page",1));
        when(nowcoderContexts.claim()).thenReturn(Collections.singletonMap("stage","LIST"));
        Map<String,Object> job=Collections.singletonMap("stage","ALL");when(nowcoderPractice.claim()).thenReturn(job);
        for(int n=0;n<7;n++)worker.tick();verify(nowcoderPracticeClient).fetch(job);verify(nowcoderPractice).apply(eq(job),any());
    }
    @Test void busyPracticeGateDoesNotSendARequestOrConsumeFailures(){
        when(catalogue.claim()).thenReturn(null);when(statements.claim()).thenReturn(null);when(accounts.claim()).thenReturn(null);
        Map<String,Object> job=Collections.singletonMap("stage","ALL");when(nowcoderPractice.claim()).thenReturn(job);
        when(values.setIfAbsent(eq("external:nowcoder:public-http"),anyString(),eq(3L),eq(TimeUnit.SECONDS))).thenReturn(false);
        worker.tick();verify(nowcoderPractice).defer(job);verify(nowcoderPractice,never()).failed(any(),anyString());verifyNoInteractions(nowcoderPracticeClient);
    }
}
