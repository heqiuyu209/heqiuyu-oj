package top.hcode.hoj.manager.oj.ai;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeVideoAssetsTest {
    @TempDir Path directory;
    KnowledgeVideoAssets assets;
    byte[] source="\0\0\0 ftypisom-original-local-video-bytes".getBytes(StandardCharsets.US_ASCII);
    @BeforeEach void setup(){assets=new KnowledgeVideoAssets();ReflectionTestUtils.setField(assets,"directory",directory.toString());}
    long files()throws IOException{try(java.util.stream.Stream<Path> paths=Files.list(directory)){return paths.count();}}
    @Test void completedSourceIsByteIdenticalAndOnlySurvivesAfterItsRowAdoptsIt()throws Exception{
        String id,hash;
        try(KnowledgeVideoAssets.Staged value=assets.stage(new ByteArrayInputStream(source),source.length)){
            id=value.assetId;hash=value.sourceHash;assertEquals(RagServiceClient.hash(source),hash);assertEquals(source.length,value.byteLength);value.adopt();
        }
        assertEquals(1,files());assertArrayEquals(source,Files.readAllBytes(directory.resolve(id+".bin")));
        try(FileChannel channel=assets.open(id,hash,source.length)){ByteBuffer value=ByteBuffer.allocate(source.length);while(value.hasRemaining())assertTrue(channel.read(value)>=0);assertArrayEquals(source,value.array());}
        try(KnowledgeVideoAssets.Staged value=assets.stage(new ByteArrayInputStream(source),-1)){assertNotEquals(id,value.assetId);}
        assertEquals(1,files());
    }
    @Test void malformedTruncatedAndInterruptedUploadsNeverLeaveUsableFiles()throws Exception{
        assertThrows(ObjectiveException.class,()->assets.stage(new ByteArrayInputStream("not video".getBytes(StandardCharsets.US_ASCII)),9));
        assertThrows(ObjectiveException.class,()->assets.stage(new ByteArrayInputStream(source),source.length+1));
        InputStream broken=new InputStream(){@Override public int read()throws IOException{throw new IOException("private input");}};
        ObjectiveException error=assertThrows(ObjectiveException.class,()->assets.stage(broken,-1));assertEquals(503,error.getStatus());assertFalse(error.getMessage().contains("private"));assertEquals(0,files());
        assertEquals(413,assertThrows(ObjectiveException.class,()->assets.stage(new ByteArrayInputStream(source),VideoTranscript.MAX_BYTES+1)).getStatus());assertEquals(0,files());
    }
    @Test void identifierCollisionCannotReplaceAnImmutableSource()throws Exception{
        String id="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";Files.write(directory.resolve(id+".bin"),source);
        KnowledgeVideoAssets fixed=new KnowledgeVideoAssets(){@Override protected String identifier(){return id;}};ReflectionTestUtils.setField(fixed,"directory",directory.toString());
        assertThrows(ObjectiveException.class,()->fixed.stage(new ByteArrayInputStream(source),source.length));assertArrayEquals(source,Files.readAllBytes(directory.resolve(id+".bin")));assertEquals(1,files());
    }
    @Test void wrongHashAndPathIdentifiersCannotReadOrDeleteAnotherFile()throws Exception{
        String id;try(KnowledgeVideoAssets.Staged value=assets.stage(new ByteArrayInputStream(source),source.length)){id=value.assetId;value.adopt();}
        assertThrows(ObjectiveException.class,()->assets.open(id,RagServiceClient.hash("different"),source.length));
        assertThrows(ObjectiveException.class,()->assets.open("../private",RagServiceClient.hash(source),source.length));
        assertThrows(ObjectiveException.class,()->assets.discard("../private"));assertEquals(1,files());
    }
    @Test void sourceNamesAreLabelsAndNeverFilePaths(){
        KnowledgeVideoAssets.sourceName("内部讲义.mp4");KnowledgeVideoAssets.sourceName("Algorithms.WEBM");
        for(String name:new String[]{"../private.mp4","C:\\private.mp4","video.mp4\n","video.txt","x\u0000.mp4","bad\udcff.mp4"})assertThrows(ObjectiveException.class,()->KnowledgeVideoAssets.sourceName(name));
    }

    @Test void rejectedTemporaryNameCollisionCannotDeleteAnotherWritersFile()throws Exception{
        String id="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";Path existing=directory.resolve("staged-"+id+".part");Files.write(existing,source);
        KnowledgeVideoAssets fixed=new KnowledgeVideoAssets(){@Override protected String identifier(){return id;}};ReflectionTestUtils.setField(fixed,"directory",directory.toString());
        assertThrows(ObjectiveException.class,()->fixed.stage(new ByteArrayInputStream(source),source.length));assertArrayEquals(source,Files.readAllBytes(existing));assertEquals(1,files());
    }

    @Test void sourceAndCopyFallbackBothRetainExclusiveLeasesUntilClose()throws Exception{
        for(boolean fallback:new boolean[]{false,true}){
            KnowledgeVideoAssets selected=fallback?new KnowledgeVideoAssets(){@Override protected void link(Path target,Path original){throw new UnsupportedOperationException();}}:assets;
            ReflectionTestUtils.setField(selected,"directory",directory.toString());
            try(KnowledgeVideoAssets.Staged value=selected.stage(new ByteArrayInputStream(source),source.length)){
                try(FileChannel original=FileChannel.open(directory.resolve(value.assetId+".bin"),StandardOpenOption.READ)){
                    assertThrows(java.nio.channels.OverlappingFileLockException.class,()->original.tryLock(0,Long.MAX_VALUE,true));
                }
            }
            assertEquals(0,files());
        }
    }

    @Test void inputCloseFailureCannotStrandAnOpenStagedLease()throws Exception{
        InputStream broken=new ByteArrayInputStream(source){@Override public void close()throws IOException{throw new IOException("private close detail");}};
        ObjectiveException error=assertThrows(ObjectiveException.class,()->assets.stage(broken,source.length));assertEquals(503,error.getStatus());assertEquals(0,files());
    }
}
