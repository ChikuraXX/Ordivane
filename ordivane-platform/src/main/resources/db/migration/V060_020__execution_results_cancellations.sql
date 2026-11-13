ALTER TABLE recovery_execution DROP CONSTRAINT recovery_execution_status_check;
ALTER TABLE recovery_execution ADD CONSTRAINT recovery_execution_status_check CHECK(status IN
 ('QUEUED','DELIVERED_UNVERIFIED','RETURNED','NACK','UNKNOWN','BLOCKED','WAITING','MANUAL_REQUIRED','SUCCEEDED','CANCELLED'));
ALTER TABLE recovery_execution ADD COLUMN action varchar(32) NOT NULL DEFAULT 'REPLAY_ORIGINAL'
 CHECK(action IN ('REPLAY_ORIGINAL','VERIFY_ONLY','WAIT_AND_RECHECK','MANUAL_INTERVENTION'));
ALTER TABLE recovery_execution ADD COLUMN business_verified boolean NOT NULL DEFAULT false;
ALTER TABLE recovery_execution ADD COLUMN action_completed boolean NOT NULL DEFAULT false;
ALTER TABLE recovery_execution ADD COLUMN reason varchar(80) NOT NULL DEFAULT 'AWAITING_DISPATCH';
ALTER TABLE recovery_execution ADD COLUMN cancel_requested_at timestamptz;
ALTER TABLE recovery_execution ADD COLUMN cancelled_at timestamptz;
ALTER TABLE recovery_execution ADD COLUMN released_at timestamptz;
ALTER TABLE recovery_execution ADD COLUMN next_check_at timestamptz;
ALTER TABLE recovery_execution ADD COLUMN verification_deadline timestamptz;
ALTER TABLE recovery_execution ADD COLUMN verification_count integer NOT NULL DEFAULT 0 CHECK(verification_count>=0);
ALTER TABLE recovery_execution ADD COLUMN verification_limit integer NOT NULL DEFAULT 6 CHECK(verification_limit BETWEEN 1 AND 20);
ALTER TABLE recovery_execution ADD COLUMN result_owner uuid;
ALTER TABLE recovery_execution ADD COLUMN result_fence bigint NOT NULL DEFAULT 0;
ALTER TABLE recovery_execution ADD COLUMN result_lease_until timestamptz;
-- 已接受执行仍可能已发；单独建立查证期限，不沿用过期的授权期限。
UPDATE recovery_execution SET next_check_at=clock_timestamp(),verification_deadline=clock_timestamp()+interval '5 minutes';
ALTER TABLE execution_outbox DROP CONSTRAINT execution_outbox_state_check;
ALTER TABLE execution_outbox ADD CONSTRAINT execution_outbox_state_check CHECK(state IN ('PENDING','STARTED','DONE','BLOCKED','CANCELLED'));
CREATE TABLE execution_result_observation (
 id uuid PRIMARY KEY, execution_id uuid NOT NULL REFERENCES recovery_execution(id),
 fencing_token bigint NOT NULL, verified boolean NOT NULL, reason varchar(80) NOT NULL,
 content text NOT NULL, content_sha256 char(64) NOT NULL,
 observed_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(execution_id,fencing_token)
);
CREATE TABLE execution_timeline (
 id uuid PRIMARY KEY, execution_id uuid NOT NULL REFERENCES recovery_execution(id),
 kind varchar(40) NOT NULL, document text NOT NULL, recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER immutable_execution_result BEFORE UPDATE ON execution_result_observation FOR EACH ROW EXECUTE FUNCTION be030_immutable();
CREATE TRIGGER immutable_execution_timeline BEFORE UPDATE ON execution_timeline FOR EACH ROW EXECUTE FUNCTION be030_immutable();
ALTER TABLE incident DROP CONSTRAINT incident_status_check;
ALTER TABLE incident ADD CONSTRAINT incident_status_check CHECK(status IN ('OPEN','RESOLVED'));
ALTER TABLE incident ADD COLUMN resolved_at timestamptz;
ALTER TABLE incident ADD COLUMN resolved_execution_id uuid REFERENCES recovery_execution(id);
ALTER TABLE incident ADD COLUMN resolution_observation_id uuid REFERENCES execution_result_observation(id);
CREATE INDEX execution_result_due ON recovery_execution(next_check_at) WHERE next_check_at IS NOT NULL;
COMMENT ON TABLE execution_result_observation IS '不可变业务查询观测及范围时点；有限历史不能证明未执行';
COMMENT ON TABLE execution_timeline IS '执行、取消、核验及资格释放依据的追加历史';
COMMENT ON COLUMN recovery_execution.action IS '准确批准计划的动作；只读动作不占写资格、不创建Outbox';
COMMENT ON COLUMN recovery_execution.business_verified IS '处理记录、当前订单及当前权益正确关联后确认的业务结果';
COMMENT ON COLUMN recovery_execution.action_completed IS '动作结束独立于投递、业务成功与案件解决';
COMMENT ON COLUMN recovery_execution.reason IS '当前状态原因，原始观测保留在独立历史';
COMMENT ON COLUMN recovery_execution.cancel_requested_at IS '取消请求受理时间，不表示已撤回外部副作用';
COMMENT ON COLUMN recovery_execution.cancelled_at IS '证明未发送并失效租约后的实际取消时刻';
COMMENT ON COLUMN recovery_execution.released_at IS '资格安全释放时刻，依据见时间线';
COMMENT ON COLUMN recovery_execution.next_check_at IS '数据库持久调度UTC时点，空表示停止自动核验';
COMMENT ON COLUMN recovery_execution.verification_deadline IS '只读查证期限，独立于计划授权期限';
COMMENT ON COLUMN recovery_execution.verification_count IS '已领取只读查证次数，崩溃也计数以保证有界';
COMMENT ON COLUMN recovery_execution.verification_limit IS '本执行固定查证预算，1至20次';
COMMENT ON COLUMN recovery_execution.result_owner IS '服务器生成的查证租约持有者';
COMMENT ON COLUMN recovery_execution.result_fence IS '只读查证单调令牌，旧Worker不得覆盖终态';
COMMENT ON COLUMN recovery_execution.result_lease_until IS '查证租约到期UTC时间';
COMMENT ON COLUMN incident.resolved_at IS '充分业务证据下同事务解决的UTC时间';
COMMENT ON COLUMN incident.resolved_execution_id IS '解决案件的执行，取消、人工及等待本身不解决案件';
COMMENT ON COLUMN incident.resolution_observation_id IS '解决依据的不可变观测';
