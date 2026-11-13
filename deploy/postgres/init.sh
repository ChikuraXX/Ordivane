#!/bin/sh
# 只在全新 Compose 数据卷首次初始化时创建两个独立数据库。
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  --set=platform_password="$ORDIVANE_PLATFORM_DB_PASSWORD" \
  --set=demo_password="$ORDIVANE_DEMO_DB_PASSWORD" <<'SQL'
CREATE ROLE ordivane_platform LOGIN PASSWORD :'platform_password';
CREATE ROLE ordivane_demo LOGIN PASSWORD :'demo_password';
CREATE DATABASE ordivane_platform OWNER ordivane_platform;
CREATE DATABASE ordivane_demo OWNER ordivane_demo;
REVOKE ALL ON DATABASE ordivane_platform FROM PUBLIC;
REVOKE ALL ON DATABASE ordivane_demo FROM PUBLIC;
SQL
