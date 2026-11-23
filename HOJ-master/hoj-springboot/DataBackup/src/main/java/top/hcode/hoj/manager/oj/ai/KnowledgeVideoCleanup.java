package top.hcode.hoj.manager.oj.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/** Flat private storage only. Never deletes an import or a version's referenced bytes. */
@Component
@Slf4j(topic="hoj")
public class KnowledgeVideoCleanup {
    static final long GRACE_MS=86400000L;
    private static final int BATCH=128;
    @Autowired private KnowledgeVideoAssets assets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"knowledge-video-cleanup");thread.setDaemon(true);return thread;});
    private DirectoryStream<Path> directory;
    private Iterator<Path> cursor;
    private boolean stopping,unavailable;

    @PostConstruct public void start(){worker.scheduleWithFixedDelay(this::tick,60000,60000,TimeUnit.MILLISECONDS);}
    @PreDestroy public synchronized void close(){stopping=true;worker.shutdownNow();closeCursor();}
    protected long now(){return System.currentTimeMillis();}
    private void closeCursor(){if(directory!=null)try{directory.close();}catch(IOException ignored){}directory=null;cursor=null;}
    private void tick(){
        try{processBatch();unavailable=false;}
        catch(Exception error){if(!unavailable)log.warn("知识库视频原件清理暂不可用，将自动重试");unavailable=true;}
    }

    private static final class Name {
        final String original,asset;
        final boolean quarantined;
        Name(String original,boolean quarantined){this.original=original;this.quarantined=quarantined;this.asset=original.matches("[a-f0-9]{32}\\.bin")?original.substring(0,32):null;}
    }
    private Name name(Path file){
        String value=file.getFileName().toString();boolean quarantined=value.matches("gc-[a-f0-9]{32}-.+");
        String original=quarantined?value.substring(36):value;
        if(!original.matches("[a-f0-9]{32}\\.bin|staged-(?:[a-f0-9]{32}|[0-9]{1,25})\\.part"))return null;
        return new Name(original,quarantined);
    }

    /** Global -> import -> version index, matching import save/cancel. All reads are current. */
    private boolean eligible(Name name,boolean old){
        if(name.asset==null)return name.quarantined||old; // A .part is never a source path.
        TransactionTemplate check=new TransactionTemplate(transactions);check.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return Boolean.TRUE.equals(check.execute(status->{
            jdbc.queryForObject("SELECT active_count FROM knowledge_video_quota WHERE owner='_all' FOR UPDATE",Integer.class);
            List<String> imports=jdbc.queryForList("SELECT state FROM knowledge_video_import WHERE asset_id=? FOR UPDATE",String.class,name.asset);
            if(!imports.isEmpty()&&!"CANCELLED".equals(imports.get(0)))return false;
            if(!jdbc.queryForList("SELECT document_id FROM knowledge_video_source WHERE asset_id=? LIMIT 1 FOR UPDATE",name.asset).isEmpty())return false;
            return name.quarantined||old||!imports.isEmpty();
        }));
    }

    private boolean same(BasicFileAttributes first,BasicFileAttributes second){
        return first.isRegularFile()&&second.isRegularFile()&&java.util.Objects.equals(first.fileKey(),second.fileKey())
                &&first.size()==second.size()&&first.lastModifiedTime().equals(second.lastModifiedTime())&&first.creationTime().equals(second.creationTime());
    }

    protected void remove(Path file)throws IOException{Files.deleteIfExists(file);}

    private boolean clean(Path base,Path file)throws IOException{
        if(!file.toAbsolutePath().normalize().getParent().equals(base))return false;
        Name name=name(file);if(name==null)return false;
        BasicFileAttributes first;
        try{first=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException ignored){return false;}
        if(!first.isRegularFile())return false;
        boolean old=first.lastModifiedTime().toMillis()<=now()-GRACE_MS;
        if(name.asset==null&&!old&&!name.quarantined)return false;
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            FileLock lease;
            try{lease=channel.tryLock(0,Long.MAX_VALUE,true);}catch(OverlappingFileLockException ignored){return false;}
            if(lease==null)return false;
            try{
                if(!same(first,Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS))||!eligible(name,old))return false;
            }finally{lease.release();}
        }catch(NoSuchFileException ignored){return false;}
        // All writers retain an exclusive Staged lease through registration.
        // No eligible file has a live writer. Close handles before rename for
        // Windows; fresh writers always CREATE_NEW and never replace an inode.
        // There is no database transaction over these filesystem operations.
        if(!same(first,Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS)))return false;
        Path garbage=name.quarantined?file:base.resolve("gc-"+UUID.randomUUID().toString().replace("-","")+"-"+name.original);
        try{if(!name.quarantined){try{Files.move(file,garbage,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ignored){Files.move(file,garbage);}}}
        catch(NoSuchFileException ignored){return false;}
        if(!garbage.toAbsolutePath().normalize().getParent().equals(base))throw new IOException();
        remove(garbage);return true;
    }

    /** A cursor bounds work without starving old files behind many saved originals. */
    synchronized int processBatch()throws IOException{
        if(stopping||!assets.configured())return 0;
        Path base=assets.storageRoot().toRealPath();int deleted=0,scanned=0;IOException failed=null;
        try{
            if(directory==null){directory=Files.newDirectoryStream(base);cursor=directory.iterator();}
            while(scanned++<BATCH&&cursor.hasNext()){
                Path file=cursor.next();
                try{if(clean(base,file))deleted++;}catch(NoSuchFileException ignored){}catch(IOException error){failed=error;}
            }
            if(!cursor.hasNext())closeCursor();
        }catch(RuntimeException error){closeCursor();throw error;}
        if(failed!=null)throw new IOException("Video cleanup will retry");
        return deleted;
    }
}
