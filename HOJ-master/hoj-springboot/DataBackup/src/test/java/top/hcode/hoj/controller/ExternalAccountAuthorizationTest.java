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
import top.hcode.hoj.controller.oj.ExternalAccountController;
import top.hcode.hoj.manager.oj.external.ExternalAccountManager;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalAccountAuthorizationTest {
    DefaultSecurityManager security;ExternalAccountController controller;ExternalAccountManager manager;
    @BeforeEach void setup(){
        security=new DefaultSecurityManager(new AuthorizingRealm(){
            @Override protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken t){return new SimpleAuthenticationInfo(t.getPrincipal(),"test",getName());}
            @Override protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection p){return new SimpleAuthorizationInfo();}
        });ThreadContext.bind(security);manager=mock(ExternalAccountManager.class);
        ExternalAccountController target=new ExternalAccountController();ReflectionTestUtils.setField(target,"manager",manager);
        AuthorizationAttributeSourceAdvisor advisor=new AuthorizationAttributeSourceAdvisor();advisor.setSecurityManager(security);
        ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvisor(advisor);controller=(ExternalAccountController)factory.getProxy();
    }
    @AfterEach void close(){ThreadContext.remove();security.destroy();}
    @Test void anonymousCannotManageAssociationsOrProofs(){
        assertThrows(UnauthenticatedException.class,controller::mine);assertThrows(UnauthenticatedException.class,()->controller.link(Collections.emptyMap()));
        assertThrows(UnauthenticatedException.class,()->controller.unlink(1));assertThrows(UnauthenticatedException.class,()->controller.proof(1));
        assertThrows(UnauthenticatedException.class,()->controller.sync(1));assertThrows(UnauthenticatedException.class,()->controller.verify(1));verifyNoInteractions(manager);
    }
    @Test void publicViewUsesFilteredManagerAndSignedInUserCanReachOwnOperations(){
        controller.publicView("alice");verify(manager).publicView("alice");
        Subject subject=new Subject.Builder(security).buildSubject();subject.login(new UsernamePasswordToken("user","test"));ThreadContext.bind(subject);
        controller.mine();controller.link(Collections.emptyMap());controller.proof(1);controller.sync(1);controller.verify(1);controller.unlink(1);
        verify(manager).mine();verify(manager).link(null,null);verify(manager).proof(1);verify(manager).requestSync(1,false);verify(manager).requestSync(1,true);verify(manager).unlink(1);
    }
}
