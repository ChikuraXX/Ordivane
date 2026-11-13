"""仅为本地开发创建专用角色和数据库；拒绝覆盖已存在对象。"""
import os
import secrets
from pathlib import Path

import psycopg
from psycopg import sql


def provision() -> None:
    """管理员连接由 PG* 环境提供，生成密码仅存于排除版本控制的 .env。"""
    root = Path(__file__).resolve().parent.parent
    env_path = root / '.env'
    if env_path.exists():
        raise RuntimeError('.env 已存在，拒绝覆盖；请先协调现有配置。')
    names = ('ordivane_platform', 'ordivane_demo')
    passwords = {name: secrets.token_urlsafe(32) for name in names}
    with psycopg.connect(autocommit=True) as connection:
        for name in names:
            if connection.execute('SELECT 1 FROM pg_roles WHERE rolname=%s', (name,)).fetchone():
                raise RuntimeError(f'角色 {name} 已存在，拒绝修改。')
            if connection.execute('SELECT 1 FROM pg_database WHERE datname=%s', (name,)).fetchone():
                raise RuntimeError(f'数据库 {name} 已存在，拒绝修改。')
        # 先落盘凭据，若数据库创建中途失败可据此协调；不自动删除数据库。
        with env_path.open('x', encoding='utf-8') as file:
            file.write('ORDIVANE_PG_HOST=127.0.0.1\nORDIVANE_PG_PORT=5432\n')
            file.write(f'ORDIVANE_PLATFORM_DB_PASSWORD={passwords[names[0]]}\n')
            file.write(f'ORDIVANE_DEMO_DB_PASSWORD={passwords[names[1]]}\n')
            file.write(f'ORDIVANE_MQ_PASSWORD={secrets.token_urlsafe(32)}\n')
            file.write('ORDIVANE_RABBITMQ_URI=amqp://ordivane:replace-me@127.0.0.1:5672/\n')
        for name in names:
            connection.execute(sql.SQL('CREATE ROLE {} LOGIN PASSWORD {}').format(
                sql.Identifier(name), sql.Literal(passwords[name])))
            connection.execute(sql.SQL('CREATE DATABASE {} OWNER {}').format(
                sql.Identifier(name), sql.Identifier(name)))
            connection.execute(sql.SQL('REVOKE ALL ON DATABASE {} FROM PUBLIC').format(sql.Identifier(name)))
            print(f'已创建独立数据库与角色：{name}')


if __name__ == '__main__':
    provision()
