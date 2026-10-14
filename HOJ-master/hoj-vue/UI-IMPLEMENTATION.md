# CDUTETC UI 实现记录

记录日期：2026-10-14。范围为 HOJ 现有前端的 UI 集成；本文件记录本模块的实现与验收，外部项目记忆仍由原协调文档维护。

## 目标与已确认决策

打开原有题目路由即进入工作台，不新增独立工作台或 AI 页面。保留首页内容与全部导航，管理员从头像菜单进入管理后台。使用原校徽和原颜色，浅色、深色均清晰。练习 rating 与比赛 rating 分开，仅展示及预留字段，计分规则待定；客观题范围为单选、多选、按预设答案判分的填空。XCPC 照片暂留展示位。

当前状态：前端代码已集成，主站/文档站构建和隔离浏览器回归通过；未部署、未推送、待用户验收。服务端新增能力见下文，不能将 UI 可见视为后端已实现。分支：`codex/cdutetc-ui`。

## 修改位置与行为

| 范围 | 主要文件（相对本目录） | 行为 |
| --- | --- | --- |
| 品牌、主题 | `src/common/brand.js`、`appearance.js`、`src/styles/cdutetc.css`、`src/components/oj/common/BrandLogo.vue` | CDUTETC / 成理工程在线测评平台；主题及密度变量；原图圆形裁切避免外部方形白底，不重绘、不反色、不改原色 |
| 导航 | `src/components/oj/common/NavBar.vue`、`AppearanceSettings.vue` | 原导航完整保留；动态模式顶部正常，滚动后缩小、聚拢、透明，悬停或键盘聚焦恢复；可切普通或静态毛玻璃 |
| 首页 | `src/views/oj/Home.vue` | 保留公告、轮播、提交统计、近期题目、比赛与过题排行；无轮播数据时显示 XCPC 照片占位 |
| 题库来源 | `src/common/learning.js`、`src/components/oj/learning/ProblemMeta.vue`、`src/views/oj/problem/ProblemList.vue` | 展示来源、CF 标尺和详情原始分数；旧列表按已知题号前缀识别来源；没有分数显示“待定” |
| 工作台 | `src/views/oj/problem/Problem.vue`、`src/components/oj/problem/WorkspaceLayout.vue`、`src/common/problemWorkspace.js` | 原题干、提交记录与编程逻辑进入可移动卡片；调整顺序、宽度、高度及展开；本地保存布局和独立语言草稿 |
| 自测 | `src/components/oj/common/CodeMirror.vue` | 原侧边抽屉改为编程卡片内自测，继续调用原接口；语言切换后不展示旧语言自测结果，切题后忽略旧异步响应 |
| 学习与账号 | `src/components/oj/learning/`、`src/components/oj/setting/PlatformAccounts.vue`、`src/views/oj/user/` | 语法、算法、数据结构、图论、DP 分类；本题笔记与计划；练习/比赛 rating 分离；CF 登记与牛客、QOJ 本地草稿 |
| AI 小窗 | `src/components/oj/ai/AiAssistant.vue`、`src/App.vue` | 可拖动小弹窗、关闭后恢复焦点；比赛页面禁用；当前发送按钮禁用，不发送题目、代码或调用模型 |
| 客观题视图 | `src/components/oj/problem/ObjectiveAnswer.vue`、`src/views/oj/problem/Problem.vue` | 同一个题目页按独立 `questionType` 显示单选、多选、填空；本地作答草稿；提交禁用，等待服务端判分 |
| 上/下一题 | `src/components/oj/common/ProblemHorizontalMenu.vue` | 使用原有受权限控制的训练/比赛题目列表，保留路由上下文及 query；首尾禁用，不推算相邻题号 |
| 系统信息及文档 | `src/App.vue`、`src/views/oj/about/Developer.vue`、`../README*.md`、`../docs/docs/` | 网站开源、API、帮助链接指向用户仓库；更新文档站名称和校徽，保留 HOJ 来源署名 |
| 管理端 | `src/views/admin/Home.vue`、`Login.vue` | 只接入品牌组件；题目创建/编辑、训练创建、比赛创建及后台权限逻辑未改 |

