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
import top.hcode.hoj.controller.oj.AiAssistantController;
import top.hcode.hoj.manager.oj.ai.AiAssistantManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiAssistantAuthorizationTest {
    DefaultSecurityManager security; AiAssistantController controller; AiAssistantManager manager;
    @BeforeEach void setup() {
        security=new DefaultSecurityManager(new AuthorizingRealm() {
            @Override protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) { return new SimpleAuthenticationInfo(token.getPrincipal(),"test",getName()); }
            @Override protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) { SimpleAuthorizationInfo info=new SimpleAuthorizationInfo();if(!"restricted".equals(principals.getPrimaryPrincipal()))info.addStringPermission("submit");return info; }
        });
        ThreadContext.bind(security);manager=mock(AiAssistantManager.class);
        AiAssistantController target=new AiAssistantController();ReflectionTestUtils.setField(target,"manager",manager);
        AuthorizationAttributeSourceAdvisor advisor=new AuthorizationAttributeSourceAdvisor();advisor.setSecurityManager(security);
        ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvisor(advisor);controller=(AiAssistantController)factory.getProxy();
    }
    @AfterEach void cleanup() { ThreadContext.remove();security.destroy(); }
    void login(String user) { Subject subject=new Subject.Builder(security).buildSubject();subject.login(new UsernamePasswordToken(user,"test"));ThreadContext.bind(subject); }
    @Test void anonymousCannotReadWriteDeleteSettingsOrChat() {
        assertThrows(UnauthenticatedException.class,controller::settings);assertThrows(UnauthenticatedException.class,()->controller.save(null));assertThrows(UnauthenticatedException.class,controller::remove);assertThrows(UnauthenticatedException.class,()->controller.chat(null));assertThrows(UnauthenticatedException.class,()->controller.recommendations(null));assertThrows(UnauthenticatedException.class,()->controller.explain(null));verifyNoInteractions(manager);
    }
    @Test void restrictedUserCannotSendPrompts() { login("restricted");assertThrows(UnauthorizedException.class,()->controller.chat(null));assertThrows(UnauthorizedException.class,()->controller.recommendations(null));assertThrows(UnauthorizedException.class,()->controller.explain(null));verifyNoInteractions(manager); }
    @Test void ordinaryAuthenticatedUserCanReachOwnSettingsAndChatManager() { login("user");controller.settings();controller.save(null);controller.remove();controller.chat(null);controller.recommendations(null);controller.explain(null);verify(manager).settings();verify(manager).save(null);verify(manager).remove();verify(manager).chat(null);verify(manager).recommendations(null);verify(manager).explain(null); }
    @Test void problemPreviewUsesTheActualMethodAdvisorForAllThreeActorTypes() {
        assertThrows(UnauthenticatedException.class,()->controller.problemContext(null));login("restricted");assertThrows(UnauthorizedException.class,()->controller.problemContext(null));verifyNoInteractions(manager);
        login("user");controller.problemContext(null);verify(manager).problemContext(null);
    }
}
