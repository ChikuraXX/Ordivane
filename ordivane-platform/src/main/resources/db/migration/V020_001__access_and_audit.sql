-- BE-020：人类账号、业务来源和追加审计分表；不预置任何账号或凭据。
CREATE TABLE access_user (
    id uuid PRIMARY KEY,
    username varchar(128) NOT NULL UNIQUE CHECK (username ~ '^[A-Za-z0-9_.-]+$'),
    password_hash varchar(60) NOT NULL CHECK (length(password_hash) = 60),
    role varchar(16) NOT NULL CHECK (role IN ('OBSERVER', 'OPERATOR', 'APPROVER')),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);
COMMENT ON TABLE access_user IS '人类账号；单一业务角色，密码仅存BCrypt摘要';
COMMENT ON COLUMN access_user.id IS '不可变主体UUID，审批时用此值核对申请人与审批人';
COMMENT ON COLUMN access_user.username IS '唯一登录名，1至128个ASCII字母数字或_.-';
COMMENT ON COLUMN access_user.password_hash IS 'BCrypt密码摘要，禁止存明文';
COMMENT ON COLUMN access_user.role IS '互不继承的观察者/操作员/审批人角色';
COMMENT ON COLUMN access_user.enabled IS '账号是否可登录和继续使用会话';
COMMENT ON COLUMN access_user.created_at IS '数据库生成的账号创建时间，带时区';

CREATE TABLE access_source (
    source_id varchar(128) PRIMARY KEY CHECK (source_id ~ '^[A-Za-z0-9_.-]+$'),
    credential_hash varchar(64) NOT NULL UNIQUE CHECK (credential_hash ~ '^[0-9a-f]{64}$'),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);
COMMENT ON TABLE access_source IS '独立业务来源；不具有任何人类角色或会话能力';
COMMENT ON COLUMN access_source.source_id IS '服务端认定的来源标识，不受请求source字段覆盖';
COMMENT ON COLUMN access_source.credential_hash IS '随机高熵服务凭据的SHA-256十六进制摘要';
COMMENT ON COLUMN access_source.enabled IS '每次请求核对的来源启用开关';
COMMENT ON COLUMN access_source.created_at IS '数据库生成的来源登记时间，带时区';

CREATE TABLE audit_record (
    id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT current_timestamp,
    subject_type varchar(16) NOT NULL CHECK (subject_type IN ('HUMAN', 'SOURCE', 'ANONYMOUS')),
    subject_id varchar(128) NOT NULL CHECK (length(subject_id) > 0),
    action varchar(64) NOT NULL CHECK (length(action) > 0),
    object_type varchar(64) NOT NULL CHECK (length(object_type) > 0),
    object_id varchar(128) NOT NULL CHECK (length(object_id) > 0),
    result varchar(16) NOT NULL CHECK (result IN ('SUCCESS', 'DENIED', 'FAILURE')),
    trace_id varchar(36) NOT NULL CHECK (length(trace_id) = 36)
);
CREATE INDEX audit_record_subject_time_idx ON audit_record(subject_type, subject_id, occurred_at);
CREATE INDEX audit_record_trace_idx ON audit_record(trace_id);
COMMENT ON TABLE audit_record IS '只追加结构化安全/业务审计；无凭据、载荷或异常正文列';
COMMENT ON COLUMN audit_record.id IS '独立审计记录UUID，与事件和执行ID分离';
COMMENT ON COLUMN audit_record.occurred_at IS '数据库生成的审计发生时间，带时区';
COMMENT ON COLUMN audit_record.subject_type IS '认证主体种类：人类、来源或匿名';
COMMENT ON COLUMN audit_record.subject_id IS '可信身份ID；匿名固定anonymous，不存失败提交用户名';
COMMENT ON COLUMN audit_record.action IS '受控业务动作标识，禁止原始请求正文';
COMMENT ON COLUMN audit_record.object_type IS '被操作的业务对象类型';
COMMENT ON COLUMN audit_record.object_id IS '受控对象标识，禁止令牌和任意请求参数';
COMMENT ON COLUMN audit_record.result IS '成功、拒绝或失败；不能用ACK代替业务成功';
COMMENT ON COLUMN audit_record.trace_id IS '服务端生成的请求关联UUID，不接受客户端覆盖';
