package top.hcode.hoj.manager.oj.ai;

/** Bounded whole-book PDF policy shared by upload, storage and the internal bridge. */
final class KnowledgeLimits {
    static final int TEXT_CHARACTERS = 200000;
    static final int PDF_CHARACTERS = 300000;
    static final int PDF_BYTES = 32 * 1024 * 1024;
    static final int PDF_RESULT_BYTES = 2 * 1024 * 1024;
    static final int PDF_INGEST_BYTES = 4 * 1024 * 1024;
    static final int PDF_INGEST_TIMEOUT_MS = 20 * 60 * 1000;
    static final int PDF_JOB_LEASE_MS = PDF_INGEST_TIMEOUT_MS + 60000;

    private KnowledgeLimits() { }

    static int characters(String format) {
        return "pdf".equals(format) ? PDF_CHARACTERS : TEXT_CHARACTERS;
    }
}
