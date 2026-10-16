# 本地 OJ 开发环境

目标：在真实的本地 HOJ 数据和接口上修改 UI，保存前端代码即可看到变化。入口为 **http://localhost:8080/home**。

开发入口使用 `compose.yaml` + `compose.dev.yaml`。原 `compose.yaml` 仍负责生产镜像构建；开发配置只覆盖前端服务，MySQL、Redis、Nacos、业务后端与判题服务继续使用原有本地环境。

## 启动、查看与停止

先启动 Docker Desktop（Linux containers），在 Git 根目录 `D:\IdeaProjects\HOJ-master` 执行：

```powershell
# 启动全部六个 OJ 服务，等待前端与网站接口就绪
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

这里显示的是原数据库中的真实本地数据；训练、比赛、团队为空时显示空态，不灌入 UI 截图里的样例。没有创建账号、重置密码或改动历史提交。AI 与 rating 计算仍待实现；客观题已有独立业务后端，已有数据库需先执行 [增量迁移](../sqlAndsetting/README.md)，再更新后端镜像。当前验证与剩余验收见 [客观题任务记录](../docs/docs/ai/TODO.md)。

## 修改 Java 后更新单个服务

以下示例会先构建后端镜像，再替换后端容器；数据库与前端开发容器保持运行。只有修改对应源码后才需要执行。

```powershell
docker compose -p hoj-master -f .\HOJ-master\compose.yaml build hoj-backend
# 确认上一条构建成功后再执行
docker compose -p hoj-master -f .\HOJ-master\compose.yaml -f .\HOJ-master\compose.dev.yaml up -d --no-deps --no-build hoj-backend
```

更新判题服务时把两条命令中的 `hoj-backend` 改为 `hoj-judgeserver`，随后做实际判题验证。普通 `restart` 不会把新镜像装进旧容器。

## 切回生产构建预览

需要检查压缩后的生产产物时，在 Git 根目录执行：

```powershell
docker compose -p hoj-master -f .\HOJ-master\compose.yaml build hoj-frontend
# 确认构建成功后，使用基础配置替换前端
docker compose -p hoj-master -f .\HOJ-master\compose.yaml up -d --no-deps --no-build hoj-frontend
```

入口仍是 `8080`，但此时不再热更新。再次运行 `dev.ps1 up` 即回到开发模式。开发与生产前端不会同时占用这个端口。

## 验证与接续

2026-10-16：客观题增量迁移与 Java 8 后端镜像更新已完成，前端保留开发模式。权限修复、镜像版本、匿名 HTTP 与页面检查结果，以及待完成的真实账号业务验收，统一记录在 [客观题任务记录](../docs/docs/ai/TODO.md)。以下表格保留 2026-10-14 的环境检查点。

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

本轮启动与页面验证不代替真实登录、管理端写入及 AC/WA/TLE 判题回归。原判题环境、原数据卷和权限规则继续沿用；没有升级 Java 或前端依赖版本，也没有开放到公网。
