package io.ordivane.platform.access;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 仅 local 且显式启用时初始化；既有账号/来源冲突即拒绝，不覆盖角色或轮换凭据。 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "ordivane.access.initialize", havingValue = "true")
public class LocalAccessInitializer implements ApplicationRunner {
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public LocalAccessInitializer(Environment environment, JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.environment = environment;
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    /** 三账号与来源一次事务创建；所有秘密只从当前进程环境解析。 */
    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        var names = new HashSet<String>();
        for (AccessRole role : AccessRole.values()) {
            String prefix = "ORDIVANE_ACCESS_" + role.name();
            String name = required(prefix + "_USERNAME");
            String password = required(prefix + "_PASSWORD");
            int length = password.getBytes(StandardCharsets.UTF_8).length;
            if (name.length() > 128 || !name.matches("[A-Za-z0-9_.-]+") || !names.add(name)
                    || length < 12 || length > 72) {
                throw new IllegalStateException("本地身份初始化参数无效：账号须唯一，密码须12至72字节");
            }
            var existing = jdbc.query("SELECT password_hash, role, enabled FROM access_user WHERE username = ?",
                    (rs, row) -> List.of(rs.getString(1), rs.getString(2), Boolean.toString(rs.getBoolean(3))), name);
            if (!existing.isEmpty()) {
                var record = existing.getFirst();
                if (!encoder.matches(password, record.get(0)) || !role.name().equals(record.get(1))
                        || !"true".equals(record.get(2))) {
                    throw new IllegalStateException("本地初始化与已有账号冲突，未覆盖已有身份");
                }
            } else {
                jdbc.update("INSERT INTO access_user(id, username, password_hash, role) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(), name, encoder.encode(password), role.name());
            }
        }
        String source = required("ORDIVANE_ACCESS_SOURCE_ID");
        String credential = required("ORDIVANE_ACCESS_SOURCE_CREDENTIAL");
        if (!source.matches("[A-Za-z0-9_.-]{1,128}") || !credential.matches("[\\x21-\\x7e]{32,512}")) {
            throw new IllegalStateException("本地来源初始化参数无效，凭据须为至少32字符的随机高熵值");
        }
        String hash = AccessRepository.hashCredential(credential);
        var existing = jdbc.query("SELECT credential_hash, enabled FROM access_source WHERE source_id = ?",
                (rs, row) -> List.of(rs.getString(1), Boolean.toString(rs.getBoolean(2))), source);
        if (!existing.isEmpty()) {
            if (!hash.equals(existing.getFirst().get(0)) || !"true".equals(existing.getFirst().get(1))) {
                throw new IllegalStateException("本地初始化与已有来源冲突，未覆盖已有凭据");
            }
        } else {
            jdbc.update("INSERT INTO access_source(source_id, credential_hash) VALUES (?, ?)", source, hash);
        }
    }

    private String required(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank() || value.equals("replace-me")) {
            throw new IllegalStateException("本地身份初始化缺少有效环境变量：" + name);
        }
        return value;
    }
}
