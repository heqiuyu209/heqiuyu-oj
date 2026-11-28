package top.hcode.hoj.controller;

import org.apache.shiro.authc.*;
import org.apache.shiro.authz.UnauthenticatedException;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.realm.AuthenticatingRealm;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.web.MockHttpServletResponse;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.controller.oj.CodeforcesBrowserSubmitController;
import top.hcode.hoj.manager.oj.external.CodeforcesBrowserSubmitManager;
import top.hcode.hoj.manager.oj.external.CodeforcesBrowserSubmitManager.BrowserEvent;
import top.hcode.hoj.manager.oj.external.CodeforcesBrowserSubmitManager.NewSubmission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class CodeforcesBrowserSubmitAuthorizationTest {
    @Test void onlyTaskKeyRoutesCanBeAccessedWithoutSiteLogin() {
        DefaultSecurityManager security = new DefaultSecurityManager(new AuthenticatingRealm() {
            @Override protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken login) {
                return new SimpleAuthenticationInfo(login.getPrincipal(), "test", getName());
            }
        });
        ThreadContext.bind(security);
        try {
            CodeforcesBrowserSubmitManager manager = mock(CodeforcesBrowserSubmitManager.class);
            when(manager.claim(1, null)).thenThrow(new ObjectiveException(401, "提交凭据无效"));
            BrowserEvent event = new BrowserEvent();
            when(manager.browserEvent(1, null, event)).thenThrow(new ObjectiveException(401, "提交凭据无效"));
            AuthorizationAttributeSourceAdvisor advisor = new AuthorizationAttributeSourceAdvisor();
            advisor.setSecurityManager(security);
            ProxyFactory factory = new ProxyFactory(new CodeforcesBrowserSubmitController(manager));
            factory.setProxyTargetClass(true);
            factory.addAdvisor(advisor);
            CodeforcesBrowserSubmitController controller = (CodeforcesBrowserSubmitController) factory.getProxy();
            MockHttpServletResponse response = new MockHttpServletResponse();
            NewSubmission input = new NewSubmission();
            assertThrows(UnauthenticatedException.class, () -> controller.create(input, response));
            assertThrows(UnauthenticatedException.class, () -> controller.latest("CF-4A", response));
            assertThrows(UnauthenticatedException.class, () -> controller.status(1, response));
            verifyNoInteractions(manager);
            assertEquals(401, controller.claim(1, null, response).getStatus());
            assertEquals(401, controller.event(1, null, event, response).getStatus());
            assertEquals("no-store", response.getHeader("Cache-Control"));

            Subject subject = new Subject.Builder(security).buildSubject();
            subject.login(new UsernamePasswordToken("owner", "test"));
            ThreadContext.bind(subject);
            controller.create(input, response);
            controller.latest("CF-4A", response);
            controller.status(1, response);
            verify(manager).create(input);
            verify(manager).latest("CF-4A");
            verify(manager).status(1);
        } finally {
            ThreadContext.remove();
            security.destroy();
        }
    }
}
