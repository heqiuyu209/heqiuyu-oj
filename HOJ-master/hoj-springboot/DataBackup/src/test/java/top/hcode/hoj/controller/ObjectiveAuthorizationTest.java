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
import top.hcode.hoj.controller.admin.AdminObjectiveController;
import top.hcode.hoj.controller.oj.ObjectiveController;
import top.hcode.hoj.service.oj.ObjectiveService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the installed Shiro advisor; no database, Redis, or real accounts. */
class ObjectiveAuthorizationTest {
    private DefaultSecurityManager securityManager;
    private ObjectiveService service;
    private ObjectiveController student;
    private AdminObjectiveController admin;

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
        service = mock(ObjectiveService.class);
        student = secured(new ObjectiveController());
        admin = secured(new AdminObjectiveController());
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

    @Test void anonymousCannotAccessAnyObjectiveEndpoint() {
        for (Method method : endpoints(ObjectiveController.class)) {
            assertThrows(UnauthenticatedException.class, () -> invoke(student, method), method.getName());
        }
        for (Method method : endpoints(AdminObjectiveController.class)) {
            assertThrows(UnauthenticatedException.class, () -> invoke(admin, method), method.getName());
        }
        verifyNoInteractions(service);
    }

    @Test void ordinaryUserCannotAccessAnyAdminEndpoint() {
        login("user");
        for (Method method : endpoints(AdminObjectiveController.class)) {
            assertThrows(UnauthorizedException.class, () -> invoke(admin, method), method.getName());
        }
        verifyNoInteractions(service);
    }

    @Test void allSupportedManagementRolesCanAccessAdminEndpoints() {
        for (String role : Arrays.asList("root", "admin", "problem_admin")) {
            login(role);
            for (Method method : endpoints(AdminObjectiveController.class)) {
                assertDoesNotThrow(() -> invoke(admin, method), role + ":" + method.getName());
            }
        }
        verify(service, times(3)).saveQuestion(null);
        verify(service, times(3)).saveSet(null);
    }

    @Test void ordinaryUserWithSubmitPermissionCanUseStudentEndpoints() {
        login("user");
        for (Method method : endpoints(ObjectiveController.class)) {
            assertDoesNotThrow(() -> invoke(student, method), method.getName());
        }
        verify(service).start(null);
        verify(service).answer(null, null);
        verify(service).finish(null);
    }

    @Test void submitPermissionStillProtectsAllStudentWrites() {
        login("restricted");
        for (Method method : endpoints(ObjectiveController.class)) {
            if (Arrays.asList("start", "answer", "finish").contains(method.getName())) {
                assertThrows(UnauthorizedException.class, () -> invoke(student, method), method.getName());
            } else {
                assertDoesNotThrow(() -> invoke(student, method), method.getName());
            }
        }
        verify(service, never()).start(any());
        verify(service, never()).answer(any(), any());
        verify(service, never()).finish(any());
    }
}
