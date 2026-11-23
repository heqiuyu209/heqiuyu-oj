package top.hcode.hoj.manager.oj.external;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NowCoderSourceManagerTest {
    NowCoderSourceManager manager;JdbcTemplate jdbc;ValueOperations<String,String> values;NowCoderStatementClient client;NowCoderCatalogManager catalogue;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        manager=new NowCoderSourceManager(){@Override protected String uid(){return "alice";}};jdbc=mock(JdbcTemplate.class);values=mock(ValueOperations.class);StringRedisTemplate redis=mock(StringRedisTemplate.class);when(redis.opsForValue()).thenReturn(values);when(values.setIfAbsent(anyString(),anyString(),anyLong(),eq(TimeUnit.SECONDS))).thenReturn(true);when(jdbc.queryForObject(anyString(),eq(Integer.class),eq("alice"))).thenReturn(1);client=mock(NowCoderStatementClient.class);catalogue=mock(NowCoderCatalogManager.class);
        ReflectionTestUtils.setField(manager,"jdbc",jdbc);ReflectionTestUtils.setField(manager,"redis",redis);ReflectionTestUtils.setField(manager,"client",client);ReflectionTestUtils.setField(manager,"catalogue",catalogue);
    }
    @Test void onlyExplicitOrdinaryContestAndFixedOfficialUrlsBecomeSourceIds(){
        assertEquals("nowcoder:acm:218144",NowCoderSourceManager.key("NC218144"));assertEquals("nowcoder:contest:11244:A",NowCoderSourceManager.key("11244/a"));assertEquals("nowcoder:contest:11244:A",NowCoderSourceManager.key("https://ac.nowcoder.com/acm/contest/11244/A"));
        for(String value:new String[]{"https://evil.test/acm/problem/6","https://ac.nowcoder.com/acm/problem/6?next=https://evil.test","NC0","NC6;drop table","https://www.nowcoder.com/practice/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})assertThrows(ObjectiveException.class,()->manager.lookup(value));verifyNoInteractions(client,catalogue);
    }
    @Test void successfulDiscoveryRequiresCurrentPermissionAndUsesAPerActorCooldown(){
        Content c=new Content();c.setTitle("公开中文题目");NowCoderStatementClient.Statement source=new NowCoderStatementClient.Statement("8","6",c);when(client.fetch(eq("nowcoder:acm:6"),any())).thenAnswer(call->{((Runnable)call.getArgument(1)).run();return source;});assertEquals("NC-6",manager.lookup("NC6").get("displayId"));verify(catalogue).discovered("nowcoder:acm:6",source);verify(values).setIfAbsent(eq("remote:nowcoder:lookup:alice"),eq("1"),eq(10L),eq(TimeUnit.SECONDS));
    }
    @Test void permissionRevocationAfterReadNeverRegistersTheDiscoveredTask(){
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq("alice"))).thenReturn(1,0);when(client.fetch(eq("nowcoder:acm:6"),any())).thenReturn(new NowCoderStatementClient.Statement("8","6",new Content()));assertEquals(403,assertThrows(ObjectiveException.class,()->manager.lookup("NC6")).getStatus());verifyNoInteractions(catalogue);
    }
    @Test void busyOrUnavailableLimiterNeverBecomesAnUnrestrictedSourceRequest(){
        when(values.setIfAbsent(anyString(),anyString(),eq(10L),eq(TimeUnit.SECONDS))).thenReturn(false);assertEquals(429,assertThrows(ObjectiveException.class,()->manager.lookup("NC6")).getStatus());verifyNoInteractions(client);when(values.setIfAbsent(anyString(),anyString(),eq(10L),eq(TimeUnit.SECONDS))).thenThrow(new IllegalStateException("unavailable"));assertEquals(503,assertThrows(ObjectiveException.class,()->manager.lookup("NC6")).getStatus());verifyNoInteractions(client);
    }
}
