package io.ordivane.demo.order;

/** 可向调用方披露的业务错误，HTTP边界负责转换为脱敏契约。 */
public class BusinessException extends RuntimeException {
    private final String code;
    private final int status;

    /** 保存稳定错误码、HTTP状态及业务说明。 */
    public BusinessException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    /** 返回稳定错误码。 */
    public String code() { return code; }
    /** 返回HTTP状态。 */
    public int status() { return status; }
}
