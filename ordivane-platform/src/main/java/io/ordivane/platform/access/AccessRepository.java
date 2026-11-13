package io.ordivane.platform.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Repository;

/** 用户密码和来源凭据分别存储，来源只能由高熵凭据的摘要查得。 */
@Repository
public class AccessRepository {
    private final JdbcTemplate jdbc;

    public AccessRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 按唯一用户名读取人类账号；不接受来源凭据。 */
    public HumanPrincipal loadHuman(String username) {
        return jdbc.query("SELECT id, username, password_hash, role, enabled FROM access_user WHERE username = ?",
                (rs, row) -> new HumanPrincipal(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("password_hash"), AccessRole.valueOf(rs.getString("role")),
                        rs.getBoolean("enabled")), username).stream().findFirst()
                .orElseThrow(() -> new UsernameNotFoundException("账号或凭据无效"));
    }

    /** 每次请求重新检查来源启用状态，不把来源身份写进会话。 */
    public Optional<String> findSource(String credential) {
        return jdbc.query("SELECT source_id FROM access_source WHERE credential_hash = ? AND enabled = true",
                (rs, row) -> rs.getString("source_id"), hashCredential(credential)).stream().findFirst();
    }

    /** SHA-256 仅适用于随机高熵来源密钥，不用于人类密码。 */
    public static String hashCredential(String credential) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(credential.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行时不支持 SHA-256", exception);
        }
    }
}
