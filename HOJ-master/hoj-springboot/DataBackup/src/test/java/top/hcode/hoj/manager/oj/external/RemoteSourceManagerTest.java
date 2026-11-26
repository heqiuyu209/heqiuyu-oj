package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.crawler.problem.ProblemStrategy;
import top.hcode.hoj.manager.admin.problem.RemoteProblemManager;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;
import top.hcode.hoj.pojo.entity.problem.Problem;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated transactions use the migration itself. No remote access or existing database. */
class RemoteSourceManagerTest {
    JdbcTemplate jdbc;
    ObjectMapper json=new ObjectMapper();
    RemoteCatalogManager catalogue;
    RemoteStatementManager statements;
    RemoteProblemManager remote;
    volatile long clock;
    String actor;
    boolean privileged;
    final String key="cf:contest:4:A";

    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:remote_sources;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        initialize(source);
    }
    void initialize(DriverManagerDataSource source) throws Exception {
        jdbc=new JdbcTemplate(source);
        if(source.getUrl().startsWith("jdbc:h2:"))jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,username VARCHAR(100),status INT)");
        jdbc.execute("CREATE TABLE role(id INT PRIMARY KEY,role VARCHAR(32))");
        jdbc.execute("CREATE TABLE user_role(uid VARCHAR(32),role_id INT)");
        jdbc.execute("CREATE TABLE problem(id BIGINT AUTO_INCREMENT PRIMARY KEY,problem_id VARCHAR(50),author VARCHAR(100),auth INT,is_remote BOOLEAN,is_group BOOLEAN,gid BIGINT,title VARCHAR(255),description TEXT,input TEXT,output TEXT,hint TEXT,examples TEXT,time_limit INT,memory_limit INT,rating INT,gmt_modified TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE language(id INT PRIMARY KEY,oj VARCHAR(20))");
        jdbc.update("INSERT INTO language VALUES(1,'CF')");
        jdbc.update("INSERT INTO user_info VALUES('alice','Alice',0),('bob','Bob',0)");
        jdbc.update("INSERT INTO role VALUES(1,'root'),(2,'admin')");
        jdbc.update("INSERT INTO user_role VALUES('alice',2),('bob',2)");
        for(String path:Arrays.asList("external-learning.sql","remote-sources.sql","atcoder-learning.sql","nowcoder-sources.sql")) {
            String ddl=StreamUtils.copyToString(new ClassPathResource(path).getInputStream(),StandardCharsets.UTF_8);
            try(Connection c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ByteArrayResource(ddl.replace(" COLLATE=utf8mb4_unicode_ci", "").replace("DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 5 SECOND)","CURRENT_TIMESTAMP(3)").getBytes(StandardCharsets.UTF_8)));}
        }
        clock=1800000000000L;actor="alice";privileged=false;
        catalogue=new RemoteCatalogManager(){@Override protected long now(){return clock;}};
        statements=new RemoteStatementManager(){@Override protected long now(){return clock;}@Override protected String uid(){return actor;}@Override protected boolean privileged(){return privileged;}};
        remote=mock(RemoteProblemManager.class);
        when(remote.adminAddOtherOJProblem(any(),anyString())).thenAnswer(invocation->{
            Problem p=((ProblemStrategy.RemoteProblemInfo)invocation.getArgument(0)).getProblem();
            return insertProblem(p);
        });
        for(Object manager:Arrays.asList(catalogue,statements)) {
            ReflectionTestUtils.setField(manager,"jdbc",jdbc);ReflectionTestUtils.setField(manager,"transactions",new DataSourceTransactionManager(source));ReflectionTestUtils.setField(manager,"json",json);
        }
        ReflectionTestUtils.setField(statements,"remoteProblems",remote);
        sync(problem(4,"A",800),problem(10,"B",null));
    }
    ObjectNode problem(int id,String index,Integer rating){ObjectNode p=json.createObjectNode().put("contestId",id).put("index",index).put("name","Problem "+id+index);if(rating!=null)p.put("rating",rating);p.putArray("tags").add("math").add("dp");return p;}
    ObjectNode data(ObjectNode... problems){ObjectNode result=json.createObjectNode();ArrayNode a=result.putArray("problems");for(ObjectNode p:problems)a.add(p);return result;}
    void sync(ObjectNode... problems){jdbc.update("UPDATE remote_source_job SET next_run_at=?,lease_until=NULL",new Timestamp(clock));catalogue.apply(catalogue.claim(),data(problems));}
    Content content(String title){Content c=new Content();c.setTitle(title);c.setDescription("<p>Count the integers.</p>");c.setInput("<p>n</p>");c.setOutput("<p>answer</p>");c.setHint("");c.setExamples("<input>3</input><output>6</output>");c.setTimeLimit(1000);c.setMemoryLimit(256);return c;}
    RemoteStatementDTO dto(Content content,String hash){RemoteStatementDTO d=new RemoteStatementDTO();d.setProblemKey(key);d.setContent(content);d.setSourceHash(hash);return d;}
    @SuppressWarnings("unchecked") String hash(Map<String,Object> view){return (String)((Map<String,Object>)view.get("source")).get("contentHash");}
    Map<String,Object> english(String title){Map<String,Object> view=statements.adminView(key);return statements.save(dto(content(title),view.get("source")==null?null:hash(view)),false);}
    long pid(){return jdbc.queryForObject("SELECT id FROM problem WHERE problem_id='CF-4A'",Long.class);}
    Problem insertProblem(Problem p){
        jdbc.update("INSERT INTO problem(problem_id,author,auth,is_remote,is_group,title,description,input,output,hint,examples,time_limit,memory_limit,rating,gmt_modified) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",p.getProblemId(),p.getAuthor(),p.getAuth(),p.getIsRemote(),p.getIsGroup(),p.getTitle(),p.getDescription(),p.getInput(),p.getOutput(),p.getHint(),p.getExamples(),p.getTimeLimit(),p.getMemoryLimit(),p.getRating(),new Timestamp(clock));
        p.setId(jdbc.queryForObject("SELECT id FROM problem WHERE problem_id=?",Long.class,p.getProblemId()));return p;
    }
    Map<String,Object> library(){return jdbc.queryForMap("SELECT title,description,input,output,hint,examples,time_limit,memory_limit,auth,rating,gmt_modified FROM problem WHERE id=?",pid());}

    @Test void cfHtmlPreviewLeavesTheCatalogueUnpublishedUntilExplicitSave() {
        CodeforcesStatementClient client = new CodeforcesStatementClient();
        try {
            ReflectionTestUtils.setField(statements, "codeforcesStatements", client);
            RemoteStatementDTO.HtmlPreview input = new RemoteStatementDTO.HtmlPreview();
            input.setProblemKey(key);
            input.setHtml("<div class='problem-statement'><div class='header'><div class='title'>A. Example</div>"
                    + "<div class='time-limit'>1 second</div><div class='memory-limit'>256 megabytes</div></div>"
                    + "<div><p>Read $$$n$$$.</p><script>unused()</script></div></div>");
            Map<String, Object> result = statements.previewCodeforcesHtml(input);
            Content parsed = (Content) result.get("content");
            assertEquals("Problem 4A", result.get("catalogTitle"));
            assertTrue(parsed.getDescription().contains("$n$"));
            assertFalse(parsed.getDescription().contains("unused"));
            assertNull(statements.adminView(key).get("source"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM remote_problem_import WHERE problem_key=?", Integer.class, key));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM problem WHERE problem_id='CF-4A'", Integer.class));
            Map<String, Object> saved = statements.save(dto(parsed, null), false);
            assertNotNull(saved.get("source"));
            assertEquals(2, ((Number) library().get("auth")).intValue());
        } finally {
            client.close();
        }
    }

    @Test void translationSourceRechecksActualRolesOwnershipVisibilityAndActiveStatus(){
        english("Source");assertNotNull(statements.translationSource(key).get("source"));
        actor="bob";privileged=true;jdbc.update("UPDATE user_role SET role_id=1 WHERE uid='bob'");assertNotNull(statements.translationSource(key).get("source"));
        jdbc.update("UPDATE user_role SET role_id=2 WHERE uid='bob'");assertEquals(403,assertThrows(ObjectiveException.class,()->statements.translationSource(key)).getStatus());
        jdbc.update("DELETE FROM user_role WHERE uid='bob'");assertEquals(403,assertThrows(ObjectiveException.class,()->statements.translationSource(key)).getStatus());
        actor="alice";privileged=false;jdbc.update("UPDATE user_info SET status=1 WHERE uuid='alice'");assertEquals(403,assertThrows(ObjectiveException.class,()->statements.translationSource(key)).getStatus());
        jdbc.update("UPDATE user_info SET status=0 WHERE uuid='alice'");sync(problem(10,"B",null));assertEquals(404,assertThrows(ObjectiveException.class,()->statements.translationSource(key)).getStatus());
    }
    @Test void successfulControlledTranslationLeavesSourceVersionsPointersAndPublicationUntouched() throws Exception {
        Map<String,Object> source=english("Synthetic source");long sourceId=jdbc.queryForObject("SELECT version_id FROM remote_statement_current WHERE problem_key=?",Long.class,key);
        top.hcode.hoj.manager.oj.ai.AiAssistantManager assistant=mock(top.hcode.hoj.manager.oj.ai.AiAssistantManager.class);
        when(assistant.generateTranslation(anyList(),anyString(),any())).thenAnswer(call->{
            ((Runnable)call.getArgument(2)).run();List<Map<String,String>> messages=call.getArgument(0);JsonNode material=json.readTree(messages.get(1).get("content"));
            Map<String,Object> response=new LinkedHashMap<>();response.put("content",material.toString());response.put("model","controlled-model");return response;
        });
        RemoteTranslationManager translations=new RemoteTranslationManager();ReflectionTestUtils.setField(translations,"statements",statements);ReflectionTestUtils.setField(translations,"assistant",assistant);ReflectionTestUtils.setField(translations,"json",json);
        top.hcode.hoj.pojo.dto.RemoteTranslationDTO dto=new top.hcode.hoj.pojo.dto.RemoteTranslationDTO();dto.setProblemKey(key);dto.setSourceHash(hash(source));dto.setModelFingerprint(hash(source));dto.setAllowModelSource(true);
        Map<String,Object> draft=translations.generate(dto);assertEquals(true,draft.get("draft"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_version WHERE problem_key=?",Integer.class,key));
        assertEquals(sourceId,jdbc.queryForObject("SELECT version_id FROM remote_statement_current WHERE problem_key=?",Long.class,key));assertNull(jdbc.queryForObject("SELECT translation_version_id FROM remote_statement_current WHERE problem_key=?",Long.class,key));assertEquals(2,jdbc.queryForObject("SELECT auth FROM problem WHERE id=?",Integer.class,pid()));
        statements.save(dto((Content)draft.get("content"),hash(source)),true);assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_version WHERE problem_key=?",Integer.class,key));assertNotNull(jdbc.queryForObject("SELECT translation_version_id FROM remote_statement_current WHERE problem_key=?",Long.class,key));
    }

    @Test void cataloguesTrackChangesRemovalAndReturnWithoutFakeRatings(){
        assertEquals(2,catalogue.status().get("total"));
        long original=jdbc.queryForObject("SELECT revision FROM remote_catalog_problem WHERE problem_key=?",Long.class,key);
        clock+=1000;ObjectNode reordered=problem(4,"A",800);reordered.putArray("tags").add("dp").add("math").add("math");
        sync(reordered,problem(10,"B",null));assertEquals(original,jdbc.queryForObject("SELECT revision FROM remote_catalog_problem WHERE problem_key=?",Long.class,key));
        assertNull(jdbc.queryForObject("SELECT rating FROM remote_catalog_problem WHERE contest_id=10",Integer.class));
        clock+=1000;sync(problem(4,"A",900));assertEquals(1,catalogue.status().get("changed"));assertEquals(1,catalogue.status().get("removed"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM external_problem",Integer.class));
        clock+=1000;sync(problem(4,"A",900),problem(10,"B",null));assertEquals(1,catalogue.status().get("changed"));
        assertEquals(3L,jdbc.queryForObject("SELECT revision FROM remote_catalog_problem WHERE contest_id=10",Long.class));
        assertEquals(1L,catalogue.list(1,"4A",850,1000).get("total"));assertThrows(ObjectiveException.class,()->catalogue.list(0,null,null,null));
    }
    @Test void invalidSnapshotAndFailedOrLostLeasesRetainPublishedCache(){
        jdbc.update("UPDATE remote_source_job SET next_run_at=? WHERE source_name='CODEFORCES_PROBLEMS'",new Timestamp(clock));Map<String,Object> job=catalogue.claim();
        ObjectNode invalid=problem(20,"A",1000);invalid.put("rating",-1);
        assertThrows(IllegalArgumentException.class,()->catalogue.apply(job,data(problem(30,"A",1500),invalid)));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM remote_catalog_problem",Integer.class));
        catalogue.failed(job,"upstream unavailable");assertEquals("BACKOFF",catalogue.status().get("state"));assertNull(catalogue.claim());
        clock+=60001;Map<String,Object> newer=catalogue.claim();catalogue.apply(job,data(problem(99,"A",800)));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM remote_catalog_problem",Integer.class));
        catalogue.apply(newer,data(problem(30,"A",1000)));assertEquals(1,catalogue.status().get("total"));
    }
    @Test void catalogRequestsAreRateLimitedAndDoNotReplaceRunningWork(){
        catalogue.requestSync();assertThrows(ObjectiveException.class,catalogue::requestSync);
        clock+=600001;assertNotNull(catalogue.claim());assertEquals(409,assertThrows(ObjectiveException.class,catalogue::requestSync).getStatus());
    }
    @Test void importingIsHiddenAndVersionsAndTranslationsAreImmutableAndBound(){
        Map<String,Object> first=english("English A");assertEquals(2,jdbc.queryForObject("SELECT auth FROM problem",Integer.class));
        assertThrows(ObjectiveException.class,()->statements.publicView(pid()));
        jdbc.update("UPDATE problem SET auth=1");assertEquals(true,statements.publicView(pid()).get("available"));
        statements.save(dto(content("中文 A"),hash(first)),true);assertNotNull(statements.publicView(pid()).get("translation"));
        Map<String,Object> second=english("English B");assertNotEquals(hash(first),hash(second));
        assertEquals(true,statements.adminView(key).get("translationStale"));assertNull(statements.publicView(pid()).get("translation"));
        assertEquals(409,assertThrows(ObjectiveException.class,()->statements.save(dto(content("旧译文"),hash(first)),true)).getStatus());
        statements.save(dto(content("中文 B"),hash(second)),true);
        english("English A");assertNull(statements.publicView(pid()).get("translation"));
        statements.save(dto(content("中文 A"),hash(first)),true);assertNotNull(statements.publicView(pid()).get("translation"));
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_version",Integer.class));
        assertEquals("English A",jdbc.queryForObject("SELECT title FROM problem",String.class));
        verify(remote,times(1)).adminAddOtherOJProblem(any(),eq("CF"));
    }
    @Test void incompleteOrStaleManualEditsAndMissingLanguagesRollBack(){
        Content invalid=content("Unsafe");invalid.setDescription("<script>alert(1)</script>");assertThrows(ObjectiveException.class,()->statements.save(dto(invalid,null),false));
        jdbc.update("DELETE FROM language");assertThrows(ObjectiveException.class,()->english("Missing language"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_version",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_current",Integer.class));
        jdbc.update("INSERT INTO language VALUES(1,'CF')");english("English A");
        assertEquals(409,assertThrows(ObjectiveException.class,()->statements.save(dto(content("Overwrite"),null),false)).getStatus());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_version",Integer.class));
    }
    @Test void refreshPreservesManualProblemAndWorkerRechecksRevokedPermissions(){
        english("Original");jdbc.update("UPDATE problem SET title='Manual title',description='Manual description'");
        clock+=60001;statements.request(key);Map<String,Object> job=statements.claim();assertNotNull(job);
        Map<String,Object> revokedJob=job;
        jdbc.update("DELETE FROM user_role WHERE uid='alice'");assertThrows(ObjectiveException.class,()->statements.apply(revokedJob,content("Source update")));
        statements.failed(job,"permission changed");assertEquals("Manual title",jdbc.queryForObject("SELECT title FROM problem",String.class));
        jdbc.update("INSERT INTO user_role VALUES('alice',2)");clock+=60001;job=statements.claim();statements.apply(job,content("Source update"));
        assertEquals("Manual description",jdbc.queryForObject("SELECT description FROM problem",String.class));assertEquals("DONE",jdbc.queryForObject("SELECT state FROM remote_problem_import",String.class));
    }
    @Test void sourceRefreshUpdatesUntouchedLibraryAndPreservesSiteRatingAndVisibility(){
        statements.request(key);statements.apply(statements.claim(),content("Original"));
        Map<String,Object> first=statements.adminView(key);statements.save(dto(content("中文"),hash(first)),true);
        jdbc.update("UPDATE problem SET auth=1,rating=2400 WHERE id=?",pid());
        clock+=86400001;Content changed=content("Changed");changed.setDescription("<p>Updated task.</p>");changed.setInput("<p>x</p>");changed.setOutput("<p>y</p>");changed.setHint("<p>Updated hint.</p>");changed.setExamples("<input>4</input><output>8</output>");changed.setTimeLimit(2000);changed.setMemoryLimit(512);
        statements.apply(statements.claim(),changed);Map<String,Object> actual=library();
        assertEquals("Changed",actual.get("title"));assertEquals(changed.getDescription(),actual.get("description"));assertEquals(changed.getInput(),actual.get("input"));assertEquals(changed.getOutput(),actual.get("output"));assertEquals(changed.getHint(),actual.get("hint"));assertEquals(changed.getExamples(),actual.get("examples"));assertEquals(2000,actual.get("time_limit"));assertEquals(512,actual.get("memory_limit"));assertEquals(1,actual.get("auth"));assertEquals(2400,actual.get("rating"));assertEquals(new Timestamp(clock),actual.get("gmt_modified"));
        assertNull(statements.publicView(pid()).get("translation"));
        Map<String,Object> saved=new LinkedHashMap<>(actual);clock+=86400001;statements.apply(statements.claim(),changed);assertEquals(saved,library(),"Identical refresh must not touch the library modification time");
    }
    @ParameterizedTest @ValueSource(strings={"title","description","input","output","hint","examples","time_limit","memory_limit"})
    void aManualChangeToAnyStatementFieldPreservesTheWholeLibraryCopy(String field){
        statements.request(key);statements.apply(statements.claim(),content("Original"));
        Object value=library().get(field);Object edited=value instanceof Number?((Number)value).intValue()+1:field.equals("title")?"original":String.valueOf(value)+" ";
        jdbc.update("UPDATE problem SET "+field+"=? WHERE id=?",edited,pid());Map<String,Object> manual=library();
        clock+=86400001;statements.apply(statements.claim(),content("Changed source"));
        assertEquals(manual,library());assertEquals("Changed source",((JsonNode)((Map)statements.adminView(key).get("source")).get("content")).path("title").asText());
    }
    @Test void manuallySavingSourceDoesNotRewriteTheLibraryCopy(){
        statements.request(key);statements.apply(statements.claim(),content("Original"));Map<String,Object> original=library();
        english("Manually maintained source");assertEquals(original,library());assertFalse(auto(statements.adminView(key)));
    }
    @Test void aLegacyLibraryWithoutAnEarlierSourceVersionIsPreserved(){
        insertProblem(new Problem().setProblemId("CF-4A").setAuthor("Alice").setAuth(1).setIsRemote(true).setIsGroup(false).setTitle("Existing library").setDescription("Legacy manual content"));
        Map<String,Object> original=library();statements.request(key);statements.apply(statements.claim(),content("Fetched source"));
        assertEquals(original,library());assertNotNull(statements.publicView(pid()).get("source"));
    }
    @Test void libraryAndSourcePointerRollBackIfImportFinalizationFails(){
        statements.request(key);statements.apply(statements.claim(),content("Original"));Map<String,Object> original=library();String oldHash=hash(statements.adminView(key));
        clock+=86400001;Map<String,Object> job=statements.claim();jdbc.execute("ALTER TABLE remote_problem_import ADD CONSTRAINT reject_done CHECK (state<>'DONE')");
        org.springframework.dao.DataAccessException failure=assertThrows(org.springframework.dao.DataAccessException.class,()->statements.apply(job,content("Changed source")));
        assertTrue(failure.getMostSpecificCause().getMessage().toLowerCase(Locale.ROOT).contains("reject_done"));
        assertEquals(original,library());assertEquals(oldHash,hash(statements.adminView(key)));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_version",Integer.class));assertEquals("RUNNING",jdbc.queryForObject("SELECT state FROM remote_problem_import",String.class));
    }
    @Test void ownershipConflictsAndPrivateOrGroupStatementsCannotLeak(){
        english("Owned");actor="bob";assertEquals(403,assertThrows(ObjectiveException.class,()->statements.request(key)).getStatus());assertThrows(ObjectiveException.class,()->statements.adminView(key));
        privileged=true;assertNotNull(statements.adminView(key));
        jdbc.update("UPDATE problem SET is_remote=FALSE");assertEquals(409,assertThrows(ObjectiveException.class,()->statements.request(key)).getStatus());
        jdbc.update("UPDATE problem SET is_remote=TRUE,is_group=TRUE,auth=1");assertThrows(ObjectiveException.class,()->statements.publicView(pid()));
        jdbc.update("UPDATE problem SET is_group=FALSE,auth=3");assertThrows(ObjectiveException.class,()->statements.publicView(pid()));
        jdbc.update("UPDATE problem SET auth=1,gid=99");assertThrows(ObjectiveException.class,()->statements.publicView(pid()));assertEquals(409,assertThrows(ObjectiveException.class,()->statements.adminView(key)).getStatus());
    }
    @Test void queueRetriesAreBoundedAndObsoleteLeaseCannotPublish(){
        statements.request(key);assertEquals(429,assertThrows(ObjectiveException.class,()->statements.request(key)).getStatus());
        Map<String,Object> old=statements.claim();clock+=90001;Map<String,Object> job=statements.claim();assertNotEquals(old.get("lease_token"),job.get("lease_token"));
        statements.apply(old,content("Obsolete"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM problem",Integer.class));
        statements.failed(job,"HTTP 403");assertNull(statements.claim());clock+=60001;
        statements.failed(statements.claim(),"HTTP 403");clock+=120001;statements.failed(statements.claim(),"HTTP 403");
        assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM remote_problem_import",String.class));assertNull(statements.claim());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_version",Integer.class));
    }
    @Test void deactivatedQueuedActorAndLegacyProblemAreHandledWithoutPublishing(){
        statements.request(key);jdbc.update("UPDATE user_info SET status=1 WHERE uuid='alice'");assertNull(statements.claim());assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM remote_problem_import",String.class));
        jdbc.update("INSERT INTO problem(problem_id,author,auth,is_remote,is_group) VALUES('CF-12A','Alice',1,TRUE,FALSE)");
        long legacy=jdbc.queryForObject("SELECT id FROM problem",Long.class);assertEquals(false,statements.publicView(legacy).get("available"));
    }

    AtCoderCatalogManager atcoderSeed(){
        AtCoderCatalogManager manager=new AtCoderCatalogManager(){@Override protected long now(){return clock;}};
        ReflectionTestUtils.setField(manager,"jdbc",jdbc);ReflectionTestUtils.setField(manager,"transactions",new DataSourceTransactionManager(jdbc.getDataSource()));
        jdbc.update("INSERT INTO language VALUES(2,'AC')");jdbc.update("UPDATE remote_source_job SET next_run_at=NULL WHERE source_name IN ('ATCODER_PROBLEMS','ATCODER_MODELS')");
        jdbc.update("UPDATE remote_source_job SET next_run_at=? WHERE source_name='ATCODER_PROBLEMS'",new Timestamp(clock));
        ArrayNode problems=json.createArrayNode();problems.addObject().put("id","abc086_a").put("contest_id","abc086").put("problem_index","A").put("title","Product");manager.apply(manager.claim(),problems);return manager;
    }
    RemoteStatementDTO atDto(Content c,String hash){RemoteStatementDTO d=new RemoteStatementDTO();d.setProblemKey("atcoder:task:abc086_a");d.setContent(c);d.setSourceHash(hash);return d;}
    @Test void atcoderUsesItsOwnCatalogAndDefaultPrivateProblemWithoutInventingSiteRating(){
        AtCoderCatalogManager at=atcoderSeed();jdbc.update("UPDATE external_atcoder_problem SET estimated_difficulty=500");
        Map<String,Object> list=at.list(1,"abc086",400,600);assertEquals(1L,list.get("total"));Map row=(Map)((List)list.get("records")).get(0);assertNull(row.get("rating"));assertEquals(500,row.get("estimatedDifficulty"));
        Content c=content("Product");c.setDescription("<p>Multiply <var>a</var>.</p><img src='https://img.atcoder.jp/a.png'>");Map<String,Object> view=statements.save(atDto(c,null),false);
        long id=jdbc.queryForObject("SELECT id FROM problem WHERE problem_id='AC-abc086_a'",Long.class);assertEquals(2,jdbc.queryForObject("SELECT auth FROM problem WHERE id=?",Integer.class,id));assertEquals("ATCODER",view.get("platform"));assertTrue(view.get("sourceUrl").toString().endsWith("/abc086/tasks/abc086_a"));
        org.mockito.ArgumentCaptor<ProblemStrategy.RemoteProblemInfo> capture=org.mockito.ArgumentCaptor.forClass(ProblemStrategy.RemoteProblemInfo.class);verify(remote).adminAddOtherOJProblem(capture.capture(),eq("AC"));assertNull(capture.getValue().getProblem().getRating());assertTrue(capture.getValue().getProblem().getDescription().contains("$a$"));
        assertThrows(ObjectiveException.class,()->statements.publicView(id));jdbc.update("UPDATE problem SET auth=1 WHERE id=?",id);statements.save(atDto(content("中文题面"),hash(view)),true);assertNotNull(statements.publicView(id).get("translation"));
        Content revised=content("Revised");statements.save(atDto(revised,hash(view)),false);assertEquals(true,statements.publicView(id).get("translationStale"));assertNull(statements.publicView(id).get("translation"));
        assertTrue(jdbc.queryForObject("SELECT description FROM problem WHERE id=?",String.class,id).contains("$a$"));actor="bob";assertThrows(ObjectiveException.class,()->statements.adminView("atcoder:task:abc086_a"));
    }
    @Test void atcoderFetchPinsTheRealContestAndRejectsChangedMetadataBeforePublication(){
        atcoderSeed();String key="atcoder:task:abc086_a";statements.request(key);Map<String,Object> job=statements.claim(),source=statements.atcoderSource(job);assertEquals("abc086",source.get("contest_id"));
        Map<String,Object> sourceJob=job;jdbc.update("UPDATE external_atcoder_problem SET contest_id='other',content_hash='changed'");assertThrows(ObjectiveException.class,()->statements.apply(new HashMap<>(sourceJob),content("stale")));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_version",Integer.class));
        statements.defer(job);assertEquals(0,jdbc.queryForObject("SELECT failures FROM remote_problem_import",Integer.class));clock+=3001;job=statements.claim();statements.atcoderSource(job);statements.apply(job,content("fresh"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM problem WHERE problem_id='AC-abc086_a'",Integer.class));
    }
    @Test void atcoderCatalogUpdatesRemainRateLimitedAndDoNotClearThePublishedDirectory(){
        AtCoderCatalogManager at=atcoderSeed();assertThrows(ObjectiveException.class,()->at.list(0,null,null,null));at.requestSync();assertEquals(429,assertThrows(ObjectiveException.class,at::requestSync).getStatus());assertEquals(1L,at.list(1,null,null,null).get("total"));
    }

    @Test void missingStaticAtcoderLanguagesUseLocalEditorConfigurationWithoutSourceLanguageIds(){
        atcoderSeed();jdbc.update("DELETE FROM language WHERE oj='AC'");jdbc.update("INSERT INTO language VALUES(3,'ME')");
        when(remote.adminAddOtherOJProblem(any(),eq("AC"),eq(true))).thenAnswer(invocation->insertProblem(((ProblemStrategy.RemoteProblemInfo)invocation.getArgument(0)).getProblem()));
        assertNotNull(statements.save(atDto(content("Product"),null),false).get("source"));verify(remote).adminAddOtherOJProblem(any(),eq("AC"),eq(true));
    }

    boolean auto(Map<String,Object> view){return Boolean.TRUE.equals(((Map)view.get("refresh")).get("enabled"));}
    @Test void sourceImportsRefreshDailyWithoutDuplicateVersionsAndInvalidateChineseOnlyOnChange(){
        statements.request(key);statements.apply(statements.claim(),content("Original"));assertTrue(auto(statements.adminView(key)));assertNull(statements.claim());
        Map<String,Object> initial=statements.adminView(key);long id=pid();jdbc.update("UPDATE problem SET auth=1,description='Library edit' WHERE id=?",id);statements.save(dto(content("中文"),hash(initial)),true);
        clock+=86400001;Map<String,Object> refresh=statements.claim();assertNotNull(refresh);statements.apply(refresh,content("Original"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_version",Integer.class));assertNotNull(statements.publicView(id).get("translation"));
        clock+=86400001;statements.apply(statements.claim(),content("Changed"));assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_version",Integer.class));assertNull(statements.publicView(id).get("translation"));assertEquals("Library edit",jdbc.queryForObject("SELECT description FROM problem WHERE id=?",String.class,id));assertEquals(true,((Map)((List)catalogue.list(1,"4A",null,null).get("records")).get(0)).get("translationStale"));
    }
    @Test void disablingRefreshOrSavingManualEnglishFencesOldCallbacksAndKeepsEditorPolicyPrivate(){
        english("Manual");assertFalse(auto(statements.adminView(key)));assertThrows(ObjectiveException.class,()->statements.refresh(key,null));statements.refresh(key,true);
        clock+=86400001;Map<String,Object> old=statements.claim();statements.refresh(key,false);statements.apply(old,content("Obsolete"));assertEquals("Manual",((com.fasterxml.jackson.databind.JsonNode)((Map)statements.adminView(key).get("source")).get("content")).path("title").asText());assertNull(statements.claim());
        statements.refresh(key,true);english("Manual corrected");assertFalse(auto(statements.adminView(key)));assertNull(statements.claim());
        jdbc.update("UPDATE problem SET auth=1");assertFalse(statements.publicView(pid()).containsKey("refresh"));actor="bob";assertThrows(ObjectiveException.class,()->statements.refresh(key,true));
    }
    @Test void failedDailyChecksRetainContentAndResumeOnTheNextDayWithAFreshRetryBudget(){
        statements.request(key);statements.apply(statements.claim(),content("Original"));clock+=86400001;
        statements.failed(statements.claim(),"source unavailable");clock+=60001;statements.failed(statements.claim(),"source unavailable");clock+=120001;statements.failed(statements.claim(),"source unavailable");
        assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM remote_problem_import",String.class));assertNotNull(jdbc.queryForObject("SELECT next_run_at FROM remote_problem_import",Timestamp.class));assertNotNull(statements.adminView(key).get("source"));assertNull(statements.claim());clock+=86400001;
        Map<String,Object> next=statements.claim();assertEquals(0,((Number)next.get("failures")).intValue());statements.apply(next,content("Original"));assertNull(((Map)statements.adminView(key).get("refresh")).get("lastError"));
    }

    @Test void nowcoderChineseSourceAndEnglishTranslationUseTheirActualLanguagesAndFenceExpiredCallbacks(){
        jdbc.update("INSERT INTO language VALUES(3,'ME')");NowCoderCatalogManager nc=new NowCoderCatalogManager(){@Override protected long now(){return clock;}};ReflectionTestUtils.setField(nc,"jdbc",jdbc);ReflectionTestUtils.setField(nc,"transactions",new DataSourceTransactionManager(jdbc.getDataSource()));ReflectionTestUtils.setField(statements,"nowcoderCatalogue",nc);
        when(remote.adminAddOtherOJProblem(any(),eq("NC"),eq(true))).thenAnswer(invocation->insertProblem(((ProblemStrategy.RemoteProblemInfo)invocation.getArgument(0)).getProblem()));
        String key="nowcoder:acm:6";NowCoderStatementClient.Statement source=new NowCoderStatementClient.Statement("8","6",content("中文原文"));nc.discovered(key,source);statements.request(key);statements.applyNowCoder(statements.claim(),source);Map<String,Object> view=statements.adminView(key);assertEquals("zh-CN",view.get("sourceLanguage"));assertEquals("en",view.get("translationLanguage"));assertEquals("zh-CN",((Map)view.get("source")).get("language"));assertTrue(auto(view));
        RemoteStatementDTO english=new RemoteStatementDTO();english.setProblemKey(key);english.setSourceHash(hash(view));english.setContent(content("English translation"));view=statements.save(english,true);assertEquals("en",((Map)view.get("translation")).get("language"));jdbc.update("UPDATE problem SET auth=1 WHERE problem_id='NC-6'");long id=jdbc.queryForObject("SELECT id FROM problem WHERE problem_id='NC-6'",Long.class);assertNotNull(statements.publicView(id).get("translation"));
        clock+=86400001;Map<String,Object> stale=statements.claim();statements.refresh(key,false);statements.applyNowCoder(stale,new NowCoderStatementClient.Statement("8","6",content("Obsolete")));assertEquals("中文原文",jdbc.queryForObject("SELECT title FROM remote_nowcoder_problem WHERE problem_key=?",String.class,key));
        RemoteStatementDTO chinese=new RemoteStatementDTO();chinese.setProblemKey(key);chinese.setSourceHash(hash(view));chinese.setContent(content("修改原文"));statements.save(chinese,false);assertNull(statements.publicView(id).get("translation"));assertEquals(true,statements.publicView(id).get("translationStale"));
    }

    Map<String,Object> fetchLease(String action){
        if(action.startsWith("catalog")){
            jdbc.update("UPDATE remote_source_job SET next_run_at=? WHERE source_name='CODEFORCES_PROBLEMS'",new Timestamp(clock));
            return catalogue.claim();
        }
        statements.request(key);return statements.claim();
    }
    String fetchJobTable(String action){return action.startsWith("catalog")?"remote_source_job":"remote_problem_import";}
    Map<String,Object> fetchJobState(String action){
        return jdbc.queryForMap("SELECT * FROM "+fetchJobTable(action)+(action.startsWith("catalog")?" WHERE source_name='CODEFORCES_PROBLEMS'":" WHERE problem_key='cf:contest:4:A'"));
    }
    void invokeFetchCallback(String action,Map<String,Object> job){
        if("catalog-apply".equals(action))catalogue.apply(job,data(problem(99,"Z",1600)));
        else if("catalog-failed".equals(action))catalogue.failed(job,"late callback");
        else if("statement-apply".equals(action))statements.apply(job,content("Late source"));
        else if("statement-failed".equals(action))statements.failed(job,"late callback");
        else statements.defer(job);
    }
    @ParameterizedTest
    @CsvSource({"catalog-apply,180000,false","catalog-failed,180000,false",
            "statement-apply,90000,false","statement-failed,90000,false","statement-defer,90000,false",
            "catalog-apply,179999,true","catalog-failed,179999,true",
            "statement-apply,89999,true","statement-failed,89999,true","statement-defer,89999,true"})
    void expiryWithoutReclaimRejectsFetchCallbacks(String action,long elapsed,boolean live){
        Map<String,Object> lease=fetchLease(action);assertNotNull(lease);String table=fetchJobTable(action);
        Map<String,Object> before=fetchJobState(action);
        List<Map<String,Object>> catalog=jdbc.queryForList("SELECT * FROM remote_catalog_problem ORDER BY problem_key");
        clock+=elapsed;invokeFetchCallback(action,lease);
        Map<String,Object> after=fetchJobState(action);
        if(live){assertNotEquals(before,after);assertNull(after.get("lease_token"));}
        else {
            assertEquals(before,after);assertEquals(catalog,jdbc.queryForList("SELECT * FROM remote_catalog_problem ORDER BY problem_key"));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_version",Integer.class));
            Map<String,Object> fresh=action.startsWith("catalog")?catalogue.claim():statements.claim();assertNotNull(fresh);
            assertNotEquals(lease.get("lease_token"),fresh.get("lease_token"));invokeFetchCallback(action,fresh);
            assertNull(fetchJobState(action).get("lease_token"));
        }
    }

    @ParameterizedTest
    @CsvSource({"90000,false","89999,true"})
    void nowCoderExpiryCannotDiscoverMetadataOrPublishAStatement(long elapsed,boolean live){
        String ncKey="nowcoder:acm:6";
        jdbc.update("INSERT INTO language VALUES(2,'ME')");
        when(remote.adminAddOtherOJProblem(any(),eq("NC"),eq(true))).thenAnswer(call->{
            Problem problem=((ProblemStrategy.RemoteProblemInfo)call.getArgument(0)).getProblem();
            return insertProblem(problem);
        });
        NowCoderCatalogManager nc=new NowCoderCatalogManager(){@Override protected long now(){return clock;}};
        ReflectionTestUtils.setField(nc,"jdbc",jdbc);
        ReflectionTestUtils.setField(nc,"transactions",new DataSourceTransactionManager(jdbc.getDataSource()));
        ReflectionTestUtils.setField(statements,"nowcoderCatalogue",nc);
        nc.discovered(ncKey,new NowCoderStatementClient.Statement("8","6",content("Original metadata")));
        statements.request(ncKey);Map<String,Object> lease=statements.claim();assertNotNull(lease);
        Map<String,Object> before=jdbc.queryForMap("SELECT * FROM remote_nowcoder_problem WHERE problem_key=?",ncKey);
        Map<String,Object> job=jdbc.queryForMap("SELECT * FROM remote_problem_import WHERE problem_key=?",ncKey);
        clock+=elapsed;statements.applyNowCoder(lease,new NowCoderStatementClient.Statement("8","6",content("New metadata")));
        if(live){
            assertEquals("New metadata",jdbc.queryForObject("SELECT title FROM remote_nowcoder_problem WHERE problem_key=?",String.class,ncKey));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_version WHERE problem_key=?",Integer.class,ncKey));
            assertNull(jdbc.queryForMap("SELECT * FROM remote_problem_import WHERE problem_key=?",ncKey).get("lease_token"));
        }else{
            assertEquals(before,jdbc.queryForMap("SELECT * FROM remote_nowcoder_problem WHERE problem_key=?",ncKey));
            assertEquals(job,jdbc.queryForMap("SELECT * FROM remote_problem_import WHERE problem_key=?",ncKey));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_version WHERE problem_key=?",Integer.class,ncKey));
        }
    }
}
