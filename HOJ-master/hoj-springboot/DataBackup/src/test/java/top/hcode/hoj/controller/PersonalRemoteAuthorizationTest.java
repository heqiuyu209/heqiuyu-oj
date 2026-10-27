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
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.controller.oj.PersonalRemoteSubmissionController;
import top.hcode.hoj.manager.oj.external.PersonalRemoteSubmissionManager;
import top.hcode.hoj.pojo.dto.PersonalRemoteSubmissionDTO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalRemoteAuthorizationTest {
    DefaultSecurityManager security;PersonalRemoteSubmissionController controller;PersonalRemoteSubmissionManager manager;
    @BeforeEach void setup(){security=new DefaultSecurityManager(new AuthorizingRealm(){@Override protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token){return new SimpleAuthenticationInfo(token.getPrincipal(),"test",getName());}@Override protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals){return new SimpleAuthorizationInfo();}});ThreadContext.bind(security);manager=mock(PersonalRemoteSubmissionManager.class);PersonalRemoteSubmissionController target=new PersonalRemoteSubmissionController();ReflectionTestUtils.setField(target,"manager",manager);AuthorizationAttributeSourceAdvisor advisor=new AuthorizationAttributeSourceAdvisor();advisor.setSecurityManager(security);ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvisor(advisor);controller=(PersonalRemoteSubmissionController)factory.getProxy();}
    @AfterEach void close(){ThreadContext.remove();security.destroy();}
    @Test void allSevenEntrypointsRejectAnonymousBeforeBusinessLogic(){PersonalRemoteSubmissionDTO input=new PersonalRemoteSubmissionDTO();assertThrows(UnauthenticatedException.class,()->controller.accounts(input));assertThrows(UnauthenticatedException.class,()->controller.options(input));assertThrows(UnauthenticatedException.class,()->controller.submit(input,new MockHttpServletRequest()));assertThrows(UnauthenticatedException.class,()->controller.status(1));assertThrows(UnauthenticatedException.class,()->controller.request("request_00000001"));assertThrows(UnauthenticatedException.class,()->controller.reconcile(1));assertThrows(UnauthenticatedException.class,()->controller.cancel(1));verifyNoInteractions(manager);}
    @Test void authenticatedActionsUseOwnerCheckingManagerAndErrorsNeverEchoSecrets(){Subject subject=new Subject.Builder(security).buildSubject();subject.login(new UsernamePasswordToken("alice","test"));ThreadContext.bind(subject);PersonalRemoteSubmissionDTO input=new PersonalRemoteSubmissionDTO();controller.accounts(input);controller.options(input);controller.submit(input,new MockHttpServletRequest());controller.status(1);controller.request("request_00000001");controller.reconcile(1);controller.cancel(1);verify(manager).accounts(input);verify(manager).options(input);verify(manager).submit(eq(input),any());verify(manager).status(1);verify(manager).request("request_00000001");verify(manager).reconcile(1);verify(manager).cancel(1);when(manager.status(2)).thenThrow(new IllegalStateException("synthetic-cookie-and-source"));assertEquals(503,controller.status(2).getStatus());assertFalse(controller.status(2).getMsg().contains("synthetic"));MockHttpServletResponse response=new MockHttpServletResponse();controller.noCache(response);assertEquals("no-store",response.getHeader("Cache-Control"));}
}
