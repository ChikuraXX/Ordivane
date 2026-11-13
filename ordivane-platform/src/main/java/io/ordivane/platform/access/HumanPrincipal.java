package io.ordivane.platform.access;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** 数据库中的人类身份；只通过专用身份响应返回非秘密字段。 */
public record HumanPrincipal(UUID id, String username, String password, AccessRole role,
                             boolean enabled) implements UserDetails {
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() { return password; }

    @Override
    public String getUsername() { return username; }

    @Override
    public boolean isEnabled() { return enabled; }

    /** 防止调试输出把密码摘要带入日志。 */
    @Override
    public String toString() { return "HumanPrincipal[id=" + id + ", role=" + role + "]"; }
}
