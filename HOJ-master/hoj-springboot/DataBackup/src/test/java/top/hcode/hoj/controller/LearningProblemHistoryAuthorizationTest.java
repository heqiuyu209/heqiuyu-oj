package top.hcode.hoj.controller;

import org.apache.shiro.authc.*;
import org.apache.shiro.authz.*;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.subject.*;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.controller.oj.LearningProblemHistoryController;
import top.hcode.hoj.manager.oj.LearningProblemHistoryManager;
import top.hcode.hoj.pojo.dto.LearningHistoryDTO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearningProblemHistoryAuthorizationTest {
    DefaultSecurityManager security; LearningProblemHistoryController controller; LearningProblemHistoryManager manager;
    @BeforeEach void setup() {
        security=new DefaultSecurityManager(new AuthorizingRealm() {
            @Override protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) { return new SimpleAuthenticationInfo(token.getPrincipal(),"test",getName()); }
            @Override protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) { SimpleAuthorizationInfo info=new SimpleAuthorizationInfo();if(!"restricted".equals(principals.getPrimaryPrincipal()))info.addStringPermission("submit");return info; }
        });
        ThreadContext.bind(security);manager=mock(LearningProblemHistoryManager.class);LearningProblemHistoryController target=new LearningProblemHistoryController();ReflectionTestUtils.setField(target,"manager",manager);
        AuthorizationAttributeSourceAdvisor advisor=new AuthorizationAttributeSourceAdvisor();advisor.setSecurityManager(security);ProxyFactory proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvisor(advisor);controller=(LearningProblemHistoryController)proxy.getProxy();
    }
    @AfterEach void cleanup() { ThreadContext.remove();security.destroy(); }
    void login(String user) { Subject subject=new Subject.Builder(security).buildSubject();subject.login(new UsernamePasswordToken(user,"test"));ThreadContext.bind(subject); }
    @Test void anonymousCannotReadHistory() { assertThrows(UnauthenticatedException.class,()->controller.list(new LearningHistoryDTO.Query(),new MockHttpServletResponse()));verifyNoInteractions(manager); }
    @Test void restrictedUserCannotReadHistory() { login("restricted");assertThrows(UnauthorizedException.class,()->controller.list(new LearningHistoryDTO.Query(),new MockHttpServletResponse()));verifyNoInteractions(manager); }
    @Test void ordinaryUserReachesMethodWithNoStoreAndNoTargetUid() {
        login("user");LearningHistoryDTO.Query query=new LearningHistoryDTO.Query();MockHttpServletResponse response=new MockHttpServletResponse();controller.list(query,response);verify(manager).list(query);assertEquals("no-store",response.getHeader("Cache-Control"));
        assertFalse(java.util.Arrays.stream(LearningHistoryDTO.Query.class.getDeclaredFields()).anyMatch(field->field.getName().equals("uid")));
    }
}
