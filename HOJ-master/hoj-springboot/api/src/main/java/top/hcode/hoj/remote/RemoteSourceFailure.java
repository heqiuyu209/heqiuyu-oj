package top.hcode.hoj.remote;

/** Safe categories only: no raw body, cookie, URL token, source code or transport exception. */
public final class RemoteSourceFailure extends RuntimeException {
    public enum Kind { AUTH_REQUIRED, CHALLENGE, FORMAT_CHANGED, UNAVAILABLE, LANGUAGE_CHANGED, AMBIGUOUS_RESULT }
    public final Kind kind;
    public RemoteSourceFailure(Kind kind){super(kind.name());this.kind=kind;}
}
