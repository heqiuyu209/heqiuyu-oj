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
import top.hcode.hoj.controller.admin.CodeforcesCposImportController;
import top.hcode.hoj.controller.admin.RemoteSourceController;
import top.hcode.hoj.controller.oj.RemoteCaptureController;
import top.hcode.hoj.manager.oj.external.*;
import top.hcode.hoj.manager.oj.external.cpos.CodeforcesCposImportManager;
import top.hcode.hoj.manager.oj.external.cpos.CodeforcesCposImportManager.Capture;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteSourceAuthorizationTest {
    DefaultSecurityManager security;RemoteSourceController controller;RemoteCatalogManager catalogue;RemoteStatementManager statements;
    @BeforeEach void setup(){
        security=new DefaultSecurityManager(new AuthorizingRealm(){
            @Override protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken t){return new SimpleAuthenticationInfo(t.getPrincipal(),"test",getName());}
            @Override protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection p){SimpleAuthorizationInfo info=new SimpleAuthorizationInfo();info.addRole(p.getPrimaryPrincipal().toString());return info;}
        });ThreadContext.bind(security);catalogue=mock(RemoteCatalogManager.class);statements=mock(RemoteStatementManager.class);
        RemoteSourceController target=new RemoteSourceController();ReflectionTestUtils.setField(target,"catalogue",catalogue);ReflectionTestUtils.setField(target,"statements",statements);
        AuthorizationAttributeSourceAdvisor advisor=new AuthorizationAttributeSourceAdvisor();advisor.setSecurityManager(security);ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvisor(advisor);controller=(RemoteSourceController)factory.getProxy();
    }
    @AfterEach void close(){ThreadContext.remove();security.destroy();}
    void login(String role){Subject subject=new Subject.Builder(security).buildSubject();subject.login(new UsernamePasswordToken(role,"test"));ThreadContext.bind(subject);}
    @Test void anonymousAndOrdinaryUsersCannotUseAnySourceOperation(){
        assertThrows(UnauthenticatedException.class, () -> controller.htmlPreview(new RemoteStatementDTO.HtmlPreview()));
        assertThrows(UnauthenticatedException.class,()->controller.list(1,null,null,null));assertThrows(UnauthenticatedException.class,controller::sync);assertThrows(UnauthenticatedException.class,()->controller.request(new RemoteStatementDTO()));assertThrows(UnauthenticatedException.class,()->controller.statement("key"));assertThrows(UnauthenticatedException.class,()->controller.english(new RemoteStatementDTO()));assertThrows(UnauthenticatedException.class,()->controller.chinese(new RemoteStatementDTO()));
        assertThrows(UnauthenticatedException.class,()->controller.refresh(new RemoteStatementDTO()));login("default_user");
        assertThrows(UnauthorizedException.class, () -> controller.htmlPreview(new RemoteStatementDTO.HtmlPreview()));
        assertThrows(UnauthorizedException.class,()->controller.list(1,null,null,null));assertThrows(UnauthorizedException.class,controller::sync);assertThrows(UnauthorizedException.class,()->controller.request(new RemoteStatementDTO()));assertThrows(UnauthorizedException.class,()->controller.statement("key"));assertThrows(UnauthorizedException.class,()->controller.english(new RemoteStatementDTO()));assertThrows(UnauthorizedException.class,()->controller.chinese(new RemoteStatementDTO()));assertThrows(UnauthorizedException.class,()->controller.refresh(new RemoteStatementDTO()));verifyNoInteractions(catalogue,statements);
    }
    @Test void allExistingProblemAdministrationRolesCanUseSourceOperations(){
        for(String role:new String[]{"admin","root","problem_admin"}){
            login(role);RemoteStatementDTO dto=new RemoteStatementDTO();dto.setProblemKey("key");controller.list(1,null,null,null);controller.sync();controller.request(dto);controller.statement("key");controller.english(dto);controller.chinese(dto);dto.setAutoRefresh(true);controller.refresh(dto);
            controller.htmlPreview(new RemoteStatementDTO.HtmlPreview());
        }
        verify(statements, times(3)).previewCodeforcesHtml(any());
        verify(statements,times(3)).refresh("key",true);verify(catalogue,times(3)).list(1,null,null,null);verify(catalogue,times(3)).requestSync();verify(statements,times(3)).request("key");verify(statements,times(3)).adminView("key");verify(statements,times(3)).save(any(),eq(false));verify(statements,times(3)).save(any(),eq(true));
    }
    @Test void cposImportRequiresAnAuthenticatedProblemAdministrator() {
        CodeforcesCposImportManager importer = mock(CodeforcesCposImportManager.class);
        AuthorizationAttributeSourceAdvisor advisor = new AuthorizationAttributeSourceAdvisor();
        advisor.setSecurityManager(security);
        ProxyFactory factory = new ProxyFactory(new CodeforcesCposImportController(importer));
        factory.setProxyTargetClass(true);
        factory.addAdvisor(advisor);
        CodeforcesCposImportController cpos = (CodeforcesCposImportController) factory.getProxy();
        Capture capture = new Capture();

        assertThrows(UnauthenticatedException.class, () -> cpos.importStatement(capture));
        login("default_user");
        assertThrows(UnauthorizedException.class, () -> cpos.importStatement(capture));
        verifyNoInteractions(importer);
        when(importer.importStatement(capture)).thenReturn(true);
        for (String role : new String[]{"admin", "root", "problem_admin"}) {
            login(role);
            assertEquals(Boolean.TRUE, cpos.importStatement(capture).getData());
        }
        verify(importer, times(3)).importStatement(capture);
    }

    @Test void browserCaptureReviewAndSiteSwitchKeepTheirSeparatePermissions() {
        RemoteCaptureManager manager = mock(RemoteCaptureManager.class);
        AuthorizationAttributeSourceAdvisor advisor = new AuthorizationAttributeSourceAdvisor();
        advisor.setSecurityManager(security);
        ProxyFactory factory = new ProxyFactory(new RemoteCaptureController(manager));
        factory.setProxyTargetClass(true);
        factory.addAdvisor(advisor);
        RemoteCaptureController captures = (RemoteCaptureController) factory.getProxy();
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThrows(UnauthenticatedException.class, () -> captures.issueCredential(response));
        assertThrows(UnauthenticatedException.class, captures::queue);
        login("default_user");
        captures.issueCredential(response);
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertThrows(UnauthorizedException.class, captures::queue);
        assertThrows(UnauthorizedException.class, () -> captures.approve(Collections.singletonList("cf:contest:4:A")));
        assertThrows(UnauthorizedException.class, () -> captures.preview("cf:contest:4:A"));
        assertThrows(UnauthorizedException.class, () -> captures.discard("cf:contest:4:A"));
        for (String role : new String[]{"admin", "problem_admin"}) {
            login(role);
            captures.queue();
            captures.approve(Collections.singletonList("cf:contest:4:A"));
            assertThrows(UnauthorizedException.class, () -> captures.policy(true));
        }
        login("root");
        captures.policy(true);
        verify(manager, times(2)).queue();
        verify(manager, times(2)).approveBatch(Collections.singletonList("cf:contest:4:A"));
        verify(manager).setEnabled(true);
    }
}
