package top.hcode.hoj.manager.oj.ai;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StreamUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeVideoCleanupTest {
    @TempDir Path temporary;
    Path media;JdbcTemplate jdbc;KnowledgeVideoAssets assets;KnowledgeVideoCleanup cleanup;DataSourceTransactionManager transactions;
    final long clock=1800000000000L;
    final byte[] bytes="\0\0\0 ftypisom-original-test-source".getBytes(StandardCharsets.US_ASCII);
    final String hash=String.join("",Collections.nCopies(64,"a"));

    @BeforeEach void setup()throws Exception{
        media=Files.createDirectory(temporary.resolve("media"));assets=new KnowledgeVideoAssets();ReflectionTestUtils.setField(assets,"directory",media.toString());
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:video_gc_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(source);transactions=new DataSourceTransactionManager(source);
        for(String name:Arrays.asList("knowledge-base.sql","knowledge-video.sql")){
            String sql=StreamUtils.copyToString(new ClassPathResource(name).getInputStream(),StandardCharsets.UTF_8).replace(" COLLATE=utf8mb4_bin","");
            try(Connection connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));}
        }
        cleanup=collector(false);
    }
    @AfterEach void close(){cleanup.close();}
    KnowledgeVideoCleanup collector(boolean fail){
        KnowledgeVideoCleanup value=new KnowledgeVideoCleanup(){@Override protected long now(){return clock;}@Override protected void remove(Path file)throws IOException{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());if(fail)throw new IOException("private cleanup detail");super.remove(file);}};
        ReflectionTestUtils.setField(value,"assets",assets);ReflectionTestUtils.setField(value,"jdbc",jdbc);ReflectionTestUtils.setField(value,"transactions",transactions);return value;
    }
    String id(){return UUID.randomUUID().toString().replace("-","");}
    Path file(String name,boolean old)throws IOException{Path value=Files.write(media.resolve(name),bytes);Files.setLastModifiedTime(value,FileTime.fromMillis(old?clock-KnowledgeVideoCleanup.GRACE_MS:clock));return value;}
    long count()throws IOException{try(java.util.stream.Stream<Path> files=Files.list(media)){return files.count();}}
    void imported(String asset,String state){
        Timestamp time=new Timestamp(clock);jdbc.update("INSERT INTO knowledge_video_import(id,requested_by,processing_by,request_id,input_hash,title,visibility,source_name,asset_id,source_hash,byte_length,language,state,stage,segments,next_run_at,revision,created_at,updated_at) VALUES(?,'alice','alice',?,?,?,'ADMIN','local.mp4',?,?,?,'auto',?,'PROBE','[]',?,1,?,?)",id(),id(),hash,"test",asset,hash,bytes.length,state,time,time,time);
    }
    void version(String asset,String state){
        String document=id();Timestamp time=new Timestamp(clock);
        jdbc.update("INSERT INTO knowledge_document(id,title,visibility,state,latest_version,revision,created_by,request_id,creation_hash,created_at,updated_at) VALUES(?,'test','ADMIN',?,1,1,'alice',?,?,?,?)",document,state,id(),hash,time,time);
        jdbc.update("INSERT INTO knowledge_version(document_id,version,title,format,content_hash,content,state,created_by,created_at) VALUES(?,1,'test','video',?,'immutable caption',?,'alice',?)",document,hash,"DELETED".equals(state)?"DELETED":"INDEXED",time);
        jdbc.update("INSERT INTO knowledge_video_source(document_id,version,asset_id,source_name,source_hash,byte_length,duration_ms,mime,language,engine,model_hash,extraction_mode) VALUES(?,1,?,'local.mp4',?,?,1000,'video/mp4','auto','faster-whisper-cpu-int8-v1',?,'ASR')",document,asset,hash,bytes.length,hash);
    }

    @Test void agedOrphansAndLegacyPartsAreCollectedWhileFreshFilesStay()throws Exception{
        file(id()+".bin",true);file("staged-123456789.part",true);file("staged-"+id()+".part",true);
        Path fresh=file(id()+".bin",false),newPart=file("staged-"+id()+".part",false);
        assertEquals(3,cleanup.processBatch());assertTrue(Files.exists(fresh));assertTrue(Files.exists(newPart));assertEquals(2,count());
    }
    @Test void everyUncancelledImportAndAllVersionAuditReferencesArePreserved()throws Exception{
        for(String state:Arrays.asList("QUEUED","RUNNING","READY","FAILED","SAVED")){String asset=id();file(asset+".bin",true);imported(asset,state);}
        for(String state:Arrays.asList("ACTIVE","DELETED")){String asset=id();file(asset+".bin",true);version(asset,state);}
        assertEquals(0,cleanup.processBatch());assertEquals(7,count());
    }
    @Test void cancelledFilesRetryImmediatelyButAnExistingVersionAlwaysWins()throws Exception{
        String cancelled=id();file(cancelled+".bin",false);imported(cancelled,"CANCELLED");
        String protectedAsset=id();file(protectedAsset+".bin",true);imported(protectedAsset,"CANCELLED");version(protectedAsset,"DELETED");
        assertEquals(1,cleanup.processBatch());assertTrue(Files.exists(media.resolve(protectedAsset+".bin")));assertEquals(1,count());
    }
    @Test void activeStagingLeaseProtectsEvenAnAgedFileUntilItsImportAdoptsIt()throws Exception{
        try(KnowledgeVideoAssets.Staged source=assets.stage(new ByteArrayInputStream(bytes),bytes.length)){
            Path original=media.resolve(source.assetId+".bin");Files.setLastModifiedTime(original,FileTime.fromMillis(clock-KnowledgeVideoCleanup.GRACE_MS));
            assertEquals(0,cleanup.processBatch());assertTrue(Files.exists(original));imported(source.assetId,"READY");source.adopt();
        }
        assertEquals(0,cleanup.processBatch());assertEquals(1,count());
    }
    @Test void databaseFailureCannotQuarantineOrDeleteAnOriginal()throws Exception{
        Path original=file(id()+".bin",true);jdbc.execute("DROP TABLE knowledge_video_quota");assertThrows(RuntimeException.class,()->cleanup.processBatch());assertTrue(Files.exists(original));assertEquals(1,count());
    }
    @Test void quarantineSurvivesAnUnlinkFailureAndAnotherCollectorResumes()throws Exception{
        Path original=file(id()+".bin",true);KnowledgeVideoCleanup failing=collector(true);
        try{assertThrows(IOException.class,()->failing.processBatch());}finally{failing.close();}
        assertFalse(Files.exists(original));assertEquals(1,count());
        try(java.util.stream.Stream<Path> paths=Files.list(media)){Path garbage=paths.findFirst().get();assertTrue(garbage.getFileName().toString().startsWith("gc-"));assertArrayEquals(bytes,Files.readAllBytes(garbage));}
        assertEquals(1,cleanup.processBatch());assertEquals(0,count());
    }
    @Test void boundedCursorEventuallyReachesFilesBehindManyPreservedEntries()throws Exception{
        for(int i=0;i<140;i++)file(id()+".bin",false);file(id()+".bin",true);
        int removed=0;for(int i=0;i<4;i++)removed+=cleanup.processBatch();assertEquals(1,removed);assertEquals(140,count());
    }
    @Test void unknownNamesDirectoriesAndLinksCannotDeleteOutsideTheMediaRoot()throws Exception{
        Path unknown=file("operator-note.txt",true),folder=Files.createDirectory(media.resolve(id()+".bin"));Files.write(folder.resolve("keep.txt"),bytes);
        Path outside=Files.write(temporary.resolve("outside.bin"),bytes);
        try{Files.createSymbolicLink(media.resolve(id()+".bin"),outside);}catch(UnsupportedOperationException|IOException ignored){/* Also exercised in Linux Java 8 verification. */}
        assertEquals(0,cleanup.processBatch());assertTrue(Files.exists(unknown));assertArrayEquals(bytes,Files.readAllBytes(outside));assertTrue(Files.exists(folder.resolve("keep.txt")));
    }
    @Test void operatingSystemLeaseFromAnotherProcessProtectsAnOrphanUntilThatProcessExits()throws Exception{
        Path original=file(id()+".bin",true);
        String java=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classes=Paths.get(VideoFileLeaseProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process child=new ProcessBuilder(java,"-cp",classes,VideoFileLeaseProcess.class.getName(),original.toString()).redirectErrorStream(true).start();
        ExecutorService reader=Executors.newSingleThreadExecutor();
        try{
            Future<String> ready=reader.submit(()->new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8)).readLine());assertEquals("LOCKED",ready.get(10,TimeUnit.SECONDS));
            assertEquals(0,cleanup.processBatch());assertTrue(Files.exists(original));child.getOutputStream().write(1);child.getOutputStream().flush();assertTrue(child.waitFor(5,TimeUnit.SECONDS));assertEquals(0,child.exitValue());
            assertEquals(1,cleanup.processBatch());assertFalse(Files.exists(original));
        }finally{child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));reader.shutdownNow();}
    }
}
