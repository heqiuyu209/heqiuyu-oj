package top.hcode.hoj.manager.oj.external;

import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.utils.RemoteCredentialCipher;
import top.hcode.hoj.utils.ShiroUtils;
import top.hcode.hoj.remote.RemoteSession;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

/** No controller can read decrypted credentials; dispatch must derive the user from the stored judge. */
@Component
public class RemoteCredentialManager {
    @org.springframework.beans.factory.annotation.Value("${hoj.personal.remote.enabled:false}") private boolean submissionEnabled;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private RemoteSessionVerifier sessions;
    @Value("${hoj.remote-credentials.secret-dir:/hoj/secrets/remote}") private String secretDirectory;
    private RemoteCredentialCipher cipher;
    private synchronized RemoteCredentialCipher cipher() {if(cipher==null)cipher=new RemoteCredentialCipher(Paths.get(secretDirectory));return cipher;}
    protected String uid() {if(ShiroUtils.getProfile()==null)throw new ObjectiveException(401,"请先登录");return ShiroUtils.getProfile().getUid();}
    private <T> T tx(Supplier<T> action) {return new TransactionTemplate(transactions).execute(status->action.get());}
    private Map<String,Object> account(long id,String actor,boolean lock) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT a.* FROM external_account a JOIN user_info u ON u.uuid=a.uid WHERE a.id=? AND a.uid=? AND u.status=0"+(lock?" FOR UPDATE":""),id,actor);
        if(rows.isEmpty()) throw new ObjectiveException(404,"账号关联不存在或不可访问");return rows.get(0);
    }
    private void verified(Map<String,Object> account) {
        if(!Arrays.asList("CODEFORCES","ATCODER","NOWCODER").contains(account.get("platform")))throw new ObjectiveException(400,"该平台暂不支持个人提交凭据");
        if(account.get("verified_identity")==null || !account.get("verified_identity").equals(account.get("handle_key")))throw new ObjectiveException(403,"请先验证该平台账号归属");
    }
    private Map<String,Object> credential(long id) {List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM remote_user_credential WHERE account_id=?",id);return rows.isEmpty()?null:rows.get(0);}
    private Map<String,Object> view(Map<String,Object> account,Map<String,Object> saved) {
        boolean configured=saved!=null && saved.get("credential_ciphertext")!=null;
        boolean session=configured && ((String)saved.get("credential_ciphertext")).startsWith("s1:");
        boolean supported=Arrays.asList("CODEFORCES","ATCODER","NOWCODER").contains(account.get("platform"));
        Map<String,Object> result=new LinkedHashMap<>();result.put("accountId",account.get("id"));result.put("platform",account.get("platform"));result.put("handle",account.get("handle"));result.put("configured",configured);result.put("version",saved==null?0:saved.get("credential_version"));result.put("updatedAt",saved==null?null:saved.get("updated_at"));result.put("ownershipVerified",Objects.equals(account.get("verified_identity"),account.get("handle_key")));
        result.put("submissionSupported",supported);result.put("submissionAvailable",supported && submissionEnabled && session && Objects.equals(account.get("verified_identity"),account.get("handle_key")));
        result.put("credentialType",configured?(session?"BROWSER_SESSION":"LEGACY_PASSWORD"):null);
        result.put("reauthorizationRequired",configured && !session);
        result.put("sessionCheckedAt",session?saved.get("updated_at"):null);return result;
    }
    public Map<String,Object> settings(long id) {String actor=uid();return tx(()->{Map<String,Object> owner=account(id,actor,true);return view(owner,credential(id));});}
    public Map<String,Object> saveSession(long id,String cookieHeader,Long expectedVersion,boolean authorized) {
        String actor=uid();
        if(!authorized || expectedVersion==null || expectedVersion<0)throw new ObjectiveException(400,"请确认会话使用授权并重新加载当前版本");
        // Do not hold account/credential locks across a source network request.
        Map<String,Object> snapshot=tx(()->{
            Map<String,Object> owner=account(id,actor,true);verified(owner);checkVersion(credential(id),expectedVersion);return owner;
        });
        RemoteSession session;
        try{session=RemoteSession.parse((String)snapshot.get("platform"),cookieHeader);}
        catch(IllegalArgumentException e){throw new ObjectiveException(400,"请粘贴源站请求中的完整 Cookie 值，不要粘贴密码、整段请求或浏览器脚本");}
        sessions.verify(id,(String)snapshot.get("platform"),(String)snapshot.get("handle_key"),session);
        return tx(()->{
            Map<String,Object> owner=account(id,actor,true);verified(owner);Map<String,Object> old=credential(id);long version=checkVersion(old,expectedVersion);
            if(!Objects.equals(owner.get("platform"),snapshot.get("platform")) || !Objects.equals(owner.get("handle_key"),snapshot.get("handle_key")))throw new ObjectiveException(409,"账号关联已变化，请重新打开后再授权");
            String encrypted;
            try {
                if(old!=null && old.get("credential_ciphertext")!=null){String prior=(String)old.get("credential_ciphertext");if(prior.startsWith("s1:"))cipher().decryptSession(id,actor,(String)owner.get("platform"),(String)owner.get("handle_key"),prior);else cipher().decrypt(id,actor,(String)owner.get("platform"),(String)owner.get("handle_key"),prior);}
                boolean mayCreate=jdbc.queryForObject("SELECT COUNT(*) FROM remote_user_credential WHERE credential_ciphertext IS NOT NULL",Integer.class)==0;
                encrypted=cipher().encryptSession(id,actor,(String)owner.get("platform"),(String)owner.get("handle_key"),session.cookieHeader(),mayCreate);
            }catch(Exception e){throw new ObjectiveException(503,"远程凭据存储不可用，请联系管理员检查凭据密钥卷");}
            jdbc.update("INSERT INTO remote_user_credential(account_id,credential_ciphertext,credential_version,updated_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE credential_ciphertext=VALUES(credential_ciphertext),credential_version=VALUES(credential_version),updated_at=VALUES(updated_at)",id,encrypted,version+1,new Timestamp(System.currentTimeMillis()));
            return view(owner,credential(id));
        });
    }
    private long checkVersion(Map<String,Object> saved,long expected) {
        long version=saved==null?0:ExternalAccountManager.number(saved.get("credential_version"));
        if(version!=expected)throw new ObjectiveException(409,"凭据已被更新，请重新打开后再操作");return version;
    }
    public Map<String,Object> delete(long id,long expectedVersion) {
        String actor=uid();return tx(()->{Map<String,Object> owner=account(id,actor,true);Map<String,Object> old=credential(id);long version=old==null?0:ExternalAccountManager.number(old.get("credential_version"));if(version!=expectedVersion)throw new ObjectiveException(409,"凭据已被更新，请重新打开后再删除");if(old!=null)jdbc.update("UPDATE remote_user_credential SET credential_ciphertext=NULL,credential_version=credential_version+1,updated_at=? WHERE account_id=?",new Timestamp(System.currentTimeMillis()),id);return view(owner,credential(id));});
    }
    /** Internal-only: caller must use the immutable judge UID, never a client-supplied owner. */
    public RemoteSession sessionForDispatch(long id,String judgeUid,long expectedVersion) {
        return tx(()->{Map<String,Object> owner=account(id,judgeUid,true);verified(owner);Map<String,Object> saved=credential(id);checkVersion(saved,expectedVersion);if(saved==null || saved.get("credential_ciphertext")==null || !((String)saved.get("credential_ciphertext")).startsWith("s1:"))throw new ObjectiveException(400,"请先授权本人平台登录会话");try{return RemoteSession.parse((String)owner.get("platform"),cipher().decryptSession(id,judgeUid,(String)owner.get("platform"),(String)owner.get("handle_key"),(String)saved.get("credential_ciphertext")));}catch(Exception e){throw new ObjectiveException(503,"远程会话无法读取，请检查凭据密钥卷或重新授权");}});
    }
    /** A source response may rotate an existing authorization; it cannot create or resurrect one. */
    public void rotateSession(long id, String actor, long expectedVersion, RemoteSession original, RemoteSession replacement) {
        tx(() -> {
            Map<String,Object> owner=account(id,actor,true);verified(owner);Map<String,Object> saved=credential(id);checkVersion(saved,expectedVersion);
            if(saved==null || saved.get("credential_ciphertext")==null || !replacement.belongsTo((String)owner.get("platform"))) throw new ObjectiveException(409,"会话授权已变化，请重新读取");
            try {
                String current=cipher().decryptSession(id,actor,(String)owner.get("platform"),(String)owner.get("handle_key"),(String)saved.get("credential_ciphertext"));
                if(!current.equals(original.cookieHeader()))throw new ObjectiveException(409,"会话已更新，请重新读取语言");
                String encrypted=cipher().encryptSession(id,actor,(String)owner.get("platform"),(String)owner.get("handle_key"),replacement.cookieHeader(),false);
                jdbc.update("UPDATE remote_user_credential SET credential_ciphertext=?,updated_at=CURRENT_TIMESTAMP WHERE account_id=?",encrypted,id);
            } catch(ObjectiveException e){throw e;} catch(Exception e){throw new ObjectiveException(503,"会话无法更新，请重新授权");}
            return null;
        });
    }
}
