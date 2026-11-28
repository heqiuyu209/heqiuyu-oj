package top.hcode.hoj.manager.oj.external;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import top.hcode.hoj.common.exception.ObjectiveException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class RemoteReadRateGate {
    static final String SHARED_SYNC_KEY = "external:cf:public-api";
    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(8);
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>(
            "if KEYS[2] and redis.call('exists', KEYS[2]) == 1 then return 2 end " +
            "if not redis.call('set', KEYS[1], '1', 'NX', 'EX', 3) then return 0 end " +
            "if KEYS[2] then redis.call('set', KEYS[2], '1', 'EX', 10) end return 1", Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end return 0", Long.class);

    private RemoteReadRateGate() {}

    static void acquire(StringRedisTemplate redis, String sourceKey, String cooldownKey, String cooldownMessage) {
        List<String> keys = cooldownKey == null ? Collections.singletonList(sourceKey) : Arrays.asList(sourceKey, cooldownKey);
        long started = System.nanoTime();
        try {
            do {
                Long result = redis.execute(ACQUIRE, keys);
                if (Long.valueOf(1).equals(result)) return;
                if (Long.valueOf(2).equals(result)) throw new ObjectiveException(429, cooldownMessage);
                if (!Long.valueOf(0).equals(result)) break;
                TimeUnit.MILLISECONDS.sleep(250);
            } while (System.nanoTime() - started < WAIT_NANOS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (DataAccessException | IllegalStateException e) {
            throw new ObjectiveException(503, "本站暂时无法安排源站请求，请稍后重试");
        }
        throw new ObjectiveException(503, "本站访问源站的请求较多，请稍后重试");
    }

    static void releaseUnusedSyncSlot(StringRedisTemplate redis, String token) {
        redis.execute(RELEASE, Collections.singletonList(SHARED_SYNC_KEY), token);
    }
}
