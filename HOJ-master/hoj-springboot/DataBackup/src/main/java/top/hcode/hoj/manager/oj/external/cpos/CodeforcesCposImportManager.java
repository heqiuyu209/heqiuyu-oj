package top.hcode.hoj.manager.oj.external.cpos;

import lombok.Data;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.manager.oj.external.CodeforcesStatementClient;
import top.hcode.hoj.manager.oj.external.RemoteStatementManager;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Imports the statement and samples captured by the CPOS browser and VS Code extensions. */
@Component
public class CodeforcesCposImportManager {
    private static final int MAX_HTML_BYTES = 4 * 1024 * 1024;
    private static final int MAX_EXAMPLE_LENGTH = 500000;
    private static final Pattern PROBLEM_PATH =
            Pattern.compile("(?:/problemset/problem/([1-9][0-9]{0,18})|/contest/([1-9][0-9]{0,18})/problem)/([A-Za-z0-9]{1,16})/?");
    private static final Pattern TITLE_INDEX = Pattern.compile("^([A-Za-z0-9]{1,16})\\.\\s");

    private final CodeforcesStatementClient client;
    private final RemoteStatementManager statements;

    public CodeforcesCposImportManager(CodeforcesStatementClient client, RemoteStatementManager statements) {
        this.client = client;
        this.statements = statements;
    }

    public boolean importStatement(Capture capture) {
        String key = problemKey(capture);
        if (statements.adminView(key).get("source") != null) {
            return false;
        }

        RemoteStatementDTO dto = new RemoteStatementDTO();
        dto.setProblemKey(key);
        dto.setContent(content(capture, key));
        // A null source hash only permits creating the first original under the existing lock.
        try {
            statements.save(dto, false);
        } catch (ObjectiveException e) {
            if (e.getStatus() == 409 && statements.adminView(key).get("source") != null) {
                return false;
            }
            throw e;
        }
        return true;
    }

    private String problemKey(Capture capture) {
        if (capture == null || !"codeforces".equals(capture.getPlatform()) || capture.getUrl() == null) {
            throw new ObjectiveException(400, "请选择 Codeforces 的 CPOS 采集记录");
        }
        URI url;
        try {
            url = URI.create(capture.getUrl());
        } catch (IllegalArgumentException e) {
            throw invalidProblem();
        }
        if (!"https".equalsIgnoreCase(url.getScheme()) || !"codeforces.com".equalsIgnoreCase(url.getRawAuthority())) {
            throw invalidProblem();
        }
        Matcher path = PROBLEM_PATH.matcher(url.getRawPath());
        if (!path.matches()) {
            throw invalidProblem();
        }
        String contest = path.group(1) == null ? path.group(2) : path.group(1);
        String index = path.group(3).toUpperCase(Locale.ROOT);
        if (!(contest + index).equalsIgnoreCase(capture.getId())) {
            throw invalidProblem();
        }
        String key = "cf:contest:" + contest + ":" + index;
        CodeforcesStatementClient.sourceUrl(key);
        return key;
    }

    private Content content(Capture capture, String key) {
        String html = capture.getStatementHtml();
        if (html == null || html.trim().isEmpty()) {
            throw new ObjectiveException(400, "CPOS 记录缺少题面，请在浏览器确认能看到题目后重新采集");
        }
        if (html.length() > MAX_HTML_BYTES || html.getBytes(StandardCharsets.UTF_8).length > MAX_HTML_BYTES) {
            throw new ObjectiveException(400, "题面 HTML 最大为 4 MiB");
        }
        Document document = Jsoup.parseBodyFragment(html, CodeforcesStatementClient.sourceUrl(key));
        Elements bodies = document.select(".problem-statement");
        Elements titles = bodies.select(".header .title");
        if (bodies.size() != 1 || titles.size() != 1) {
            throw new ObjectiveException(400, "CPOS 记录缺少完整的 Codeforces 题面");
        }
        Matcher title = TITLE_INDEX.matcher(titles.first().text());
        if (!title.find() || !title.group(1).equalsIgnoreCase(key.substring(key.lastIndexOf(':') + 1))) {
            throw new ObjectiveException(400, "题面标题的题号与 CPOS 记录不一致");
        }
        String examples = examples(capture.getTests());
        bodies.select(".sample-tests").remove();
        document.outputSettings().prettyPrint(false);
        Content content;
        try {
            content = client.parse(bodies.first().outerHtml(), key);
        } catch (ObjectiveException e) {
            throw new ObjectiveException(400, "CPOS 题面解析失败，请确认已采集完整英文题面和时空限制");
        }
        content.setExamples(examples);
        return content;
    }

    private String examples(List<Sample> samples) {
        if (samples == null) {
            throw new ObjectiveException(400, "CPOS 记录缺少配对的样例文件");
        }
        StringBuilder examples = new StringBuilder();
        for (Sample sample : samples) {
            if (sample == null || sample.getInput() == null || sample.getExpectedOutput() == null) {
                throw new ObjectiveException(400, "CPOS 样例缺少输入或输出");
            }
            String input = sample.getInput();
            String output = sample.getExpectedOutput();
            if (input.contains("</input><output>") || output.contains("</output>")) {
                throw new ObjectiveException(400, "样例含有当前题库格式无法保存的文本，请在原文编辑器中校对");
            }
            if ((long) examples.length() + input.length() + output.length()
                    + "<input></input><output></output>".length() > MAX_EXAMPLE_LENGTH) {
                throw new ObjectiveException(400, "样例内容过长");
            }
            examples.append("<input>").append(input).append("</input><output>")
                    .append(output).append("</output>");
        }
        return examples.toString();
    }

    private ObjectiveException invalidProblem() {
        return new ObjectiveException(400, "CPOS 题号与 Codeforces 公开题库地址不一致");
    }

    @Data
    public static class Capture {
        private String platform;
        private String id;
        private String url;
        private String statementHtml;
        private List<Sample> tests;
    }

    @Data
    public static class Sample {
        private String input;
        private String expectedOutput;
    }
}
