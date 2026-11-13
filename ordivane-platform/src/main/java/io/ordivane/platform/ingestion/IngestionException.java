package io.ordivane.platform.ingestion;

/** 受控业务错误；消息只使用固定安全文案。 */
public class IngestionException extends RuntimeException {
    private final int status;
    private final String code;
    public IngestionException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static IngestionException invalid() {
        return new IngestionException(400, "INVALID_REQUEST", "输入格式或字段关系无效");
    }
}
