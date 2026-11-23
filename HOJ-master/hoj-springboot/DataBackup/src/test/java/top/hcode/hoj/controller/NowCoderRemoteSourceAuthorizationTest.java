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
import top.hcode.hoj.controller.admin.NowCoderRemoteSourceController;
import top.hcode.hoj.manager.oj.external.*;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NowCoderRemoteSourceAuthorizationTest {
    DefaultSecurityManager security;NowCoderRemoteSourceController controller;NowCoderCatalogManager catalogue;RemoteStatementManager statements;NowCoderSourceManager discovery;
    @BeforeEach void setup(){
        security=new DefaultSecurityManager(new AuthorizingRealm(){
            @Override protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken t){return new SimpleAuthenticationInfo(t.getPrincipal(),"test",getName());}
            @Override protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection p){SimpleAuthorizationInfo info=new SimpleAuthorizationInfo();info.addRole(p.getPrimaryPrincipal().toString());return info;}
        });ThreadContext.bind(security);catalogue=mock(NowCoderCatalogManager.class);statements=mock(RemoteStatementManager.class);discovery=mock(NowCoderSourceManager.class);
        NowCoderRemoteSourceController target=new NowCoderRemoteSourceController();ReflectionTestUtils.setField(target,"catalogue",catalogue);ReflectionTestUtils.setField(target,"statements",statements);ReflectionTestUtils.setField(target,"discovery",discovery);
        AuthorizationAttributeSourceAdvisor advisor=new AuthorizationAttributeSourceAdvisor();advisor.setSecurityManager(security);ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvisor(advisor);controller=(NowCoderRemoteSourceController)factory.getProxy();
    }
    @AfterEach void close(){ThreadContext.remove();security.destroy();}
    void login(String role){Subject subject=new Subject.Builder(security).buildSubject();subject.login(new UsernamePasswordToken(role,"test"));ThreadContext.bind(subject);}
    @Test void anonymousAndOrdinaryUsersCannotUseAnySourceOperation(){
        assertThrows(UnauthenticatedException.class,()->controller.list(1,null,null,null));assertThrows(UnauthenticatedException.class,controller::sync);assertThrows(UnauthenticatedException.class,()->controller.request(new RemoteStatementDTO()));assertThrows(UnauthenticatedException.class,()->controller.statement("nowcoder:acm:6"));assertThrows(UnauthenticatedException.class,()->controller.english(new RemoteStatementDTO()));assertThrows(UnauthenticatedException.class,()->controller.chinese(new RemoteStatementDTO()));
        assertThrows(UnauthenticatedException.class,()->controller.refresh(new RemoteStatementDTO()));assertThrows(UnauthenticatedException.class,()->controller.lookup(new RemoteStatementDTO()));login("default_user");assertThrows(UnauthorizedException.class,()->controller.list(1,null,null,null));assertThrows(UnauthorizedException.class,controller::sync);assertThrows(UnauthorizedException.class,()->controller.request(new RemoteStatementDTO()));assertThrows(UnauthorizedException.class,()->controller.statement("nowcoder:acm:6"));assertThrows(UnauthorizedException.class,()->controller.english(new RemoteStatementDTO()));assertThrows(UnauthorizedException.class,()->controller.chinese(new RemoteStatementDTO()));assertThrows(UnauthorizedException.class,()->controller.refresh(new RemoteStatementDTO()));assertThrows(UnauthorizedException.class,()->controller.lookup(new RemoteStatementDTO()));verifyNoInteractions(catalogue,statements);
    }
    @Test void allExistingProblemAdministrationRolesCanUseSourceOperations(){
        for(String role:new String[]{"admin","root","problem_admin"}){
            login(role);RemoteStatementDTO dto=new RemoteStatementDTO();dto.setProblemKey("nowcoder:acm:6");controller.list(1,null,null,null);controller.sync();controller.request(dto);controller.statement("nowcoder:acm:6");controller.english(dto);controller.chinese(dto);dto.setAutoRefresh(true);controller.refresh(dto);dto.setSourceId("NC6");controller.lookup(dto);
        }
        verify(discovery,times(3)).lookup("NC6");verify(statements,times(3)).refresh("nowcoder:acm:6",true);verify(catalogue,times(3)).list(1,null,null,null);verify(catalogue,times(3)).requestSync();verify(statements,times(3)).request("nowcoder:acm:6");verify(statements,times(3)).adminView("nowcoder:acm:6");verify(statements,times(3)).save(any(),eq(false));verify(statements,times(3)).save(any(),eq(true));
    }

    @Test void chineseIsTheSourceAndEnglishIsTheReviewedTranslation(){login("admin");RemoteStatementDTO chinese=new RemoteStatementDTO(),english=new RemoteStatementDTO();chinese.setProblemKey("nowcoder:acm:6");english.setProblemKey("nowcoder:acm:6");controller.chinese(chinese);controller.english(english);verify(statements).save(same(chinese),eq(false));verify(statements).save(same(english),eq(true));}
}
