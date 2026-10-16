package top.hcode.hoj.controller;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.*;
import org.apache.shiro.authz.*;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import top.hcode.hoj.controller.admin.AdminPaperController;
import top.hcode.hoj.controller.oj.PaperController;
import top.hcode.hoj.service.oj.PaperService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the installed Shiro advisor; no database, Redis, or real accounts. */
class PaperAuthorizationTest {
    private DefaultSecurityManager securityManager;
    private PaperService service;
    private PaperController student;
    private AdminPaperController admin;

    @BeforeEach void setup() {
        securityManager = new DefaultSecurityManager(new AuthorizingRealm() {
            @Override protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) {
                return new SimpleAuthenticationInfo(token.getPrincipal(), "test-password", getName());
            }

            @Override protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
                String role = (String) principals.getPrimaryPrincipal();
                SimpleAuthorizationInfo info = new SimpleAuthorizationInfo(Collections.singleton(role));
                if (!"restricted".equals(role)) info.addStringPermission("submit");
                return info;
            }
        });
        ThreadContext.bind(securityManager);
        service = mock(PaperService.class);
        student = secured(new PaperController());
        admin = secured(new AdminPaperController());
    }

    @AfterEach void cleanup() {
        ThreadContext.remove();
        securityManager.destroy();
    }

    @SuppressWarnings("unchecked")
    private <T> T secured(T controller) {
        ReflectionTestUtils.setField(controller, "service", service);
        AuthorizationAttributeSourceAdvisor advisor = new AuthorizationAttributeSourceAdvisor();
        advisor.setSecurityManager(securityManager);
        ProxyFactory factory = new ProxyFactory(controller);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(advisor);
        return (T) factory.getProxy();
    }

    private void login(String role) {
        Subject subject = new Subject.Builder(securityManager).buildSubject();
        subject.login(new UsernamePasswordToken(role, "test-password"));
        ThreadContext.bind(subject);
        assertTrue(SecurityUtils.getSubject().isAuthenticated());
    }

    private List<Method> endpoints(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> AnnotationUtils.findAnnotation(method, RequestMapping.class) != null)
                .collect(Collectors.toList());
    }

    private void invoke(Object controller, Method method) throws Throwable {
        Object[] args = new Object[method.getParameterCount()];
        try {
            method.invoke(controller, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test void anonymousCannotAccessAnyPaperEndpoint() {
        for (Method method : endpoints(PaperController.class)) {
            assertThrows(UnauthenticatedException.class, () -> invoke(student, method), method.getName());
        }
        for (Method method : endpoints(AdminPaperController.class)) {
            assertThrows(UnauthenticatedException.class, () -> invoke(admin, method), method.getName());
        }
        verifyNoInteractions(service);
    }

    @Test void ordinaryUserCannotAccessAnyAdminEndpoint() {
        login("user");
        for (Method method : endpoints(AdminPaperController.class)) {
            assertThrows(UnauthorizedException.class, () -> invoke(admin, method), method.getName());
        }
        verifyNoInteractions(service);
    }

    @Test void allSupportedManagementRolesCanAccessAdminEndpoints() {
        for (String role : Arrays.asList("root", "admin", "problem_admin")) {
            login(role);
            for (Method method : endpoints(AdminPaperController.class)) {
                assertDoesNotThrow(() -> invoke(admin, method), role + ":" + method.getName());
            }
        }
        verify(service, times(3)).save(null);
    }

    @Test void ordinaryUserWithSubmitPermissionCanUseStudentEndpoints() {
        login("user");
        for (Method method : endpoints(PaperController.class)) {
            assertDoesNotThrow(() -> invoke(student, method), method.getName());
        }
        verify(service).start(null);
        verify(service).answer(null, null);
        verify(service).finish(null);
    }

    @Test void submitPermissionStillProtectsAllStudentWrites() {
        login("restricted");
        for (Method method : endpoints(PaperController.class)) {
            if (Arrays.asList("start", "answer", "submit", "finish", "retry").contains(method.getName())) {
                assertThrows(UnauthorizedException.class, () -> invoke(student, method), method.getName());
            } else {
                assertDoesNotThrow(() -> invoke(student, method), method.getName());
            }
        }
        verify(service, never()).start(any());
        verify(service, never()).answer(any(), any());
        verify(service, never()).finish(any());
    }

    @Test void mixedContestEndpointsUseTheInstalledShiroAdvisor() {
        top.hcode.hoj.controller.oj.MixedContestController controller = new top.hcode.hoj.controller.oj.MixedContestController();
        top.hcode.hoj.manager.oj.PaperManager papers = mock(top.hcode.hoj.manager.oj.PaperManager.class);
        top.hcode.hoj.manager.oj.MixedContestRankManager ranks = mock(top.hcode.hoj.manager.oj.MixedContestRankManager.class);
        ReflectionTestUtils.setField(controller, "papers", papers); ReflectionTestUtils.setField(controller, "ranks", ranks);
        AuthorizationAttributeSourceAdvisor advisor = new AuthorizationAttributeSourceAdvisor(); advisor.setSecurityManager(securityManager);
        ProxyFactory factory = new ProxyFactory(controller); factory.setProxyTargetClass(true); factory.addAdvisor(advisor);
        top.hcode.hoj.controller.oj.MixedContestController secured = (top.hcode.hoj.controller.oj.MixedContestController) factory.getProxy();
        assertThrows(UnauthenticatedException.class, () -> secured.start(1L)); assertThrows(UnauthenticatedException.class, () -> secured.rank(1L));
        login("restricted"); assertThrows(UnauthorizedException.class, () -> secured.start(1L)); assertDoesNotThrow(() -> secured.rank(1L));
        verify(papers, never()).startContest(anyLong()); login("user"); assertDoesNotThrow(() -> secured.start(1L)); verify(papers).startContest(1L);
    }
}
