CREATE TABLE recovery_execution (
 id uuid PRIMARY KEY,
 requester_id uuid NOT NULL REFERENCES access_user(id),
 idempotency_key varchar(128) NOT NULL,
 fingerprint varchar(64) NOT NULL,
 plan_id uuid NOT NULL REFERENCES recovery_plan(id),
 plan_version integer NOT NULL CHECK(plan_version>0),
 plan_digest varchar(64) NOT NULL,
 event_id uuid NOT NULL,
 business_key varchar(64) NOT NULL,
 transport_fingerprint varchar(64) NOT NULL,
 status varchar(32) NOT NULL CHECK(status IN ('QUEUED','DELIVERED_UNVERIFIED','RETURNED','NACK','UNKNOWN','BLOCKED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(requester_id,idempotency_key)
);
CREATE TABLE execution_eligibility (
 business_key varchar(64) PRIMARY KEY,
 execution_id uuid NOT NULL UNIQUE REFERENCES recovery_execution(id),
 conflicting_execution varchar(4) NOT NULL CHECK(conflicting_execution='PASS'),
 atomic_execution_eligibility varchar(4) NOT NULL CHECK(atomic_execution_eligibility='PASS'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE execution_outbox (
 execution_id uuid PRIMARY KEY REFERENCES recovery_execution(id),
 body text NOT NULL,
 exchange_name varchar(200) NOT NULL,
 routing_key varchar(100) NOT NULL,
 queue_name varchar(200) NOT NULL,
 transport_fingerprint varchar(64) NOT NULL,
 state varchar(16) NOT NULL CHECK(state IN ('PENDING','STARTED','DONE','BLOCKED')),
 owner uuid,
 fencing_token bigint NOT NULL DEFAULT 0 CHECK(fencing_token>=0),
 lease_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE execution_attempt (
 id uuid PRIMARY KEY,
 execution_id uuid NOT NULL REFERENCES execution_outbox(execution_id),
 fencing_token bigint NOT NULL,
 outcome varchar(24) NOT NULL CHECK(outcome IN ('UNKNOWN','ACK','RETURNED','NACK','CONFIRM_TIMEOUT','CONNECTION_LOST')),
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 finished_at timestamptz,
 UNIQUE(execution_id,fencing_token)
);
CREATE INDEX execution_outbox_pending ON execution_outbox(created_at) WHERE state='PENDING';
COMMENT ON TABLE recovery_execution IS '持久执行身份与准确计划/调用人/参数/传输指纹；不是业务成功记录';
COMMENT ON TABLE execution_eligibility IS '可信业务资源唯一保留资格；UNKNOWN及未核验投递不得释放';
COMMENT ON TABLE execution_outbox IS '与资格同事务待发原始事件；STARTED提交后永不自动重放';
COMMENT ON TABLE execution_attempt IS '发送尝试独立UUID；发布开始先UNKNOWN，broker确认不表示业务成功';
COMMENT ON COLUMN recovery_execution.id IS '执行UUID，与原支付eventId和每次attemptId分离';
COMMENT ON COLUMN recovery_execution.requester_id IS '创建时已鉴权调用人；Worker必须重新查当前数据库权限';
COMMENT ON COLUMN recovery_execution.idempotency_key IS '调用人域内幂等键，1至128字符';
COMMENT ON COLUMN recovery_execution.fingerprint IS '调用人、资源、准确版本、动作规范参数、配置、有效传输的SHA256';
COMMENT ON COLUMN recovery_execution.plan_id IS '不可变计划版本UUID';
COMMENT ON COLUMN recovery_execution.plan_version IS '准确计划版本正整数';
COMMENT ON COLUMN recovery_execution.plan_digest IS '批准绑定的计划摘要';
COMMENT ON COLUMN recovery_execution.event_id IS '原始支付事实UUID，恢复不改变';
COMMENT ON COLUMN recovery_execution.business_key IS '可信事件源、订单、权益与处理器规范JSON的SHA256，不使用planId或eventId替代';
COMMENT ON COLUMN recovery_execution.transport_fingerprint IS '已固定传输映射快照摘要';
COMMENT ON COLUMN recovery_execution.status IS '投递阶段；没有SUCCEEDED，必须由后继另核验业务';
COMMENT ON COLUMN recovery_execution.created_at IS '数据库UTC创建时刻';
COMMENT ON COLUMN execution_eligibility.business_key IS '原子唯一业务写资格';
COMMENT ON COLUMN execution_eligibility.execution_id IS '资格持有者，未收敛不得另建绕过';
COMMENT ON COLUMN execution_eligibility.conflicting_execution IS 'CONFLICTING_EXECUTION真实唯一占用检查结果';
COMMENT ON COLUMN execution_eligibility.atomic_execution_eligibility IS 'ATOMIC_EXECUTION_ELIGIBILITY同事务提交结果';
COMMENT ON COLUMN execution_eligibility.created_at IS '资格建立UTC时刻';
COMMENT ON COLUMN execution_outbox.execution_id IS '同事务执行外键';
COMMENT ON COLUMN execution_outbox.body IS '严格验证的原始OrderPaid规范JSON，不是FailureReport';
COMMENT ON COLUMN execution_outbox.exchange_name IS '服务端固定exchange快照';
COMMENT ON COLUMN execution_outbox.routing_key IS '服务端固定routing key快照';
COMMENT ON COLUMN execution_outbox.queue_name IS '固定目标队列快照，客户端不可覆盖';
COMMENT ON COLUMN execution_outbox.transport_fingerprint IS '实际传输快照摘要，重启更改拒旧待发';
COMMENT ON COLUMN execution_outbox.state IS 'PENDING安全接管；STARTED可能已发；DONE仅确认结束；BLOCKED发送前拒绝';
COMMENT ON COLUMN execution_outbox.owner IS '领取者UUID，未领取为空';
COMMENT ON COLUMN execution_outbox.fencing_token IS '每次接管单调递增令牌，条件回写拒旧持有者';
COMMENT ON COLUMN execution_outbox.lease_until IS '数据库UTC租约，未领取为空；过期不证明副作用未发生';
COMMENT ON COLUMN execution_outbox.created_at IS '待发创建UTC时刻';
COMMENT ON COLUMN execution_attempt.id IS '独立attemptId，每次发送UUID';
COMMENT ON COLUMN execution_attempt.execution_id IS '持久执行UUID';
COMMENT ON COLUMN execution_attempt.fencing_token IS '发送时租约令牌';
COMMENT ON COLUMN execution_attempt.outcome IS '分别记录ACK、return、NACK、超时和连接丢失；UNKNOWN待核验';
COMMENT ON COLUMN execution_attempt.started_at IS '外部发送前先提交的UTC时刻';
COMMENT ON COLUMN execution_attempt.finished_at IS '持久确认UTC时刻；崩溃未回写为null';