没有新增运行时依赖；拖动、缩放使用浏览器能力。主题样式最后加载，兼容原 Element/Muse/vxe 组件。无 `backdrop-filter` 时使用实色，系统选择减少动画时关闭导航过渡。移动端卡片纵向排列，使用前后移动按钮调整顺序。

首页沿用后端“最近一周过题排行榜”，当前接口按滚动日期统计，并非自然周周一到周日。本次没有修改排行 SQL 或将其冒称自然周排行榜。

权限保留 HOJ 现状：`user`、`admin`、`root`，以及原有委派的 `problem_admin`。普通用户不显示“管理后台”；已有管理员角色沿用原后台访问规则。前端隐藏入口不替代后端鉴权。

## 预留字段与接入边界

以下新增字段是前端可消费的约定，**本轮没有创建数据库列、修改 Java VO 或新增接口**。现有响应不带字段时，原编程题仍正常渲染。

| 对象/字段 | 类型与含义 | 当前处理 |
| --- | --- | --- |
| 题目 `sourcePlatform` | 平台代码，如 CF、NC / NOWCODER、QOJ | 优先使用；旧列表不返回 `isRemote` 时以已知 `problemId` 前缀识别；明确 `isRemote=false` 时不按前缀猜测 |
| `sourceRating` | 原平台数值或 null | 原始分数独立保留，不将牛客/QOJ 分数直接等同 CF |
| `cfRating` | 经标定的 CF 标尺数值或 null | 优先显示；仅 CF 原始分数可直接复用；不由 AC 数、HOJ 简单/中等/困难或 OI score 推导 |
| `ratingProvenance` | `estimated` 或来源说明 | estimated 显示非官方估计提示；后续需后端记录版本与映射依据 |
| `sourceUrl` | 原题 HTTP(S) 地址 | 兼容原 `link`；拒绝 javascript/data 等协议，不拼造未知题目链接 |
| 个人 `practiceRating`、`contestRating` | 数值或 null | 分开展示，空值“待定”，0 保留为 0；原有 `rating` 仍表示 CF 分数 |
| 个人 `knowledgeStats` | `{ syntax: { solved }, algorithms: { solved }, structures: { solved }, graphs: { solved }, dp: { solved } }` | 仅显示真实已通过题数；无统计“暂无统计”，没有虚构掌握度或雷达图 |
| 题目 `questionType` | `PROGRAMMING` / `SINGLE_CHOICE` / `MULTIPLE_CHOICE` / `FILL_BLANK` | 缺失时沿用编程题；**不复用原来的 `type`，原 `type` 仍为 ACM/OI** |
| `questionVersion` | 题目版本标识 | 客观题本地草稿按版本隔离 |
| `options` | `[{ id, text }]`，稳定唯一的字符串 ID | 展示纯文本选项，不包含正确性标记 |
| `blanks` | `[{ id, label }]`，稳定唯一的字符串 ID | 展示填空输入框，不包含预设答案 |

前端不提供“把当前题随意切成另一个题型”的用户开关，题型由题目数据决定。新题型示例仅存在于隔离浏览器验收数据中，不注入真实题库。客观题标准答案、判分、解析开放、版本快照和作答记录必须由后端实现；当前没有客户端假判分，也不把客观题送往编程判题接口。

CF 关联复用原 `change-userInfo` 和 `cfUsername`，属于公开账号登记，不等于平台归属验证。牛客、QOJ 没有现成服务端绑定字段，此轮提供明确标注的本地草稿与公开主页入口。未实现跨平台抓取、同步任务、归属验证、解绑联动、rating 计算或 XCPC 重现赛计分。

AI / RAG 仅留界面接口位置，未实现会话、流式回答、检索、引用、配额或 RAG 数据管理。日后开启发送前，必须先实现服务端身份、资料权限和比赛限制；当前前端禁用不构成该服务端限制已完成。

## 本地数据与兼容风险

