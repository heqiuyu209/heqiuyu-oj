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
import top.hcode.hoj.controller.admin.KnowledgeAdminController;
import top.hcode.hoj.controller.oj.KnowledgeController;
import top.hcode.hoj.controller.admin.KnowledgeVideoAdminController;
import top.hcode.hoj.controller.oj.KnowledgeVideoController;
import top.hcode.hoj.manager.oj.ai.KnowledgeVideoImports;
import top.hcode.hoj.manager.oj.ai.KnowledgeVideoSources;
import top.hcode.hoj.shiro.KnowledgeVideoSession;
import top.hcode.hoj.manager.oj.ai.KnowledgeManager;
import top.hcode.hoj.manager.oj.ai.KnowledgePdfManager;
import top.hcode.hoj.manager.oj.ai.AiAssistantManager;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeAuthorizationTest {
    KnowledgeVideoAdminController videoAdmin;KnowledgeVideoController videoUser;KnowledgeVideoImports imports;KnowledgeVideoSources videos;KnowledgeVideoSession sessions;
    DefaultSecurityManager security;KnowledgeManager manager;KnowledgePdfManager pdf;AiAssistantManager assistant;KnowledgeAdminController admin;KnowledgeController user;
    @BeforeEach void setup(){
        security=new DefaultSecurityManager(new AuthorizingRealm(){
            @Override protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token){return new SimpleAuthenticationInfo(token.getPrincipal(),"test",getName());}
            @Override protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection p){String actor=p.getPrimaryPrincipal().toString();SimpleAuthorizationInfo info=new SimpleAuthorizationInfo();if(!"restricted".equals(actor))info.addStringPermission("submit");info.addRole(actor);return info;}
        });ThreadContext.bind(security);manager=mock(KnowledgeManager.class);assistant=mock(AiAssistantManager.class);
        pdf=mock(KnowledgePdfManager.class);
        KnowledgeAdminController target=new KnowledgeAdminController();ReflectionTestUtils.setField(target,"manager",manager);ReflectionTestUtils.setField(target,"pdf",pdf);admin=proxy(target);
        KnowledgeController users=new KnowledgeController();ReflectionTestUtils.setField(users,"manager",manager);ReflectionTestUtils.setField(users,"assistant",assistant);ReflectionTestUtils.setField(users,"pdf",pdf);user=proxy(users);
        imports=mock(KnowledgeVideoImports.class);videos=mock(KnowledgeVideoSources.class);sessions=mock(KnowledgeVideoSession.class);
        KnowledgeVideoAdminController videoTarget=new KnowledgeVideoAdminController();ReflectionTestUtils.setField(videoTarget,"imports",imports);ReflectionTestUtils.setField(videoTarget,"sources",videos);ReflectionTestUtils.setField(videoTarget,"sessions",sessions);videoAdmin=proxy(videoTarget);
        KnowledgeVideoController videoUsers=new KnowledgeVideoController();ReflectionTestUtils.setField(videoUsers,"sources",videos);ReflectionTestUtils.setField(videoUsers,"sessions",sessions);videoUser=proxy(videoUsers);
    }
    @SuppressWarnings("unchecked") <T>T proxy(T target){AuthorizationAttributeSourceAdvisor advisor=new AuthorizationAttributeSourceAdvisor();advisor.setSecurityManager(security);ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvisor(advisor);return (T)factory.getProxy();}
    void login(String actor){Subject subject=new Subject.Builder(security).buildSubject();subject.login(new UsernamePasswordToken(actor,"test"));ThreadContext.bind(subject);}
    @AfterEach void cleanup(){ThreadContext.remove();security.destroy();}
    @Test void anonymousCannotSearchReadAskOrMaintain(){
        assertThrows(UnauthenticatedException.class,()->user.directory(1,null));
        assertThrows(UnauthenticatedException.class,()->user.search(null));assertThrows(UnauthenticatedException.class,()->user.answer(null));assertThrows(UnauthenticatedException.class,()->user.read("a",1));assertThrows(UnauthenticatedException.class,()->admin.list(1,null));verifyNoInteractions(manager,assistant);
    }
    @Test void ordinaryTeamAndProblemAdministratorsCannotMaintainKnowledge(){
        for(String actor:new String[]{"user","team_admin","problem_admin"}){login(actor);assertThrows(UnauthorizedException.class,()->admin.create(null));assertThrows(UnauthorizedException.class,()->admin.detail("a"));assertThrows(UnauthorizedException.class,()->admin.version("a",1));assertThrows(UnauthorizedException.class,()->admin.upload("a",null));assertThrows(UnauthorizedException.class,()->admin.publish("a",null));assertThrows(UnauthorizedException.class,()->admin.visibility("a",null));assertThrows(UnauthorizedException.class,()->admin.unpublish("a",null));assertThrows(UnauthorizedException.class,()->admin.delete("a",null));assertThrows(UnauthorizedException.class,()->admin.retry("a",1,null));}
        verifyNoInteractions(manager);
    }
    @Test void rootAndAdministratorReachMaintenanceManager(){for(String actor:new String[]{"root","admin"}){login(actor);admin.list(1,null);admin.create(null);admin.detail("a");admin.version("a",1);admin.upload("a",null);admin.publish("a",null);admin.unpublish("a",null);admin.visibility("a",null);admin.delete("a",null);admin.retry("a",1,null);}verify(manager,times(2)).create(null);verify(manager,times(2)).remove("a",null);}
    @Test void restrictedCannotReadOrSendAndOrdinaryUserCanReachOnlyUserManager(){
        login("restricted");assertThrows(UnauthorizedException.class,()->user.directory(1,null));assertThrows(UnauthorizedException.class,()->user.search(null));assertThrows(UnauthorizedException.class,()->user.answer(null));assertThrows(UnauthorizedException.class,()->user.read("a",1));verifyNoInteractions(manager,assistant);
        login("user");user.directory(1,null);user.search(null);user.answer(null);user.read("a",1);verify(manager).directory(1,null);verify(manager).search(null);verify(manager).read("a",1);verify(assistant).answerKnowledge(null);
    }
    @Test void pdfRoutesEnforceAuthenticationRolesAndSubmitPermission() throws Exception {
        HttpServletRequest request=mock(HttpServletRequest.class);HttpServletResponse response=mock(HttpServletResponse.class);
        assertThrows(UnauthenticatedException.class,()->admin.createPdf("t","ADMIN","n","t.pdf",false,request));
        assertThrows(UnauthenticatedException.class,()->admin.uploadPdf("a","t","ADMIN","n","t.pdf",1L,false,request));
        assertThrows(UnauthenticatedException.class,()->admin.original("a",1,response));
        assertThrows(UnauthenticatedException.class,()->user.original("a",1,response));
        for(String actor:new String[]{"user","team_admin","problem_admin","restricted"}){
            login(actor);assertThrows(UnauthorizedException.class,()->admin.createPdf("t","ADMIN","n","t.pdf",false,request));
            assertThrows(UnauthorizedException.class,()->admin.uploadPdf("a","t","ADMIN","n","t.pdf",1L,false,request));
            assertThrows(UnauthorizedException.class,()->admin.original("a",1,response));
        }
        assertThrows(UnauthorizedException.class,()->user.original("a",1,response));verifyNoInteractions(pdf,request);
        login("user");user.original("a",1,response);verify(pdf).userOriginal(eq("a"),eq(1),any());
        for(String actor:new String[]{"root","admin"}){
            login(actor);admin.createPdf("t","ADMIN","n","t.pdf",false,request);admin.uploadPdf("a","t","ADMIN","n","t.pdf",1L,false,request);admin.original("a",1,response);
        }
        verify(pdf,times(2)).upload(isNull(),any(),eq("t.pdf"),isNull(),eq(0L));
        verify(pdf,times(2)).upload(eq("a"),any(),eq("t.pdf"),isNull(),eq(0L));verify(pdf,times(2)).adminOriginal(eq("a"),eq(1),any());
    }

    void rejectVideoMaintenance(Class<? extends Exception> expected) {
        HttpServletRequest request=mock(HttpServletRequest.class);HttpServletResponse response=mock(HttpServletResponse.class);
        assertThrows(expected,()->videoAdmin.list());assertThrows(expected,()->videoAdmin.detail("a"));
        assertThrows(expected,()->videoAdmin.upload("t","ADMIN","n","a.mp4","auto",null,null,request));
        assertThrows(expected,()->videoAdmin.save("a",null));assertThrows(expected,()->videoAdmin.retry("a",null));assertThrows(expected,()->videoAdmin.cancel("a",null));
        assertThrows(expected,()->videoAdmin.previewAccess("a",request,response));assertThrows(expected,()->videoAdmin.clearPreview("a",request,response));assertThrows(expected,()->videoAdmin.preview("a",request,response));
        assertThrows(expected,()->videoAdmin.originalAccess("a",1,request,response));assertThrows(expected,()->videoAdmin.clearOriginal("a",1,request,response));assertThrows(expected,()->videoAdmin.original("a",1,request,response));
    }

    @Test void allVideoMaintenanceMethodsUseActualShiroAdvisorAndRejectNonAdministrators() {
        rejectVideoMaintenance(UnauthenticatedException.class);
        for(String actor:new String[]{"user","team_admin","problem_admin","restricted"}){login(actor);rejectVideoMaintenance(UnauthorizedException.class);}
        verifyNoInteractions(imports,videos,sessions);
    }

    @Test void allVideoUserMethodsRequireAuthenticationAndSubmitPermission() throws Exception {
        HttpServletRequest request=mock(HttpServletRequest.class);HttpServletResponse response=mock(HttpServletResponse.class);
        assertThrows(UnauthenticatedException.class,()->videoUser.access("a",1,request,response));
        assertThrows(UnauthenticatedException.class,()->videoUser.clear("a",request,response));assertThrows(UnauthenticatedException.class,()->videoUser.original("a",1,request,response));
        login("restricted");assertThrows(UnauthorizedException.class,()->videoUser.access("a",1,request,response));
        assertThrows(UnauthorizedException.class,()->videoUser.clear("a",request,response));assertThrows(UnauthorizedException.class,()->videoUser.original("a",1,request,response));
        verifyNoInteractions(videos,sessions);
        when(request.getMethod()).thenReturn("GET");login("user");videoUser.original("a",1,request,response);
        verify(sessions).requireAuthenticated(request);verify(videos).userOriginal(eq("a"),eq(1),any());
    }

    @Test void rootAndAdminReachVideoMaintenanceWithoutOpeningPublicAccess() throws Exception {
        HttpServletRequest request=mock(HttpServletRequest.class);HttpServletResponse response=mock(HttpServletResponse.class);when(request.getMethod()).thenReturn("GET");
        when(sessions.grant(any(),any(),anyString(),any())).thenAnswer(call->{java.util.Map<String,Object> result=new java.util.LinkedHashMap<>();result.put("url",call.getArgument(2));return result;});
        for(String actor:new String[]{"root","admin"}){
            login(actor);videoAdmin.list();videoAdmin.detail("a");videoAdmin.upload("t","ADMIN","n","a.mp4","auto",null,null,request);
            videoAdmin.save("a",null);videoAdmin.retry("a",null);videoAdmin.cancel("a",null);
            videoAdmin.previewAccess("a",request,response);videoAdmin.clearPreview("a",request,response);videoAdmin.preview("a",request,response);
            videoAdmin.originalAccess("a",1,request,response);videoAdmin.clearOriginal("a",1,request,response);videoAdmin.original("a",1,request,response);
        }
        verify(imports,times(2)).list();verify(imports,times(2)).save("a",null);verify(videos,times(2)).adminOriginal(eq("a"),eq(1),any());
    }
}
