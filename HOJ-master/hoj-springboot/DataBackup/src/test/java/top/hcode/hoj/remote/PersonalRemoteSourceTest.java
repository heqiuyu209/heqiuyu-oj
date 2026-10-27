package top.hcode.hoj.remote;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static top.hcode.hoj.remote.RemoteSourceFailure.Kind.*;

class PersonalRemoteSourceTest {
    FakeTransport http;PersonalRemoteSource source;RemoteProblemRef ref;
    String code="#include <iostream>\nint main() { std::cout << 42; }\n";
    static class FakeTransport extends PersonalRemoteTransport {
        Map<String,String> pages=new HashMap<>();Map<String,String> posted;int posts;String postedPath;
        FakeTransport(String platform){super(platform,RemoteSession.parse(platform,"ATCODER".equals(platform)?"REVEL_SESSION=synthetic":"JSESSIONID=synthetic"),()->{});}
        @Override public String get(String path){if(!pages.containsKey(path))throw new RemoteSourceFailure(UNAVAILABLE);return pages.get(path);}
        @Override public Reply postCode(String path,Map<String,String> fields,Runnable barrier){barrier.run();posts++;posted=new LinkedHashMap<>(fields);postedPath=path;return new Reply(200,"synthetic throttle / error, not acceptance");}
    }
    void at(){ref=new RemoteProblemRef("AC-abc001_1","abc001");http=new FakeTransport("ATCODER");source=new PersonalRemoteSource(ref,"tourist",http);}
    void cf(){ref=new RemoteProblemRef("CF-4A",null);http=new FakeTransport("CODEFORCES");source=new PersonalRemoteSource(ref,"tourist",http);}
    @AfterEach void close(){if(http!=null)http.close();}
    String nav(){return "ATCODER".equals(ref.platform)?"<div id='navbar-collapse'><ul class='navbar-right'><span class='username'>Tourist</span></ul></div><form method='POST' action='/logout'></form>":"<div class='lang-chooser'><a href='/profile/Tourist'>Tourist</a><a href='/logout?csrf_token=synthetic'>Logout</a></div>";}
    String form(){return nav()+"<form method='post' action='"+ref.submitPath()+"'><input type='hidden' name='csrf_token' value='synthetic-csrf'><input name='"+("ATCODER".equals(ref.platform)?"data.TaskScreenName":"submittedProblemIndex")+"' value='"+ref.task+"'><select name='"+("ATCODER".equals(ref.platform)?"data.LanguageId":"programTypeId")+"'><option>choose</option><option value='5400'>C++ 23</option><option disabled value='9999'>disabled</option></select></form>";}
    String detail(String text){return nav()+"<pre id='"+("ATCODER".equals(ref.platform)?"submission-code":"program-source-text")+"'>"+text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")+"</pre>";}
    String cfRow(long id,long seconds,String contest,String task,String verdict){return "{\"id\":"+id+",\"creationTimeSeconds\":"+seconds+",\"author\":{\"members\":[{\"handle\":\"tourist\"}]},\"problem\":{\"contestId\":"+contest+",\"index\":\""+task+"\"},\"programmingLanguage\":\"C++ 23\",\"verdict\":\""+verdict+"\",\"timeConsumedMillis\":12,\"memoryConsumedBytes\":2147483648}";}
    @Test void sourceIdentifiersResolveExactlyAndNeverGuessAtCoderContestOrAcceptURLs(){
        assertEquals("/gym/100001/submit",new RemoteProblemRef("GYM-100001A",null).submitPath());assertEquals("/contest/4/submit",new RemoteProblemRef("CF-4a",null).submitPath());
        assertThrows(IllegalArgumentException.class,()->new RemoteProblemRef("AC-abc001_1",null));assertThrows(IllegalArgumentException.class,()->new RemoteProblemRef("AC-abc001_1","//other.invalid"));assertThrows(IllegalArgumentException.class,()->new RemoteProblemRef("https://other.invalid",null));
    }
    @Test void taskFormSuppliesCurrentLanguageIdsAndSensitiveFieldsNeverLeaveTheForm(){
        at();http.pages.put(ref.formPath(),form());PersonalRemoteSource.Form form=source.form();assertEquals(Collections.singletonMap("5400","C++ 23"),form.languages());assertFalse(form.toString().contains("synthetic-csrf"));
        assertThrows(UnsupportedOperationException.class,()->form.languages().put("1","other"));source.post(form,"5400",code,()->{});assertEquals(1,http.posts);assertEquals(code,http.posted.get("sourceCode"));assertEquals("abc001_1",http.posted.get("data.TaskScreenName"));assertEquals("5400",http.posted.get("data.LanguageId"));assertFalse(http.posted.containsKey("password"));
    }
    @Test void codeforcesUsesItsActualFormWithoutRetryingARejectedCodePost(){
        cf();http.pages.put(ref.formPath(),form());source.post(source.form(),"5400",code,()->{});assertEquals(1,http.posts);assertEquals(code,http.posted.get("source"));assertEquals("A",http.posted.get("submittedProblemIndex"));assertEquals("submitSolutionFormSubmitted",http.posted.get("action"));assertTrue(http.postedPath.startsWith("/contest/4/submit?csrf_token="));
    }
    @Test void staleLanguageWrongTaskForeignActionAmbiguousListsAndAnonymousFormFailBeforeCodeIo(){
        at();http.pages.put(ref.formPath(),form());PersonalRemoteSource.Form valid=source.form();assertEquals(LANGUAGE_CHANGED,assertThrows(RemoteSourceFailure.class,()->source.post(valid,"5031",code,()->{})).kind);assertEquals(0,http.posts);
        for(String invalid:Arrays.asList(form().replace("value='abc001_1'","value='another_task'"),form().replace("action='/contests/abc001/submit'","action='https://other.invalid/submit'"),form().replace("</select>","</select><select name='data.LanguageId'><option value='1'>other</option></select>"),form().replace(nav(),""))){http.pages.put(ref.formPath(),invalid);assertThrows(RemoteSourceFailure.class,()->source.form());}
        assertEquals(0,http.posts);
    }
    @Test void failedDurableBarrierPreventsTheActualPost(){
        cf();http.pages.put(ref.formPath(),form());assertThrows(IllegalStateException.class,()->source.post(source.form(),"5400",code,()->{throw new IllegalStateException("stale lease");}));assertEquals(0,http.posts);
    }
    @Test void cfBaselineIncludesOtherContestsAndLargeMemoryIsConvertedWithoutOverflow(){
        cf();long now=System.currentTimeMillis()/1000;List<PersonalRemoteSource.Candidate> rows=source.parseCodeforcesRecent("{\"status\":\"OK\",\"result\":["+cfRow(200,now,"5","A","SKIPPED")+","+cfRow(101,now,"4","A","OK")+"]}");
        assertEquals(200,source.baseline(rows));assertEquals(2097152,rows.get(1).memory);assertEquals(0,rows.get(1).verdict);assertNull(rows.get(0).verdict);
    }
    @Test void resultRequiresExactCodeMetadataTimeAndOneCandidateRatherThanMaximumId(){
        cf();long now=System.currentTimeMillis()/1000;List<PersonalRemoteSource.Candidate> rows=source.parseCodeforcesRecent("{\"status\":\"OK\",\"result\":["+cfRow(102,now,"4","A","OK")+","+cfRow(101,now,"4","A","WRONG_ANSWER")+"]}");http.pages.put(ref.submissionPath(102),detail("different code"));http.pages.put(ref.submissionPath(101),detail(code));
        assertEquals(101,source.reconcile(rows,"C++ 23",code,100,now*1000).id);assertNull(source.reconcile(rows,"C++ 23",code,102,now*1000));assertNull(source.reconcile(rows,"old language",code,100,now*1000));assertNull(source.reconcile(rows,"C++ 23",code,100,(now+10)*1000));
        http.pages.put(ref.submissionPath(102),detail(code));assertEquals(AMBIGUOUS_RESULT,assertThrows(RemoteSourceFailure.class,()->source.reconcile(rows,"C++ 23",code,100,now*1000)).kind);
    }
    @Test void codeWhitespaceIsSignificantAndSourceIsDecodedWithoutCollapsingIndentation(){
        assertTrue(PersonalRemoteSource.sameCode("  a\r\n\tb\n","  a\n\tb\n"));assertFalse(PersonalRemoteSource.sameCode(" a\n","a\n"));assertFalse(PersonalRemoteSource.sameCode("a","a\n"));
        cf();long now=System.currentTimeMillis()/1000;List<PersonalRemoteSource.Candidate> rows=source.parseCodeforcesRecent("{\"status\":\"OK\",\"result\":["+cfRow(101,now,"4","A","OK")+"]}");http.pages.put(ref.submissionPath(101),detail(code));assertEquals(101,source.reconcile(rows,"C++ 23",code,100,now*1000).id);
    }
    @Test void laterIdenticalManualSubmissionCannotBeAdoptedDuringRecoveryOrPolling(){
        cf();long posted=System.currentTimeMillis()/1000-3600;
        String body="{\"status\":\"OK\",\"result\":["+cfRow(101,posted+121,"4","A","OK")+"]}";
        List<PersonalRemoteSource.Candidate> rows=source.parseCodeforcesRecent(body);
        http.pages.put(ref.submissionPath(101),detail(code));
        http.pages.put("/api/user.status?handle=tourist&from=1&count=50",body);
        assertNull(source.reconcile(rows,"C++ 23",code,100,posted*1000));
        assertEquals(AMBIGUOUS_RESULT,assertThrows(RemoteSourceFailure.class,()->source.result(101,"C++ 23",code,100,posted*1000)).kind);
    }
    @Test void originalSubmissionCanStillBeRecoveredLongAfterItsFixedCreationWindow(){
        cf();long posted=System.currentTimeMillis()/1000-86400;
        http.pages.put(ref.submissionPath(101),detail(code));
        for(long offset:Arrays.asList(-2L,0L,120L)){
            List<PersonalRemoteSource.Candidate> rows=source.parseCodeforcesRecent("{\"status\":\"OK\",\"result\":["+cfRow(101,posted+offset,"4","A","OK")+"]}");
            assertEquals(101,source.reconcile(rows,"C++ 23",code,100,posted*1000).id);
        }
        List<PersonalRemoteSource.Candidate> tooEarly=source.parseCodeforcesRecent("{\"status\":\"OK\",\"result\":["+cfRow(101,posted-3,"4","A","OK")+"]}");
        assertNull(source.reconcile(tooEarly,"C++ 23",code,100,posted*1000));
    }
    @Test void laterDuplicateDoesNotMakeAnOriginalExactMatchAmbiguous(){
        cf();long posted=System.currentTimeMillis()/1000-3600;
        List<PersonalRemoteSource.Candidate> rows=source.parseCodeforcesRecent("{\"status\":\"OK\",\"result\":["+cfRow(102,posted+121,"4","A","OK")+","+cfRow(101,posted,"4","A","OK")+"]}");
        http.pages.put(ref.submissionPath(101),detail(code));http.pages.put(ref.submissionPath(102),detail(code));
        assertEquals(101,source.reconcile(rows,"C++ 23",code,100,posted*1000).id);
    }
    @Test void malformedOrDuplicateCfDataAndUnknownVerdictsCannotBecomeAccepted(){
        cf();long now=System.currentTimeMillis()/1000;
        for(String body:Arrays.asList("<html>challenge</html>","{\"status\":\"FAILED\",\"result\":[]}","{\"status\":\"OK\",\"result\":["+cfRow(101,now,"4","A","UNKNOWN")+"]}","{\"status\":\"OK\",\"result\":["+cfRow(101,now,"4","A","OK")+","+cfRow(101,now,"4","A","OK")+"]}"))assertThrows(RemoteSourceFailure.class,()->source.parseCodeforcesRecent(body));
    }
    @Test void atcoderSourceTableProducesTypedVerdictAndRequiresTheAuthenticatedSourceCode(){
        at();String row="<tr><td>2026-10-17 17:00:00+0900</td><td><a href='/contests/abc001/tasks/abc001_1'>A</a></td><td><a href='/users/tourist'>tourist</a></td><td>C++ 23</td><td>100</td><td>10 Byte</td><td>AC</td><td>12 ms</td><td>2048 KB</td><td><a href='/contests/abc001/submissions/1001'>Detail</a></td></tr>";
        List<PersonalRemoteSource.Candidate> rows=source.parseAtCoderRecent(Jsoup.parse("<table><thead><th>Submission Time</th></thead><tbody>"+row+"</tbody></table>"));assertEquals(1,rows.size());assertEquals(0,rows.get(0).verdict);assertEquals(12,rows.get(0).time);assertEquals(2048,rows.get(0).memory);
        assertThrows(RemoteSourceFailure.class,()->source.parseAtCoderRecent(Jsoup.parse("<html>login page</html>")));
    }
}
