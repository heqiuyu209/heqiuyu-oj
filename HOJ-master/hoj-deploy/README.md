# 本地 OJ 开发环境

目标：在真实的本地 HOJ 数据和接口上修改 UI，保存前端代码即可看到变化。入口为 **http://localhost:8080/home**。

开发入口使用 `compose.yaml` + `compose.dev.yaml`，并从已有本地容器识别、保留已启用的 RAG / 视频 / 重排 / 上下文覆盖文件。原 `compose.yaml` 仍负责生产镜像构建；开发配置只覆盖前端服务，MySQL、Redis、Nacos、业务后端与判题服务继续使用原有本地环境。

采集、校对和批量入库 Codeforces 题面，见 [CPOS 题面导入教程](#cpos-批量导入-codeforces-题面)。

## 启动、查看与停止

先启动 Docker Desktop（Linux containers），在 Git 根目录 `D:\IDEA\HOJ-master` 执行：

```powershell
# 启动现有 OJ 配置（已启用 RAG 时一并保留），等待前端与网站接口就绪
.\HOJ-master\hoj-deploy\dev.ps1 up

# 查看状态 / 查看前端日志（Ctrl+C 退出日志查看，不停止服务）
.\HOJ-master\hoj-deploy\dev.ps1 status
.\HOJ-master\hoj-deploy\dev.ps1 logs

# 停止 OJ，保留容器、数据库和依赖缓存
.\HOJ-master\hoj-deploy\dev.ps1 stop
```

如果 PowerShell 阻止执行本地脚本，可以只对此次命令使用：

```powershell
powershell -ExecutionPolicy Bypass -File .\HOJ-master\hoj-deploy\dev.ps1 up
```

需要 Docker Compose 2.24.4 以上；本机已核对为 2.40.3。脚本固定项目名 `hoj-master`，复用已有 `hoj-master_hoj-local-mysql-data-v2` 等卷。它使用 `--no-build`，不会悄悄重建 Java 镜像。前提是现有 `hoj-backend:local`、`hoj-judgeserver:local` 镜像和已初始化的数据库/Nacos 仍在；这份开发配置不是空电脑的数据库初始化流程。

第一次启动需要下载 Node 22 镜像、安装锁文件中的 Linux 依赖并编译前端。以后复用两个独立 Docker 卷：前端 `node_modules` 和 npm 缓存，不会把 Windows 的依赖目录拿进 Linux 使用。停止时不要使用 `down -v`，它会删除数据卷。

## 哪些修改能自动显示

| 修改内容 | 操作与效果 |
| --- | --- |
| `hoj-vue/src/` 内的 Vue、CSS、JS 和引用的图片 | 保存后自动重新编译，支持的模块直接热替换；某些模块会触发浏览器刷新。无需构建 Docker 镜像 |
| `hoj-vue/public/` 静态文件 | 开发服务监听变动，必要时刷新浏览器 |
| `hoj-scrollBoard/` 滚榜静态文件 | 直接读取本地文件，刷新 `/scrollBoard/` 查看 |
| `package.json` / `package-lock.json` | 执行下方 `restart-frontend`；启动时检测指纹，变化后按锁文件重新 `npm ci` |
| `vue.config.js`、环境变量或开发启动脚本 | 重启前端；修改 Compose 配置则重新运行 `dev.ps1 up` |
| Java 后端或 JudgeServer 源码 | 本轮没有启用 Java 自动编译/热重启，仍需编译并替换对应服务，见下方命令 |
| 数据库结构或 Nacos 配置 | 需要单独迁移/配置，不会因保存源码自动改变数据库 |

```powershell
.\HOJ-master\hoj-deploy\dev.ps1 restart-frontend
```

镜像本身不会随源文件改变。这里利用 [Docker bind mount](https://docs.docker.com/engine/storage/bind-mounts/) 把本地代码映射给开发容器，再由 Vue 开发服务器检测改动。源目录对容器只读，依赖缓存位于可写卷；Windows Docker Desktop 使用 1 秒轮询，避免文件事件丢失。容器仅发布到 `127.0.0.1`。

开发配置使用 [Compose 覆盖规则](https://docs.docker.com/reference/compose-file/merge/#replace-value) 删除前端的镜像构建步骤、替换端口映射，使原 `8080` 入口直接运行开发服务器。`CDUTETC_LOCAL_ASSETS=1` 使 UI 运行库来自本地依赖，减少 CDN 对本地预览的影响。

## 服务地址和数据

| 服务 | 本机地址 / 使用方式 |
| --- | --- |
| OJ（含原管理后台） | http://localhost:8080/home，使用原账号；管理员从头像菜单进入后台 |
| 独立滚榜静态页面 | http://localhost:8080/scrollBoard/ |
| 后端 API | http://localhost:6688；前端 `/api` 代理到 Docker 内的 `hoj-backend:6688` |
| Nacos | http://localhost:8848/nacos |
| MySQL | `127.0.0.1:13306`，沿用原本地配置 |
| Redis / 判题 / 沙箱 | Docker 内部访问，不增加宿主机端口 |

这里显示的是原数据库中的真实本地数据，空列表保留空态。按用户要求保留的蓝桥杯风格演示题、套卷与混合比赛，以及客观题、混合卷和站内 Rating 的实现与验证状态，统一见 [任务记录](../docs/docs/ai/TODO.md)。真实集成检查会创建随机临时账号并清理自己的数据，不重置原账号密码；AI 助教与知识库的实际实现和验收边界见上述任务记录。已有数据库需先执行相应 [增量迁移](../sqlAndsetting/README.md)，再更新后端镜像。

## 节省内存的镜像更新

修改 Java 后端、判题或已验收的 RAG 源码后，用统一入口更新。前端开发模式保存 Vue/CSS/JS 即可热更新，无需构建镜像。

```powershell
# 只读预检：识别完整覆盖文件，核对原镜像、数据卷与环境配置
.\HOJ-master\hoj-deploy\update.ps1 -Service hoj-backend,hoj-judgeserver -PauseRag -Plan

# 停止选中的 Java 服务并暂时暂停 RAG，逐个构建，成功后逐个替换并检查
.\HOJ-master\hoj-deploy\update.ps1 -Service hoj-backend,hoj-judgeserver -PauseRag

# 只更新后端；不带 PauseRag 时，知识库继续运行
.\HOJ-master\hoj-deploy\update.ps1 -Service hoj-backend
```

先停止服务就能释放运行内存。脚本停止全部选中服务后，按顺序一次构建一个镜像，再由 Compose 替换旧容器；不用手工删除容器。Java 构建默认 Maven 堆上限为768MiB；该值不是整个 BuildKit 或 Docker 的总内存上限。`-PauseRag` 会临时释放本地模型的运行内存，结束或失败后恢复原 RAG 容器并等待健康；暂停期间知识库暂不可用。

脚本保留命名数据卷和现有全部覆盖文件，禁止并发执行两个更新；不执行数据库迁移、删卷或缓存清理。构建失败会恢复原镜像标签和原容器运行状态，并停止后续构建；替换启动失败会尝试恢复旧镜像。恢复失败会明确报错，旧镜像以 `hoj-update-backup/...` 本地标签保留。停止的容器与镜像不占运行内存，备份标签共享镜像层。

此入口更新源码镜像；若环境变量或命名卷配置改变，预检会拒绝，须单独审查配置变更。构建可能在依赖缺失时访问软件仓库，但启动阶段使用 `--no-build --pull never`，不会隐式构建或拉取镜像。

RAG 更新使用 `-Service hoj-rag`，脚本实际构建共享镜像的 `hoj-rag-identity` 定义，仅替换 RAG 服务，保留身份容器及凭据卷。运行前须完成对应源码版本的质量和性能验收；本命令本身不代表未部署的 ONNX 候选已获资格。普通 `restart` 不会加载新镜像。

无需真实 Docker 的故障回归：

```powershell
.\HOJ-master\hoj-deploy\tests\update.Tests.ps1
```

## 切回生产构建预览

需要检查压缩后的生产产物时，在 Git 根目录执行：

```powershell
docker compose -p hoj-master -f .\HOJ-master\compose.yaml build hoj-frontend
# 确认构建成功后，使用基础配置替换前端
docker compose -p hoj-master -f .\HOJ-master\compose.yaml up -d --no-deps --no-build hoj-frontend
```

入口仍是 `8080`，但此时不再热更新。再次运行 `dev.ps1 up` 即回到开发模式。开发与生产前端不会同时占用这个端口。

## 验证与接续

2026-10-16：客观题及混合卷增量迁移、Java 8 后端和判题镜像更新已完成，前端保留开发模式。当前测试、真实账号联调、匿名 HTTP 与页面检查结果，以及待完成的用户验收，统一记录在 [任务记录](../docs/docs/ai/TODO.md)。以下表格保留 2026-10-14 的环境检查点。

2026-10-14：开发环境已启动，待用户实际登录验收。前端、后端、MySQL、Redis、Nacos、判题六个服务均运行；有健康探测的五个服务均为 healthy，后端以真实 API 响应核验。一键启动脚本退出码为 0。Git 提交由用户自行完成。

| 验证项 | 实际结果 |
| --- | --- |
| Compose 合并与脚本 | 配置校验通过；生产基础配置未改，前端开发映射仅 `127.0.0.1:8080 → 8066`；PowerShell / JavaScript 语法检查通过 |
| 真实匿名页面与接口 | 首页、题库、训练、比赛、讨论、团队可打开；网站配置、题库、语言接口返回 200；浏览器未捕获异常为 0 |
| 热更新 | 临时加入 CSS 属性后自动反映到浏览器，恢复后自动移除；两次都保留同一个页面状态，测试文件内容已恢复 |
| 滚榜路径 | `/scrollBoard/`、实际 JS 与 CSS 均返回 200 且类型正确；无斜杠路径 301 跳转，查询参数保留 |
| 判题服务连接与登记 | 后端容器请求判题 `/version` 返回 200；本次动态地址为 `172.18.0.6:8088`，与 Nacos 健康实例及数据库新登记一致；未把该地址写入配置 |
| 持久化与范围 | 复用原数据卷，没有删除卷或创建样例业务数据；Java 服务使用原有本地镜像；未重建/发布生产前端 |

真实页面与热更新的结构化证据见 [dev-review.json](dev-review.json)。数据库中此前的 `127.0.0.1:8088` 和 `172.18.0.7:8088` 登记仍保留，未擅自删除；失效记录清理属于后续部署维护工作。没有把登记一致性当作本轮 AC/WA/TLE 已重新验证。

本轮排查经验：Vue 开发服务对 `/home` 的页面回退依赖请求头，Node 健康探测应检查实际生成的 `/index.html`；滚榜静态资源通过当前 webpack-dev-server 的 `contentBase` 配置提供，不依赖项目未直接安装的 Express 包。

在 `HOJ-master/hoj-vue` 下可复查真实环境：

```powershell
npm run test:dev
# 额外验证热更新：临时添加一条不可见 CSS 属性，自动恢复，不写业务数据
npm run test:dev -- --hmr
```

此检查需要 Node 22 与 Chrome；默认访问 `http://127.0.0.1:8080`，可通过 `UI_REVIEW_URL` 修改。默认报告和首页截图输出到系统临时目录 `hoj-dev-review`，可通过 `DEV_REVIEW_OUTPUT` 修改。此命令不拦截 API、不创建测试用户、不提交题目；它和 `npm run test:browser` 的模拟接口 UI 回归用途不同。

混合试卷浏览器回归：`npm run test:paper` 拦截全部 API，验证混排、草稿、两种判题方式、组卷和手机布局；`PAPER_REVIEW_OUTPUT` 可指定报告/截图目录。真实本地集成另用 `$env:HOJ_LOCAL_PAPER_TEST='1'; npm run test:paper:local`，仅访问 `127.0.0.1:6688` 与本地 Compose 项目 `hoj-master`。它会创建两个随机临时账号，调用实际录题/组卷/练习/判题接口，验证普通编程 AC/WA/TLE，并在 finally 中清理自己的测试数据；不调整原有账号、题目或站点开关。运行前须已迁移混合卷结构并同时重建后端与 JudgeServer。报告区分通过与清理失败；失败时先查报告，不要反复执行。

页面验证、真实账号联调、用户验收和正式发布分别记录；当前结果见上述任务记录。原判题环境、原数据卷和权限规则继续沿用；没有升级 Java 或前端依赖版本，也没有开放到公网。

站内 Rating 浏览器回归：设置 `$env:PAPER_REVIEW_CASE='rating'` 后运行 `node tests/paper-browser.cjs`，使用隔离接口样例验证两种榜单、历史、搜索分页、数值区间和管理字段。真实 Rating 联调另用 `$env:HOJ_LOCAL_RATING_TEST='1'; node tests/rating-local-integration.cjs`；须先完成 `site-rating.sql` 迁移并更新 Java 服务。该脚本仅连接上述本地服务，创建三个随机临时账号和各自的题目、试卷、计分赛，等待后台交卷与 Rating 结算，验证重复通过仅计一次、升降分、题库筛选和个人主页，最后清理自己的数据；预计需要一至两分钟。`PAPER_REVIEW_OUTPUT` 指定报告目录，默认输出到系统临时目录 `hoj-rating-review`；报告中的 `validated` 和 `cleaned` 分别表示业务验证与清理结果。真实混合比赛回归使用 `$env:HOJ_LOCAL_MIXED_CONTEST_TEST='1'; node tests/mixed-contest-local-integration.cjs`，需要已有演示试卷 3；不设置 `HOJ_SEED_MIXED_DEMO` 时仅清理自身临时数据，不新增保留样例。

## CPOS 批量导入 Codeforces 题面

管理员在自己的 Windows 电脑上采集，登录目标 HOJ 后批量导入。每道待导入的新题需要在浏览器正常打开一次，已采集的题可以复用文件。同一批采集文件可以分别导入本机和云端 HOJ。学生在 HOJ 查看已开放的题面。

### 1. 安装采集扩展

1. 在 Windows 本机的 VS Code 按 `Ctrl+Shift+X`，搜索 `sohamaggarwal.cpos-vscode`，安装 **CPOS**。如果提示重新加载窗口，按提示操作。 [VS Code 扩展商店](https://marketplace.visualstudio.com/items?itemName=sohamaggarwal.cpos-vscode)
2. 在日常用来打开 Codeforces 的 Edge 或 Chrome 安装 **CPOS Companion**。Edge 若提示「允许来自其他商店的扩展」，选「允许」后添加扩展。 [浏览器扩展商店](https://chromewebstore.google.com/detail/cpos-companion/gjnbapmjonegeeamdeahcoojgokeogmm) · [Edge 安装说明](https://support.microsoft.com/zh-cn/edge/add-turn-off-or-remove-extensions-in-microsoft-edge)
3. 在 VS Code 选择 **文件 → 打开文件夹**，打开一个单独的采集工作目录，例如 `D:\CPOS-Capture`，并保持窗口打开。CPOS 会在工作目录生成代码模板。上述两个扩展即可完成题面采集。 [CPOS 快速开始](https://cpos.sohamaggarwal.com/docs#quick-start)

### 2. 采集一题并检查文件

1. 用安装了 Companion 的浏览器打开 [Codeforces 2290A 英文题面](https://codeforces.com/problemset/problem/2290/A?locale=en)。等英文正文和样例显示。若标签页是在安装扩展前打开的，刷新一次。
2. 等浏览器显示 `CPOS: Captured … → VS Code`，或在 VS Code 的 CPOS 面板中核对当前题的 **Statement** 和 **Tests**。用同样的方式打开这一批需要入库的其他题页。 [CPOS 浏览器采集说明](https://cpos.sohamaggarwal.com/docs#how-it-works)
3. 按 `Win+R`，输入 `%USERPROFILE%\.cpos-vscode`，确认有下面两个子目录。每题的文件在两个目录里使用同一个文件名。 [CPOS 缓存实现](https://github.com/Soham109/cpos/blob/main/extensions/vscode/src/extension.ts#L1456-L1482)

   ```text
   .cpos-vscode/
   ├── problems/    题面 JSON
   └── samples/     样例 JSON
   ```

### 3. 导入本地 HOJ

1. 本地 HOJ 已运行时，打开 [本机 HOJ](http://localhost:8080/home)。第一次搭站先完成 [数据库初始化](../sqlAndsetting/README.md)，已有本地实例按 [本地启动说明](#启动查看与停止) 启动。前后端都应包含 CPOS 导入功能（提交 `b4ca1be`）。如果本地后端需要更新，在 Git 根目录运行 `.\HOJ-master\hoj-deploy\update.ps1 -Service hoj-backend`。如果使用生产前端镜像，按 [生产前端构建说明](#切回生产构建预览) 更新前端。
2. 登录管理员账号，从头像菜单进管理后台。打开 **题库 → 远程题目 → Codeforces**，确认能搜到 `2290A`。如果目录里还没有这题，点 **更新目录**，等同步完成后再搜。
3. 在页面上方点 **从 CPOS 批量导入 → 选择采集目录**，选择包含 `problems` 和 `samples` 的整个 `.cpos-vscode` 目录。浏览器弹出目录上传确认时确认选择。核对可导入条数后点 **导入 N 条采集记录**。
4. 导入结束后，在 **原文与译文** 核对英文正文、输入输出、样例和时空限制。需要中文题面时，在这里校对并保存译文。新题入库后为「待检查 / 未公开」。确认内容后点 **检查与开放**，把权限设为公开并保存，再用普通账号打开题目确认可见。

只选一条新题记录试导时，结果应显示「导入 1」。如果该题已有原文，则会显示在「已有原文」计数中。每批导入使用所选目录中的配对记录。如果只想导入其中几题，可以另建临时目录，保留 `problems` 和 `samples` 两个子目录，并把目标题的同名文件分别复制进去。题面 JSON 的 `id` 字段可用于核对题号。

### 4. 部署到云端 HOJ 并导入

1. 云上初次安装 HOJ 时，从 [HOJ 部署入口](../README.md#二部署) 准备 Linux 基础服务，并按 [数据库初始化与升级说明](../sqlAndsetting/README.md) 初始化数据库。采集扩展和采集文件留在管理员电脑上。
2. 给云端部署同一版本的 HOJ 前端和后端，版本应包含 `5010557`（网页 HTML 导入）和 `b4ca1be`（CPOS 批量导入）。云端从 Git 更新时，先把这些提交发布到部署分支。CPOS 沿用现有题面表。对于已经运行、且从本仓库 Compose 构建前后端的站点，在云端包含 `compose.yaml` 的目录执行下列命令。站点使用了项目名、环境文件或生产覆盖文件时，在两条命令上沿用原来的 `-p`、`--env-file` 和 `-f` 参数。 [Docker Compose 更新说明](https://docs.docker.com/reference/cli/docker/compose/up/)

   ```bash
   docker compose -f compose.yaml up -d --build --no-deps hoj-backend hoj-frontend
   docker compose -f compose.yaml ps hoj-backend hoj-frontend
   ```

3. 在采集电脑上打开云端 HOJ 的 HTTPS 域名，按 [导入本地 HOJ](#3-导入本地-hoj) 的第 2 到第 4 步操作。网页文件选择框读取的是这台电脑的 `.cpos-vscode`。在云端题目目录核对新增原文、样例和开放权限，再用普通账号验证访问。

本地和云端分别写入各自的 MySQL。云端重新导入同一份 CPOS 采集文件，会保存英文原文和样例。本地已保存的译文、原文修订与开放状态随本地数据库保存。线上已有原文时会跳过该题。

### 常见问题

| 现象 | 处理 |
| --- | --- |
| CF 页面有正文，CPOS 没有采集 | 保持 Windows 本机的 VS Code 和采集工作目录打开。在 VS Code 按 `Ctrl+Shift+P`，运行 `CPOS: Start Capture Server`，再刷新题页。 [CPOS 排查说明](https://cpos.sohamaggarwal.com/docs#troubleshooting) |
| 找不到目录或提示缺少配对样例 | 按第二节检查采集是否完成，确认选中了包含两个子目录的根目录。缺失的题重新采集。 |
| 云端找不到题号或导入提示题目不在目录 | 在目标 HOJ 的 Codeforces 目录点「更新目录」，等题号出现后重试导入。 |
| HOJ 的「获取题面」显示 HTTP 403 | 从本机浏览器打开英文题面，等正文正常显示后采集，回 HOJ 使用「从 CPOS 批量导入」。这条路径使用采集文件，目标 HOJ 的 CF 目录仍需有该题。 |
| 找不到「从 CPOS 批量导入」 | 确认当前登录的是管理员账号，进入远程题目的 Codeforces 标签页，并核对前后端部署版本。 |

本地浏览器和 HTTP/MySQL 导入已用合成采集文件验证。真实 CF 采集和云端部署待按上述步骤验收。 [验证记录](../docs/docs/ai/TODO.md#codeforces-题面获取与-cpos-导入)

## 浏览器自动采集题面

1. 新站随 MySQL 初始化建表。已有站在各自 HOJ 数据库执行 [remote-capture.sql](../sqlAndsetting/remote-capture.sql)，然后部署包含浏览器采集队列的前后端版本。
2. 超管进入「后台 → 远程题目 → 浏览器采集队列」，打开自动导入。功能默认暂停。比赛开始前可在同一处暂停，待导入题面会留在队列里。
3. Chrome 或 Edge 加载 HOJCom 浏览器扩展。已有解压扩展可在扩展管理页重新加载。登录目标 HOJ，在「我的设置 → 浏览器题面采集」生成并复制完整密钥。打开扩展右上角的「设置」，将密钥粘贴到「连接 HOJ」，扩展会识别站点并显示连接状态。语言和本地采集文件夹也在同一个设置页。
4. 打开公开的 Codeforces 题面或带英文原文的 AtCoder 题面。HOJ 通过源站目录确认题号，并按 `CF-4A` 或 `AC-abc086_a` 检查题库。同题只接受一份待导入原文与样例。
5. 管理员在「浏览器采集队列」预览并导入，一批最多 30 题。通过的题以原文在题库开放。密钥支持在账号设置里更换和撤销。
