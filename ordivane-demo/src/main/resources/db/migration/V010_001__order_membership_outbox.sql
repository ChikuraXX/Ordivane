CREATE TABLE orders (
    order_id varchar(100) PRIMARY KEY,
    entitlement_type varchar(40) NOT NULL CHECK (entitlement_type = 'MEMBERSHIP'),
    status varchar(16) NOT NULL CHECK (status IN ('CREATED', 'PAID', 'REFUNDED')),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE orders IS '演示订单；仅模拟状态，不处理资金';
COMMENT ON COLUMN orders.order_id IS '业务订单标识，独立于事件和恢复执行';
COMMENT ON COLUMN orders.entitlement_type IS '固定会员权益类型 MEMBERSHIP';
COMMENT ON COLUMN orders.status IS '创建、支付、退款状态；退款不可再次支付或开通';
COMMENT ON COLUMN orders.version IS '订单状态变化版本';
COMMENT ON COLUMN orders.created_at IS '创建时间';
COMMENT ON COLUMN orders.updated_at IS '最近状态变更时间';

CREATE TABLE outbox_event (
    event_id uuid PRIMARY KEY,
    order_id varchar(100) NOT NULL REFERENCES orders(order_id),
    body text NOT NULL CHECK (octet_length(body) <= 16384),
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (order_id)
);
CREATE INDEX outbox_event_pending ON outbox_event(created_at) WHERE published_at IS NULL;
COMMENT ON TABLE outbox_event IS '与支付同事务保存；确认并成功路由后标记发布，允许重复投递';
COMMENT ON COLUMN outbox_event.event_id IS '逻辑事件UUID，重发保持不变';
COMMENT ON COLUMN outbox_event.order_id IS '关联支付订单，每单一份支付事件';
COMMENT ON COLUMN outbox_event.body IS '不可变OrderPaid v1信封，最多16384字节';
COMMENT ON COLUMN outbox_event.published_at IS 'broker确认且可路由时间，不代表会员成功';
COMMENT ON COLUMN outbox_event.created_at IS '事务创建时间';

CREATE TABLE membership_entitlement (
    order_id varchar(100) NOT NULL REFERENCES orders(order_id),
    entitlement_type varchar(40) NOT NULL CHECK (entitlement_type = 'MEMBERSHIP'),
    event_id uuid NOT NULL REFERENCES outbox_event(event_id),
    granted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (order_id, entitlement_type)
);
COMMENT ON TABLE membership_entitlement IS '订单加权益类型为业务幂等键，原始和恢复操作共用';
COMMENT ON COLUMN membership_entitlement.order_id IS '权益所属订单';
COMMENT ON COLUMN membership_entitlement.entitlement_type IS '业务权益类型';
COMMENT ON COLUMN membership_entitlement.event_id IS '首次产生权益的逻辑事件';
COMMENT ON COLUMN membership_entitlement.granted_at IS '开通时间；退款不删除历史权益';

CREATE TABLE processing_record (
    attempt_id uuid PRIMARY KEY,
    event_id uuid NOT NULL,
    execution_id uuid,
    order_id varchar(100) NOT NULL REFERENCES orders(order_id),
    entitlement_type varchar(40) NOT NULL,
    origin varchar(16) NOT NULL CHECK (origin IN ('ORIGINAL', 'RECOVERY')),
    result varchar(32) NOT NULL CHECK (result IN ('GRANTED', 'ALREADY_GRANTED', 'REFUND_REJECTED')),
    processed_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((origin = 'ORIGINAL' AND execution_id IS NULL) OR (origin = 'RECOVERY' AND execution_id IS NOT NULL))
);
CREATE INDEX processing_record_event ON processing_record(event_id, processed_at);
CREATE INDEX processing_record_order ON processing_record(order_id, processed_at);
COMMENT ON TABLE processing_record IS '每次有效消费的业务结果，与权益在同一事务提交；异常不伪造成功';
COMMENT ON COLUMN processing_record.attempt_id IS '独立消费尝试UUID，重复投递保留观察';
COMMENT ON COLUMN processing_record.event_id IS '逻辑事件UUID，不作为业务效果唯一键';
COMMENT ON COLUMN processing_record.execution_id IS '授权恢复UUID，原始消费为空，不替代业务幂等键';
COMMENT ON COLUMN processing_record.order_id IS '处理目标订单';
COMMENT ON COLUMN processing_record.entitlement_type IS '处理目标权益类型';
COMMENT ON COLUMN processing_record.origin IS '原始或恢复传输来源';
COMMENT ON COLUMN processing_record.result IS '已开通、已有权益或退款拒绝，broker ACK不代表成功';
COMMENT ON COLUMN processing_record.processed_at IS '业务事务记录时间';
