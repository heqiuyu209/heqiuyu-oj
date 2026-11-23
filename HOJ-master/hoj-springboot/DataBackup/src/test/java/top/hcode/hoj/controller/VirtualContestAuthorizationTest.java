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
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.controller.oj.VirtualContestController;
import top.hcode.hoj.manager.oj.VirtualContestManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VirtualContestAuthorizationTest {
    DefaultSecurityManager security;VirtualContestController controller;VirtualContestManager manager;
    @BeforeEach void setup(){security=new DefaultSecurityManager(new AuthorizingRealm(){@Override protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token){return new SimpleAuthenticationInfo(token.getPrincipal(),"test",getName());}@Override protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals){SimpleAuthorizationInfo info=new SimpleAuthorizationInfo();if(!"restricted".equals(principals.getPrimaryPrincipal()))info.addStringPermission("submit");return info;}});ThreadContext.bind(security);manager=mock(VirtualContestManager.class);VirtualContestController target=new VirtualContestController();ReflectionTestUtils.setField(target,"manager",manager);AuthorizationAttributeSourceAdvisor advisor=new AuthorizationAttributeSourceAdvisor();advisor.setSecurityManager(security);ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvisor(advisor);controller=(VirtualContestController)factory.getProxy();}
    @AfterEach void cleanup(){ThreadContext.remove();security.destroy();}
    void login(String user){Subject subject=new Subject.Builder(security).buildSubject();subject.login(new UsernamePasswordToken(user,"test"));ThreadContext.bind(subject);}
    @Test void anonymousCannotReadOrStartVirtualRuns(){assertThrows(UnauthenticatedException.class,()->controller.current(1L));assertThrows(UnauthenticatedException.class,()->controller.start(1L,null));verifyNoInteractions(manager);}
    @Test void restrictedUserCannotStartOrRead(){login("restricted");assertThrows(UnauthorizedException.class,()->controller.current(1L));assertThrows(UnauthorizedException.class,()->controller.start(1L,null));verifyNoInteractions(manager);}
    @Test void ordinaryUserReachesServerScopedManager(){login("alice");controller.current(1L);controller.start(1L,null);verify(manager).current(1L);verify(manager).start(1L,null);}
}
