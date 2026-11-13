package io.ordivane.platform.shared;

/** 对外错误只含稳定代码、可展示消息和服务端生成的关联标识。 */
public record ApiError(String code, String message, String traceId) { }
