CREATE TABLE incident_event (
 source varchar(128) NOT NULL, event_id uuid NOT NULL, body text NOT NULL,
 body_sha256 char(64) NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(source,event_id)
);
COMMENT ON TABLE incident_event IS '可信来源内不可变原始事件；内容冲突不得覆盖';
COMMENT ON COLUMN incident_event.body IS '严格白名单事件规范JSON，不含恢复授权头';
CREATE TABLE incident (
 id uuid PRIMARY KEY, source varchar(128) NOT NULL, event_id uuid NOT NULL,
 handler_id varchar(128) NOT NULL, status varchar(32) NOT NULL DEFAULT 'OPEN' CHECK(status='OPEN'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(source,event_id,handler_id), FOREIGN KEY(source,event_id) REFERENCES incident_event(source,event_id)
);
COMMENT ON TABLE incident IS '来源、逻辑事件和处理器唯一案件；本阶段不提供恢复状态迁移';
CREATE TABLE failure_observation (
 id uuid PRIMARY KEY, source varchar(128) NOT NULL, report_id uuid, observation_id uuid,
 incident_id uuid REFERENCES incident(id), event_id uuid, handler_id varchar(128) NOT NULL,
 kind varchar(32) NOT NULL, body text, body_sha256 char(64) NOT NULL,
 canonical_sha256 char(64), transport_key char(64), event_conflict boolean NOT NULL DEFAULT false,
 received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(source,report_id), UNIQUE(source,transport_key)
);
COMMENT ON TABLE failure_observation IS '不可变独立失败观察；格式失败或不可信原文不关联案件，仅留摘要';
COMMENT ON COLUMN failure_observation.body IS '仅已验证的脱敏报告或白名单事件；非法原文不保存';
COMMENT ON COLUMN failure_observation.transport_key IS '仅原文兜底使用队列、正文摘要和可信messageId的稳定摘要；无messageId时按正文折叠，非独立业务观察计数';
COMMENT ON COLUMN failure_observation.event_conflict IS '相同逻辑事件的正文冲突，保留观察且不覆盖有效原事件';
CREATE TABLE evidence_snapshot (
 id uuid PRIMARY KEY, incident_id uuid NOT NULL REFERENCES incident(id),
 source varchar(128) NOT NULL, collected_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 outcome varchar(32) NOT NULL CHECK(outcome IN ('COLLECTED','DEPENDENCY_FAILED')),
 content text NOT NULL, content_sha256 char(64) NOT NULL
);
COMMENT ON TABLE evidence_snapshot IS '采证每次新增不可变快照；来源、采集时间、规范内容和摘要可追溯';
CREATE INDEX incident_page_idx ON incident(created_at DESC,id DESC);
CREATE INDEX observation_incident_idx ON failure_observation(incident_id,received_at,id);
CREATE INDEX evidence_incident_idx ON evidence_snapshot(incident_id,collected_at DESC,id DESC);
CREATE FUNCTION be030_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'immutable snapshot'; END $$;
COMMENT ON FUNCTION be030_immutable() IS '禁止应用原地改写事件、观察和证据；精确测试清理允许DELETE';
CREATE TRIGGER immutable_event BEFORE UPDATE ON incident_event FOR EACH ROW EXECUTE FUNCTION be030_immutable();
CREATE TRIGGER immutable_observation BEFORE UPDATE ON failure_observation FOR EACH ROW EXECUTE FUNCTION be030_immutable();
CREATE TRIGGER immutable_evidence BEFORE UPDATE ON evidence_snapshot FOR EACH ROW EXECUTE FUNCTION be030_immutable();
