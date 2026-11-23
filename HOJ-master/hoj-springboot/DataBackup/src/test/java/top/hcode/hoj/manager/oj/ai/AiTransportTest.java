package top.hcode.hoj.manager.oj.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiTransportTest {
    AiEndpointPolicy endpoints=new AiEndpointPolicy();
    @AfterEach void cleanup() { endpoints.close(); }
    @Test void endpointParsingAllowsOnlyPublicHttpsBaseUrls() {
        assertEquals("https://relay.example.com/v1/chat/completions",endpoints.endpoint("https://RELAY.example.com/v1/").toString());
        for(String bad:Arrays.asList("http://relay.example.com/v1","https://127.0.0.1/v1","https://[::1]/v1","https://localhost/v1","https://user:pass@relay.example.com/v1","https://relay.example.com/v1?key=secret","https://relay.example.com/v1#token","https://relay.example.com:6688/v1","https://relay.example.com/v1/chat/completions","https://relay.example.com/%2e%2e/v1","https://relay.example.com/a/../v1"))
            assertThrows(ObjectiveException.class,()->endpoints.endpoint(bad),bad);
    }
    @Test void dnsChecksEveryActualConnectionAddressIncludingMappedAndTransitionRanges() throws Exception {
        for(String bad:Arrays.asList("0.0.0.0","10.0.0.2","127.0.0.1","100.64.0.2","169.254.169.254","172.31.1.1","192.168.1.1","192.0.2.1","198.18.0.1","224.0.0.1","255.255.255.255","::1","fe80::1","fc00::1","::ffff:127.0.0.1","2001:db8::1","2002:7f00:1::"))
            assertFalse(endpoints.isPublic(InetAddress.getByName(bad)),bad);
        assertTrue(endpoints.isPublic(InetAddress.getByName("8.8.8.8")));assertTrue(endpoints.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
        AiEndpointPolicy mixed=new AiEndpointPolicy(){@Override protected InetAddress[] lookup(String host)throws UnknownHostException{return new InetAddress[]{InetAddress.getByName("8.8.8.8"),InetAddress.getByName("127.0.0.1")};}};
        try { assertThrows(UnknownHostException.class,()->mixed.resolve("mixed.example.com")); } finally { mixed.close(); }
    }
    CloseableHttpResponse response(int status,String body) {
        CloseableHttpResponse result=mock(CloseableHttpResponse.class);when(result.getStatusLine()).thenReturn(new BasicStatusLine(new ProtocolVersion("HTTP",1,1),status,"test"));
        when(result.getEntity()).thenReturn(new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8)));return result;
    }
    @Test void upstreamErrorsNeverExposeProviderBodiesOrCredentials() throws Exception {
        AiProviderClient client=new AiProviderClient();ReflectionTestUtils.setField(client,"json",new ObjectMapper());
        try {
            for(int code:new int[]{301,401,403,429,500}) {
                ObjectiveException error=assertThrows(ObjectiveException.class,()->client.reply(response(code,"raw-secret-and-provider-error")));
                assertFalse(error.getMessage().contains("raw-secret"));
            }
            assertEquals("下一步先分析边界。",client.reply(response(200,"{\"choices\":[{\"message\":{\"content\":\"下一步先分析边界。\"}}]}")));
            assertThrows(ObjectiveException.class,()->client.reply(response(200,"{\"choices\":[]}")));
            assertThrows(ObjectiveException.class,()->client.reply(response(200,String.join("",Collections.nCopies(262145,"x")))));
        } finally { client.close(); }
    }
    @Test void randomizedAuthenticatedEncryptionDetectsTampering(@TempDir Path folder) {
        AiSecretStore store=new AiSecretStore();ReflectionTestUtils.setField(store,"directory",folder.toString());
        String first=store.encrypt("a","https://api.example.com/v1","chat","test-key",true), second=store.encrypt("a","https://api.example.com/v1","chat","test-key",false);
        assertNotEquals(first,second);byte[] payload=Base64.getDecoder().decode(first.substring(3));payload[14]^=1;
        assertThrows(ObjectiveException.class,()->store.decrypt("a","https://api.example.com/v1","chat","v1:"+Base64.getEncoder().encodeToString(payload)));
    }
}
