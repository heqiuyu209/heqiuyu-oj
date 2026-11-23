package top.hcode.hoj.manager.oj.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;
import top.hcode.hoj.pojo.dto.KnowledgeVideoDTO;

import javax.annotation.PreDestroy;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Fixed operator-configured internal service. No browser cookie or provider key is sent. */
@Component
public class RagServiceClient {
    @Autowired private ObjectMapper json;
    @Value("${hoj.rag.url:}") private String baseUrl = "";
    @Value("${hoj.rag.shared-key-file:}") private String keyFile = "";
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "rag-request-deadline"); thread.setDaemon(true); return thread;
    });
    @PreDestroy public void close() { deadlines.shutdownNow(); }

    public boolean configured() { return baseUrl != null && !baseUrl.trim().isEmpty() && keyFile != null && !keyFile.trim().isEmpty(); }

    protected URI endpoint(String operation) {
        if (!configured()) throw new ObjectiveException(503, "知识库检索服务尚未配置");
        try {
            URI base = new URI(baseUrl.trim());
            if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                    || !(base.getPath().isEmpty() || "/".equals(base.getPath()))) throw new IllegalArgumentException();
            return new URI(base.getScheme(), null, base.getHost(), base.getPort(), "/internal/v1/" + operation, null, null);
        } catch (Exception e) { throw new ObjectiveException(503, "知识库内部服务配置无效"); }
    }

    private byte[] key() {
        try {
            Path path = Paths.get(keyFile);
            if (Files.size(path) > 128) throw new IllegalArgumentException();
            String value = new String(Files.readAllBytes(path), StandardCharsets.US_ASCII).trim();
            if (!value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
            byte[] bytes = new byte[32];
            for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
            return bytes;
        } catch (Exception e) { throw new ObjectiveException(503, "知识库内部服务凭据不可用"); }
    }

    public static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    public static String hash(byte[] bytes) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    protected static String signature(byte[] key, String path, String requestId, String deadline, byte[] body) {
        try {
            String material = String.join("\n", "v1", "POST", path, requestId, deadline, hash(body));
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return hex(mac.doFinal(material.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void ref(KnowledgeDTO.VersionRef ref) {
        if (ref == null || ref.getDocumentId() == null || !ref.getDocumentId().matches("[a-f0-9]{32}")
                || ref.getVersion() == null || ref.getVersion() < 1 || ref.getContentHash() == null || !ref.getContentHash().matches("[a-f0-9]{64}"))
            throw new ObjectiveException(400, "知识库资料版本无效");
    }
    private String identity(KnowledgeDTO.VersionRef ref) { return ref.getDocumentId() + ":" + ref.getVersion() + ":" + ref.getContentHash(); }
    private Map<String, Object> referenceBody(KnowledgeDTO.VersionRef ref) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("documentId", ref.getDocumentId());
        result.put("version", ref.getVersion()); result.put("contentHash", ref.getContentHash()); return result;
    }
    private int length(String value) { return value.codePointCount(0, value.length()); }

    public int ingest(KnowledgeDTO.Ingest dto) {
        ref(dto);
        if (dto.getContent() == null || dto.getContent().trim().isEmpty() || length(dto.getContent()) > KnowledgeLimits.characters(dto.getFormat()) || dto.getContent().indexOf('\0') >= 0
                || !hash(dto.getContent()).equals(dto.getContentHash()) || dto.getTitle() == null || dto.getTitle().trim().isEmpty()
                || length(dto.getTitle()) > 200 || dto.getTitle().indexOf('\0') >= 0
                || !("markdown".equals(dto.getFormat()) || "text".equals(dto.getFormat()) || "pdf".equals(dto.getFormat()) || "video".equals(dto.getFormat())))
            throw new ObjectiveException(400, "知识库入库内容无效");
        if("video".equals(dto.getFormat()))try{VideoTranscript.parse(dto.getContent());}catch(IllegalArgumentException error){throw new ObjectiveException(400,"视频时间轴无效");}
        JsonNode result = post("ingest", dto, "pdf".equals(dto.getFormat()) ? KnowledgeLimits.PDF_INGEST_TIMEOUT_MS : 115000);
        if (!sameVersion(result, dto) || !"INDEXED".equals(result.path("state").asText()) || !result.path("chunks").isIntegralNumber() || !result.path("chunks").canConvertToInt()
                || result.path("chunks").asInt() < 1 || result.path("chunks").asInt() > 400) throw invalidResult();
        return result.path("chunks").asInt();
    }

    public KnowledgeDTO.PdfText extractPdf(byte[] bytes) {
        return extractPdf(bytes, false);
    }

    public KnowledgeDTO.PdfText extractPdfOcr(byte[] bytes) {
        return extractPdf(bytes, true);
    }

    private void videoAsset(KnowledgeVideoDTO.Asset asset) {
        if(asset==null||asset.getAssetId()==null||!asset.getAssetId().matches("[a-f0-9]{32}")
                ||asset.getSourceHash()==null||!asset.getSourceHash().matches("[a-f0-9]{64}")||asset.getByteLength()==null
                ||asset.getByteLength()<12||asset.getByteLength()>VideoTranscript.MAX_BYTES)
            throw new ObjectiveException(400,"视频来源标识无效");
    }
    private ObjectiveException invalidVideo() { return new VideoServiceFailure(502,"视频处理回执校验失败，请重试","INVALID_VIDEO_RESULT"); }
    private long videoNumber(JsonNode node,long minimum,long maximum) {
        if(!node.isIntegralNumber()||!node.canConvertToLong()||node.asLong()<minimum||node.asLong()>maximum)throw invalidVideo();
        return node.asLong();
    }
    private void sameVideo(JsonNode result,KnowledgeVideoDTO.Asset asset) {
        if(!result.isObject()||!result.path("assetId").isTextual()||!asset.getAssetId().equals(result.path("assetId").asText())
                ||!result.path("sourceHash").isTextual()||!asset.getSourceHash().equals(result.path("sourceHash").asText())
                ||videoNumber(result.path("byteLength"),12,VideoTranscript.MAX_BYTES)!=asset.getByteLength())throw invalidVideo();
        videoNumber(result.path("durationMs"),1,VideoTranscript.MAX_DURATION);
    }
    public KnowledgeVideoDTO.Probe probeVideo(KnowledgeVideoDTO.Asset asset) {
        videoAsset(asset);JsonNode result=post("probe-video",asset,25000);sameVideo(result,asset);
        try {
            if(result.size()!=10)throw invalidVideo();
            for(String field:Arrays.asList("mime","videoCodec","audioCodec"))if(!result.path(field).isTextual())throw invalidVideo();
            long width=videoNumber(result.path("width"),1,4096),height=videoNumber(result.path("height"),1,2304);
            videoNumber(result.path("audioIndex"),0,31);if(width*height>9437184)throw invalidVideo();
            String mime=result.path("mime").asText(),video=result.path("videoCodec").asText(),audio=result.path("audioCodec").asText();
            if(!("video/mp4".equals(mime)&&"h264".equals(video)&&"aac".equals(audio)
                    ||"video/webm".equals(mime)&&Arrays.asList("vp8","vp9","av1").contains(video)&&Arrays.asList("opus","vorbis").contains(audio)))throw invalidVideo();
            return json.treeToValue(result,KnowledgeVideoDTO.Probe.class);
        }catch(ObjectiveException error){throw error;}catch(Exception error){throw invalidVideo();}
    }
    public KnowledgeVideoDTO.Transcribed transcribeVideo(KnowledgeVideoDTO.Slice slice) {
        videoAsset(slice);
        if(slice.getStartMs()==null||slice.getEndMs()==null||slice.getStartMs()<0||slice.getEndMs()>VideoTranscript.MAX_DURATION
                ||slice.getEndMs()<=slice.getStartMs()||slice.getEndMs()-slice.getStartMs()>VideoTranscript.SLICE
                ||!Arrays.asList("auto","zh","en").contains(slice.getLanguage())
                ||slice.getExpectedModelHash()!=null&&!slice.getExpectedModelHash().matches("[a-f0-9]{64}"))throw new ObjectiveException(400,"视频转写片段无效");
        JsonNode result=post("transcribe-video",slice,105000);sameVideo(result,slice);
        try {
            if(result.size()!=10||videoNumber(result.path("startMs"),0,VideoTranscript.MAX_DURATION-1)!=slice.getStartMs()
                    ||videoNumber(result.path("endMs"),1,VideoTranscript.MAX_DURATION)!=slice.getEndMs()
                    ||slice.getEndMs()>result.path("durationMs").asLong()||!result.path("language").isTextual()
                    ||!result.path("language").asText().matches("[a-z]{2,3}")||!result.path("modelHash").isTextual()
                    ||!result.path("modelHash").asText().matches("[a-f0-9]{64}")
                    ||slice.getExpectedModelHash()!=null&&!slice.getExpectedModelHash().equals(result.path("modelHash").asText())
                    ||!"faster-whisper-cpu-int8-v1".equals(result.path("engine").asText())
                    ||!result.path("segments").isArray()||result.path("segments").size()>200)throw invalidVideo();
            if(result.path("segments").size()>0){
                Map<String,Object> timeline=new LinkedHashMap<>();timeline.put("schema",1);timeline.put("durationMs",result.path("durationMs").asLong());timeline.put("segments",result.path("segments"));
                VideoTranscript transcript=VideoTranscript.parse(json.writeValueAsString(timeline));
                int total=0;for(VideoTranscript.Speech speech:transcript.segments){if(speech.startMs<slice.getStartMs()||speech.endMs>slice.getEndMs())throw invalidVideo();total+=length(speech.text);}
                if(total>20000)throw invalidVideo();
            }
            return json.treeToValue(result,KnowledgeVideoDTO.Transcribed.class);
        }catch(ObjectiveException error){throw error;}catch(Exception error){throw invalidVideo();}
    }

    private KnowledgeDTO.PdfText extractPdf(byte[] bytes, boolean ocr) {
        if (bytes == null || bytes.length < 5 || bytes.length > KnowledgeLimits.PDF_BYTES
                || !"%PDF-".equals(new String(bytes, 0, 5, StandardCharsets.US_ASCII)))
            throw new ObjectiveException(400, "请选择 32 MiB 以内的有效 PDF 文件");
        JsonNode result = exchange(ocr ? "extract-pdf-ocr" : "extract-pdf", bytes, ContentType.create("application/pdf"), ocr ? 105000 : 55000, KnowledgeLimits.PDF_BYTES, KnowledgeLimits.PDF_RESULT_BYTES);
        try {
            if (result.size() != 5 || !result.path("sourceHash").isTextual() || !hash(bytes).equals(result.path("sourceHash").asText())
                    || !result.path("content").isTextual() || !result.path("pages").isIntegralNumber() || !result.path("pages").canConvertToInt()
                    || !(ocr ? Collections.singletonList("pdfium-tesseract-chi_sim-eng-v1") : Arrays.asList("pypdf-6.21.1", "pymupdf-text-v1")).contains(result.path("parser").asText()) || !result.path("emptyPages").isArray()) throw invalidResult();
            String content = result.path("content").asText();
            if (content.trim().isEmpty() || length(content) > KnowledgeLimits.PDF_CHARACTERS || content.indexOf('\0') >= 0 || content.indexOf('\r') >= 0) throw invalidResult();
            String[] pages = content.split("\n\f\n", -1);
            if (pages.length < 1 || pages.length > 200 || result.path("pages").asInt() != pages.length) throw invalidResult();
            List<Integer> empty = new ArrayList<>();
            for (int i = 0; i < pages.length; i++) { if (pages[i].indexOf('\f') >= 0) throw invalidResult(); if (pages[i].trim().isEmpty()) empty.add(i + 1); }
            if (result.path("emptyPages").size() != empty.size()) throw invalidResult();
            for (int i = 0; i < empty.size(); i++) if (!result.path("emptyPages").get(i).isIntegralNumber()
                    || !result.path("emptyPages").get(i).canConvertToInt() || result.path("emptyPages").get(i).asInt() != empty.get(i)) throw invalidResult();
            return json.treeToValue(result, KnowledgeDTO.PdfText.class);
        } catch (ObjectiveException e) { throw e; }
        catch (Exception e) { throw invalidResult(); }
    }

    public void delete(KnowledgeDTO.VersionRef ref) {
        ref(ref); JsonNode result = post("delete", referenceBody(ref), 25000);
        if (!result.path("documentId").isTextual() || !ref.getDocumentId().equals(result.path("documentId").asText()) || !result.path("version").isIntegralNumber() || !result.path("version").canConvertToInt()
                || ref.getVersion() != result.path("version").asInt() || !"DELETED".equals(result.path("state").asText())) throw invalidResult();
    }

    public List<KnowledgeDTO.Hit> retrieve(String query, List<KnowledgeDTO.VersionRef> allowedVersions, int topK) {
        if (query == null || query.trim().isEmpty() || length(query) > 2000 || query.indexOf('\0') >= 0 || topK < 1 || topK > 10
                || allowedVersions == null || allowedVersions.size() > 500) throw new ObjectiveException(400, "知识库检索范围无效");
        Set<String> identities = new HashSet<>(), versions = new HashSet<>();
        for (KnowledgeDTO.VersionRef ref : allowedVersions) {
            ref(ref);
            if (!versions.add(ref.getDocumentId() + ":" + ref.getVersion())) throw new ObjectiveException(400, "知识库资料版本重复");
            identities.add(identity(ref));
        }
        if (allowedVersions.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> references = new ArrayList<>(); for (KnowledgeDTO.VersionRef ref : allowedVersions) references.add(referenceBody(ref));
        Map<String, Object> dto = new LinkedHashMap<>(); dto.put("query", query); dto.put("allowedVersions", references); dto.put("topK", topK);
        JsonNode result = post("retrieve", dto, 25000), items = result.path("items");
        if (!items.isArray() || items.size() > topK) throw invalidResult();
        List<KnowledgeDTO.Hit> hits = new ArrayList<>(); Set<String> chunkIds = new HashSet<>();
        try {
            for (JsonNode item : items) {
                for (String field : Arrays.asList("documentId", "contentHash", "chunkId", "chunkHash", "title", "excerpt", "headingPath"))
                    if (!item.path(field).isTextual()) throw invalidResult();
                if (!item.path("version").isIntegralNumber() || !item.path("version").canConvertToInt()
                        || !item.path("chunkIndex").isIntegralNumber() || !item.path("chunkIndex").canConvertToInt()
                        || !item.path("score").isNumber()) throw invalidResult();
                KnowledgeDTO.Hit hit = json.treeToValue(item, KnowledgeDTO.Hit.class);
                if (!identities.contains(identity(hit)) || hit.getChunkId() == null || !hit.getChunkId().matches("[a-f0-9]{64}")
                        || !chunkIds.add(hit.getChunkId()) || hit.getChunkIndex() < 0 || hit.getChunkIndex() >= 400
                        || hit.getExcerpt() == null || hit.getExcerpt().trim().isEmpty() || length(hit.getExcerpt()) > 1000
                        || !hash(hit.getExcerpt()).equals(hit.getChunkHash()) || hit.getTitle() == null || length(hit.getTitle()) > 200
                        || hit.getHeadingPath() == null || length(hit.getHeadingPath()) > 1000
                        || !Double.isFinite(hit.getScore())) throw invalidResult();
                hits.add(hit);
            }
        } catch (ObjectiveException e) { throw e; }
        catch (Exception e) { throw invalidResult(); }
        return hits;
    }

    private boolean sameVersion(JsonNode item, KnowledgeDTO.VersionRef ref) {
        return item.path("documentId").isTextual() && ref.getDocumentId().equals(item.path("documentId").asText())
                && item.path("version").isIntegralNumber() && item.path("version").canConvertToInt() && ref.getVersion() == item.path("version").asInt()
                && item.path("contentHash").isTextual() && ref.getContentHash().equals(item.path("contentHash").asText());
    }
    private ObjectiveException invalidResult() { return new ObjectiveException(502, "知识库检索结果校验失败，请重试"); }

    protected long now() { return System.currentTimeMillis(); }

    protected JsonNode post(String operation, Object body, int timeoutMs) {
        byte[] data;
        try { data = json.writeValueAsBytes(body); }
        catch (Exception e) { throw new ObjectiveException(400, "知识库请求无效"); }
        return exchange(operation, data, ContentType.APPLICATION_JSON, timeoutMs, "ingest".equals(operation) ? KnowledgeLimits.PDF_INGEST_BYTES : 1048576, 262144);
    }

    protected JsonNode exchange(String operation, byte[] data, ContentType type, int timeoutMs, int maxRequest, int maxResponse) {
        URI uri = endpoint(operation);
        if (data.length > maxRequest) throw new ObjectiveException(400, "知识库请求过长，请减少资料内容");
        byte[] key = key();
        String id = UUID.randomUUID().toString().replace("-", ""), end = Long.toString(now() + timeoutMs);
        HttpPost request = new HttpPost(uri); request.setEntity(new ByteArrayEntity(data, type));
        request.setHeader("X-HOJ-Request-Id", id); request.setHeader("X-HOJ-Deadline", end);
        request.setHeader("X-HOJ-Signature", signature(key, uri.getPath(), id, end, data)); request.setHeader("Accept", "application/json");
        Arrays.fill(key, (byte) 0);
        ScheduledFuture<?> deadline = deadlines.schedule(request::abort, timeoutMs, TimeUnit.MILLISECONDS);
        try (CloseableHttpClient client = HttpClients.custom().disableRedirectHandling().disableAutomaticRetries()
                .disableCookieManagement().disableAuthCaching().disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(3000).setConnectionRequestTimeout(1000).setSocketTimeout(timeoutMs).build()).build();
             CloseableHttpResponse response = client.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            boolean video="probe-video".equals(operation)||"transcribe-video".equals(operation);
            if(video&&(status==400||status==404||status==409||status==503))throw videoFailure(readResult(response,4096),status);
            if (status == 409) throw new ObjectiveException(409, "知识库版本冲突，请刷新任务状态");
            if (status == 400 && ("extract-pdf".equals(operation) || "extract-pdf-ocr".equals(operation))) throw pdfFailure(readResult(response, 4096));
            if (status == 503 && "extract-pdf-ocr".equals(operation)) throw new ObjectiveException(503, "本地 OCR 尚未就绪，请联系管理员检查识别组件");
            if (status == 400 && "ingest".equals(operation)) throw new ObjectiveException(400, "资料无法入库，请检查正文格式或减少内容");
            if (status == 429) throw new ObjectiveException(429, "知识库检索繁忙，请稍后再试");
            if (status != 200 || response.getEntity() == null) throw new ObjectiveException(503, "知识库服务暂不可用，请稍后再试");
            JsonNode result = readResult(response, maxResponse);
            if (System.currentTimeMillis() >= Long.parseLong(end)) throw new ObjectiveException(503, "知识库检索超时，请重试");
            return result;
        } catch (ObjectiveException e) { throw e; }
        catch (Exception e) { throw new ObjectiveException(503, "知识库服务连接失败或超时，请稍后再试"); }
        finally { deadline.cancel(false); }
    }

    private JsonNode readResult(CloseableHttpResponse response, int limit) throws IOException {
        if (response.getEntity() == null || response.getEntity().getContentLength() > limit) throw invalidResult();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream input = response.getEntity().getContent()) {
            byte[] bytes = new byte[4096]; int count;
            while ((count = input.read(bytes)) != -1) { if (buffer.size() + count > limit) throw invalidResult(); buffer.write(bytes, 0, count); }
        }
        try {
            JsonNode result = json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(buffer.toByteArray());
            if (result == null || !result.isObject()) throw invalidResult(); return result;
        } catch (IOException error) { throw invalidResult(); }
    }

    private ObjectiveException pdfFailure(JsonNode result) {
        String code = result.path("code").asText(), message;
        switch (code) {
            case "PDF_NO_TEXT": message = "PDF 没有可提取文字；扫描件可启用本地 OCR，仍为空时请检查扫描质量"; break;
            case "PDF_OCR_FAILED": message = "本地 OCR 识别失败，请检查扫描质量或拆分文件重试"; break;
            case "PDF_ENCRYPTED": message = "暂不支持加密 PDF，请上传未加密文件"; break;
            case "PDF_TOO_MANY_PAGES": message = "PDF 最多支持 200 页，请拆分文件"; break;
            case "PDF_TOO_MUCH_TEXT": message = "PDF 提取文字超过 30 万字，请拆分文件"; break;
            case "PDF_RESOURCE_LIMIT": case "PDF_CONTENT_STREAM_TOO_LARGE": message = "PDF 解析超过资源限制，请拆分文件"; break;
            case "PDF_UNSUPPORTED_TEXT": message = "PDF 文字编码不受支持，请转换后重试"; break;
            default: message = "PDF 文件损坏或格式不受支持";
        }
        return new ObjectiveException(400, message);
    }
    private ObjectiveException videoFailure(JsonNode result,int status) {
        String message,code=result.path("code").asText();
        switch(code){
            case "VIDEO_NO_AUDIO":message="视频没有可用音轨，请上传包含讲解音频的视频";break;
            case "VIDEO_UNSUPPORTED_CODEC":message="请使用 H.264 / AAC 的 MP4，或 VP8、VP9、AV1 / Opus、Vorbis 的 WebM";break;
            case "VIDEO_DURATION_LIMIT":message="视频最多 2 小时，请拆分文件";break;
            case "VIDEO_RESOURCE_LIMIT":message="视频处理超过资源限制，可拆分或降低分辨率后重试";break;
            case "VIDEO_MODEL_CHANGED":message="本地转写模型已更新，请重新开始本次转写";break;
            case "VIDEO_SOURCE_CHANGED":case "VIDEO_ASSET_MISSING":message="视频原件不可用或已改变，请重新上传";break;
            case "VIDEO_RUNTIME_UNAVAILABLE":case "VIDEO_ASR_UNAVAILABLE":case "VIDEO_ASSETS_UNAVAILABLE":message="本地视频处理组件尚未就绪，请联系管理员";break;
            case "VIDEO_INVALID":case "VIDEO_INVALID_SLICE":message="视频格式或转写范围无效，请检查文件";break;
            case "VIDEO_ASR_FAILED":message="视频转写暂时失败，请稍后重试";break;
            default:code="VIDEO_SERVICE_UNAVAILABLE";status=503;message="本地视频处理接口尚未就绪，请联系管理员";
        }
        return new VideoServiceFailure(status,message,code);
    }
}
