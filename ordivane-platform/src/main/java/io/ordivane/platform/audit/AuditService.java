package io.ordivane.platform.audit;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 只追加结构化审计；调用者传受控业务标识，禁止凭据、请求体和异常文本。 */
@Service
public class AuditService {
    /** 主体种类与认证载体分离。 */
    public enum SubjectType { HUMAN, SOURCE, ANONYMOUS }

    /** DENIED 表示安全拒绝，FAILURE 表示业务或运行失败。 */
    public enum Result { SUCCESS, DENIED, FAILURE }

    /** 主体 ID 来自认证结果；匿名主体固定为 anonymous。 */
    public record Subject(SubjectType type, String id) { }

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 独立事务保留失败尝试；业务回滚不抹去审计，写失败仍须向调用边界传播。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID record(Subject subject, String action, String objectType, String objectId,
                       Result result, String traceId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_record(id, subject_type, subject_id, action, object_type, object_id, result, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, subject.type().name(), subject.id(), action, objectType, objectId, result.name(), traceId);
        return id;
    }
}
