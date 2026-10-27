package top.hcode.hoj.manager.oj.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
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
import java.util.Arrays;
import java.util.Base64;

/** The wrapping key lives in a separate server volume, never in the database or logs. */
@Component
public class AiSecretStore {
    @Value("${hoj.ai.secret-dir:/hoj/secrets/ai}") private String directory;
    private final SecureRandom random = new SecureRandom();

    private synchronized byte[] master(boolean mayCreate) throws Exception {
        Path folder = Paths.get(directory);
        if (mayCreate) Files.createDirectories(folder);
        if (!Files.isDirectory(folder)) throw new IllegalStateException("Missing secret directory");
        Path keyFile = folder.resolve("master.key");
        try (FileChannel channel = FileChannel.open(folder.resolve("master.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            if (!Files.exists(keyFile)) {
                if (!mayCreate) throw new IllegalStateException("Missing wrapping key");
                byte[] key = new byte[16]; random.nextBytes(key);
                Path temp = Files.createTempFile(folder, "master-", ".tmp");
                try {
                    if (Files.getFileStore(temp).supportsFileAttributeView("posix"))
                        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
                    Files.write(temp, key);
                    Files.move(temp, keyFile, StandardCopyOption.ATOMIC_MOVE);
                } finally { Files.deleteIfExists(temp); }
            }
            byte[] bytes = Files.readAllBytes(keyFile);
            if (bytes.length != 16) throw new IllegalStateException("Invalid wrapping key");
            return bytes;
        }
    }
    private byte[] context(String uid, String endpoint, String model) {
        return (uid + "\n" + endpoint + "\n" + model).getBytes(StandardCharsets.UTF_8);
    }
    public String encrypt(String uid, String endpoint, String model, String value, boolean mayCreate) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(master(mayCreate), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context(uid, endpoint, model));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (Exception e) { throw new ObjectiveException(503, "AI 密钥存储不可用，请联系管理员检查密钥卷"); }
    }
    public String decrypt(String uid, String endpoint, String model, String value) {
        try {
            if (!value.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] bytes = Base64.getDecoder().decode(value.substring(3));
            if (bytes.length < 29) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(master(false), "AES"), new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
            cipher.updateAAD(context(uid, endpoint, model));
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new ObjectiveException(503, "AI 密钥无法读取，请检查密钥卷或重新配置 API Key"); }
    }
}
