package top.hcode.hoj.pojo.dto;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class RemoteStatementDTO {
    private String problemKey;
    private String sourceId;
    private String sourceHash;
    private Boolean autoRefresh;
    private Content content;

    @Getter @Setter
    public static class HtmlPreview {
        private String problemKey;
        private String html;
        private String pageUrl;
    }

    @Getter @Setter
    public static class Content {
        private String title,description,input,output,examples,hint;
        private Integer timeLimit,memoryLimit;
    }
}
