package top.hcode.hoj.pojo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Source URLs, owner IDs, credentials and result IDs are never accepted from the browser. */
public class PersonalRemoteSubmissionDTO {
    public String pid;
    public Long cid;
    public Long tid;
    public Long gid;
    public Long accountId;
    public String offerId;
    public String languageId;
    public String requestId;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) public String code;
}