- 代码草稿按账号、团队、比赛、训练、题目、语言保存；旧 HOJ 草稿没有账号归属，因此提供手动导入提示，不自动进入新账号。空草稿也保留，不自动覆盖为模板。
- 布局/外观是本浏览器偏好；代码、笔记、计划、平台草稿按账号区分。当前没有跨设备同步或云端备份；原 HOJ 退出登录会清理浏览器缓存。浏览器配额/隐私模式也可能阻止保存。
- 能力画像与推荐需要真实学习事件服务；当前分类和笔记可用，统计/推荐显示尚未接入。计分规则需另行设计、回放校验，不能把这次 UI 提交视为 rating 上线。
- 旧题号前缀识别只是兼容策略；正式采集后应在列表和详情接口中明确返回来源平台、原题 ID、原分和标定版本，避免本地自定义前缀歧义。
- `brand.js` 统一覆盖 API 中的展示名称和介绍，功能开关、备案信息继续来自后端。若后台改站名，需同步调整品牌定义。
- 主题覆盖共享组件会影响管理端外观，原创建/编辑组件和请求参数未改；复杂后台弹窗及所有图表仍需用户实际业务验收。新增文字首版为中文，尚未补齐全部语言包。
- 图片为用户提供的原文件（实际编码 WebP），应用内用 CSS 圆形裁切，内圈原本的白色保留。文档站也加圆形适配。旧历史资料和第三方 OJ 自己的商标保留，未对上游署名或历史内容做全局替换。
- 依赖仍为原 Vue 2 / Webpack 4 技术栈；生产 CDN 的可达性仍由部署环境决定。可选本地资源模式会增加前端包体。
- 文档站没有确定公开域名，关闭 feed / sitemap，避免继续使用上游域名。配置真实域名后再启用；现有插件初始化仍会打印缺少 hostname 提示，但不影响静态站生成。

## 验证与接续

验收脚本及执行方式见 [前端 README](README.md)。验证对象为本次分支提交的前端源码；截图与结构化结果保存在 [review/](review/README.md)。

| 验证项 | 2026-10-14 结果 |
| --- | --- |
| `npm run test:ui` | 22 条断言通过：缺失分数、平台分数不混用、旧列表来源、链接协议、草稿账号/题目/语言/比赛/训练/团队隔离 |
| `npm run test:browser` | 通过，未捕获异常 0；完整导航、动态收拢/悬停、题库来源、C++/Python 草稿恢复、空草稿、布局顺序/宽度持久化、卡片 DOM 拖放、展开/Esc、各角色真实菜单、自测接口参数、训练上/下一题及 query、延迟提交响应隔离、比赛 AI 禁用、两类 rating、三类客观题视图、五个手机页面无横向溢出 |
| 主站 `npm run build` | 成功，沿用默认 CDN 生产方式；另以本地依赖模式启动开发服务完成浏览器验收。未升级 lockfile |
| 文档站 `npm run build` | 安装锁文件中的已有依赖后成功；品牌/校徽静态页面在 Chrome 中打开，两个徽标均加载且圆形裁切，无横向溢出或浏览器异常 |
| 原图一致性 | 三处 WebP 资产与用户原图 SHA-256 相同；视觉处理仅为 CSS 裁切 |
| 兼容范围核对 | 原角色规则、路由、后端代码、后台题目/训练/比赛创建编辑表单没有改动；`git diff --check` 通过 |
| 真实服务联调 | 未验证：本轮后端 `localhost:6688` 未连通。没有向真实数据库写入验收账号、题目或提交 |

浏览器仍有原组件的 vxe tooltip 配置与 Vue Router 废弃属性提示；构建有旧 Browserslist 数据提示。文档站保留历史 Markdown 代码语言拼写提示、缺少域名提示和更新检查权限提示，构建退出码为 0。未因此升级整个依赖栈或修改历史技术文档。

浏览器检查使用模拟接口，不能替代真实后端 AC/WA/TLE、自测、账号保存、服务端权限及比赛排名回归。主站生产包与文档静态站仅构建，未更新 Docker 容器或远程网站。

下一步最小工作：用户先验收主站布局与工作台，再按独立后端任务落实新增字段、平台关联/同步、客观题录题到判分闭环、真实学习统计与 AI/RAG。管理端已有录题流程保持可用后再扩展新题型，勿直接改变原 ACM/OI `type`。

设计参考：[OJ Insight](https://github.com/Whalica/OJ_Insight) 的多平台记录与账号信息组织；实现沿用本仓库 HOJ 组件与接口，没有复制其桌面框架或假定双方后台协议相同。
