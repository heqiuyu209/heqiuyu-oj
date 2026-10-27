package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.manager.oj.ai.AiAssistantManager;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;
import top.hcode.hoj.pojo.dto.RemoteTranslationDTO;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteTranslationManagerTest {
    ObjectMapper json=new ObjectMapper();RemoteTranslationManager manager;RemoteStatementManager statements;AiAssistantManager assistant;
    String hash=String.join("",Collections.nCopies(64,"a"));Map<String,Object> detail,version;Content original;
    @BeforeEach void setup(){
        manager=new RemoteTranslationManager();statements=mock(RemoteStatementManager.class);assistant=mock(AiAssistantManager.class);
        ReflectionTestUtils.setField(manager,"json",json);ReflectionTestUtils.setField(manager,"statements",statements);ReflectionTestUtils.setField(manager,"assistant",assistant);
        original=new Content();original.setTitle("Given task");original.setDescription("<p>Given <var>n</var> values.</p>");original.setInput("");original.setOutput("");original.setHint("");original.setExamples("<input>1 2</input><output>3</output>");original.setTimeLimit(1000);original.setMemoryLimit(256);
        version=new LinkedHashMap<>();version.put("contentHash",hash);version.put("content",json.valueToTree(original));detail=new LinkedHashMap<>();detail.put("source",version);detail.put("sourceLanguage","en");detail.put("translationLanguage","zh-CN");
        when(statements.translationSource(anyString())).thenAnswer(call->detail);when(statements.normalize(any(),anyString())).thenAnswer(call->call.getArgument(0));
        when(assistant.generateTranslation(anyList(),eq(hash),any())).thenAnswer(call->{((Runnable)call.getArgument(2)).run();return generated((List<Map<String,String>>)call.getArgument(0));});
    }
    Map<String,Object> generated(List<Map<String,String>> messages) throws Exception {
        assertEquals("system",messages.get(0).get("role"));assertEquals("user",messages.get(1).get("role"));
        ObjectNode reply=(ObjectNode)json.readTree(messages.get(1).get("content"));reply.put("title",reply.path("title").asText().replace("Given","给定"));
        Map<String,Object> result=new LinkedHashMap<>();result.put("content",reply.toString());result.put("model","controlled-model");return result;
    }
    RemoteTranslationDTO dto(String key){RemoteTranslationDTO dto=new RemoteTranslationDTO();dto.setProblemKey(key);dto.setSourceHash(hash);dto.setModelFingerprint(hash);dto.setAllowModelSource(true);return dto;}
    @Test void allThreePlatformsReturnDraftsInTheSourceDirectionWithoutPublishing(){
        for(String key:Arrays.asList("cf:contest:4:A","atcoder:task:abc086_a","nowcoder:acm:218144")){
            boolean nc=key.startsWith("nowcoder:");detail.put("sourceLanguage",nc?"zh-CN":"en");detail.put("translationLanguage",nc?"en":"zh-CN");
            Map<String,Object> result=manager.generate(dto(key));assertEquals(key,result.get("problemKey"));assertEquals(nc?"en":"zh-CN",result.get("translationLanguage"));assertEquals(true,result.get("draft"));assertEquals(hash,result.get("sourceHash"));assertEquals("controlled-model",result.get("model"));
            Content content=(Content)result.get("content");assertEquals(original.getExamples(),content.getExamples());assertEquals(original.getTimeLimit(),content.getTimeLimit());
        }
        verify(statements,never()).save(any(),anyBoolean());
    }
    @Test void consentAndSourceIdentityMustBeValidBeforeAnyModelCall(){
        RemoteTranslationDTO dto=dto("cf:contest:4:A");dto.setAllowModelSource(false);assertThrows(ObjectiveException.class,()->manager.generate(dto));verifyNoInteractions(statements,assistant);
        dto.setAllowModelSource(true);dto.setSourceHash("bad");assertThrows(ObjectiveException.class,()->manager.generate(dto));verifyNoInteractions(statements,assistant);
        dto.setSourceHash(hash);version.put("contentHash",String.join("",Collections.nCopies(64,"b")));assertThrows(ObjectiveException.class,()->manager.generate(dto));verifyNoInteractions(assistant);
    }
    @Test void sourceOrMaintenancePermissionsChangingDuringGenerationDiscardTheDraft(){
        when(assistant.generateTranslation(anyList(),anyString(),any())).thenAnswer(call->{Map<String,Object> reply=generated((List<Map<String,String>>)call.getArgument(0));when(statements.translationSource(anyString())).thenThrow(new ObjectiveException(403,"revoked"));return reply;});
        assertThrows(ObjectiveException.class,()->manager.generate(dto("cf:contest:4:A")));verify(statements,never()).save(any(),anyBoolean());
    }
    @Test void malformedModelReplyDoesNotCreateAPartialTranslation(){
        when(assistant.generateTranslation(anyList(),anyString(),any())).thenReturn(Collections.singletonMap("content","{\"title\":\"truncated"));
        assertThrows(ObjectiveException.class,()->manager.generate(dto("cf:contest:4:A")));verify(statements,never()).save(any(),anyBoolean());
    }
}
