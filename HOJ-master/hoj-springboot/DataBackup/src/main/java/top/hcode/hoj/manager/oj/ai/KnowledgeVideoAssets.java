package top.hcode.hoj.manager.oj.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Private immutable files. Identifiers and paths never come from a filename. */
@Component
public class KnowledgeVideoAssets {
    @Value("${hoj.knowledge.media-dir:}") private String directory="";
    private final Semaphore uploads=new Semaphore(1);
    private final Map<String,String> verified=Collections.synchronizedMap(new LinkedHashMap<String,String>(128,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,String> entry){return size()>128;}
    });
    protected String identifier(){return UUID.randomUUID().toString().replace("-","");}
    public boolean configured(){return directory!=null&&!directory.trim().isEmpty();}
    private Path root(){
        if(!configured())throw new ObjectiveException(503,"视频原件存储尚未配置");
        try{
            Path value=Paths.get(directory).toAbsolutePath().normalize();
            if(value.equals(value.getRoot()))throw new IOException();
            Files.createDirectories(value);return value;
        }catch(Exception error){throw new ObjectiveException(503,"视频原件存储不可用");}
    }
    Path storageRoot(){return root();}
    private Path path(String id){
        if(id==null||!id.matches("[a-f0-9]{32}"))throw new ObjectiveException(400,"视频原件标识无效");
        Path base=root(),value=base.resolve(id+".bin").normalize();
        if(!value.getParent().equals(base))throw new ObjectiveException(400,"视频原件标识无效");return value;
    }
    public static void sourceName(String name){
        if(name==null||name.codePointCount(0,name.length())>200||!name.toLowerCase(Locale.ROOT).matches(".+\\.(mp4|webm)")
                ||name.matches("(?s).*[\\p{Cntrl}/\\\\].*")||!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(name))throw new ObjectiveException(400,"请选择 MP4 / WebM 文件，文件名最多 200 字且不能包含路径");
    }
    private static String hex(byte[] value){StringBuilder result=new StringBuilder();for(byte item:value)result.append(String.format(Locale.ROOT,"%02x",item&255));return result.toString();}
    protected void link(Path target,Path source)throws IOException{Files.createLink(target,source);}
    public final class Staged implements AutoCloseable {
        public final String assetId,sourceHash;
        public final long byteLength;
        private final Path file;
        private final Path temporary;
        private final FileChannel channel;
        private final FileLock lease;
        private final FileChannel copy;
        private final FileLock copyLease;
        private boolean adopted,closed;
        private Staged(String id,String hash,long length,Path file,Path temporary,FileChannel channel,FileLock lease,FileChannel copy,FileLock copyLease){this.assetId=id;this.sourceHash=hash;this.byteLength=length;this.file=file;this.temporary=temporary;this.channel=channel;this.lease=lease;this.copy=copy;this.copyLease=copyLease;}
        /** Call only after the import row transaction has committed. */
        public void adopt(){if(closed)throw new IllegalStateException("Closed video source");adopted=true;}
        /** A lost commit receipt must not delete bytes that MySQL may already reference. */
        public void retainAfterUncertainCommit(){if(closed)throw new IllegalStateException("Closed video source");adopted=true;}
        @Override public void close(){
            if(closed)return;closed=true;release(copyLease,copy);release(lease,channel);
            if(!adopted)try{Files.deleteIfExists(file);}catch(IOException ignored){/* Background cleanup retries unreferenced originals. */}
            try{Files.deleteIfExists(temporary);}catch(IOException ignored){/* A leftover hard link is never a published source path. */}
        }
    }
    private static void release(FileLock lease,FileChannel channel){
        if(lease!=null)try{lease.release();}catch(IOException ignored){}
        if(channel!=null)try{channel.close();}catch(IOException ignored){}
    }
    public Staged stage(InputStream input,long length){
        if(length>VideoTranscript.MAX_BYTES)throw new ObjectiveException(413,"视频文件最多 1 GiB");
        if(input==null)throw new ObjectiveException(400,"请选择视频文件");
        if(!uploads.tryAcquire())throw new ObjectiveException(429,"正在接收视频，请稍后再上传");
        Path temporary=null,target=null;FileChannel output=null,copy=null;FileLock lease=null,copyLease=null;boolean temporaryOwned=false,linked=false,completed=false,inputClosed=false;
        try{
            InputStream source=input;
            Path base=root();String id=identifier();target=path(id);temporary=base.resolve("staged-"+id+".part");
            output=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);
            temporaryOwned=true;
            lease=output.tryLock();if(lease==null)throw new IOException();
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
            byte[] buffer=new byte[65536],header=new byte[16];int headerSize=0,count;
            while((count=source.read(buffer))!=-1){
                if(total+count>VideoTranscript.MAX_BYTES)throw new ObjectiveException(413,"视频文件最多 1 GiB");
                int copiedHeader=Math.min(header.length-headerSize,count);if(copiedHeader>0){System.arraycopy(buffer,0,header,headerSize,copiedHeader);headerSize+=copiedHeader;}
                total+=count;digest.update(buffer,0,count);ByteBuffer bytes=ByteBuffer.wrap(buffer,0,count);while(bytes.hasRemaining())output.write(bytes);
            }
            output.force(true);
            boolean mp4=headerSize>=12&&header[4]=='f'&&header[5]=='t'&&header[6]=='y'&&header[7]=='p';
            boolean webm=headerSize>=12&&(header[0]&255)==0x1a&&(header[1]&255)==0x45&&(header[2]&255)==0xdf&&(header[3]&255)==0xa3;
            if(!mp4&&!webm||total<12)throw new ObjectiveException(400,"请选择有效的 MP4 / WebM 视频文件");
            if(length>=0&&length!=total)throw new ObjectiveException(400,"视频上传不完整，请重试");
            // Atomic create-new hard link: unlike rename, even a collision can
            // never replace an existing immutable source. The fallback also
            // uses copy without REPLACE_EXISTING on providers lacking links.
            try{link(target,temporary);linked=true;}
            catch(UnsupportedOperationException|IOException error){
                copy=FileChannel.open(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);linked=true;
                copyLease=copy.tryLock();if(copyLease==null)throw new IOException();
                long position=0;while(position<total){long moved=output.transferTo(position,total-position,copy);if(moved<=0)throw new IOException();position+=moved;}copy.force(true);
            }
            if(Files.getFileStore(target).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(target,PosixFilePermissions.fromString("r--r--r--"));
            // Keep the exclusive inode lease until the import transaction has
            // committed and adopted this source (or rejected it). The hard link
            // shares this lease, so cleanup cannot race the commit window.
            source.close();inputClosed=true;
            Staged value=new Staged(id,hex(digest.digest()),total,target,temporary,output,lease,copy,copyLease);completed=true;return value;
        }catch(ObjectiveException error){throw error;}catch(Exception error){throw new ObjectiveException(503,"视频文件保存失败，请稍后重试");}
        finally{
            if(!inputClosed)try{input.close();}catch(IOException ignored){}
            if(!completed){release(copyLease,copy);release(lease,output);if(temporaryOwned)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
            // A completed target is owned by Staged; failures after linking
            // must not leave an uncommitted asset that looks usable.
            if(linked&&!completed&&target!=null)try{Files.deleteIfExists(target);}catch(IOException ignored){}
            uploads.release();
        }
    }
    private String identity(BasicFileAttributes value){return String.valueOf(value.fileKey())+":"+value.size()+":"+value.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS)+":"+value.creationTime().to(java.util.concurrent.TimeUnit.NANOSECONDS);}
    public FileChannel open(String id,String hash,long length){
        if(hash==null||!hash.matches("[a-f0-9]{64}")||length<12||length>VideoTranscript.MAX_BYTES)throw new ObjectiveException(503,"视频原件信息无效");
        Path file=path(id);FileChannel channel=null;
        try{
            BasicFileAttributes before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!before.isRegularFile()||before.size()!=length)throw new IOException();
            channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
            String current=identity(before),key=id+":"+hash+":"+length;
            if(channel.size()!=length||!identity(Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS)).equals(current))throw new IOException();
            if(!current.equals(verified.get(key))){
                MessageDigest digest=MessageDigest.getInstance("SHA-256");ByteBuffer buffer=ByteBuffer.allocate(65536);
                while(channel.read(buffer)!=-1){buffer.flip();digest.update(buffer);buffer.clear();}
                if(!hex(digest.digest()).equals(hash))throw new IOException();channel.position(0);
                if(!identity(Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS)).equals(current))throw new IOException();
                verified.put(key,current);
            }
            return channel;
        }catch(Exception error){if(channel!=null)try{channel.close();}catch(IOException ignored){}throw new ObjectiveException(503,"视频原件缺失或校验失败，请联系管理员");}
    }
    /** Only after the caller fences/cancels an unreferenced import in MySQL. */
    void discard(String id){try{Files.deleteIfExists(path(id));}catch(IOException error){throw new ObjectiveException(503,"视频原件清理失败，请稍后重试");}}
}
