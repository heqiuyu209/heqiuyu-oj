package top.hcode.hoj.manager.oj.ai;

import org.junit.jupiter.api.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgePdfManagerTest {
    JdbcTemplate jdbc; KnowledgeManager manager; KnowledgePdfManager pdf; KnowledgeJobManager jobs; RagServiceClient index; AiAccessGuard access;
    boolean administrator=true; int nonce;
    byte[] original="%PDF-test-original\u0000bytes".getBytes(StandardCharsets.UTF_8);
    String content="第一页的前缀和算法。\n\f\n\n\f\n# 字面文字\n第三页的区间和公式。";
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:pdf_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");jdbc.update("INSERT INTO user_info VALUES('alice',0)");
        String schema=StreamUtils.copyToString(new ClassPathResource("knowledge-base.sql").getInputStream(),StandardCharsets.UTF_8).replace(" COLLATE=utf8mb4_bin","");
        try(Connection c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)));}
        index=mock(RagServiceClient.class);access=mock(AiAccessGuard.class);when(index.configured()).thenReturn(true);when(index.ingest(any())).thenReturn(2);when(index.extractPdf(any())).thenAnswer(call->parsed());
        manager=new KnowledgeManager(){@Override protected String uid(){return "alice";}@Override protected boolean maintainer(){return administrator;}};
        jobs=new KnowledgeJobManager();
        for(Object bean:Arrays.asList(manager,jobs)){ReflectionTestUtils.setField(bean,"jdbc",jdbc);ReflectionTestUtils.setField(bean,"transactions",new DataSourceTransactionManager(source));ReflectionTestUtils.setField(bean,"index",index);}
        ReflectionTestUtils.setField(manager,"access",access);
        pdf=new KnowledgePdfManager();ReflectionTestUtils.setField(pdf,"knowledge",manager);ReflectionTestUtils.setField(pdf,"parser",index);ReflectionTestUtils.setField(pdf,"jdbc",jdbc);
    }
    @AfterEach void cleanup(){jobs.close();}
    KnowledgeDTO.PdfText parsed(){KnowledgeDTO.PdfText value=new KnowledgeDTO.PdfText();value.setContent(content);value.setPages(3);value.setEmptyPages(Collections.singletonList(2));return value;}
    KnowledgeDTO.Upload metadata(){KnowledgeDTO.Upload dto=new KnowledgeDTO.Upload();dto.setTitle("PDF 算法讲义");dto.setVisibility("PUBLIC");dto.setRequestId("pdf_nonce_"+(++nonce));return dto;}
    Map<String,Object> send(String id,KnowledgeDTO.Upload dto,byte[] bytes){return pdf.upload(id,dto,"算法.pdf",new ByteArrayInputStream(bytes),bytes.length);}
    String create(){return (String)send(null,metadata(),original).get("id");}
    long revision(String id){return ((Number)manager.detail(id).get("revision")).longValue();}
    KnowledgeDTO.Change change(String id,int version){KnowledgeDTO.Change dto=new KnowledgeDTO.Change();dto.setVersion(version);dto.setExpectedRevision(revision(id));return dto;}
    void publish(String id){assertTrue(jobs.processOne());manager.publish(id,change(id,1));}
    void fail(Runnable action,int code){assertEquals(code,assertThrows(ObjectiveException.class,action::run).getStatus());}

    @Test void originalAndExtractionAreImmutableVersionDataWithoutImplicitPublication(){
        String id=create();assertNull(manager.detail(id).get("publishedVersion"));
        assertArrayEquals(original,jdbc.queryForObject("SELECT source_data FROM knowledge_source",byte[].class));
        Map<String,Object> version=manager.adminVersion(id,1);assertEquals(content,version.get("content"));assertEquals("pdf",version.get("format"));
        Map<?,?> source=(Map<?,?>)version.get("source");assertEquals(Collections.singletonList(2),source.get("emptyPages"));assertFalse(source.containsKey("source_data"));
        publish(id);administrator=false;assertEquals(content,manager.read(id,1).get("content"));
    }
    @Test void wholeBookSourcesAndTextExceedOldLimitsButRetainBoundaries(){
        byte[] large=new byte[9*1024*1024];System.arraycopy(original,0,large,0,original.length);
        content=String.join("",Collections.nCopies(210001,"文"));
        KnowledgeDTO.PdfText text=new KnowledgeDTO.PdfText();text.setContent(content);text.setPages(1);text.setEmptyPages(Collections.emptyList());
        when(index.extractPdf(any())).thenReturn(text);
        String id=(String)send(null,metadata(),large).get("id");
        assertEquals(content,manager.adminVersion(id,1).get("content"));assertArrayEquals(large,jdbc.queryForObject("SELECT source_data FROM knowledge_source WHERE document_id=?",byte[].class,id));
        KnowledgeDTO.Upload plain=metadata();plain.setFormat("markdown");plain.setContent(content);fail(()->manager.create(plain),400);
        fail(()->pdf.upload(null,metadata(),"large.pdf",new ByteArrayInputStream(original),KnowledgeLimits.PDF_BYTES+1L),413);
        text.setContent(String.join("",Collections.nCopies(300001,"文")));fail(()->send(null,metadata(),original),400);
    }
    @Test void nonceReplayReturnsReceiptWithoutParsingEvenWhenParserIsUnavailable(){
        KnowledgeDTO.Upload first=metadata();String id=(String)send(null,first,original).get("id");
        when(index.extractPdf(any())).thenThrow(new ObjectiveException(503,"parser offline"));assertEquals(id,send(null,first,original).get("id"));
        fail(()->send(null,first,"%PDF-changed".getBytes(StandardCharsets.UTF_8)),409);verify(index,times(1)).extractPdf(any());
        KnowledgeDTO.Upload next=metadata();next.setExpectedRevision(revision(id));reset(index);when(index.extractPdf(any())).thenReturn(parsed());
        send(id,next,original);when(index.extractPdf(any())).thenThrow(new ObjectiveException(503,"offline"));
        assertEquals(1,((Number)send(id,next,original).get("requestedVersion")).intValue());verify(index,times(1)).extractPdf(any());
    }
    @Test void metadataEditUsesServerOriginalAndIgnoresForgedBrowserText(){
        String id=create();KnowledgeDTO.Upload edit=metadata();edit.setTitle("修订标题");edit.setFormat("pdf");edit.setSourceVersion(1);edit.setContent("forged text");edit.setExpectedRevision(revision(id));
        pdf.reuse(id,edit);assertEquals(content,manager.adminVersion(id,2).get("content"));
        assertArrayEquals(original,jdbc.queryForObject("SELECT source_data FROM knowledge_source WHERE version=2",byte[].class));
        assertEquals(content,manager.adminVersion(id,1).get("content"));verify(index,times(1)).extractPdf(any());
        KnowledgeDTO.Upload forged=metadata();forged.setFormat("pdf");forged.setContent(content);fail(()->manager.create(forged),400);
    }
    @Test void sourceAndVersionRollbackTogetherIfQueueInsertionFails(){
        jdbc.execute("ALTER TABLE knowledge_job ADD CONSTRAINT reject_pdf_job CHECK(operation<>'INGEST')");
        assertThrows(RuntimeException.class,()->create());
        for(String table:Arrays.asList("knowledge_document","knowledge_version","knowledge_source","knowledge_command","knowledge_job"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
    }
    @Test void authorizationAndSizeChecksRunBeforeParserAndFailureReleasesUploadSlot(){
        administrator=false;fail(()->create(),403);administrator=true;
        fail(()->pdf.upload(null,metadata(),"../bad.pdf",new ByteArrayInputStream(original),original.length),400);
        fail(()->pdf.upload(null,metadata(),"file.pdf",new ByteArrayInputStream(original),KnowledgeLimits.PDF_BYTES+1L),413);
        fail(()->send(null,metadata(),new byte[KnowledgeLimits.PDF_BYTES+1]),413);verify(index,never()).extractPdf(any());
        when(index.extractPdf(any())).thenThrow(new ObjectiveException(400,"broken pdf"));fail(()->create(),400);
        doReturn(parsed()).when(index).extractPdf(any());assertNotNull(create());
    }
    @Test void originalDownloadChecksCurrentPublicationAndReleasesCapacityAfterWriterFailure() throws Exception {
        String id=create();AtomicInteger writes=new AtomicInteger();KnowledgePdfManager.PdfWriter writer=(name,bytes)->{assertEquals("算法.pdf",name);assertArrayEquals(original,bytes);writes.incrementAndGet();};
        assertThrows(ObjectiveException.class,()->pdf.userOriginal(id,1,writer));pdf.adminOriginal(id,1,writer);publish(id);
        administrator=false;pdf.userOriginal(id,1,writer);
        for(int i=0;i<5;i++)assertThrows(IOException.class,()->pdf.userOriginal(id,1,(n,b)->{throw new IOException("disconnected");}));
        pdf.userOriginal(id,1,writer);administrator=true;manager.unpublish(id,change(id,1));
        assertThrows(ObjectiveException.class,()->pdf.userOriginal(id,1,writer));assertEquals(3,writes.get());
    }
    @Test void corruptOriginalOrPageMetadataCannotBeDownloadedOrReused() throws Exception {
        String id=create();jdbc.update("UPDATE knowledge_source SET source_data=?", "%PDF-tampered".getBytes(StandardCharsets.UTF_8));
        assertEquals(503,assertThrows(ObjectiveException.class,()->pdf.adminOriginal(id,1,(n,b)->Assertions.fail("must not write"))).getStatus());
        jdbc.update("UPDATE knowledge_source SET source_data=?,empty_pages='3,2'",original);
        fail(()->manager.adminVersion(id,1),503);
        KnowledgeDTO.Upload edit=metadata();edit.setSourceVersion(1);edit.setExpectedRevision(revision(id));fail(()->pdf.reuse(id,edit),503);
    }
    private KnowledgeDTO.Hit pageHit(String id, int page, String excerpt) {
        KnowledgeDTO.Hit hit = new KnowledgeDTO.Hit();
        hit.setDocumentId(id);
        hit.setVersion(1);
        hit.setContentHash(RagServiceClient.hash(content));
        hit.setTitle("PDF 算法讲义");
        hit.setExcerpt(excerpt);
        hit.setChunkHash(RagServiceClient.hash(excerpt));
        hit.setChunkId(String.format("%064x", page));
        hit.setChunkIndex(page - 1);
        hit.setHeadingPath("第 " + page + " 页");
        hit.setScore(.9);
        return hit;
    }

    @Test void multiplePdfCitationsReadTheSourceOncePerVerification() {
        String id = create();
        publish(id);
        administrator = false;
        KnowledgeDTO.Hit third = pageHit(id, 3, "第三页的区间和公式。");
        KnowledgeDTO.Hit first = pageHit(id, 1, "第一页的前缀和算法。");
        when(index.retrieve(anyString(), anyList(), anyInt())).thenReturn(Arrays.asList(third, first));
        AtomicInteger fullTextReads = new AtomicInteger();
        JdbcTemplate countingJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.contains("AS version_state") && sql.contains("v.content,")) {
                    fullTextReads.incrementAndGet();
                }
                return super.queryForList(sql, args);
            }
        };
        ReflectionTestUtils.setField(manager, "jdbc", countingJdbc);
        KnowledgeDTO.Search query = new KnowledgeDTO.Search();
        query.setQuery("前缀和与区间和");
        KnowledgeManager.Context context = manager.retrieve("alice", query);
        assertEquals(1, fullTextReads.get(), "One authoritative text read for two citations in a pass");
        List<?> items = (List<?>) manager.view(context).get("items");
        assertEquals(3, ((Map<?, ?>) items.get(0)).get("page"));
        assertEquals(1, ((Map<?, ?>) items.get(1)).get("page"));
        assertEquals(2, fullTextReads.get());

        first.setHeadingPath("第 3 页");
        fail(() -> manager.verify(context), 502);
        first.setHeadingPath("第 1 页");
        jdbc.update("UPDATE knowledge_document SET visibility='ADMIN',revision=revision+1 WHERE id=?", id);
        fail(() -> manager.verify(context), 409);
    }

    @Test void citationsDerivePhysicalPageAndRejectExcerptFromAnotherPage(){
        String id=create();publish(id);administrator=false;
        KnowledgeDTO.Hit hit=pageHit(id,3,"第三页的区间和公式。");hit.setPage(99);
        when(index.retrieve(anyString(),anyList(),anyInt())).thenReturn(Collections.singletonList(hit));KnowledgeDTO.Search query=new KnowledgeDTO.Search();query.setQuery("区间和");
        Map<?,?> item=(Map<?,?>)((List<?>)manager.search(query).get("items")).get(0);assertEquals(3,item.get("page"));assertTrue(item.get("url").toString().endsWith("&page=3"));
        hit.setHeadingPath("第 1 页");fail(()->manager.search(query),502);
        hit.setHeadingPath("# 字面文字");fail(()->manager.search(query),502);
    }
    @Test void ocrIsExplicitVersionedAndIdempotentWithoutReprocessingOnMetadataEdits(){
        KnowledgeDTO.Upload first=metadata();first.setOcr(true);when(index.extractPdfOcr(any())).thenReturn(parsed());
        String id=(String)send(null,first,original).get("id");verify(index,never()).extractPdf(any());
        assertEquals("OCR",((Map<?,?>)manager.adminVersion(id,1).get("source")).get("extractionMode"));
        assertNull(manager.detail(id).get("publishedVersion"));assertArrayEquals(original,jdbc.queryForObject("SELECT source_data FROM knowledge_source",byte[].class));
        when(index.extractPdfOcr(any())).thenThrow(new ObjectiveException(503,"OCR offline"));
        assertEquals(id,send(null,first,original).get("id"));verify(index,times(1)).extractPdfOcr(any());
        first.setOcr(false);fail(()->send(null,first,original),409);
        KnowledgeDTO.Upload edit=metadata();edit.setTitle("OCR 修订标题");edit.setSourceVersion(1);edit.setOcr(false);edit.setContent("forged OCR");edit.setExpectedRevision(revision(id));
        pdf.reuse(id,edit);assertEquals(content,manager.adminVersion(id,2).get("content"));
        assertEquals("OCR",((Map<?,?>)manager.adminVersion(id,2).get("source")).get("extractionMode"));
        verify(index,times(1)).extractPdfOcr(any());
        KnowledgeDTO.Upload nativeAgain=metadata();nativeAgain.setTitle(edit.getTitle());nativeAgain.setExpectedRevision(revision(id));
        assertEquals(3,((Number)send(id,nativeAgain,original).get("requestedVersion")).intValue());
        assertEquals("NATIVE",((Map<?,?>)manager.adminVersion(id,3).get("source")).get("extractionMode"));
    }
    @Test void failedOcrLeavesPublishedSourceAndAllVersionPointersUntouched(){
        String id=create();publish(id);Map<String,Object> before=manager.detail(id);
        KnowledgeDTO.Upload next=metadata();next.setOcr(true);next.setExpectedRevision(revision(id));
        when(index.extractPdfOcr(any())).thenThrow(new ObjectiveException(400,"OCR resource limit"));fail(()->send(id,next,original),400);
        Map<String,Object> after=manager.detail(id);for(String key:Arrays.asList("revision","latestVersion","publishedVersion"))assertEquals(before.get(key),after.get(key));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_source",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_version",Integer.class));
    }
    @Test void nativePdfCorrectionsPreserveOriginalPagesPublicationAndReplayIdentity() throws Exception {
        String id=create();publish(id);
        String corrected=content.replace("第一页的前缀和算法。","第一页的前缀和公式：S[r]-S[l-1]。\nif(x % i == 0)");
        KnowledgeDTO.Upload edit=metadata();edit.setSourceVersion(1);edit.setReviewedPdf(true);edit.setContent(corrected.replace("\n","\r\n"));edit.setExpectedRevision(revision(id));
        Map<String,Object> saved=pdf.reuse(id,edit);
        assertEquals(2,((Number)saved.get("requestedVersion")).intValue());assertEquals(1,((Number)saved.get("publishedVersion")).intValue());
        assertEquals(content,manager.adminVersion(id,1).get("content"));assertEquals(corrected,manager.adminVersion(id,2).get("content"));
        Map<?,?> source=(Map<?,?>)manager.adminVersion(id,2).get("source");
        assertEquals("NATIVE_REVIEWED",source.get("extractionMode"));assertEquals(3,((Number)source.get("pages")).intValue());assertEquals(Collections.singletonList(2),source.get("emptyPages"));
        pdf.adminOriginal(id,2,(name,bytes)->assertArrayEquals(original,bytes));
        assertEquals(2,((Number)pdf.reuse(id,edit).get("requestedVersion")).intValue());
        edit.setContent(corrected+"changed");fail(()->pdf.reuse(id,edit),409);
        administrator=false;assertEquals(content,manager.read(id,1).get("content"));fail(()->manager.read(id,2),404);administrator=true;
        assertTrue(jobs.processOne());manager.publish(id,change(id,2));administrator=false;
        assertEquals(corrected,manager.read(id,2).get("content"));fail(()->manager.read(id,1),404);
        pdf.userOriginal(id,2,(name,bytes)->assertArrayEquals(original,bytes));verify(index,times(1)).extractPdf(any());
    }
    @Test void metadataOnlyReviewedNativeReuseIgnoresBrowserTextWithoutMutatingCorrectionIntent(){
        String id=create();KnowledgeDTO.Upload correction=metadata();correction.setSourceVersion(1);correction.setReviewedPdf(true);correction.setContent(content+"\n校订");correction.setExpectedRevision(revision(id));pdf.reuse(id,correction);
        KnowledgeDTO.Upload title=metadata();title.setSourceVersion(2);title.setTitle("校订后标题");title.setContent("untrusted browser text");title.setExpectedRevision(revision(id));
        assertEquals(3,((Number)pdf.reuse(id,title).get("requestedVersion")).intValue());
        assertFalse(title.isReviewedPdf());assertFalse(title.isReviewedOcr());assertFalse(title.isOcr());
        assertEquals(3,((Number)pdf.reuse(id,title).get("requestedVersion")).intValue());
        assertEquals(correction.getContent(),manager.adminVersion(id,3).get("content"));
        assertEquals("NATIVE_REVIEWED",((Map<?,?>)manager.adminVersion(id,3).get("source")).get("extractionMode"));
        verify(index,times(1)).extractPdf(any());verify(index,never()).extractPdfOcr(any());
    }
    @Test void neutralAndLegacyOcrCorrectionsShareTheSameReceipt(){
        when(index.extractPdfOcr(any())).thenReturn(parsed());KnowledgeDTO.Upload first=metadata();first.setOcr(true);String id=(String)send(null,first,original).get("id");
        KnowledgeDTO.Upload edit=metadata();edit.setSourceVersion(1);edit.setReviewedPdf(true);edit.setContent(content+"\n校订");edit.setExpectedRevision(revision(id));
        assertEquals(2,((Number)pdf.reuse(id,edit).get("requestedVersion")).intValue());
        edit.setReviewedPdf(false);edit.setReviewedOcr(true);assertEquals(2,((Number)pdf.reuse(id,edit).get("requestedVersion")).intValue());
        assertEquals("OCR_REVIEWED",((Map<?,?>)manager.adminVersion(id,2).get("source")).get("extractionMode"));
        KnowledgeDTO.Upload title=metadata();title.setSourceVersion(2);title.setTitle("OCR 校订后标题");title.setContent("ignored");title.setExpectedRevision(revision(id));
        assertEquals(3,((Number)pdf.reuse(id,title).get("requestedVersion")).intValue());
        assertEquals(3,((Number)pdf.reuse(id,title).get("requestedVersion")).intValue());assertFalse(title.isReviewedOcr());assertFalse(title.isReviewedPdf());
        verify(index,times(1)).extractPdfOcr(any());
    }
    @Test void legacyNativeOcrAndReviewedOcrReceiptHashesRemainCompatible(){
        String id=create();assertEquals("0349fa76e3363f192483268b66405c8976c19e2529e3c6e917eb5d06dbd06dc5",jdbc.queryForObject("SELECT creation_hash FROM knowledge_document WHERE id=?",String.class,id));
        when(index.extractPdfOcr(any())).thenReturn(parsed());KnowledgeDTO.Upload first=metadata();first.setOcr(true);String ocrId=(String)send(null,first,original).get("id");
        assertEquals("79524a81b6611bb50a3e99f26dfde4fff98268f746ad82c59bf5d7e55ee1b47a",jdbc.queryForObject("SELECT creation_hash FROM knowledge_document WHERE id=?",String.class,ocrId));
        KnowledgeDTO.Upload edit=metadata();edit.setSourceVersion(1);edit.setReviewedPdf(true);edit.setContent(content);edit.setExpectedRevision(revision(ocrId));pdf.reuse(ocrId,edit);
        assertEquals("e3d1b3fa1c3ef4f4572a2776e244e27668f896eb0caae4ddf60416219ee646cf",jdbc.queryForObject("SELECT input_hash FROM knowledge_command WHERE document_id=? AND request_id=?",String.class,ocrId,edit.getRequestId()));
    }
    @Test void rawParserUploadsCannotAcquireAdministratorReviewedLabels(){
        KnowledgeDTO.Upload nativeUpload=metadata();nativeUpload.setReviewedPdf(true);nativeUpload.setReviewedOcr(true);
        String id=(String)send(null,nativeUpload,original).get("id");
        assertEquals("NATIVE",((Map<?,?>)manager.adminVersion(id,1).get("source")).get("extractionMode"));
        when(index.extractPdfOcr(any())).thenReturn(parsed());KnowledgeDTO.Upload ocr=metadata();ocr.setOcr(true);ocr.setReviewedPdf(true);ocr.setReviewedOcr(true);
        String ocrId=(String)send(null,ocr,original).get("id");assertEquals("OCR",((Map<?,?>)manager.adminVersion(ocrId,1).get("source")).get("extractionMode"));
    }
    @Test void nativeCorrectionValidationFailureDoesNotChangeVersionOrPublication(){
        String id=create();publish(id);long before=revision(id);
        for(String invalid:Arrays.asList("", "lost page boundaries", content+"\f", content+"\0", content+"\uD800", String.join("",Collections.nCopies(300001,"文")))){
            KnowledgeDTO.Upload edit=metadata();edit.setSourceVersion(1);edit.setReviewedPdf(true);edit.setContent(invalid);edit.setExpectedRevision(before);fail(()->pdf.reuse(id,edit),400);
        }
        KnowledgeDTO.Upload missing=metadata();missing.setSourceVersion(1);missing.setReviewedPdf(true);missing.setExpectedRevision(before);fail(()->pdf.reuse(id,missing),400);
        assertEquals(before,revision(id));assertEquals(1,((Number)manager.detail(id).get("latestVersion")).intValue());assertEquals(1,((Number)manager.detail(id).get("publishedVersion")).intValue());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_source",Integer.class));
    }
    @Test void nativeCorrectionRecomputesBlankPagesAndRequiresAdministrator(){
        String id=create();KnowledgeDTO.Upload edit=metadata();edit.setSourceVersion(1);edit.setReviewedPdf(true);edit.setContent(content.replace("\n\f\n\n\f\n","\n\f\n补全原空白页\n\f\n"));edit.setExpectedRevision(revision(id));
        administrator=false;fail(()->pdf.reuse(id,edit),403);administrator=true;pdf.reuse(id,edit);
        assertEquals(Collections.emptyList(),((Map<?,?>)manager.adminVersion(id,2).get("source")).get("emptyPages"));
    }
    @Test void explicitOcrCorrectionsCreatePagePreservingVersionsAndKeepOldPublication(){
        when(index.extractPdfOcr(any())).thenReturn(parsed());KnowledgeDTO.Upload first=metadata();first.setOcr(true);
        String id=(String)send(null,first,original).get("id");publish(id);
        String corrected=content.replace("第一页的前缀和算法。","第一页的前缀和算法，管理员校订。");
        KnowledgeDTO.Upload edit=metadata();edit.setSourceVersion(1);edit.setReviewedOcr(true);edit.setContent(corrected);edit.setExpectedRevision(revision(id));
        assertEquals(2,((Number)pdf.reuse(id,edit).get("requestedVersion")).intValue());
        assertEquals(content,manager.adminVersion(id,1).get("content"));assertEquals(corrected,manager.adminVersion(id,2).get("content"));
        assertEquals(1,((Number)manager.detail(id).get("publishedVersion")).intValue());
        Map<?,?> source=(Map<?,?>)manager.adminVersion(id,2).get("source");assertEquals("OCR_REVIEWED",source.get("extractionMode"));assertEquals(Collections.singletonList(2),source.get("emptyPages"));
        assertArrayEquals(original,jdbc.queryForObject("SELECT source_data FROM knowledge_source WHERE document_id=? AND version=2",byte[].class,id));
        assertEquals(2,((Number)pdf.reuse(id,edit).get("requestedVersion")).intValue());edit.setContent(content);fail(()->pdf.reuse(id,edit),409);
        KnowledgeDTO.Upload title=metadata();title.setTitle("校订后改标题");title.setSourceVersion(2);title.setContent("untrusted");title.setExpectedRevision(revision(id));pdf.reuse(id,title);
        assertEquals(corrected,manager.adminVersion(id,3).get("content"));assertEquals("OCR_REVIEWED",((Map<?,?>)manager.adminVersion(id,3).get("source")).get("extractionMode"));
        KnowledgeDTO.Upload bad=metadata();bad.setSourceVersion(1);bad.setReviewedOcr(true);bad.setContent("lost page boundaries");bad.setExpectedRevision(revision(id));fail(()->pdf.reuse(id,bad),400);
        verify(index,times(1)).extractPdfOcr(any());
        String nativeId=create();KnowledgeDTO.Upload forged=metadata();forged.setSourceVersion(1);forged.setReviewedOcr(true);forged.setContent(corrected);forged.setExpectedRevision(revision(nativeId));fail(()->pdf.reuse(nativeId,forged),400);
    }
}
