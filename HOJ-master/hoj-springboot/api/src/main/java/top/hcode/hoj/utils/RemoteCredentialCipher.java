package top.hcode.hoj.utils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;

/** Separate wrapping key and authenticated identity context for personal remote-submit credentials. */
public final class RemoteCredentialCipher {
    private final Path directory;
    private final SecureRandom random=new SecureRandom();
    public RemoteCredentialCipher(Path directory) { this.directory=directory; }
    private synchronized byte[] master(boolean mayCreate) throws Exception {
        if(mayCreate) Files.createDirectories(directory);
        if(!Files.isDirectory(directory)) throw new IllegalStateException("Missing credential key directory");
        Path key=directory.resolve("master.key");
        // Atomic creation is the only key mutation. Readers (including a read-only judge mount)
        // must not need write permission or create a replacement key after data already exists.
        if(Files.exists(key) || !mayCreate) return readKey(key);
        try(FileChannel channel=FileChannel.open(directory.resolve("master.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock ignored=channel.lock()) {
            if(!Files.exists(key)) {
                if(!mayCreate) throw new IllegalStateException("Missing credential wrapping key");
                byte[] bytes=new byte[16];random.nextBytes(bytes);Path temporary=Files.createTempFile(directory,"master-",".tmp");
                try {
                    if(Files.getFileStore(temporary).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));
                    Files.write(temporary,bytes);Files.move(temporary,key,StandardCopyOption.ATOMIC_MOVE);
                } finally { Files.deleteIfExists(temporary); }
            }
            return readKey(key);
        }
    }
    private byte[] readKey(Path key) throws Exception {
        byte[] bytes=Files.readAllBytes(key);if(bytes.length!=16) throw new IllegalStateException("Invalid credential key");return bytes;
    }
    private byte[] context(long accountId,String uid,String platform,String handleKey) {
        if(accountId<=0 || uid==null || !uid.matches("[A-Za-z0-9_-]{1,32}") || !Arrays.asList("CODEFORCES","ATCODER","NOWCODER").contains(platform) || handleKey==null || !handleKey.matches("[a-z0-9_.-]{1,64}") || "NOWCODER".equals(platform) && !handleKey.matches("[1-9][0-9]{0,11}")) throw new IllegalArgumentException("Invalid credential identity");
        return ("hoj-personal-remote:v1\n"+accountId+"\n"+uid+"\n"+platform+"\n"+handleKey).getBytes(StandardCharsets.UTF_8);
    }
    public String encrypt(long accountId,String uid,String platform,String handleKey,String secret,boolean mayCreate) throws Exception {
        return encrypt(accountId,uid,platform,handleKey,secret,mayCreate,"v1:");
    }
    public String encryptSession(long accountId,String uid,String platform,String handleKey,String secret,boolean mayCreate) throws Exception {
        return encrypt(accountId,uid,platform,handleKey,secret,mayCreate,"s1:");
    }
    private String encrypt(long accountId,String uid,String platform,String handleKey,String secret,boolean mayCreate,String format) throws Exception {
        byte[] aad=context(accountId,uid,platform,handleKey),nonce=new byte[12];random.nextBytes(nonce);
        if("s1:".equals(format))aad=(new String(aad,StandardCharsets.UTF_8)+"\nBROWSER_SESSION").getBytes(StandardCharsets.UTF_8);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(master(mayCreate),"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad);
        byte[] encrypted=cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));return format+Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length+encrypted.length).put(nonce).put(encrypted).array());
    }
    public String decrypt(long accountId,String uid,String platform,String handleKey,String value) throws Exception {
        return decrypt(accountId,uid,platform,handleKey,value,"v1:");
    }
    public String decryptSession(long accountId,String uid,String platform,String handleKey,String value) throws Exception {
        return decrypt(accountId,uid,platform,handleKey,value,"s1:");
    }
    private String decrypt(long accountId,String uid,String platform,String handleKey,String value,String format) throws Exception {
        byte[] aad=context(accountId,uid,platform,handleKey);
        if(value==null || !value.startsWith(format)) throw new IllegalArgumentException("Invalid credential format");
        if("s1:".equals(format))aad=(new String(aad,StandardCharsets.UTF_8)+"\nBROWSER_SESSION").getBytes(StandardCharsets.UTF_8);
        byte[] bytes=Base64.getDecoder().decode(value.substring(3));if(bytes.length<29 || bytes.length>16384) throw new IllegalArgumentException("Invalid credential size");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(master(false),"AES"),new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));cipher.updateAAD(aad);
        return new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);
    }
}
