CREATE TABLE recovery_plan (
 id uuid PRIMARY KEY,
 incident_id uuid NOT NULL REFERENCES incident(id),
 version integer NOT NULL CHECK(version>0),
 previous_id uuid REFERENCES recovery_plan(id),
 requester_id uuid NOT NULL REFERENCES access_user(id),
 configuration_digest varchar(64) NOT NULL REFERENCES configuration_version(digest),
 event_digest varchar(64) NOT NULL,
 action_digest varchar(64) NOT NULL,
 digest varchar(64) NOT NULL,
 document text NOT NULL,
 created_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL CHECK(expires_at>created_at),
 UNIQUE(incident_id,version)
);
CREATE TABLE recovery_precheck (
 id uuid PRIMARY KEY,
 plan_id uuid NOT NULL REFERENCES recovery_plan(id),
 document text NOT NULL,
 passed boolean NOT NULL,
 created_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL
);
CREATE TABLE recovery_approval_request (
 plan_id uuid PRIMARY KEY REFERENCES recovery_plan(id),
 precheck_id uuid NOT NULL UNIQUE REFERENCES recovery_precheck(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE recovery_approval_decision (
 plan_id uuid PRIMARY KEY REFERENCES recovery_approval_request(plan_id),
 approver_id uuid NOT NULL REFERENCES access_user(id),
 decision varchar(8) NOT NULL CHECK(decision IN ('APPROVE','REJECT')),
 digest varchar(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX recovery_precheck_plan_time ON recovery_precheck(plan_id,created_at DESC,id DESC);
CREATE FUNCTION be050_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'immutable recovery record'; END $$;
COMMENT ON FUNCTION be050_immutable() IS '计划、检查、审批只追加；不得改写或删除历史版本';
CREATE TRIGGER recovery_plan_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON recovery_plan FOR EACH STATEMENT EXECUTE FUNCTION be050_immutable();
CREATE TRIGGER recovery_precheck_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON recovery_precheck FOR EACH STATEMENT EXECUTE FUNCTION be050_immutable();
CREATE TRIGGER recovery_request_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON recovery_approval_request FOR EACH STATEMENT EXECUTE FUNCTION be050_immutable();
CREATE TRIGGER recovery_decision_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON recovery_approval_decision FOR EACH STATEMENT EXECUTE FUNCTION be050_immutable();
COMMENT ON TABLE recovery_plan IS '同案递增不可变计划版本；不存执行或消息，版本唯一约束配合案件行锁';
COMMENT ON TABLE recovery_precheck IS '不可变检查结果；通过只允许申请审批，不授予执行资格';
COMMENT ON TABLE recovery_approval_request IS '每版本至多一次申请，绑定准确预检查';
COMMENT ON TABLE recovery_approval_decision IS '每版本至多一个他人决定，主键拒绝并发重复审批';
COMMENT ON COLUMN recovery_plan.id IS '计划版本UUID，与事件和未来执行身份分离';
COMMENT ON COLUMN recovery_plan.incident_id IS '可信案件外键';
COMMENT ON COLUMN recovery_plan.version IS '同案连续递增版本号，从1开始';
COMMENT ON COLUMN recovery_plan.previous_id IS '上一版本UUID，首版本为空';
COMMENT ON COLUMN recovery_plan.requester_id IS '不可变申请人UUID，自审判断不受其后续角色变化影响';
COMMENT ON COLUMN recovery_plan.configuration_digest IS '已登记非秘密配置快照摘要';
COMMENT ON COLUMN recovery_plan.event_digest IS '可信不可变原事件摘要';
COMMENT ON COLUMN recovery_plan.action_digest IS '动作与严格参数规范JSON的SHA256';
COMMENT ON COLUMN recovery_plan.digest IS 'document规范JSON的SHA256，服务器生成';
COMMENT ON COLUMN recovery_plan.document IS '包含版本、动作、证据引用及期限的不可变规范JSON';
COMMENT ON COLUMN recovery_plan.created_at IS '创建UTC时刻';
COMMENT ON COLUMN recovery_plan.expires_at IS '配置TTL确定的失效UTC时刻';
COMMENT ON COLUMN recovery_precheck.id IS '独立检查UUID';
COMMENT ON COLUMN recovery_precheck.plan_id IS '准确计划版本UUID';
COMMENT ON COLUMN recovery_precheck.document IS '检查原因、事实和绑定摘要；不含凭据';
COMMENT ON COLUMN recovery_precheck.passed IS '本阶段检查通过，不包含未来冲突执行检查';
COMMENT ON COLUMN recovery_precheck.created_at IS '检查开始UTC时刻';
COMMENT ON COLUMN recovery_precheck.expires_at IS '不晚于计划期限且至多60秒';
COMMENT ON COLUMN recovery_approval_request.plan_id IS '唯一申请所属不可变版本';
COMMENT ON COLUMN recovery_approval_request.precheck_id IS '申请时最近有效通过的检查';
COMMENT ON COLUMN recovery_approval_request.created_at IS '数据库申请UTC时刻';
COMMENT ON COLUMN recovery_approval_decision.plan_id IS '唯一决定对应申请';
COMMENT ON COLUMN recovery_approval_decision.approver_id IS '持久化他人审批主体UUID';
COMMENT ON COLUMN recovery_approval_decision.decision IS 'APPROVE或REJECT，不表示业务成功';
COMMENT ON COLUMN recovery_approval_decision.digest IS '准确被审计划摘要';
COMMENT ON COLUMN recovery_approval_decision.created_at IS '数据库决定UTC时刻';
