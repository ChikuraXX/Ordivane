CREATE TABLE execution_result_attempt (
 id uuid PRIMARY KEY, execution_id uuid NOT NULL REFERENCES recovery_execution(id),
 fencing_token bigint NOT NULL, claimed_at timestamptz NOT NULL,
 lease_until timestamptz NOT NULL, UNIQUE(execution_id,fencing_token)
);
CREATE TRIGGER immutable_result_attempt BEFORE UPDATE ON execution_result_attempt FOR EACH ROW EXECUTE FUNCTION be030_immutable();
COMMENT ON TABLE execution_result_attempt IS '结果领取与预算原子追加；无对应观测表示崩溃、过期或被取消，不删除旧领取';
COMMENT ON COLUMN execution_result_attempt.id IS '服务器生成的结果租约owner UUID，同时作为此次领取身份';
COMMENT ON COLUMN execution_result_attempt.execution_id IS '所属执行，不能迁移到新资格持有者';
COMMENT ON COLUMN execution_result_attempt.fencing_token IS '领取时的单调令牌，和观测表联合关联结果';
COMMENT ON COLUMN execution_result_attempt.claimed_at IS '数据库UTC领取时刻，崩溃也消耗预算';
COMMENT ON COLUMN execution_result_attempt.lease_until IS '此次固定租约到期点，后续接管不覆盖此历史';
