package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NowCoderContestClientTest {
    ObjectMapper json=new ObjectMapper();NowCoderContestClient client;
    @BeforeEach void setup(){client=new NowCoderContestClient(){@Override protected long now(){return 1800000000000L;}};ReflectionTestUtils.setField(client,"json",json);}
    ObjectNode list(){
        ObjectNode root=json.createObjectNode().put("code",0),data=root.putObject("data");
        data.putObject("pageInfo").put("pageCurrent",1).put("pageSize",10).put("pageCount",1).put("elementCount",1).put("totalCount",1);
        ObjectNode contest=data.putArray("dataList").addObject().put("contestId",99785).put("contestName","Public contest").put("startTime",1737111600000L).put("endTime",1737118800000L)
                .put("teamId",12345).put("isTeamSignUp",false).put("participationType","OFFICIAL").put("acceptedCount",2).put("rank",10).put("canShowRank",true);
        contest.putObject("settingInfo").put("forceOnlyMyStatusFilter",false);return root;
    }
    ObjectNode statuses(){
        ObjectNode root=json.createObjectNode().put("code",0),data=root.putObject("data").put("isContestFinished",true);
        data.putObject("basicInfo").put("contestId",99785).put("pageCurrent",1).put("pageSize",20).put("pageCount",1).put("statusCount",1).put("searchUserName","12345");
        data.putArray("data").addObject().put("submissionId",74761010).put("userId",12345).put("isTeam",false).put("problemId",286065).put("index","A").put("statusMessage","答案正确").put("submitTime",1737112080000L);
        return root;
    }
    @Test void officialEndpointsPinIdentityAndContestOnlyFilter(){
        assertTrue(client.endpoint("LIST","12345",0,2).toString().contains("uid=12345&page=2"));
        assertTrue(client.endpoint("VERIFY_LIST","12345",0,20).toString().contains("page=1"));
        String status=client.endpoint("STATUS","12345",99785,3).toString();assertTrue(status.startsWith("https://ac.nowcoder.com/acm-heavy/"));assertTrue(status.contains("searchUserName=12345&contestTypeList=contest"));
        assertThrows(ObjectiveException.class,()->client.endpoint("LIST","123?url=evil",0,1));
        assertThrows(IllegalArgumentException.class,()->client.endpoint("STATUS","12345",0,1));assertThrows(IllegalArgumentException.class,()->client.endpoint("UNKNOWN","12345",99785,1));
    }
    @Test void completedParticipationRetainsIdentityPrivacyAndSourceAggregate(){
        JsonNode result=client.contests(list(),"12345",1);assertEquals(1,result.path("total").asInt());JsonNode row=result.path("items").get(0);
        assertEquals(99785,row.path("contestId").asLong());assertFalse(row.path("team").asBoolean());assertFalse(row.path("restricted").asBoolean());assertEquals(2,row.path("officialAccepted").asInt());
        ObjectNode team=list();((ObjectNode)team.path("data").path("dataList").get(0)).put("teamId",777).put("isTeamSignUp",true);assertTrue(client.contests(team,"12345",1).path("items").get(0).path("team").asBoolean());
        ObjectNode privateList=list();((ObjectNode)privateList.path("data").path("dataList").get(0).path("settingInfo")).put("forceOnlyMyStatusFilter",true);assertTrue(client.contests(privateList,"12345",1).path("items").get(0).path("restricted").asBoolean());
    }
    @Test void movedPageWrongUserFractionalIdentityAndOngoingContestFailClosed(){
        for(String field:Arrays.asList("contestId","startTime","endTime","teamId")){
            ObjectNode root=list();((ObjectNode)root.path("data").path("dataList").get(0)).put(field,1.5);assertThrows(ObjectiveException.class,()->client.contests(root,"12345",1));
        }
        ObjectNode wrong=list();((ObjectNode)wrong.path("data").path("dataList").get(0)).put("teamId",54321);assertThrows(ObjectiveException.class,()->client.contests(wrong,"12345",1));
        ObjectNode moved=list();((ObjectNode)moved.path("data").path("pageInfo")).put("pageCurrent",2);assertThrows(ObjectiveException.class,()->client.contests(moved,"12345",1));
        ObjectNode future=list();((ObjectNode)future.path("data").path("dataList").get(0)).put("endTime",1800000010000L);assertThrows(ObjectiveException.class,()->client.contests(future,"12345",1));
    }
    @Test void exactSubmissionIdsGlobalProblemsAndVerdictsAreNormalized(){
        JsonNode row=client.submissions(statuses(),"12345",99785,1).path("items").get(0);assertEquals("nowcoder:acm:286065",row.path("problemKey").asText());assertEquals("OK",row.path("verdict").asText());assertEquals(1737112080000L,row.path("submittedMs").asLong());
    }
    @Test void officialStarParticipationAndHiddenAggregatesAreKeptTruthful(){
        ObjectNode root=list(),row=(ObjectNode)root.path("data").path("dataList").get(0);row.put("participationType","STAR").put("canShowRank",false);
        JsonNode parsed=client.contests(root,"12345",1).path("items").get(0);assertEquals("STAR",parsed.path("participation").asText());assertTrue(parsed.path("rank").isNull());assertTrue(parsed.path("officialAccepted").isNull());
        row.put("participationType","UNKNOWN_SOURCE_TYPE");assertThrows(ObjectiveException.class,()->client.contests(root,"12345",1));
    }
    @Test void wrongContestHiddenUsersTeamsUnknownVerdictsAndIncompletePagesAreNotEvidence(){
        for(String change:Arrays.asList("user","team","verdict","missing","contest","filter")){
            ObjectNode root=statuses(),data=(ObjectNode)root.path("data"),row=(ObjectNode)data.path("data").get(0);
            if("user".equals(change))row.put("userId",54321);if("team".equals(change))row.put("isTeam",true);if("verdict".equals(change))row.put("statusMessage","新状态");
            if("missing".equals(change))data.putArray("data");if("contest".equals(change))((ObjectNode)data.path("basicInfo")).put("contestId",1);
            if("filter".equals(change))((ObjectNode)data.path("basicInfo")).put("searchUserName","another");
            assertThrows(ObjectiveException.class,()->client.submissions(root,"12345",99785,1));
        }
    }
    @Test void savedOfficialSamplesProveContestFilterAndExistingPracticeOverlap() throws Exception {
        String directory=System.getenv("HOJ_NOWCODER_CONTEXT_SAMPLES");org.junit.jupiter.api.Assumptions.assumeTrue(directory!=null,"Explicit local read-only samples required");
        Path folder=Paths.get(directory);assertEquals(5,client.contests(json.readTree(Files.readAllBytes(folder.resolve("joined.json"))),"399269849",1).path("total").asInt());
        assertEquals(6,client.problems(json.readTree(Files.readAllBytes(folder.resolve("contest-problems.json"))),99785,1).path("total").asInt());
        JsonNode contest=client.submissions(json.readTree(Files.readAllBytes(folder.resolve("contest-only-status.json"))),"399269849",99785,1);assertEquals(11,contest.path("total").asInt());
        NowCoderDataClient coding=new NowCoderDataClient();ReflectionTestUtils.setField(coding,"json",json);
        try {
            JsonNode publicRows=coding.submissions(new String(Files.readAllBytes(folder.resolve("practice.html")),java.nio.charset.StandardCharsets.UTF_8),"399269849",1);
            Map<Long,String> known=new HashMap<>();for(JsonNode row:publicRows.path("items"))known.put(row.path("id").asLong(),row.path("problemKey").asText());
            for(JsonNode row:contest.path("items"))assertEquals(row.path("problemKey").asText(),known.get(row.path("id").asLong()));
        }finally{coding.close();}
    }
}
