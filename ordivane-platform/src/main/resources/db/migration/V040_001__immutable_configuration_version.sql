-- 配置版本仅保存非秘密有效快照；不修改已应用迁移或业务计划。
CREATE TABLE configuration_version (
    digest varchar(64) PRIMARY KEY CHECK (digest ~ '^[a-f0-9]{64}$'),
    snapshot text NOT NULL CHECK (jsonb_typeof(snapshot::jsonb) = 'object'),
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);
COMMENT ON TABLE configuration_version IS '启动加载的非秘密不可变配置版本，不代表执行授权';
COMMENT ON COLUMN configuration_version.digest IS '规范UTF-8 JSON的SHA-256；不含凭据及凭据摘要';
COMMENT ON COLUMN configuration_version.snapshot IS '排序对象键、保留类型的完整非秘密规范JSON文本';
COMMENT ON COLUMN configuration_version.created_at IS '首次登记时间，UTC时间点；重启复用不更新';
CREATE FUNCTION reject_configuration_version_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'configuration versions are immutable';
END;
$$;
COMMENT ON FUNCTION reject_configuration_version_mutation() IS '禁止更新/删除/清空历史配置快照';
CREATE TRIGGER configuration_version_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON configuration_version
    FOR EACH STATEMENT EXECUTE FUNCTION reject_configuration_version_mutation();
