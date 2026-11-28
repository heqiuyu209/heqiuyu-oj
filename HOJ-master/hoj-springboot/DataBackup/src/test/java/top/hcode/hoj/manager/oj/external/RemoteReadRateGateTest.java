package top.hcode.hoj.manager.oj.external;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import top.hcode.hoj.common.exception.ObjectiveException;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RemoteReadRateGateTest {
    private static final String SOURCE = RemoteReadRateGate.SHARED_SYNC_KEY;
    private static final String COOLDOWN = "external:personal-options:1";
    private static final String REPEAT = "语言读取较频繁，请稍后重试";
    private final List<String> keys = Arrays.asList(SOURCE, COOLDOWN);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    void firstRequestWaitsForSharedSlot() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), eq(keys))).thenReturn(0L, 1L);

        assertDoesNotThrow(this::acquire);
        verify(redis, times(2)).execute(any(), eq(keys));
    }

    @Test
    void ownCooldownReturns429WithoutWaiting() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), eq(keys))).thenReturn(2L);

        ObjectiveException failure = assertThrows(ObjectiveException.class, this::acquire);
        assertEquals(429, failure.getStatus());
        assertEquals(REPEAT, failure.getMessage());
        verify(redis).execute(any(), eq(keys));
    }

    @Test
    void unavailableRedisIsNotReportedAsAnAccountCooldown() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), eq(keys)))
                .thenThrow(new DataAccessResourceFailureException("controlled test"));

        ObjectiveException failure = assertThrows(ObjectiveException.class, this::acquire);
        assertEquals(503, failure.getStatus());
        assertNotEquals(REPEAT, failure.getMessage());
    }

    @Test
    void interruptedSharedWaitStopsAndPreservesInterrupt() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), eq(keys))).thenReturn(0L);

        Thread.currentThread().interrupt();
        try {
            ObjectiveException failure = assertThrows(ObjectiveException.class, this::acquire);
            assertEquals(503, failure.getStatus());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private void acquire() {
        RemoteReadRateGate.acquire(redis, SOURCE, COOLDOWN, REPEAT);
    }
}
