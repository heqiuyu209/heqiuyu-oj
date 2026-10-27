package top.hcode.hoj.remote;

import java.util.Locale;
import java.util.regex.*;

/** An exact source task, never a user-supplied URL or guessed AtCoder contest. */
public final class RemoteProblemRef {
    public final String platform, problem, contest, task;
    private final boolean gym;
    public RemoteProblemRef(String problem, String atCoderContest) {
        this.problem=problem;
        Matcher cf=Pattern.compile("^(CF|GYM)-([1-9][0-9]{0,8})([A-Za-z][A-Za-z0-9]{0,15})$").matcher(problem==null?"":problem);
        if(cf.matches()) {
            platform="CODEFORCES";contest=cf.group(2);task=cf.group(3).toUpperCase(Locale.ROOT);gym="GYM".equals(cf.group(1));
        } else if(problem!=null && problem.matches("AC-[A-Za-z0-9_-]{1,80}") && atCoderContest!=null && atCoderContest.matches("[A-Za-z0-9_-]{1,80}")) {
            platform="ATCODER";contest=atCoderContest;task=problem.substring(3);gym=false;
        } else if(problem!=null && problem.matches("NC-[1-9][0-9]{0,11}(?:-[A-Za-z][A-Za-z0-9]{0,15})?")) {
            String[] parts=problem.split("-",-1);platform="NOWCODER";contest=parts.length==3?parts[1]:"";task=parts.length==3?parts[2].toUpperCase(Locale.ROOT):parts[1];gym=false;
        } else throw new IllegalArgumentException("Unsupported or unresolved source task");
    }
    public boolean submissionSupported(){return "CODEFORCES".equals(platform)||"ATCODER".equals(platform)||"NOWCODER".equals(platform);}
    public String origin(){return "NOWCODER".equals(platform)?"https://ac.nowcoder.com":"ATCODER".equals(platform)?"https://atcoder.jp":"https://codeforces.com";}
    public String contestPath(){return "NOWCODER".equals(platform)?"/acm/contest/"+contest:"ATCODER".equals(platform)?"/contests/"+contest:(gym?"/gym/":"/contest/")+contest;}
    public String submitPath(){if("NOWCODER".equals(platform))throw new IllegalStateException("NowCoder uses its fixed judge API");return contestPath()+"/submit";}
    public String formPath(){return "NOWCODER".equals(platform)?contest.isEmpty()?"/acm/problem/"+task:contestPath()+"/"+task:"ATCODER".equals(platform)?contestPath()+"/tasks/"+task+"?lang=en":submitPath()+"?locale=en";}
    public String submissionPath(long id){if(id<=0)throw new IllegalArgumentException("Invalid source submission");if("NOWCODER".equals(platform))return "/acm/contest/view-submission?submissionId="+id;return contestPath()+("ATCODER".equals(platform)?"/submissions/":"/submission/")+id;}
}
