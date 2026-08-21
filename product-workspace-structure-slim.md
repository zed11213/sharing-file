# 产品 Workspace 目录结构设计

## 1. 背景与目标

一个产品通常同时包含需求、线框图、接口、数据库设计、多个代码仓库、项目工具，以及智能体产生的报告和日志。若这些内容在硬盘上平铺存放，容易出现：人或智能体难以判断当前任务属于哪个产品、把 PRD 与 backend 当作互不相关的项目、报告与临时文件污染源码仓库、多产品并行时检索到错误项目。

因此推荐以**产品**为单位组织 workspace，目标是：

1. **产品边界清晰**：一个 workspace 对应一个产品。
2. **仓库职责清晰**：文档、源码、工具、智能体产物各自归类。
3. **保持 multi-repo**：子目录继续保留独立 Git 仓库，workspace 只组织本地上下文。
4. **降低智能体误操作**：通过固定目录与入口文件约束行为。
5. **便于扩展**：后续可增加 mobile、miniapp、ops、test-cases 等目录。

## 2. 推荐目录结构

以 `good7ob` 产品为例：

```text
/home/ubuntu/workspaces/good7ob/
  WORKSPACE.md
  README.md
  .agentignore
  develop-rules/
  docs/
    prd/  wireframe/  api/  db/  architecture/  legacy-documents/
  source-code/
    backend/  front-web/  front-admin/
  tools/
    mcp/  cli/  scripts/
  agents/
    reports/  logs/  plans/  scratch/  artifacts/
```

各层级目录下建议各自放置 `README.md` 说明该目录的收录范围。

## 3. 目录职责

### 3.1 顶层

| 目录 | 职责 |
|---|---|
| `WORKSPACE.md` | 入口说明文件，定义产品边界、目录规则和智能体路由规则。 |
| `README.md` | 简要说明用途，并指向 `WORKSPACE.md`。 |
| `.agentignore` | 智能体忽略规则，减少检索噪音。 |
| `develop-rules/` | 开发规则、协作与编码规范、Git 规范、测试规范、AI 开发规则。 |
| `docs/` | 产品文档：PRD、线框图、API、DB、架构设计等。 |
| `source-code/` | 产品源码仓库。 |
| `tools/` | 产品专属 MCP、CLI、脚本。 |
| `agents/` | 智能体产生的报告、日志、计划、临时分析和产物。 |

### 3.2 `docs/`

| 目录 | 职责 |
|---|---|
| `prd/` | 当前有效的产品需求文档。 |
| `wireframe/` | 线框图、Figma 导出、画面清单、交互说明。 |
| `api/` | Workspace 级 API 文档、OpenAPI、接口契约；契约若由代码仓库维护，此处放链接。 |
| `db/` | Workspace 级数据库设计、ERD、表结构；迁移文件与实现级 schema 若在后端仓库，此处放链接。 |
| `architecture/` | 架构图、模块边界、技术决策、跨仓库技术设计。 |
| `legacy-documents/` | 历史合并文档仓库，仅供参考，非当前主文档入口。 |

### 3.3 `source-code/`

`backend/`（后端）、`front-web/`（用户端 Web）、`front-admin/`（管理后台）。

源码目录只放产品实现代码；智能体生成的分析报告、执行日志、临时计划一律写入 `agents/`。

### 3.4 `tools/`

`mcp/`（产品专属 MCP 工具与配置）、`cli/`（产品专属 CLI）、`scripts/`（workspace 级辅助脚本）。

全局共享工具不应放进单个产品 workspace，除非此处保存的是该产品专属配置。

### 3.5 `agents/`

`reports/`（最终报告、审查、调研）、`logs/`（执行日志，通常不提交）、`plans/`（实施与研究计划、任务拆解）、`scratch/`（临时分析与草稿，通常不提交）、`artifacts/`（截图、diff、导出文件）。

该目录的核心作用是隔离智能体产物，避免污染 PRD、wireframe 和源码仓库。

## 4. Multi-repo 原则与仓库映射

该 workspace 是 **multi-repo workspace**，不是 monorepo。每个子目录保持自己的远程仓库、分支、提交历史和 CI/CD 配置：

| 职责 | 路径 | Remote |
|---|---|---|
| 开发规则 | `develop-rules/` | `git@github.com:remo-studio/rules.git` |
| PRD | `docs/prd/` | `git@github.com:good7ob/prd.git` |
| Wireframe | `docs/wireframe/` | `git@github.com:good7ob/wireframe.git` |
| 历史文档 | `docs/legacy-documents/` | `git@github.com:good7ob/documents.git` |
| 后端源码 | `source-code/backend/` | `git@github.com:good7ob/backend.git` |
| 用户端前端 | `source-code/front-web/` | `git@github.com:good7ob/front-web.git` |
| 管理端前端 | `source-code/front-admin/` | `git@github.com:good7ob/front-admin.git` |

## 5. 智能体路由规则

智能体执行任务前，先读取根目录的 `WORKSPACE.md`，再按任务类型选择目标目录：

| 任务类型 | 目标目录 |
|---|---|
| 开发规则、规范、流程 | `develop-rules/` |
| 产品需求 | `docs/prd/` |
| 线框图、页面流、画面清单 | `docs/wireframe/` |
| API 文档 | `docs/api/` 或相关代码仓库 |
| 数据库设计 | `docs/db/` 或 `source-code/backend/db/` |
| 后端代码 | `source-code/backend/` |
| 用户端 Web 前端 | `source-code/front-web/` |
| 管理后台前端 | `source-code/front-admin/` |
| 项目工具 | `tools/` |
| 智能体报告、日志、计划 | `agents/` |

## 6. 防止智能体找错项目的机制

1. **产品级命名**：workspace 使用产品名，而非人名或临时目录名。
2. **固定入口文件**：`WORKSPACE.md` 明确产品边界与目录职责。
3. **文档与源码分离**：避免把文档仓库当代码仓库。
4. **产物隔离**：`agents/` 统一存放报告、日志、计划与临时产物。
5. **忽略规则减噪**：`.agentignore` 排除 `.git`、依赖与构建目录、日志与临时目录。
6. **multi-repo 边界独立**：不因统一目录破坏各仓库的 Git 边界。

推荐 `.agentignore`：

```gitignore
**/.git/
**/node_modules/
**/dist/
**/build/
**/.next/
**/coverage/
**/.turbo/
**/target/
**/.gradle/
**/.idea/
**/.vscode/
agents/logs/
agents/scratch/
agents/artifacts/tmp/
```

## 7. 使用建议

1. 新建产品时以产品名创建 workspace：`/home/ubuntu/workspaces/<product-name>/`。
2. 每个 workspace 必须包含 `WORKSPACE.md`、`README.md`、`.agentignore`。
3. 新增子仓库前，先判断它属于 `develop-rules/`、`docs/`、`source-code/`、`tools/` 还是 `agents/`。
4. 智能体产物默认写入 `agents/`，不写入 PRD、wireframe 或 source-code 仓库。
5. 不要强行合并成 monorepo，除非团队明确决定统一版本、CI/CD 和发布节奏。

## 8. 结论

```text
workspaces/<product-name>/
  develop-rules/  docs/  source-code/  tools/  agents/
```

该结构既保留多仓库独立性，又为人类和智能体提供清晰的产品上下文，适合 AI 辅助开发的产品 workspace 组织方式。
