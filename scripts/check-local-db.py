"""核实每个应用账号的库身份，并验证跨库连接被拒绝。"""
import os

import psycopg


def verify() -> None:
    """需要显式导入 .env；只读验证，不修改业务数据。"""
    names = ('ordivane_platform', 'ordivane_demo')
    for name, other in (names, tuple(reversed(names))):
        password = os.environ[f'ORDIVANE_{"PLATFORM" if name == names[0] else "DEMO"}_DB_PASSWORD']
        parameters = dict(host=os.environ.get('ORDIVANE_PG_HOST', '127.0.0.1'),
                          port=os.environ.get('ORDIVANE_PG_PORT', '5432'),
                          user=name, password=password, connect_timeout=5)
        with psycopg.connect(dbname=name, **parameters) as connection:
            actual = connection.execute('SELECT current_database(), current_user').fetchone()
            if actual != (name, name):
                raise RuntimeError(f'数据库身份不符：{actual}')
            print(f'PASS own database/user: {name}')
        try:
            with psycopg.connect(dbname=other, **parameters):
                raise RuntimeError(f'FAIL 跨库连接被允许：{name} -> {other}')
        except psycopg.OperationalError as error:
            # libpq 在建立连接阶段将 FATAL 权限拒绝包装为 OperationalError。
            if 'permission denied for database' not in str(error):
                raise
            print(f'PASS cross-database denied: {name} -> {other}')


if __name__ == '__main__':
    verify()
