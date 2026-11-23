package top.hcode.hoj.manager.oj.ai;

import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.KnowledgeDTO;
import java.util.Map;

/** Server-created source snapshot; browser JSON can never construct this subtype. */
final class KnowledgeVideoUpload extends KnowledgeDTO.Upload {
    final String assetId,sourceName,sourceHash,mime,language,engine,modelHash,mode;
    final long byteLength,duration;
    KnowledgeVideoUpload(KnowledgeDTO.Upload metadata,Map<String,Object> source,VideoTranscript transcript,boolean reviewed){
        assetId=(String)source.get("asset_id");sourceName=(String)source.get("source_name");sourceHash=(String)source.get("source_hash");
        byteLength=((Number)source.get("byte_length")).longValue();duration=((Number)source.get("duration_ms")).longValue();
        mime=(String)source.get("mime");language=(String)source.get("language");engine=(String)source.get("engine");modelHash=(String)source.get("model_hash");
        if(transcript.durationMs!=duration)throw new ObjectiveException(400,"校订必须保留原视频时长");
        mode=reviewed?"ASR_REVIEWED":"ASR";
        setTitle(metadata.getTitle());setVisibility(metadata.getVisibility());setRequestId(metadata.getRequestId());setExpectedRevision(metadata.getExpectedRevision());
        setFormat("video");setContent(transcript.canonical());setReviewedVideo(reviewed);
    }
}
