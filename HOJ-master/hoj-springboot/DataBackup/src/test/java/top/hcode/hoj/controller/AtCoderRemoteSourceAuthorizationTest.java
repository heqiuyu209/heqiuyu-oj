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
import top.hcode.hoj.controller.admin.AtCoderRemoteSourceController;
import top.hcode.hoj.manager.oj.external.*;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AtCoderRemoteSourceAuthorizationTest {
    DefaultSecurityManager security;AtCoderRemoteSourceController controller;AtCoderCatalogManager catalogue;RemoteStatementManager statements;
    @BeforeEach void setup(){
        security=new DefaultSecurityManager(new AuthorizingRealm(){
            @Override protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken t){return new SimpleAuthenticationInfo(t.getPrincipal(),"test",getName());}
            @Override protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection p){SimpleAuthorizationInfo info=new SimpleAuthorizationInfo();info.addRole(p.getPrimaryPrincipal().toString());return info;}
        });ThreadContext.bind(security);catalogue=mock(AtCoderCatalogManager.class);statements=mock(RemoteStatementManager.class);
        AtCoderRemoteSourceController target=new AtCoderRemoteSourceController();ReflectionTestUtils.setField(target,"catalogue",catalogue);ReflectionTestUtils.setField(target,"statements",statements);
        AuthorizationAttributeSourceAdvisor advisor=new AuthorizationAttributeSourceAdvisor();advisor.setSecurityManager(security);ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvisor(advisor);controller=(AtCoderRemoteSourceController)factory.getProxy();
    }
    @AfterEach void close(){ThreadContext.remove();security.destroy();}
    void login(String role){Subject subject=new Subject.Builder(security).buildSubject();subject.login(new UsernamePasswordToken(role,"test"));ThreadContext.bind(subject);}
    @Test void anonymousAndOrdinaryUsersCannotUseAnySourceOperation(){
        assertThrows(UnauthenticatedException.class,()->controller.list(1,null,null,null));assertThrows(UnauthenticatedException.class,controller::sync);assertThrows(UnauthenticatedException.class,()->controller.request(new RemoteStatementDTO()));assertThrows(UnauthenticatedException.class,()->controller.statement("atcoder:task:abc086_a"));assertThrows(UnauthenticatedException.class,()->controller.english(new RemoteStatementDTO()));assertThrows(UnauthenticatedException.class,()->controller.chinese(new RemoteStatementDTO()));
        assertThrows(UnauthenticatedException.class,()->controller.refresh(new RemoteStatementDTO()));login("default_user");assertThrows(UnauthorizedException.class,()->controller.list(1,null,null,null));assertThrows(UnauthorizedException.class,controller::sync);assertThrows(UnauthorizedException.class,()->controller.request(new RemoteStatementDTO()));assertThrows(UnauthorizedException.class,()->controller.statement("atcoder:task:abc086_a"));assertThrows(UnauthorizedException.class,()->controller.english(new RemoteStatementDTO()));assertThrows(UnauthorizedException.class,()->controller.chinese(new RemoteStatementDTO()));assertThrows(UnauthorizedException.class,()->controller.refresh(new RemoteStatementDTO()));verifyNoInteractions(catalogue,statements);
    }
    @Test void allExistingProblemAdministrationRolesCanUseSourceOperations(){
        for(String role:new String[]{"admin","root","problem_admin"}){
            login(role);RemoteStatementDTO dto=new RemoteStatementDTO();dto.setProblemKey("atcoder:task:abc086_a");controller.list(1,null,null,null);controller.sync();controller.request(dto);controller.statement("atcoder:task:abc086_a");controller.english(dto);controller.chinese(dto);dto.setAutoRefresh(true);controller.refresh(dto);
        }
        verify(statements,times(3)).refresh("atcoder:task:abc086_a",true);verify(catalogue,times(3)).list(1,null,null,null);verify(catalogue,times(3)).requestSync();verify(statements,times(3)).request("atcoder:task:abc086_a");verify(statements,times(3)).adminView("atcoder:task:abc086_a");verify(statements,times(3)).save(any(),eq(false));verify(statements,times(3)).save(any(),eq(true));
    }
}
