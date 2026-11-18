# Ordivane · 缀衡

**项目状态：初期开发阶段。** 当前版本用于本地开发、联调和演示。功能、接口和配置仍可能变化，暂不建议用于生产环境。

Ordivane 是一个面向 Java 业务系统的事件异常诊断与受控恢复平台。它接收失败事件并关联业务证据，支持制定恢复计划、审批、执行和结果核验。消息投递确认只代表消息送达相应环节，最终结果需要结合业务证据核验。

仓库包含两个独立运行的 Spring Boot 应用和一个 Vue 3 / TypeScript 前端。演示场景以订单支付后的会员开通为例，使用 PostgreSQL 保存业务状态，通过 RabbitMQ 传递事件。

## 项目结构

| 目录 | 用途 |
| --- | --- |
| [ordivane-platform](ordivane-platform/) | 平台后端，负责事件接入、案件与证据、恢复计划、审批、执行和结果核验。 |
| [ordivane-demo](ordivane-demo/) | 演示业务后端，模拟订单支付、会员开通及异常场景。 |
| [ordivane-web](ordivane-web/) | 管理界面，提供登录、案件列表与详情、恢复计划和执行详情。 |
| [contracts](contracts/) | API 定义和事件数据契约。 |
| [deploy](deploy/) | 本地 PostgreSQL 和 RabbitMQ 的 Docker Compose 配置。 |

平台启动时从配置根目录读取 [ordivane.yml](ordivane.yml)，用 [schemas](schemas/) 校验主配置和 [scenarios](scenarios/) 中的场景，再加载场景引用的 [prompts](prompts/) 和 [runbooks](runbooks/) 资源。

## 本地构建

准备 JDK 25、Node.js 和 npm。前端所用版本见 [package.json](ordivane-web/package.json)，后端的 Maven 版本由 Wrapper 固定。在仓库根目录使用 PowerShell 构建：

```powershell
./mvnw.cmd -B -DskipTests package
npm.cmd --prefix ordivane-web ci --ignore-scripts
npm.cmd --prefix ordivane-web run build
```

两个后端的 JAR 分别位于各自的 `target/`，前端产物位于 `ordivane-web/dist/`。以上命令用于打包，完整测试另行运行。

## 本地运行

参考 [.env.example](.env.example) 创建 `.env`，填写本地数据库、RabbitMQ 和演示 API 所需的值，将 `ORDIVANE_CONFIGURATION_ROOT` 设置为当前克隆的根目录。可使用以下命令启动本地依赖：

```powershell
docker compose --env-file .env -f deploy/compose.yml up -d --wait
```

在各后端终端中从仓库根目录运行 `. ./scripts/import-env.ps1`，再通过 `java -jar` 启动各自的 JAR。默认平台端口为 `8080`，演示服务为 `8081`。运行 `npm.cmd --prefix ordivane-web run dev` 可在 `http://127.0.0.1:5175` 打开前端。目前 [Vite 配置](ordivane-web/vite.config.ts) 将 `/api` 转发至 `127.0.0.1:18460`，联调时可在平台进程设置 `SERVER_PORT=18460`。

## 许可证

Ordivane 原创内容采用双许可，使用者可任选 [GNU GPL v3.0](LICENSES/GPL-3.0-only.txt) 或 [GNU LGPL v3.0](LICENSES/LGPL-3.0-only.txt)。

SPDX 表达式：`GPL-3.0-only OR LGPL-3.0-only`。

第三方组件（包括 Maven Wrapper）沿用各自的许可证和版权声明。
