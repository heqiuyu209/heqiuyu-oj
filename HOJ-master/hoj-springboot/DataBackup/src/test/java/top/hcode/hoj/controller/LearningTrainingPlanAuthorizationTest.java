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
import top.hcode.hoj.controller.oj.LearningTrainingPlanController;
import top.hcode.hoj.manager.oj.LearningTrainingPlanManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearningTrainingPlanAuthorizationTest {
    DefaultSecurityManager security; LearningTrainingPlanController controller; LearningTrainingPlanManager manager;
    @BeforeEach void setup() {
        security=new DefaultSecurityManager(new AuthorizingRealm() {
            @Override protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) { return new SimpleAuthenticationInfo(token.getPrincipal(),"test",getName()); }
            @Override protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) { SimpleAuthorizationInfo info=new SimpleAuthorizationInfo(); if(!"restricted".equals(principals.getPrimaryPrincipal()))info.addStringPermission("submit"); return info; }
        });
        ThreadContext.bind(security); manager=mock(LearningTrainingPlanManager.class);
        LearningTrainingPlanController target=new LearningTrainingPlanController(); ReflectionTestUtils.setField(target,"manager",manager);
        AuthorizationAttributeSourceAdvisor advisor=new AuthorizationAttributeSourceAdvisor(); advisor.setSecurityManager(security);
        ProxyFactory proxy=new ProxyFactory(target); proxy.setProxyTargetClass(true); proxy.addAdvisor(advisor); controller=(LearningTrainingPlanController)proxy.getProxy();
    }
    @AfterEach void cleanup() { ThreadContext.remove(); security.destroy(); }
    void login(String user) { Subject subject=new Subject.Builder(security).buildSubject(); subject.login(new UsernamePasswordToken(user,"test")); ThreadContext.bind(subject); }
    @Test void anonymousCannotReachAnyPlanOperation() { denied(UnauthenticatedException.class); }
    @Test void restrictedUserCannotReachAnyPlanOperation() { login("restricted"); denied(UnauthorizedException.class); }
    <T extends Throwable> void denied(Class<T> type) { assertThrows(type,()->controller.list("ACTIVE",1)); assertThrows(type,()->controller.create(null)); assertThrows(type,()->controller.detail("id")); assertThrows(type,()->controller.update("id",null)); verifyNoInteractions(manager); }
    @Test void ordinaryUserReachesTheManagerWithAllFourMethodLevelAnnotations() { login("user"); controller.list("ACTIVE",1); controller.create(null); controller.detail("id"); controller.update("id",null); verify(manager).list("ACTIVE",1); verify(manager).create(null); verify(manager).detail("id"); verify(manager).update("id",null); }
}
