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
import top.hcode.hoj.controller.admin.RemoteTranslationController;
import top.hcode.hoj.manager.oj.external.RemoteTranslationManager;
import top.hcode.hoj.pojo.dto.RemoteTranslationDTO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteTranslationAuthorizationTest {
    DefaultSecurityManager security;RemoteTranslationController controller;RemoteTranslationManager translations;
    @BeforeEach void setup(){
        security=new DefaultSecurityManager(new AuthorizingRealm(){
            @Override protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token){return new SimpleAuthenticationInfo(token.getPrincipal(),"test",getName());}
            @Override protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals){SimpleAuthorizationInfo info=new SimpleAuthorizationInfo();info.addRole(principals.getPrimaryPrincipal().toString());return info;}
        });ThreadContext.bind(security);translations=mock(RemoteTranslationManager.class);
        RemoteTranslationController target=new RemoteTranslationController();ReflectionTestUtils.setField(target,"translations",translations);
        AuthorizationAttributeSourceAdvisor advisor=new AuthorizationAttributeSourceAdvisor();advisor.setSecurityManager(security);ProxyFactory proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvisor(advisor);controller=(RemoteTranslationController)proxy.getProxy();
    }
    @AfterEach void close(){ThreadContext.remove();security.destroy();}
    void login(String role){Subject subject=new Subject.Builder(security).buildSubject();subject.login(new UsernamePasswordToken(role,"test"));ThreadContext.bind(subject);}
    @Test void anonymousAndOrdinaryUsersCannotInvokeTranslation(){
        assertThrows(UnauthenticatedException.class,()->controller.generate(new RemoteTranslationDTO(),new MockHttpServletResponse()));
        login("default_user");assertThrows(UnauthorizedException.class,()->controller.generate(new RemoteTranslationDTO(),new MockHttpServletResponse()));verifyNoInteractions(translations);
    }
    @Test void existingProblemAdministrationRolesCanRequestANonCachedDraft(){
        for(String role:new String[]{"root","admin","problem_admin"}){login(role);MockHttpServletResponse response=new MockHttpServletResponse();controller.generate(new RemoteTranslationDTO(),response);assertEquals("no-store",response.getHeader("Cache-Control"));}
        verify(translations,times(3)).generate(any());
    }
}
