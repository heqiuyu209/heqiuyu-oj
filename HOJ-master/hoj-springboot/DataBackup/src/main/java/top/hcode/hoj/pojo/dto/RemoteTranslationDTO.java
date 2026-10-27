package top.hcode.hoj.pojo.dto;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class RemoteTranslationDTO {
    private String problemKey;
    private String sourceHash;
    private String modelFingerprint;
    private boolean allowModelSource;
}
