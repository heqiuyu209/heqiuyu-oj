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
import top.hcode.hoj.controller.oj.RemoteCredentialController;
import top.hcode.hoj.manager.oj.external.RemoteCredentialManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteCredentialAuthorizationTest {
    DefaultSecurityManager security;RemoteCredentialController controller;RemoteCredentialManager manager;
    @BeforeEach void setup(){security=new DefaultSecurityManager(new AuthorizingRealm(){@Override protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token){return new SimpleAuthenticationInfo(token.getPrincipal(),"test",getName());}@Override protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals){return new SimpleAuthorizationInfo();}});ThreadContext.bind(security);manager=mock(RemoteCredentialManager.class);RemoteCredentialController target=new RemoteCredentialController();ReflectionTestUtils.setField(target,"manager",manager);AuthorizationAttributeSourceAdvisor advisor=new AuthorizationAttributeSourceAdvisor();advisor.setSecurityManager(security);ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvisor(advisor);controller=(RemoteCredentialController)factory.getProxy();}
    @AfterEach void close(){ThreadContext.remove();security.destroy();}
    @Test void anonymousCannotReadStoreOrRemoveCredentials(){assertThrows(UnauthenticatedException.class,()->controller.settings(1));assertThrows(UnauthenticatedException.class,()->controller.save(1,new RemoteCredentialController.Input()));assertThrows(UnauthenticatedException.class,()->controller.delete(1,0));verifyNoInteractions(manager);}
    @Test void authenticatedCallsReachOnlyOwnerCheckingManager(){Subject subject=new Subject.Builder(security).buildSubject();subject.login(new UsernamePasswordToken("alice","test"));ThreadContext.bind(subject);RemoteCredentialController.Input input=new RemoteCredentialController.Input();input.cookieHeader="JSESSIONID=synthetic";input.authorized=true;input.version=0L;controller.settings(1);controller.save(1,input);controller.delete(1,1);verify(manager).settings(1);verify(manager).saveSession(1,"JSESSIONID=synthetic",0L,true);verify(manager).delete(1,1);}
    @Test void passwordInputIsRejectedBeforeAnyManagerCall(){Subject subject=new Subject.Builder(security).buildSubject();subject.login(new UsernamePasswordToken("alice","test"));ThreadContext.bind(subject);RemoteCredentialController.Input input=new RemoteCredentialController.Input();input.password="synthetic-password";input.version=0L;assertEquals(400,controller.save(1,input).getStatus());verifyNoInteractions(manager);}
}
