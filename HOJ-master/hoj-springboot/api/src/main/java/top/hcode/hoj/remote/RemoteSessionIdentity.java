package top.hcode.hoj.remote;

import org.jsoup.nodes.*;
import java.util.*;
import java.util.regex.*;

/** Public profile text is never sufficient evidence of an authenticated source identity. */
public final class RemoteSessionIdentity {
    private RemoteSessionIdentity(){}
    public static void require(String platform,String expected,Document document) {
        if("NOWCODER".equals(platform)){
            if(!Objects.equals(expected,NowCoderPage.authenticatedOwner(document)))throw new RemoteSourceFailure(RemoteSourceFailure.Kind.AUTH_REQUIRED);
            return;
        }
        Set<String> handles=new HashSet<>();boolean logout;
        if("CODEFORCES".equals(platform)) {
            logout=!document.select(".lang-chooser a[href^=/logout]").isEmpty();
            for(Element link:document.select(".lang-chooser a[href^=/profile/]")) {
                Matcher match=Pattern.compile("^/profile/([A-Za-z0-9_.-]{1,64})(?:\\?locale=en)?$").matcher(link.attr("href"));
                if(match.matches() && match.group(1).equalsIgnoreCase(link.text().trim()))handles.add(match.group(1).toLowerCase(Locale.ROOT));
            }
        } else if("ATCODER".equals(platform)) {
            logout=!document.select("form[action=/logout][method=post], form[action=/logout][method=POST]").isEmpty();
            for(Element name:document.select("#navbar-collapse .navbar-right .username")) {
                String handle=name.text().trim();if(handle.matches("[A-Za-z0-9_]{1,32}"))handles.add(handle.toLowerCase(Locale.ROOT));
            }
        } else throw new RemoteSourceFailure(RemoteSourceFailure.Kind.FORMAT_CHANGED);
        if(!logout || handles.size()!=1)throw new RemoteSourceFailure(RemoteSourceFailure.Kind.AUTH_REQUIRED);
        if(expected==null || !handles.contains(expected.toLowerCase(Locale.ROOT)))throw new RemoteSourceFailure(RemoteSourceFailure.Kind.AUTH_REQUIRED);
    }
}
