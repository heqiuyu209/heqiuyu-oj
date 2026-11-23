# CDUTETC 前端

成理工程在线测评平台，基于 HOJ 的 Vue 2 前端迭代。保留原有路由、权限、编程判题和管理端录题流程；在题目页提供可调整的学习工作台。

源码仓库：[heqiuyu209/heqiuyu-oj](https://github.com/heqiuyu209/heqiuyu-oj)。实现范围、字段约定、验证证据与后续事项见 [UI 实现记录](UI-IMPLEMENTATION.md)。

首页、训练、比赛、讨论、团队已适配统一卡片布局。查看 [五页深浅色 / 手机截图预览](review/cards-preview.html)（下载仓库后用浏览器打开），或 [截图索引](review/README.md)。截图使用测试数据，实际页面继续使用原 HOJ 接口。

## 本地运行

推荐使用 Docker 热更新环境，在 Git 根目录执行 `.\HOJ-master\hoj-deploy\dev.ps1 up`，打开 **http://localhost:8080/home**。保存 Vue/CSS/JS 后自动更新，不需要重建前端镜像。完整启停、依赖更新与切回生产预览的说明见 [本地 OJ 开发环境](../hoj-deploy/README.md)。

页面已更新却看不到过渡动画时，登录后打开「头像 → 主题与布局 → 动画效果 → 完整动画」，导航栏选择「悬浮玻璃」。未登录时使用导航右侧的外观按钮。默认「跟随系统」会遵循电脑的减少动态效果设置；「完整动画」只覆盖本站，不修改系统设置，刷新后保留。主题过渡还需要浏览器支持 View Transitions；不支持时直接切换颜色。

以下是直接在 Windows 运行前端的可选方式；如果已经使用 Docker 开发入口，无需重复启动：

在本目录执行。沿用 lockfile，不需要新增依赖。

```powershell
npm ci
$env:NODE_OPTIONS='--openssl-legacy-provider'
$env:CDUTETC_LOCAL_ASSETS='1'
npm run serve -- --host 127.0.0.1 --port 8066
```

访问 `http://127.0.0.1:8066/home`。`/api` 默认代理到 `http://localhost:6688`，需要已有 HOJ 后端。`CDUTETC_LOCAL_ASSETS=1` 从已安装的依赖打包 UI 运行库，适合本机验收；没有开启此开关时仍使用原有 CDN 配置。它不是离线数据模式。

Node 22 使用上述 OpenSSL 兼容参数。项目没有在本次 UI 改动中升级 Vue、Webpack 或依赖版本。

## 构建与验证

```powershell
npm run test:ui
$env:NODE_OPTIONS='--openssl-legacy-provider'
Remove-Item Env:CDUTETC_LOCAL_ASSETS -ErrorAction SilentlyContinue
npm run build
```

`test:ui` 检查缺失 rating、来源兼容、安全链接和草稿上下文隔离。生产输出在 `dist/`；构建不会更新正在运行的 Docker 前端。

个人页能力分布支持综合覆盖与本站 / CF / AtCoder / 牛客来源切换。综合视图只合并明确题源编号和可靠目录映射的题数，难度按平台分别展示，缺分类不绘制零值能力图。公开接口为 `/api/learning/users/{uid}/evidence`；原本站 `/profile` 保持原定义。AI 默认不附带画像，勾选画像默认只发送本站范围，外部画像需另行勾选。隔离浏览器可选 `PAPER_REVIEW_CASE=learning`。真实本地 MySQL / HTTP 验收需显式设置 `HOJ_LOCAL_LEARNING_EVIDENCE_TEST=1` 后运行 `node tests/learning-evidence-local-integration.cjs`，另以 `HOJ_LOCAL_LEARNING_TEST=1` 运行 `node tests/learning-local-integration.cjs` 检查旧能力 / 标签和客观题开放策略。脚本创建并清理命名临时记录，不进行真实平台认证或外部代码投递；需已有当前增量表及本地来源目录。

本人个人页的“下一组训练”按来源评级、知识方向和已尝试记录生成最多六个候选，规则推荐不需要模型。点击“让 AI 解释推荐”才会发送候选与推荐依据至本人配置的模型，服务端只接受既有题目的解释和排序；完整画像仍由勾选项控制。接口、范围与限制见 [推荐接口](../docs/docs/ai/08-data-model-and-api.md#当前已实现训练候选与模型解释2026-10-19)。隔离页面检查设置 `PAPER_REVIEW_CASE=recommendations` 后运行 `node tests/paper-browser.cjs`，使用 `UI_REVIEW_URL` / `PAPER_REVIEW_OUTPUT` 指定页面与证据位置。真实本地验证设置 `HOJ_LOCAL_RECOMMENDATION_TEST=1` 后运行 `node tests/recommendation-local-integration.cjs`：仅创建并清理随机测试用户及其记录，不调用模型或向源站提交代码；需已有本地后端、MySQL 和三平台目录。

启动上面的本地开发服务后，可运行隔离浏览器验收：

```powershell
$env:UI_REVIEW_URL='http://127.0.0.1:8066'
$env:UI_REVIEW_OUTPUT=Join-Path $env:TEMP 'cdutetc-ui-review'
# Chrome 不在默认 Windows 安装位置时指定 CHROME_PATH。
npm run test:browser
```

需要 Node 22 的内置 WebSocket 和已安装的 Chrome。脚本用全新的临时浏览器资料，拦截所有 `/api/*` 请求并返回固定测试数据，不向真实后端提交代码或修改账号。输出 JSON 和页面截图，用于 UI 回归；不代表真实登录、同步或判题已通过。

客观题专项使用 `npm run test:objective`（或 `node tests/objective-browser.cjs`），同样要求上述本地开发服务、Node 22 和 Chrome，所有 API 使用隔离样例。使用 `UI_REVIEW_URL` 指定页面地址，`OBJECTIVE_REVIEW_OUTPUT` 指定 JSON 与截图目录；默认写入临时目录 `hoj-objective-review`。覆盖单选/多选/判断、整卷保存修改与交卷、确认期间切换记录/取消、错题复习、管理端独立开放策略、账号切换、手机布局及后台菜单跳转。

列表页的隔离数据和检查在 `tests/collection-fixtures.cjs`、`tests/collection-review.cjs`，由同一浏览器命令执行；覆盖五页双主题和手机布局、平板双列、长内容、分页筛选、加载失败重试及原登录门槛。测试文件不进入应用运行时。

外观检查 `tests/appearance-review.cjs` 同样由上述浏览器命令执行：覆盖通知旁一键主题扩散、头像外观面板、效果开关、比赛自动简化与恢复、设置持久化、手机边界、事件清理和 4 倍 CPU 降速下的功能；记录外观操作接口请求与静止采样，不等同于生产并发压测。

管理端主题专项检查使用 `node tests/admin-theme-review.cjs`。先通过临时环境变量 `HOJ_REVIEW_USERNAME`、`HOJ_REVIEW_PASSWORD` 提供本地管理员凭据，不要写入仓库。默认访问 `http://127.0.0.1:8080`，可用 `UI_REVIEW_URL` 修改；截图与 JSON 默认写入临时目录 `hoj-admin-review`，可用 `ADMIN_REVIEW_OUTPUT` 修改。脚本允许真实登录和原有只读会话查询，拦截其他 API 写入；仅在隔离浏览器中修改表单展示值，不保存。覆盖深浅主题、通知提示、开关、编辑器、弹层、手机菜单、前后台主题隔离、校徽加载页及减少动态效果。

导航与主题动效专项检查：`node tests/motion-review.cjs`，默认访问真实本地服务 `http://127.0.0.1:8080`，也可用 `UI_REVIEW_URL` 修改。需要 Node 22+ 和 Chrome，使用隔离访客会话，不替换接口响应或执行业务写入。覆盖双向按钮抛物线过渡、连续请求、键盘、系统跟随、减少动画及浏览器回退、导航收缩幅度/双阈值/悬停/焦点、桌面和手机断点及触屏横屏；通过临时条纹背景的像素采样检查真实玻璃模糊。JSON 与截图默认写入临时目录 `hoj-motion-review`，可用 `MOTION_REVIEW_OUTPUT` 指定。中间帧按实际 1400ms 过渡速度截取，未放慢浏览器播放。

## 使用入口

- 通知旁的太阳 / 月亮按钮：单击直接切换深浅主题，扩散从该按钮中心开始；使用快捷按钮后保存为明确的深色或浅色主题。
- 头像 → 主题与布局：主题跟随系统、标准效果 / 简洁模式、导航模式、卡片柔光、首页液态玻璃、菜单变形、紧凑间距和动画偏好。未登录时保留外观按钮；手机使用相同设置。
- 默认开启比赛中自动简化：进入带比赛 ID 的比赛页面（包括补题）使用普通导航，暂停主题扩散、菜单变形、柔光与玻璃模糊；离开后恢复原选择。用户可关闭自动简化；它不会修改工作台草稿、角色或比赛业务规则。
- 首页：保留参赛照片展示位、最近一周过题榜、公告、提交统计、近期比赛、最新题目和远程 OJ；训练/比赛/团队按屏幕显示三列、双列或单列卡片，讨论保持便于阅读的横向卡片。
- 打开题目：简洁双栏 / 学习模式、拖动卡片标题、调整分栏宽度和卡片高度、左右互换、展开 / Esc 恢复、重置布局。
- 编程区：语言列表仍由 HOJ 后端提供，自测在同一卡片内；草稿按账号、团队、训练、比赛、题目和语言隔离。
- 客观题：题库 → 客观题练习（`/objective`）；管理端 → 题目管理 → 客观题与组卷（`/admin/objective`）。需要先执行 `sqlAndsetting/objective.sql` 并更新业务后端，具体规则与待验收状态见 [客观题接口说明](../docs/docs/ai/08-data-model-and-api.md#6-客观题前后端同步开发)。
- 右下角 AI 助教：本人配置模型 API 地址、模型名与 Key 后可提问，密钥由后端加密保存。正式参赛或编程 / 混合虚拟计时期间服务端禁止调用，提前交卷不提前解除。可切换到知识库页面；笔记和长期学习计划尚未接入，具体边界见 [任务记录](../docs/docs/ai/TODO.md)。
- 导航“知识库”（`/knowledge`）：选择可见资料检索片段和当前原文，不需要模型；默认不外发。明确勾选后，本次请求才允许把检索片段发送给本人配置的模型。管理端“知识库资料”（`/admin/knowledge`）仅 root / admin 维护，支持 UTF-8 Markdown / TXT、PDF 原件上传、版本 / 入库状态与显式发布。PDF 最多 32 MiB / 200 页 / 30 万字，正文只读提取并保留空白页号，用户可定位引用页及下载当前发布原件；扫描件需先做 OCR。部署需 knowledge-base.sql 及可选本地 RAG overlay，详见 [运行步骤与实现边界](../docs/docs/ai/02-rag-knowledge-base.md#11-知识库页面与常驻本地索引已接通待用户验收)。
- 已结束的编程赛 → 虚拟计时练习：按原赛时长固定个人截止，刷新 / 离开不暂停，重试不延长，截止前所有 AI 入口禁用。提交仍是赛后记录，暂不生成独立虚拟榜单。需先执行 `sqlAndsetting/virtual-contests.sql` 并更新后端。隔离浏览器设置 `PAPER_REVIEW_CASE=virtual` 后运行 `node tests/paper-browser.cjs`；真实本地验证以 `HOJ_LOCAL_VIRTUAL_TEST=1` 运行 `node tests/virtual-contest-local-integration.cjs`，仅使用自动清理的测试记录，不调用模型或外站提交。
- 已结束的混合赛 → 题目 → 虚拟计时练习：创建独立答卷，个人截止自动交卷，标准答案 / 解析还需满足管理员当前规则；正式榜单、比赛 Rating 与平时练习保持独立。可从同一入口继续或查看本轮结果。升级还需先执行 `sqlAndsetting/mixed-virtual-contest.sql`。隔离检查使用 `PAPER_REVIEW_CASE=mixed-virtual`；真实本地复验给 `tests/mixed-contest-local-integration.cjs` 同时设置 `HOJ_LOCAL_MIXED_CONTEST_TEST=1` / `HOJ_LOCAL_MIXED_VIRTUAL_TEST=1`，含本地 AC / WA / TLE，需已存在演示套卷 3。具体计分、开放和未实现的虚拟榜单见 [混合虚拟答卷](../docs/docs/ai/08-data-model-and-api.md#当前已实现混合虚拟答卷2026-10-19)。
- 本人个人主页 → 跨平台训练记录：关联并验证 CF / AtCoder / 牛客账号，按来源查看同步记录和评级；“会话授权”用于本人远程提交，与公开记录同步分开。真实本人源站 AC / WA 尚待联调。旧账号设置中的资料字段不等于已验证关联。

牛客账号的“比赛与赛后训练”可展开查看公开的已结束参赛摘要、比赛内提交和赛后同题通过，受限 / 团队明细保留未知，计数不重复加到总题数。需要先执行 `sqlAndsetting/nowcoder-context.sql` 并更新后端。隔离页面回归使用 `PAPER_REVIEW_CASE=nowcoder`；真实本地接口用 `HOJ_LOCAL_NOWCODER_TEST=1` 运行 `tests/nowcoder-local-integration.cjs`，仅投影断言使用明确标记的合成快照。额外设置 `HOJ_NOWCODER_LIVE_TEST=1` 和 `HOJ_NOWCODER_CONTEXT_LIVE_TEST=1` 才让后台只读同步公开样例，不用 Cookie / 不验证样例归属 / 不投递代码；临时账号和上下文自动清理。

管理端远程题目的译文页可使用本人模型生成草稿：先在面板的“模型设置”中保存配置，明确勾选外发原文后生成，应用到编辑器核对，最后“核对并保存译文”。默认不外发，不自动保存；缺少配置或超出翻译边界时仍可手工编辑。参赛禁用、版本与模型绑定在服务端校验，真实本人模型质量尚待体验，详细限制见 [译文草稿 API](../docs/docs/ai/08-data-model-and-api.md#当前已实现管理员-ai-译文草稿2026-10-20)。隔离回归设置 `PAPER_REVIEW_CASE=translation` 运行 `tests/paper-browser.cjs`，7 组 / 3 图均为受控 API / 模型样例；真实本地拒绝路径以 `HOJ_LOCAL_TRANSLATION_TEST=1` 运行 `tests/translation-local-integration.cjs`，只创建并清理命名测试数据，成功模型路径另由后端受控 HTTP / H2 验证，不能将其当作真实用户模型生成验收。

本地草稿与偏好不做跨设备同步，清除浏览器数据或原 HOJ 退出登录流程可能清除缓存；重要代码请另存。新增题型、rating 及统计的服务端接入状态详见实现记录。

知识库隔离页面回归运行 `node tests/knowledge-browser.cjs`，用 `UI_REVIEW_URL`（默认 http://127.0.0.1:8080）和 `UI_REVIEW_OUTPUT` 指定入口与证据；所有 API 为受控样例，检查项和截图以实际 JSON 为准，含 PDF 上传 / 只读正文、数字或字符串页码、原件错误 Blob、换号迟到下载与真实深色颜色断言，不证明真实向量或模型。真实上传 / 本地索引 / 发布 / Vue 原文流程需显式设置 `HOJ_LOCAL_KNOWLEDGE_RAG_TEST=1` 后运行 `node tests/knowledge-rag-local-integration.cjs`；可额外设置 `HOJ_KNOWLEDGE_RESTART_TEST=1` 验证有向量时重启保留，以 `PAPER_REVIEW_OUTPUT` 指定证据目录。该脚本读取本地 Compose MySQL 并创建、清理命名测试资料和账号，不伪设 INDEXED、不调用外部模型；服务端撤销墓碑保留，需先部署上述 RAG overlay。旧 `knowledge-local-integration.cjs` 用于索引未配置时的后端权限流程，含显式 INDEXED 夹具，不能替代真实链路，也不要对启用的服务运行它。

Markdown 知识库原文和回答正文支持四种常见 TeX 分隔符，公式超限 / 排版失败时保留原文，代码和展开的原文摘录保持字面内容。TXT / PDF 提取页不作公式解析，公式识别仍需对照原件。`npm run test:knowledge-render`（Node 22+）验证隔离的 Markdown / KaTeX 渲染与限制；完整页面回归另见上述脚本。安装使用普通 `npm ci`，KaTeX 固定本地版本且旧插件共用同版；默认生产 CDN 构建和后台编辑器也不再加载旧 KaTeX CDN。详细边界见 [公式排版](../docs/docs/ai/02-rag-knowledge-base.md#17-知识库公式排版2026-10-21)。

PDF 真实流程使用 `tests/knowledge-pdf-local-integration.cjs`，显式设置 `HOJ_LOCAL_KNOWLEDGE_PDF_TEST=1`、`HOJ_PDF_FIXTURE`（原创测试 fixture JSON）和 `PAPER_REVIEW_OUTPUT`；也支持上述重启开关。fixture 可用 `tests/knowledge-pdf-fixture.py` 在内存生成并把 JSON 写入忽略的临时目录，需要本机 PyMuPDF、pypdf 和 CJK 字体，字体路径由 HOJ_PDF_TEST_FONT 指定（Windows 默认 simsun.ttc）；文件和字体不提交 Git。该流程检查真实解析 / BGE 检索、三页含空白、原件字节一致、元数据沿用 / nonce、发布替换、加密 / 无文字拒绝，以及正式 / 虚拟参赛、可见性 / 撤回与下载权限，最后清理命名资料 / 账号 / 向量；不能与另一真实资料集成同时运行，避免全库计数互相干扰。

本轮外观不增加动画依赖、图片请求或后台接口调用。柔光只随鼠标移动更新，首页玻璃为静态小范围装饰，手机关闭玻璃模糊和折射。头像展开时临时监听定位和关闭事件，关闭或销毁时清理。300–400 人同时访问的服务容量仍需在生产构建和服务端环境中单独压测，本地热更新预览不是容量证明。

扫描 PDF 在“后台 → 知识库资料 → 导入 PDF”勾选“启用本地 OCR”。保存后按物理页核对、校订，保存校订会生成新版本；等待入库后显式发布。原件、原识别版本和旧发布保留，学生页标明自动识别 / 管理员校订。普通文字 PDF 保持原来的只读提取路径。OCR 在本地完成，最多 32 MiB / 200 页 / 30 万字，并受识别时限约束；超时需拆分，公式和代码请对照原件。

新增页面回归覆盖真实 OCR 复选框及按页校订输入，其他权限 / 页码 / 迟到响应检查保留。真实 OCR 闭环沿用 tests/knowledge-pdf-local-integration.cjs，额外设 HOJ_KNOWLEDGE_PDF_OCR=1，HOJ_PDF_FIXTURE 指向 knowledge-ocr-fixture.py 用本机 CJK 字体生成的原创扫描 JSON；需要运行本地更新后的后端 / RAG，显式设置 HOJ_LOCAL_KNOWLEDGE_PDF_TEST=1，可设 HOJ_KNOWLEDGE_RESTART_TEST=1 验证向量保留。它会创建并清理临时账号 / 资料，不调用外部聊天模型，不把识别结果与标准文字相等作为假设；保留原识别和人工校订证据。不要提交生成的 PDF / 字体 / 报告。视频资料尚未接入，完整进度以 HOJ TODO 为准。

本人训练清单的真实本地回归：设置 `HOJ_LOCAL_TRAINING_PLAN_TEST=1` 后运行 `node tests/training-plans-local-integration.cjs`；额外设置 `HOJ_LOCAL_TRAINING_PLAN_BROWSER=1` 可一起检查真实 Vue 页面。通过 `PAPER_REVIEW_OUTPUT` 指定证据目录；脚本只创建、清理随机账号 / 题目 / 清单，不调用模型或远程提交。覆盖已评级客观题的严格难度筛选、清单当前评级、保密作答、快速返回后的推荐读取与手机可见性；编程进度使用合成已结算行，不代表真实判题 AC / WA。

已有真实讲义的本地流程：设置 `HOJ_LOCAL_KNOWLEDGE_MATERIAL_TEST=1`、`HOJ_KNOWLEDGE_MATERIAL` 指向 RAG 项目现有博弈论讲义、`PAPER_REVIEW_OUTPUT` 指向新的证据目录，运行 `node tests/knowledge-material-local-integration.cjs`；可设 `HOJ_LOCAL_KNOWLEDGE_MATERIAL_BROWSER=1` 核对实际 Vue 引用跳转及标题可见性。四个完整证据在入库前固定，原文件不改；只创建、清理本轮 ADMIN 资料 / 账号 / 向量，不调用聊天模型。需已运行本地 RAG / 后端；不要和另一真实资料集成同时运行，避免全库计数互相干扰。结果及资源限制见 [任务记录](../docs/docs/ai/TODO.md)。
