CREATE TABLE failure_report (
    report_id uuid PRIMARY KEY,
    observation_id uuid NOT NULL UNIQUE,
    source varchar(128) NOT NULL,
    event_id uuid,
    body text NOT NULL CHECK (octet_length(body) <= 32768),
    topology_prefix varchar(210) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    http_status varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (http_status IN ('PENDING','SENT','STOPPED')),
    http_attempts integer NOT NULL DEFAULT 0 CHECK (http_attempts >= 0),
    http_next_at timestamptz NOT NULL DEFAULT now(),
    http_lease_until timestamptz,
    http_owner uuid,
    dead_status varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (dead_status IN ('PENDING','SENT','STOPPED')),
    dead_attempts integer NOT NULL DEFAULT 0 CHECK (dead_attempts >= 0),
    dead_next_at timestamptz NOT NULL DEFAULT now(),
    dead_lease_until timestamptz,
    dead_owner uuid
);
CREATE INDEX failure_report_http_pending ON failure_report(topology_prefix,http_next_at) WHERE http_status='PENDING';
CREATE INDEX failure_report_dead_pending ON failure_report(topology_prefix,dead_next_at) WHERE dead_status='PENDING';
COMMENT ON TABLE failure_report IS '不可变失败观察与HTTP/死信持久待发；确认后保留，停止需人工核验';
COMMENT ON COLUMN failure_report.report_id IS '传输去重UUID；重试、重启保持不变';
COMMENT ON COLUMN failure_report.observation_id IS '独立消费失败观察UUID，非业务幂等键';
COMMENT ON COLUMN failure_report.source IS '与BE-020来源凭据绑定的报告来源';
COMMENT ON COLUMN failure_report.event_id IS '严格事件信封中的逻辑ID；非法消息为NULL';
COMMENT ON COLUMN failure_report.body IS '固定FailureReport v1 JSON；不保存原始异常/任意消息头或非法正文';
COMMENT ON COLUMN failure_report.topology_prefix IS '生成报告的演示实例拓扑；发送器仅处理本前缀';
COMMENT ON COLUMN failure_report.created_at IS '数据库接收失败观察时间';
COMMENT ON COLUMN failure_report.http_status IS 'HTTP待发/已确认/停止，停止不删除';
COMMENT ON COLUMN failure_report.http_attempts IS 'HTTP网络动作前原子占用预算，崩溃也计数';
COMMENT ON COLUMN failure_report.http_next_at IS 'HTTP下次允许时间，毫秒基数指数退避，指数最多32倍';
COMMENT ON COLUMN failure_report.http_lease_until IS 'HTTP发送租约，崩溃后到期可重领';
COMMENT ON COLUMN failure_report.http_owner IS '每次HTTP领取UUID，过期持有者不能覆盖新结果';
COMMENT ON COLUMN failure_report.dead_status IS '死信待发/已确认/停止；确认不是业务恢复';
COMMENT ON COLUMN failure_report.dead_attempts IS '死信发布前原子占用预算';
COMMENT ON COLUMN failure_report.dead_next_at IS '死信下次允许时间，指数退避';
COMMENT ON COLUMN failure_report.dead_lease_until IS '死信发布租约';
COMMENT ON COLUMN failure_report.dead_owner IS '每次死信领取UUID，防止过期更新';
