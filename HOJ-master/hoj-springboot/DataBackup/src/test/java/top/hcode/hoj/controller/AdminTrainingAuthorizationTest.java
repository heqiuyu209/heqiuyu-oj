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
import top.hcode.hoj.controller.admin.AdminTrainingController;
import top.hcode.hoj.service.admin.training.AdminTrainingService;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AdminTrainingAuthorizationTest {
    private DefaultSecurityManager security;
    private AdminTrainingController controller;
    private AdminTrainingService service;

    @BeforeEach
    void setup() {
        security = new DefaultSecurityManager(new AuthorizingRealm() {
            @Override
            protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) {
                return new SimpleAuthenticationInfo(token.getPrincipal(), "test", getName());
            }
            @Override
            protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
                SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
                info.addRole((String) principals.getPrimaryPrincipal());
                return info;
            }
        });
        ThreadContext.bind(security);
        service = mock(AdminTrainingService.class);
        AdminTrainingController target = new AdminTrainingController();
        ReflectionTestUtils.setField(target, "adminTrainingService", service);
        AuthorizationAttributeSourceAdvisor advisor = new AuthorizationAttributeSourceAdvisor();
        advisor.setSecurityManager(security);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvisor(advisor);
        controller = (AdminTrainingController) proxy.getProxy();
    }

    @AfterEach
    void cleanup() {
        ThreadContext.remove();
        security.destroy();
    }

    private void login(String role) {
        Subject subject = new Subject.Builder(security).buildSubject();
        subject.login(new UsernamePasswordToken(role, "test"));
        ThreadContext.bind(subject);
    }

    private void denied(Class<? extends Throwable> exception) {
        assertThrows(exception, () -> controller.updateTraining(null));
        assertThrows(exception, () -> controller.changeTrainingStatus(7L, "untrusted", true));
        verifyNoInteractions(service);
    }

    @Test
    void anonymousCannotReachEitherMutation() {
        denied(UnauthenticatedException.class);
    }

    @Test
    void studentCannotReachEitherMutation() {
        login("default_user");
        denied(UnauthorizedException.class);
    }

    @Test
    void allowedRolesReachTheServiceWhereStoredOwnershipIsChecked() {
        for (String role : new String[]{"root", "admin", "problem_admin"}) {
            login(role);
            controller.updateTraining(null);
            controller.changeTrainingStatus(7L, "untrusted", true);
        }
        verify(service, times(3)).updateTraining(null);
        verify(service, times(3)).changeTrainingStatus(7L, "untrusted", true);
    }
}
