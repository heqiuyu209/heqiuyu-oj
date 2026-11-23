package top.hcode.hoj.manager.oj.ai;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VideoTranscriptTest {
    String source="{\"schema\":1,\"durationMs\":10000,\"segments\":[{\"startMs\":1000,\"endMs\":9000,\"text\":\"前缀和：S[r]-S[l-1]；\\\"sum\\\" 😀\"}]}";
    @Test void canonicalJsonMatchesThePythonUnicodeGoldenVectorAndClockBounds(){
        VideoTranscript value=VideoTranscript.parse(source);assertEquals(source,value.canonical());
        assertEquals("e7065429e92455d2718d3841318b21f8e776f5d9282a08dda33aa56f447d034c",RagServiceClient.hash(value.canonical()));
        assertEquals(10000,value.durationMs);assertEquals("00:00:01.000",VideoTranscript.clock(1000));
        assertEquals("02:00:00.000",VideoTranscript.clock(VideoTranscript.MAX_DURATION));
        String text=value.segments.get(0).text;assertFalse(source.contains(text));
        assertArrayEquals(new long[]{1000,9000},value.citation("00:00:01.000–00:00:09.000",text));
        assertThrows(IllegalArgumentException.class,()->value.citation("00:00:01.001–00:00:09.000",text));
        assertThrows(IllegalArgumentException.class,()->value.citation("00:00:01.000–00:00:09.000","invented"));
        assertThrows(UnsupportedOperationException.class,()->value.segments.clear());
    }
    @Test void malformedAmbiguousOverlappingAndOutOfRangeTimelinesCannotBeSaved(){
        List<String> values=Arrays.asList(source.replace("\"schema\":1","\"schema\":1,\"schema\":1"),source+"{}",source.replace("\"schema\":1","\"schema\":true"),
                source.replace("\"durationMs\":10000","\"durationMs\":7200001"),source.replace("\"startMs\":1000","\"startMs\":true"),
                source.replace("\"endMs\":9000","\"endMs\":10001"),source.replace("\"endMs\":9000","\"endMs\":1000"),
                source.replace("前缀和", "bad\\u0000text"),source.replace("前缀和", "bad\\uDCFFtext"),
                source.replace("\"text\":", "\"url\":\"file:///private\",\"text\":"),"[]","{}",
                "{\"schema\":1,\"durationMs\":10000,\"segments\":[{\"startMs\":0,\"endMs\":100,\"text\":\"x\"},{\"startMs\":99,\"endMs\":101,\"text\":\"y\"}]}");
        for(String value:values){IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->VideoTranscript.parse(value));assertEquals("Invalid video transcript",error.getMessage());}
        String deep=String.join("",Collections.nCopies(10000,"["))+String.join("",Collections.nCopies(10000,"]"));assertThrows(IllegalArgumentException.class,()->VideoTranscript.parse(deep));
    }
    @Test void repeatedSpeechAtAnotherClockCannotSupplyAnUnrelatedExcerpt(){
        String json="{\"schema\":1,\"durationMs\":100000,\"segments\":[{\"startMs\":1000,\"endMs\":9000,\"text\":\"前缀和\"},{\"startMs\":90000,\"endMs\":95000,\"text\":\"Dijkstra\"}]}";
        VideoTranscript value=VideoTranscript.parse(json);
        assertArrayEquals(new long[]{90000,95000},value.citation("00:01:30.000–00:01:35.000","Dijkstra"));
        assertThrows(IllegalArgumentException.class,()->value.citation("00:00:01.000–00:00:09.000","Dijkstra"));
    }
}
