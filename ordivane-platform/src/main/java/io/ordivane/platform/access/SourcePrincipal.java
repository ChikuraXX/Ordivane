package io.ordivane.platform.access;

import java.security.Principal;

/** 来源标识只取自服务端凭据记录；请求载荷的 source 不能覆盖它。 */
public record SourcePrincipal(String sourceId) implements Principal {
    @Override
    public String getName() { return sourceId; }
}
