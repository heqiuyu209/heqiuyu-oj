package top.hcode.hoj.manager.oj.ai;

import org.junit.jupiter.api.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class KnowledgeManagerTest {
    JdbcTemplate jdbc;KnowledgeManager manager;KnowledgeJobManager jobs;RagServiceClient index;AiAccessGuard access;
    AtomicLong clock;boolean administrator;String actor;int nonce;
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:knowledge_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");jdbc.update("INSERT INTO user_info VALUES('alice',0),('bob',0)");
        String schema=StreamUtils.copyToString(new ClassPathResource("knowledge-base.sql").getInputStream(),StandardCharsets.UTF_8).replace(" COLLATE=utf8mb4_bin","");
        try(Connection c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)));}
        administrator=true;actor="alice";clock=new AtomicLong(1000000);index=mock(RagServiceClient.class);access=mock(AiAccessGuard.class);
        when(index.configured()).thenReturn(true);when(index.ingest(any())).thenReturn(1);when(index.retrieve(anyString(),anyList(),anyInt())).thenReturn(Collections.emptyList());
        manager=new KnowledgeManager(){@Override protected String uid(){return actor;}@Override protected boolean maintainer(){return administrator;}@Override protected long now(){return clock.get();}};
        jobs=new KnowledgeJobManager(){@Override protected long now(){return clock.get();}};
        for(Object bean:Arrays.asList(manager,jobs)){ReflectionTestUtils.setField(bean,"jdbc",jdbc);ReflectionTestUtils.setField(bean,"transactions",new DataSourceTransactionManager(source));ReflectionTestUtils.setField(bean,"index",index);}
        ReflectionTestUtils.setField(manager,"access",access);
    }
    @AfterEach void cleanup(){jobs.close();}
    KnowledgeDTO.Upload upload(String text){KnowledgeDTO.Upload dto=new KnowledgeDTO.Upload();dto.setRequestId("knowledge_nonce_"+(++nonce));dto.setTitle("算法讲义");dto.setFormat("markdown");dto.setContent(text);dto.setVisibility("PUBLIC");return dto;}
    String create(){return (String)manager.create(upload("# 前缀和\n\n区间和等于两个前缀和之差。")).get("id");}
    long revision(String id){return ((Number)manager.detail(id).get("revision")).longValue();}
    KnowledgeDTO.Change change(String id,int version){KnowledgeDTO.Change dto=new KnowledgeDTO.Change();dto.setExpectedRevision(revision(id));dto.setVersion(version);return dto;}
    void publish(String id){jobs.processOne();manager.publish(id,change(id,1));}
    void fail(Runnable action,int status){assertEquals(status,assertThrows(ObjectiveException.class,action::run).getStatus());}
    KnowledgeDTO.Search search(){KnowledgeDTO.Search dto=new KnowledgeDTO.Search();dto.setQuery("怎么求区间和");return dto;}
    KnowledgeDTO.Hit hit(String id,int version){Map<String,Object> row=jdbc.queryForMap("SELECT * FROM knowledge_version WHERE document_id=? AND version=?",id,version);KnowledgeDTO.Hit hit=new KnowledgeDTO.Hit();hit.setDocumentId(id);hit.setVersion(version);hit.setContentHash((String)row.get("content_hash"));hit.setTitle((String)row.get("title"));hit.setExcerpt((String)row.get("content"));hit.setChunkId(String.join("",Collections.nCopies(64,"a")));hit.setChunkHash(RagServiceClient.hash(hit.getExcerpt()));hit.setChunkIndex(0);hit.setHeadingPath("前缀和");hit.setScore(.8);return hit;}

    @Test void onlyAdministratorsMaintainDocumentsIncludingTeamAndProblemManagers(){
        String id=create();administrator=false;fail(()->manager.create(upload("x")),403);fail(()->manager.upload(id,upload("x")),403);
        fail(()->manager.detail(id),403);fail(()->manager.list(1,null),403);fail(()->manager.adminVersion(id,1),403);
        fail(()->manager.publish(id,null),403);fail(()->manager.unpublish(id,null),403);fail(()->manager.remove(id,null),403);fail(()->manager.retry(id,1,null),403);
    }
    @Test void createIsIdempotentAndDifferentDocumentsNeverMergeIdenticalBytes(){
        KnowledgeDTO.Upload dto=upload("same");String id=(String)manager.create(dto).get("id");assertEquals(id,manager.create(dto).get("id"));
        dto.setContent("changed");fail(()->manager.create(dto),409);assertNotEquals(id,manager.create(upload("same")).get("id"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document",Integer.class));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_job",Integer.class));
    }
    @Test void failedQueueInsertionRollsBackDocumentVersionAndNonce(){
        jdbc.execute("ALTER TABLE knowledge_job ADD CONSTRAINT reject_job CHECK (operation<>'INGEST')");
        assertThrows(RuntimeException.class,()->manager.create(upload("text")));
        for(String table:Arrays.asList("knowledge_document","knowledge_version","knowledge_command"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
    }
    @Test void uploadCanonicalizesLineEndingsAndDoesNotTrustFrontmatterForScope(){
        KnowledgeDTO.Upload dto=upload("---\nvisibility: PUBLIC\n---\n正文\r\n第二行\r第三行");dto.setVisibility("ADMIN");String id=(String)manager.create(dto).get("id");
        assertEquals("ADMIN",manager.detail(id).get("visibility"));assertEquals(RagServiceClient.hash(dto.getContent()),jdbc.queryForObject("SELECT content_hash FROM knowledge_version",String.class));assertFalse(dto.getContent().contains("\r"));
        administrator=false;assertEquals(0,((List<?>)manager.search(search()).get("items")).size());verify(index,never()).retrieve(anyString(),anyList(),anyInt());
    }
    @Test void unchangedReuploadRecordsNonceWithoutNewVersionAndOldRetriesCannotOverwriteNewerEdit(){
        KnowledgeDTO.Upload first=upload("one");String id=(String)manager.create(first).get("id");
        KnowledgeDTO.Upload same=upload("one");same.setExpectedRevision(revision(id));manager.upload(id,same);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_version",Integer.class));
        KnowledgeDTO.Upload next=upload("two");next.setExpectedRevision(revision(id));manager.upload(id,next);
        assertEquals(1,((Number)manager.upload(id,same).get("requestedVersion")).intValue());assertEquals(2,((Number)manager.detail(id).get("latestVersion")).intValue());
        same.setContent("three");fail(()->manager.upload(id,same),409);KnowledgeDTO.Upload stale=upload("three");stale.setExpectedRevision(1L);fail(()->manager.upload(id,stale),409);
    }
    @Test void indexCompletionDoesNotPublishAndOnlyIndexedVersionsCanPublish(){
        String id=create();fail(()->manager.publish(id,change(id,1)),409);assertNull(manager.detail(id).get("publishedVersion"));
        assertTrue(jobs.processOne());assertNull(manager.detail(id).get("publishedVersion"));manager.publish(id,change(id,1));
        administrator=false;assertEquals(1,manager.read(id,1).get("version"));fail(()->manager.read(id,2),404);
    }
    @Test void failedNewVersionPreservesPublishedOldVersionAndCanBeRetried(){
        String id=create();publish(id);KnowledgeDTO.Upload newer=upload("第二版");newer.setExpectedRevision(revision(id));manager.upload(id,newer);
        when(index.ingest(any())).thenThrow(new ObjectiveException(503,"private provider error must not be persisted"));
        for(int i=0;i<3;i++){assertTrue(jobs.processOne());clock.addAndGet(60000);}
        assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM knowledge_version WHERE version=2",String.class));
        assertEquals("INDEX_UNAVAILABLE",jdbc.queryForObject("SELECT last_error FROM knowledge_job WHERE version=2",String.class));
        administrator=false;assertEquals(1,manager.read(id,null).get("version"));fail(()->manager.read(id,2),404);administrator=true;
        manager.retry(id,2,change(id,2));doReturn(1).when(index).ingest(any());assertTrue(jobs.processOne());
        assertEquals("INDEXED",jdbc.queryForObject("SELECT state FROM knowledge_version WHERE version=2",String.class));assertEquals(1,((Number)manager.detail(id).get("publishedVersion")).intValue());
    }
    @Test void deleteRevokesImmediatelyCancelsIngestAndFencesLateCompletion(){
        String id=create();Map<String,Object> lease=jobs.claim();manager.remove(id,change(id,1));jobs.succeeded(lease,1);jobs.failed(lease,"INDEX_UNAVAILABLE");
        assertEquals("DELETED",jdbc.queryForObject("SELECT state FROM knowledge_version",String.class));assertNull(manager.detail(id).get("publishedVersion"));
        administrator=false;fail(()->manager.read(id,null),404);assertEquals(0,((List<?>)manager.search(search()).get("items")).size());
        assertTrue(jobs.processOne());verify(index).delete(any());verify(index,never()).ingest(any());assertFalse(jobs.processOne());
    }
    @Test void leasesSurviveRestartAndExpiredCallbacksCannotWinOverReclaimedJob(){
        create();Map<String,Object> old=jobs.claim();assertNull(jobs.claim());clock.addAndGet(180001);Map<String,Object> current=jobs.claim();
        assertNotEquals(old.get("lease_token"),current.get("lease_token"));jobs.succeeded(old,1);jobs.failed(old,"INDEX_UNAVAILABLE");
        assertEquals("RUNNING",jdbc.queryForObject("SELECT state FROM knowledge_job",String.class));jobs.succeeded(current,1);
        assertEquals("DONE",jdbc.queryForObject("SELECT state FROM knowledge_job",String.class));assertNull(jobs.claim());
    }
    @Test void expiredLeaseCannotCompleteEvenBeforeAnotherWorkerClaims(){
        create();Map<String,Object> old=jobs.claim();clock.addAndGet(180000);jobs.succeeded(old,1);
        assertEquals("QUEUED",jdbc.queryForObject("SELECT state FROM knowledge_version",String.class));assertEquals("RUNNING",jdbc.queryForObject("SELECT state FROM knowledge_job",String.class));
    }
    @Test void unconfiguredIndexLeavesDurableJobsQueued(){create();when(index.configured()).thenReturn(false);assertFalse(jobs.processOne());assertEquals("QUEUED",jdbc.queryForObject("SELECT state FROM knowledge_job",String.class));}
    @Test void invalidContentStopsAutomaticRetriesAndDoesNotPublish(){create();when(index.ingest(any())).thenThrow(new ObjectiveException(400,"content rejected"));assertTrue(jobs.processOne());assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM knowledge_job",String.class));assertEquals("INVALID_CONTENT",jdbc.queryForObject("SELECT last_error FROM knowledge_job",String.class));assertFalse(jobs.processOne());}
    @Test void publicSearchUsesOnlyCurrentPublishedAuthorizedVersionsAndNoModel(){
        String publicId=create();publish(publicId);KnowledgeDTO.Upload hidden=upload("hidden");hidden.setVisibility("ADMIN");String adminId=(String)manager.create(hidden).get("id");publish(adminId);create();
        when(index.retrieve(anyString(),anyList(),anyInt())).thenReturn(Collections.singletonList(hit(publicId,1)));administrator=false;
        Map<String,Object> result=manager.search(search());assertEquals(false,result.get("modelUsed"));assertNull(result.get("content"));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<KnowledgeDTO.VersionRef>> refs=ArgumentCaptor.forClass(List.class);verify(index).retrieve(anyString(),refs.capture(),eq(5));
        assertEquals(1,refs.getValue().size());assertEquals(publicId,refs.getValue().get(0).getDocumentId());
        Map<?,?> item=(Map<?,?>)((List<?>)result.get("items")).get(0);assertEquals("K1",item.get("citationId"));assertTrue(((String)item.get("url")).startsWith("/knowledge/"+publicId));assertFalse(item.containsKey("contentHash"));
    }
    @Test void explicitEmptyOrInvisibleDocumentSelectionNeverQueriesAll(){
        KnowledgeDTO.Upload hidden=upload("private");hidden.setVisibility("ADMIN");String id=(String)manager.create(hidden).get("id");publish(id);administrator=false;
        KnowledgeDTO.Search dto=search();dto.setDocumentIds(Collections.emptyList());assertEquals(0,((List<?>)manager.search(dto).get("items")).size());
        dto.setDocumentIds(Collections.singletonList(id));assertEquals(0,((List<?>)manager.search(dto).get("items")).size());verify(index,never()).retrieve(anyString(),anyList(),anyInt());
    }
    @Test void visibilityChangeDuringRetrievalSuppressesExcerpts(){
        String id=create();publish(id);KnowledgeDTO.Hit hit=hit(id,1);administrator=false;
        when(index.retrieve(anyString(),anyList(),anyInt())).thenAnswer(call->{jdbc.update("UPDATE knowledge_document SET visibility='ADMIN',revision=revision+1 WHERE id=?",id);return Collections.singletonList(hit);});
        fail(()->manager.search(search()),409);fail(()->manager.read(id,1),404);
    }
    @Test void rejectsWithdrawalOfAnEarlierSourceDuringTheFinalExcerptCheck() {
        String earlier = create();
        String later = create();
        publish(earlier);
        publish(later);
        KnowledgeDTO.Hit first = hit(earlier, 1);
        KnowledgeDTO.Hit second = hit(later, 1);
        second.setChunkId(String.join("", Collections.nCopies(64, "b")));
        when(index.retrieve(anyString(), anyList(), anyInt())).thenReturn(Arrays.asList(first, second));
        administrator = false;

        AtomicInteger readsOfLaterSource = new AtomicInteger();
        JdbcTemplate verifyingJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
                List<Map<String,Object>> rows = super.queryForList(sql, args);
                if (sql.contains("AS version_state") && sql.contains("v.content,")
                        && later.equals(args[1]) && readsOfLaterSource.incrementAndGet() == 2) {
                    // An independent connection commits a withdrawal after validation read the earlier source.
                    assertEquals(1, jdbc.update("UPDATE knowledge_document "
                            + "SET published_version=NULL,revision=revision+1 WHERE id=?", earlier));
                }
                return rows;
            }
        };
        ReflectionTestUtils.setField(manager, "jdbc", verifyingJdbc);
        fail(() -> manager.search(search()), 409);
        assertEquals(2, readsOfLaterSource.get());
    }

    @Test void unpublishOrPublishRoundTripInvalidatesAlreadyRetrievedContext(){
        String id=create();publish(id);when(index.retrieve(anyString(),anyList(),anyInt())).thenReturn(Collections.singletonList(hit(id,1)));
        KnowledgeManager.Context context=manager.retrieve(actor,search());manager.unpublish(id,change(id,1));manager.publish(id,change(id,1));fail(()->manager.verify(context),409);
    }
    @Test void corruptExcerptOrTitleCannotBecomeAServerCitation(){
        String id=create();publish(id);KnowledgeDTO.Hit hit=hit(id,1);hit.setExcerpt("not present in authoritative original");when(index.retrieve(anyString(),anyList(),anyInt())).thenReturn(Collections.singletonList(hit));fail(()->manager.search(search()),502);
        hit.setExcerpt("前缀和");hit.setTitle("wrong title");fail(()->manager.search(search()),502);
    }
    @Test void contestStartingDuringRetrievalSuppressesAllResults(){
        String id=create();publish(id);when(index.retrieve(anyString(),anyList(),anyInt())).thenAnswer(call->{doThrow(new ObjectiveException(403,"contest")).when(access).requireChat(actor);return Collections.singletonList(hit(id,1));});
        fail(()->manager.search(search()),403);fail(()->manager.read(id,1),403);
    }
    @Test void invalidInputAndSourceSelectorsCannotReachIndex(){
        KnowledgeDTO.Search dto=search();dto.setDocumentIds(Collections.singletonList("../../private"));fail(()->manager.search(dto),400);
        dto.setDocumentIds(null);dto.setTopK(11);fail(()->manager.search(dto),400);dto.setTopK(5);dto.setQuery(" ");fail(()->manager.search(dto),400);verify(index,never()).retrieve(anyString(),anyList(),anyInt());
    }
    @Test void staleMetadataAndReindexCommandsCannotOverwriteCurrentPublication(){
        String id=create();publish(id);KnowledgeDTO.Change stale=change(id,1);manager.unpublish(id,change(id,1));fail(()->manager.publish(id,stale),409);fail(()->manager.remove(id,stale),409);
        manager.retry(id,1,change(id,1));assertEquals("INDEXED",jdbc.queryForObject("SELECT state FROM knowledge_version",String.class));
    }
    @Test void directoryShowsPublishedTitlesOnlyAndNeverReturnsBodyOrDraftMetadata(){
        String id=create();publish(id);KnowledgeDTO.Upload next=upload("private draft body");next.setTitle("尚未发布的标题");next.setExpectedRevision(revision(id));manager.upload(id,next);
        KnowledgeDTO.Upload hidden=upload("admin content");hidden.setVisibility("ADMIN");String adminId=(String)manager.create(hidden).get("id");jobs.processOne();jobs.processOne();manager.publish(adminId,change(adminId,1));
        administrator=false;Map<String,Object> directory=manager.directory(1,null);assertEquals(1L,directory.get("total"));Map<?,?> row=(Map<?,?>)((List<?>)directory.get("items")).get(0);
        assertEquals("算法讲义",row.get("title"));assertFalse(row.containsKey("content"));assertFalse(row.containsKey("revision"));assertEquals(0L,manager.directory(1,"尚未发布").get("total"));
    }
}
