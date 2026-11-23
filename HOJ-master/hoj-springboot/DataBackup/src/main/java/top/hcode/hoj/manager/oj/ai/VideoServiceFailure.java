package top.hcode.hoj.manager.oj.ai;

import top.hcode.hoj.common.exception.ObjectiveException;

/** Sanitized machine code for a durable import, without upstream bodies or paths. */
public final class VideoServiceFailure extends ObjectiveException {
    private final String code;
    public VideoServiceFailure(int status,String message,String code){super(status,message);this.code=code;}
    public String getCode(){return code;}
}
