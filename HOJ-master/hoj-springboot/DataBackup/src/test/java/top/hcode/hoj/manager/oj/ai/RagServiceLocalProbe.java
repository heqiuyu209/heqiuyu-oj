package top.hcode.hoj.manager.oj.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit -Dtest=RagServiceLocalProbe only, driven by the RAG repository's
 * verify_hoj_bridge.py. That harness owns the isolated real service and data. */
class RagServiceLocalProbe {
    String required(String name) {String value=System.getenv(name);assertNotNull(value,"Set "+name+" through the isolated local harness");return value;}
    KnowledgeDTO.Ingest doc(String id,int version,String title,String content) {
        KnowledgeDTO.Ingest dto=new KnowledgeDTO.Ingest();dto.setDocumentId(id);dto.setVersion(version);dto.setContent(content);
        dto.setTitle(title);dto.setFormat("markdown");dto.setContentHash(RagServiceClient.hash(content));return dto;
    }
    @Test void javaToPythonWithRealLocalEmbeddingsAndPersistentVersionIsolation() throws Exception {
        String url=required("HOJ_RAG_PROBE_URL"), keyFile=required("HOJ_RAG_PROBE_KEY_FILE");
        URI endpoint=new URI(url);assertEquals("http",endpoint.getScheme());assertEquals("127.0.0.1",endpoint.getHost());
        RagServiceClient client=new RagServiceClient();ReflectionTestUtils.setField(client,"json",new ObjectMapper());
        ReflectionTestUtils.setField(client,"baseUrl",url);ReflectionTestUtils.setField(client,"keyFile",keyFile);
        try {
            String a=UUID.randomUUID().toString().replace("-",""),b=UUID.randomUUID().toString().replace("-","");
            KnowledgeDTO.Ingest prefix=doc(a,1,"前缀和讲义","# 前缀和\n\n用前缀和数组求静态区间和。令 S[0]=0，S[i]=S[i-1]+a[i]，区间 [l,r] 的元素之和是 S[r]-S[l-1]。预处理 O(n)，每次查询 O(1)。");
            KnowledgeDTO.Ingest shortest=doc(b,1,"最短路讲义","# Dijkstra 最短路\n\n非负边权图中，用优先队列反复取出距离起点最近的顶点，并松弛相邻边，求从起点到各顶点的最短距离。");
            assertEquals(1,client.ingest(prefix));assertEquals(1,client.ingest(shortest));assertEquals(1,client.ingest(prefix));
            List<KnowledgeDTO.Hit> hits=client.retrieve("怎样用前缀数组快速计算区间元素之和？",Arrays.asList(prefix,shortest),2);
            assertEquals(2,hits.size());assertEquals(a,hits.get(0).getDocumentId());assertEquals("前缀和",hits.get(0).getHeadingPath());
            assertEquals(b,client.retrieve("区间求和",Collections.singletonList(shortest),5).get(0).getDocumentId());
            KnowledgeDTO.Ingest newer=doc(a,2,"前缀和讲义修订","# 二维前缀和\n\n矩形区域之和可以用四个前缀矩形的容斥公式计算。");
            client.ingest(newer);assertEquals(1,client.retrieve("区间求和",Collections.singletonList(prefix),5).get(0).getVersion());
            client.delete(prefix);assertTrue(client.retrieve("区间求和",Collections.singletonList(prefix),5).isEmpty());
            assertEquals(409,assertThrows(ObjectiveException.class,()->client.ingest(prefix)).getStatus());
            assertEquals(2,client.retrieve("矩形区域之和",Collections.singletonList(newer),5).get(0).getVersion());
            assertEquals(b,client.retrieve("最短路",Collections.singletonList(shortest),5).get(0).getDocumentId());
            assertTrue(client.retrieve("q",Collections.emptyList(),5).isEmpty());
        } finally {client.close();}
    }
}
