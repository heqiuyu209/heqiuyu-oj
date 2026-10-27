package top.hcode.hoj.manager.oj.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;
import top.hcode.hoj.pojo.dto.KnowledgeVideoDTO;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class RagServiceClientTest {
    @TempDir Path folder;
    RagServiceClient client; ObjectMapper json = new ObjectMapper(); HttpServer server; ExecutorService executor;
    String key = String.join("", Collections.nCopies(32, "42"));
    String doc = String.join("", Collections.nCopies(32, "a"));
    String response; int status; AtomicInteger requests; JsonNode received; byte[] receivedBytes; String contentType, receivedPath; Map<String,List<String>> headers;
    final long clock = 1800000000000L;

    @BeforeEach void setup() throws Exception {
        response="{\"items\":[]}";status=200;requests=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/",exchange->{
            requests.incrementAndGet();byte[] body=org.springframework.util.StreamUtils.copyToByteArray(exchange.getRequestBody());
            receivedBytes=body;contentType=exchange.getRequestHeaders().getFirst("Content-Type");
            receivedPath=exchange.getRequestURI().getPath();
            received=contentType.startsWith("application/pdf")?null:json.readTree(body);headers=new HashMap<>(exchange.getRequestHeaders());
            String id=exchange.getRequestHeaders().getFirst("X-HOJ-Request-Id"), deadline=exchange.getRequestHeaders().getFirst("X-HOJ-Deadline");
            byte[] secret=new byte[32];Arrays.fill(secret,(byte)0x42);
            String signed=RagServiceClient.signature(secret,exchange.getRequestURI().getPath(),id,deadline,body);
            if (!signed.equals(exchange.getRequestHeaders().getFirst("X-HOJ-Signature"))) {exchange.sendResponseHeaders(401,-1);exchange.close();return;}
            if(status==302) exchange.getResponseHeaders().set("Location","/leaked");
            byte[] out=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,out.length);
            exchange.getResponseBody().write(out);exchange.close();
        });server.start();
        Path keyFile=folder.resolve("service.key");Files.write(keyFile,key.getBytes(StandardCharsets.US_ASCII));
        client=new RagServiceClient(){@Override protected long now(){return clock;}};ReflectionTestUtils.setField(client,"json",json);
        ReflectionTestUtils.setField(client,"keyFile",keyFile.toString());
        ReflectionTestUtils.setField(client,"baseUrl","http://localhost:"+server.getAddress().getPort());
    }
    @AfterEach void cleanup() {client.close();server.stop(0);executor.shutdownNow();}
    KnowledgeDTO.Ingest document() {
        KnowledgeDTO.Ingest dto=new KnowledgeDTO.Ingest();dto.setDocumentId(doc);dto.setVersion(1);dto.setTitle("区间求和");dto.setFormat("markdown");
        dto.setContent("# 前缀和\n\n先计算前缀数组。");dto.setContentHash(RagServiceClient.hash(dto.getContent()));return dto;
    }
    List<KnowledgeDTO.VersionRef> refs() {return Collections.singletonList(document());}
    ObjectNode hit() {
        KnowledgeDTO.Ingest d=document();ObjectNode hit=json.createObjectNode();hit.put("documentId",doc);hit.put("version",1);hit.put("contentHash",d.getContentHash());
        hit.put("chunkId",String.join("",Collections.nCopies(64,"b")));hit.put("chunkHash",RagServiceClient.hash(d.getContent()));
        hit.put("chunkIndex",0);hit.put("excerpt",d.getContent());hit.put("title",d.getTitle());hit.put("headingPath","");hit.put("score",0.85);return hit;
    }
    void hits(ObjectNode... hits) {ObjectNode result=json.createObjectNode();for(ObjectNode h:hits)result.withArray("items").add(h);response=result.toString();}
    void failure(Runnable action,int status) {assertEquals(status,assertThrows(ObjectiveException.class,action::run).getStatus());}

    @Test void wireSignatureMatchesPythonGoldenVector() {
        byte[] secret=new byte[32];Arrays.fill(secret,(byte)0x42);
        assertEquals("e8288ab217a8082df19a0803f909cbdcfa29c4016982a71f97a61b4e057ccc14",RagServiceClient.signature(secret,
                "/internal/v1/retrieve",doc,"1792380000000","{\"query\":\"前缀和\",\"allowedVersions\":[]}".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void validRetrievalSendsOnlyExplicitScopeAndServiceAuthentication() {
        hits(hit());List<KnowledgeDTO.Hit> result=client.retrieve("如何求区间和",refs(),5);
        assertEquals(1,result.size());assertEquals(doc,result.get(0).getDocumentId());assertEquals(document().getContentHash(),received.path("allowedVersions").path(0).path("contentHash").asText());
        assertEquals(3,received.size());assertEquals(3,received.path("allowedVersions").path(0).size());assertTrue(headers.keySet().stream().noneMatch(h->"cookie".equalsIgnoreCase(h)||"authorization".equalsIgnoreCase(h)));
        assertFalse(received.toString().contains("apiKey"));assertEquals(1,requests.get());
    }
    @Test void emptyScopeDoesNotNeedConfiguredServiceOrPerformNetworkRequest() {
        ReflectionTestUtils.setField(client,"baseUrl","");assertFalse(client.configured());
        assertTrue(client.retrieve("前缀和",Collections.emptyList(),5).isEmpty());assertEquals(0,requests.get());
    }
    @Test void invalidAndDuplicateScopeFailBeforeTransport() {
        KnowledgeDTO.VersionRef invalid=document();invalid.setDocumentId("../../other");
        failure(()->client.retrieve("q",Collections.singletonList(invalid),5),400);
        failure(()->client.retrieve("q",Arrays.asList(document(),document()),5),400);
        failure(()->client.retrieve(" ",refs(),5),400);failure(()->client.retrieve("q",refs(),11),400);assertEquals(0,requests.get());
    }
    @Test void receiptsMustMatchExactVersionAndSuccessfulState() {
        KnowledgeDTO.Ingest d=document();ObjectNode receipt=json.createObjectNode();receipt.put("documentId",doc);receipt.put("version",1);receipt.put("contentHash",d.getContentHash());receipt.put("state","INDEXED");receipt.put("chunks",2);
        response=receipt.toString();assertEquals(2,client.ingest(d));receipt.put("version",2);response=receipt.toString();failure(()->client.ingest(d),502);
        receipt.put("version",4294967297L);response=receipt.toString();failure(()->client.ingest(d),502);
        receipt.put("version",1);receipt.put("state","WRITING");response=receipt.toString();failure(()->client.ingest(d),502);
    }
    @Test void invalidIngestHashOrFormatIsNeverSent() {
        KnowledgeDTO.Ingest d=document();d.setContent("changed");failure(()->client.ingest(d),400);
        KnowledgeDTO.Ingest bad=document();bad.setFormat("html");failure(()->client.ingest(bad),400);assertEquals(0,requests.get());
    }
    @Test void wholeBookPdfUsesBoundedLargerUtf8BodyAndScopedDeadline(){
        KnowledgeDTO.Ingest value=document();value.setFormat("pdf");value.setContent(String.join("",Collections.nCopies(270001,"🙂")));value.setContentHash(RagServiceClient.hash(value.getContent()));
        ObjectNode receipt=json.createObjectNode();receipt.put("documentId",doc);receipt.put("version",1);receipt.put("contentHash",value.getContentHash());receipt.put("state","INDEXED");receipt.put("chunks",310);response=receipt.toString();
        assertEquals(310,client.ingest(value));assertTrue(receivedBytes.length>1048576);
        assertEquals(clock + KnowledgeLimits.PDF_INGEST_TIMEOUT_MS, Long.parseLong(headers.get("X-hoj-deadline").get(0)));
        value.setFormat("markdown");failure(()->client.ingest(value),400);assertEquals(1,requests.get());
    }
    @Test void nativeMupdfReceiptSupportsLargeUnicodeAndRetainsOcrSeparation(){
        byte[] binary=new byte[9*1024*1024];System.arraycopy(pdf(),0,binary,0,pdf().length);
        ObjectNode receipt=pdfReceipt();receipt.put("sourceHash",RagServiceClient.hash(binary));receipt.put("parser","pymupdf-text-v1");receipt.put("content",String.join("",Collections.nCopies(270001,"🙂")));receipt.put("pages",1);receipt.putArray("emptyPages");response=receipt.toString();
        String extracted=client.extractPdf(binary).getContent();assertEquals(270001,extracted.codePointCount(0,extracted.length()));
        failure(()->client.extractPdfOcr(binary),502);
        receipt.put("parser","unknown");response=receipt.toString();failure(()->client.extractPdf(binary),502);
    }
    @Test void permanentContentRejectionIsNotReportedAsAnUnavailableService(){
        status=400;response="{\"code\":\"EMPTY_OR_TOO_MANY_CHUNKS\"}";failure(()->client.ingest(document()),400);assertEquals(1,requests.get());
    }
    @Test void rejectCrossScopeCorruptExcerptsAndMalformedCitationFields() {
        List<Consumer<ObjectNode>> mutations=Arrays.asList(h->h.put("documentId",String.join("",Collections.nCopies(32,"f"))),
                h->h.put("version",2),h->h.put("contentHash",String.join("",Collections.nCopies(64,"0"))),h->h.put("excerpt","tampered"),
                h->h.put("chunkIndex",-1),h->h.remove("chunkIndex"),h->h.put("score","0.8"),h->h.put("version",4294967297L),h->h.put("title",true),h->h.remove("headingPath"));
        for(Consumer<ObjectNode> mutation:mutations){ObjectNode bad=hit();mutation.accept(bad);hits(bad);failure(()->client.retrieve("q",refs(),5),502);}
        hits(hit(),hit());failure(()->client.retrieve("q",refs(),5),502);
    }
    @Test void rejectMoreHitsThanRequestedAndOversizedResponse() {
        ObjectNode second=hit();second.put("chunkId",String.join("",Collections.nCopies(64,"c")));hits(hit(),second);
        failure(()->client.retrieve("q",refs(),1),502);response=String.join("",Collections.nCopies(262145,"x"));failure(()->client.retrieve("q",refs(),5),502);
    }
    @Test void doesNotFollowRedirectsRetryOrReflectInternalErrors() {
        status=302;response="private-source-content";failure(()->client.retrieve("q",refs(),5),503);assertEquals(1,requests.get());
        status=409;failure(()->client.retrieve("q",refs(),5),409);assertEquals(2,requests.get());
        status=503;ObjectiveException error=assertThrows(ObjectiveException.class,()->client.retrieve("q",refs(),5));
        assertFalse(error.getMessage().contains("private-source"));assertEquals(3,requests.get());
    }
    @Test void deleteReceiptAndOperatorConfigurationAreValidated() {
        response="{\"documentId\":\""+doc+"\",\"version\":1,\"state\":\"DELETED\"}";client.delete(document());
        assertEquals(3,received.size());assertFalse(received.has("content"));
        response="{}";failure(()->client.delete(document()),502);
        ReflectionTestUtils.setField(client,"baseUrl","http://localhost:"+server.getAddress().getPort()+"/?redirect=other");failure(()->client.retrieve("q",refs(),5),503);
        assertEquals(2,requests.get());
    }
    @Test void unicodeLengthMatchesPythonCodePointsForCitations() {
        ObjectNode item=hit();String excerpt=String.join("",Collections.nCopies(600,"😀"));
        item.put("excerpt",excerpt);item.put("chunkHash",RagServiceClient.hash(excerpt));hits(item);
        assertEquals(excerpt,client.retrieve("q",refs(),5).get(0).getExcerpt());
    }
    byte[] pdf() { return "%PDF-synthetic-binary\u0000source".getBytes(StandardCharsets.UTF_8); }
    ObjectNode pdfReceipt() {
        ObjectNode value=json.createObjectNode();value.put("sourceHash",RagServiceClient.hash(pdf()));value.put("pages",3);
        value.put("content","第一页\n\f\n\n\f\n# 原始文字，不是标题");value.withArray("emptyPages").add(2);value.put("parser","pypdf-6.21.1");return value;
    }
    @Test void pdfUsesSignedRawBytesAndPreservesPhysicalPages() {
        response=pdfReceipt().toString();KnowledgeDTO.PdfText text=client.extractPdf(pdf());
        assertArrayEquals(pdf(),receivedBytes);assertEquals("application/pdf",contentType);assertEquals(3,text.getPages());
        assertEquals(Collections.singletonList(2),text.getEmptyPages());assertEquals(1,requests.get());
        assertTrue(headers.keySet().stream().noneMatch(h->"cookie".equalsIgnoreCase(h)||"authorization".equalsIgnoreCase(h)));
    }
    @Test void pdfRejectsTamperedHashAndPageMetadata() {
        List<Consumer<ObjectNode>> mutations=Arrays.asList(v->v.put("sourceHash",RagServiceClient.hash("other")),v->v.put("pages",2),
                v->v.put("pages",4294967299L),v->v.put("content","text\fraw"),v->v.put("content"," \n\f\n "),
                v->v.withArray("emptyPages").removeAll(),v->v.putArray("emptyPages").add(true),v->v.put("parser","unknown"),v->v.put("extra","x"));
        for(Consumer<ObjectNode> mutation:mutations){ObjectNode value=pdfReceipt();mutation.accept(value);response=value.toString();failure(()->client.extractPdf(pdf()),502);}
    }
    @Test void pdfRejectsInvalidBodiesBeforeNetworkAndMapsSafeParserErrors() {
        failure(()->client.extractPdf(new byte[8388609]),400);failure(()->client.extractPdf("not pdf".getBytes(StandardCharsets.UTF_8)),400);assertEquals(0,requests.get());
        status=400;response="{\"code\":\"PDF_ENCRYPTED\",\"detail\":\"private file name\"}";
        ObjectiveException error=assertThrows(ObjectiveException.class,()->client.extractPdf(pdf()));assertEquals(400,error.getStatus());assertTrue(error.getMessage().contains("加密"));assertFalse(error.getMessage().contains("private"));
        response=String.join("",Collections.nCopies(4097,"x"));failure(()->client.extractPdf(pdf()),502);
        status=302;response="";failure(()->client.extractPdf(pdf()),503);assertEquals(3,requests.get());
    }
    @Test void ocrUsesDistinctSignedPathAndAcceptsOnlyItsBoundReceipt(){
        ObjectNode receipt=pdfReceipt();receipt.put("parser","pdfium-tesseract-chi_sim-eng-v1");response=receipt.toString();
        assertEquals(3,client.extractPdfOcr(pdf()).getPages());assertEquals("/internal/v1/extract-pdf-ocr",receivedPath);
        assertArrayEquals(pdf(),receivedBytes);failure(()->client.extractPdf(pdf()),502);
        response=pdfReceipt().toString();failure(()->client.extractPdfOcr(pdf()),502);
        status=400;response="{\"code\":\"PDF_OCR_FAILED\",\"detail\":\"private\"}";
        ObjectiveException error=assertThrows(ObjectiveException.class,()->client.extractPdfOcr(pdf()));assertEquals(400,error.getStatus());assertTrue(error.getMessage().contains("识别失败"));assertFalse(error.getMessage().contains("private"));
        status=503;response="{\"code\":\"PDF_OCR_UNAVAILABLE\"}";failure(()->client.extractPdfOcr(pdf()),503);
    }
    KnowledgeVideoDTO.Asset videoAsset(){KnowledgeVideoDTO.Asset value=new KnowledgeVideoDTO.Asset();value.setAssetId(doc);value.setSourceHash(String.join("",Collections.nCopies(64,"b")));value.setByteLength(1024L);return value;}
    ObjectNode videoProbe(){KnowledgeVideoDTO.Asset asset=videoAsset();ObjectNode value=json.createObjectNode();value.put("assetId",asset.getAssetId());value.put("sourceHash",asset.getSourceHash());value.put("byteLength",1024);value.put("durationMs",125000);value.put("mime","video/mp4");value.put("width",640);value.put("height",480);value.put("videoCodec","h264");value.put("audioCodec","aac");value.put("audioIndex",1);return value;}
    KnowledgeVideoDTO.Slice videoSlice(){KnowledgeVideoDTO.Slice value=new KnowledgeVideoDTO.Slice();value.setAssetId(doc);value.setSourceHash(videoAsset().getSourceHash());value.setByteLength(1024L);value.setStartMs(60000L);value.setEndMs(120000L);value.setLanguage("zh");value.setExpectedModelHash(String.join("",Collections.nCopies(64,"c")));return value;}
    ObjectNode videoSliceResult(){ObjectNode value=videoProbe();for(String field:Arrays.asList("mime","width","height","videoCodec","audioCodec","audioIndex"))value.remove(field);value.put("startMs",60000);value.put("endMs",120000);value.put("language","zh");value.put("engine","faster-whisper-cpu-int8-v1");value.put("modelHash",videoSlice().getExpectedModelHash());value.putArray("segments").addObject().put("startMs",60500).put("endMs",62000).put("text","前缀和用于区间求和。");return value;}
    @Test void videoTransportUsesOnlyOpaqueSourceIdentityAndStrictMatchingClockModelReceipts(){
        response=videoProbe().toString();assertEquals(125000,client.probeVideo(videoAsset()).getDurationMs());assertEquals("/internal/v1/probe-video",receivedPath);assertEquals(3,received.size());
        response=videoSliceResult().toString();KnowledgeVideoDTO.Transcribed result=client.transcribeVideo(videoSlice());assertEquals(60500,result.getSegments().get(0).getStartMs());assertEquals("/internal/v1/transcribe-video",receivedPath);assertFalse(received.has("filePath"));assertEquals(videoSlice().getExpectedModelHash(),received.path("expectedModelHash").asText());
        ObjectNode silence=videoSliceResult();silence.putArray("segments");silence.put("language","und");response=silence.toString();assertTrue(client.transcribeVideo(videoSlice()).getSegments().isEmpty());
    }
    @Test void badVideoIdentitiesAndUnboundedWorkNeverReachTheTransport(){
        KnowledgeVideoDTO.Asset asset=videoAsset();asset.setAssetId("../private");failure(()->client.probeVideo(asset),400);
        KnowledgeVideoDTO.Slice slice=videoSlice();slice.setEndMs(120001L);failure(()->client.transcribeVideo(slice),400);slice.setEndMs(120000L);slice.setExpectedModelHash("unknown");failure(()->client.transcribeVideo(slice),400);assertEquals(0,requests.get());
    }
    @Test void corruptedDuplicateTrailingAndCrossSliceVideoRepliesAreRejected(){
        List<Consumer<ObjectNode>> mutations=Arrays.asList(v->v.put("startMs",0),v->v.put("durationMs",true),v->v.put("modelHash",String.join("",Collections.nCopies(64,"d"))),v->v.put("assetId",String.join("",Collections.nCopies(32,"f"))),v->v.put("extra","x"),v->((ObjectNode)v.path("segments").get(0)).put("endMs",125000),v->((ObjectNode)v.path("segments").get(0)).put("text","bad\0text"));
        for(Consumer<ObjectNode> mutation:mutations){ObjectNode value=videoSliceResult();mutation.accept(value);response=value.toString();failure(()->client.transcribeVideo(videoSlice()),502);}
        response=videoSliceResult().toString()+"{}";failure(()->client.transcribeVideo(videoSlice()),502);
        response=videoSliceResult().toString().replace("\"startMs\":60000","\"startMs\":60000,\"startMs\":60000");failure(()->client.transcribeVideo(videoSlice()),502);
        status=409;response="{\"code\":\"VIDEO_MODEL_CHANGED\",\"path\":\"private-file\"}";ObjectiveException error=assertThrows(ObjectiveException.class,()->client.transcribeVideo(videoSlice()));assertEquals(409,error.getStatus());assertTrue(error.getMessage().contains("模型"));assertFalse(error.getMessage().contains("private"));
    }
}
