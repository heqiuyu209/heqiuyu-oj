# 数据模型与 API 草案

这份文档描述接口边界，不是要求一次性全部实现。先实现最小闭环，再逐步扩展字段。

2026-10-15 状态：下述 AI、学习数据表属于设计；远程判题应优先复用现有代码与表。新增业务使用现有 `CommonResult(status, data, msg)`，稳定业务错误码需要兼容现有调用，不能直接更改全站响应格式。

## 1. AI 会话

### 建议表

- `ai_conversation`：用户、标题、入口、题目/训练关联、状态、创建时间。
- `ai_message`：会话、角色、内容、模型、Token、耗时、状态。
- `ai_citation`：消息、文档/Chunk、来源展示信息。
- `ai_feedback`：消息、用户评价、原因。
- `ai_usage`：用户、供应商、模型、Token、费用估算、时间。

### API 草案

```text
POST /api/ai/conversations
GET  /api/ai/conversations
GET  /api/ai/conversations/{id}
POST /api/ai/chat              # SSE 或普通 JSON
POST /api/ai/messages/{id}/feedback
```

`POST /api/ai/chat` 的后端流程必须先检查比赛状态，再做 RAG 和模型调用。

## 2. 知识库

### 建议表

- `ai_document`
- `ai_document_version`
- `ai_ingest_job`
- `ai_document_permission`

向量内容可以放 Qdrant，文档状态、版本、来源和权限放 MySQL。MySQL 是业务事实来源，Qdrant 是可重建的检索索引。

### API 草案

```text
POST /api/admin/ai/documents
GET  /api/admin/ai/documents
POST /api/admin/ai/documents/{id}/reindex
DELETE /api/admin/ai/documents/{id}
GET  /api/admin/ai/ingest-jobs/{id}
```

上传接口只负责创建任务，不应同步完成视频转写和大文件向量化。

## 3. 学习工作台

### 建议表

- `learning_goal`
- `learning_plan`
- `learning_plan_item`
- `learning_event`
- `learning_weakness_snapshot`
- `learning_recommendation`
- `learning_feedback`

### API 草案

```text
GET  /api/learning/dashboard
POST /api/learning/goals
GET  /api/learning/plans
POST /api/learning/plans/generate
PUT  /api/learning/plans/{id}
GET  /api/learning/weaknesses
GET  /api/learning/recommendations
POST /api/learning/recommendations/{id}/feedback
POST /api/learning/events
```

推荐接口返回推荐理由、关联知识点、难度和来源题目，不只返回题目 ID。

事件上报只接受浏览与用户反馈等允许类型；UID 从登录态获取。提交、AC、客观题判分及计划完成校验由服务端生成，具备去重、重判修正与回填规则。

## 4. 远程提交

### 现有数据优先复用

先核验现有 `problem`、`judge`（含 `vjudge_submit_id`）、远程账号配置、`training`、`training_problem` 和 `training_record`。原草案中的 `remote_submission`、`vj_training*` 仅作为差距分析候选，不直接建第二套提交和训练体系。

### API 草案

```text
GET  /api/remote/codeforces/problems
POST /api/remote/codeforces/accounts
POST /api/remote/submissions
GET  /api/remote/submissions/{id}
GET  /api/vj/trainings/{id}
POST /api/vj/trainings/{id}/submit
```

## 5. 错误码建议

- `AI_DISABLED_IN_CONTEST`
- `AI_QUOTA_EXCEEDED`
- `AI_PROVIDER_TIMEOUT`
- `AI_KNOWLEDGE_NOT_READY`
- `AI_NO_RELIABLE_CONTEXT`
- `AI_DOCUMENT_PARSE_FAILED`
- `REMOTE_PROVIDER_UNAVAILABLE`
- `REMOTE_AUTH_FAILED`
- `REMOTE_SUBMISSION_REJECTED`

错误码应稳定，前端根据错误码展示多语言文本，不要依赖后端中文错误消息做判断。

## 6. 客观题（前后端同步开发）

目标：后台录题 → 学生作答 → 业务后端判分 → 按规则返回答案解析 → 作答记录与错题复习。2026-10-15 用户确认单选、多选、判断，管理端分别配置答案和解析开放时机。本轮完成练习与整卷，比赛隔离并预留独立规则，暂不开放比赛入口。未验证实现不得标为完成。

- 独立题目、不可变版本及作答记录；`Problem.type` 保持 ACM/OI 含义，客观题不进入编译沙箱或 ICPC 排名。
- 题目包含题干、题型、选项、答案、解析、来源、知识点、发布状态及版本。可选 `source` 为最多 200 字的纯文本，保存在题目 JSON 及答题快照内；旧题或留空时前端显示“本校题库”。已发布题目可单独训练，也可加入题集或试卷。`practiceEnabled` 控制单题入口；仅用于试卷的题目可关闭，防止经单题练习提前查看答案。作答绑定版本，保留题目与作答快照，改题不覆盖旧成绩。
- 学生列表和详情使用专用响应对象，提交前不得返回答案、解析、正确标记或管理实体。
- 练习逐题提交；整卷先保存答案，交卷后统一判分。答案与解析分别配置提交后、交卷后、指定时间开放或暂不开放，默认练习提交后、整卷交卷后。开放前也不返回可推断答案的正确标记与逐题得分。
- 作答身份从登录态取得，后端验证选项、发布状态并计算结果，客户端不传成绩。开卷请求使用唯一键；重复作答/交卷不重复记账；答题会话冻结题目版本，改题不影响旧卷。单题使用题目的单题开放配置；题集/试卷使用自身配置，互不覆盖。两种场景均读取当前管理配置，支持管理员之后调整开放时间。
- 管理接口沿用 root/admin/problem_admin 权限，普通管理员的编辑范围需明确；学生只能查询自己的作答。
- 前端：题库增加编程题/客观题入口和题型筛选；作答组件接真实提交、判分反馈、解析及历史记录；后台增加录题、编辑、发布/下架；错题入口可回看原版本并重练。登录、请求失败、重复点击、版本过期、切换账号及移动端均需验证。
- 原前端 `ObjectiveAnswer` 为展示/草稿原型，其多选、填空占位不代表后端已支持；正式界面仅开放已实现并验收的题型。

本轮源码中的数据表为 `objective_question`、`objective_question_version`、`objective_set`、`objective_run`。新建客观题表使用 `sqlAndsetting/objective.sql`，升级旧版表使用一次性的 `objective-standalone.sql`，具体前提见 [数据库说明](../../../sqlAndsetting/README.md)。全新安装的 `hoj.sql` 包含相同结构；现有数据库不能重跑完整初始化脚本。

实际接口（均要求登录，沿用 `{status, msg, data}` 响应结构）：

```text
GET      /api/admin/objective/questions      # 分页；keyword、questionType
GET      /api/admin/objective/questions/{id}
POST/PUT /api/admin/objective/questions      # 新建/修改；修改带 id、version
GET      /api/admin/objective/sets           # 分页；keyword、mode
GET      /api/admin/objective/sets/{id}
POST/PUT /api/admin/objective/sets            # 组卷、发布、独立开放设置
GET      /api/objective/sets                  # 仅发布的题集
GET      /api/objective/questions             # 仅发布且允许单题练习；keyword、questionType
GET      /api/objective/questions/{id}/neighbors # 上/下一题元信息；沿用 keyword、questionType 和 ID 倒序
GET      /api/objective/sets/{id}             # 元信息，不返回标准答案
POST     /api/objective/runs                 # {questionId, requestId} 或 {setId, requestId}，两种来源只能选一个
GET      /api/objective/runs                 # 本人历史，不含隐藏成绩
GET      /api/objective/runs/{id}             # 本人作答及当前可见结果
PUT      /api/objective/runs/{id}/answers     # {questionId, selectedAnswers}
POST     /api/objective/runs/{id}/finish      # 幂等交卷
```

- 题型：`SINGLE_CHOICE`、`MULTIPLE_CHOICE`、`TRUE_FALSE`、`FILL_BLANK`；判断题选项固定为 `TRUE` / `FALSE`。选择和判断在原练习/客观题集内每题 1 分，多选全部选对才得分。填空题各空独立计分，见下方混合卷扩展。
- 模式：`PRACTICE` / `PAPER`。单题练习使用 `PRACTICE`，记录带 `questionId`、`setId` 为空；题集/试卷记录带 `setId`、`questionId` 为空。单题提交后自动结束本次练习，可重新练习。整卷保存支持清空答案；交卷后不可修改。题集练习中已提交题目不可改答，可重新开卷练习。未答题交卷计 0 分。
- `answerPolicy` / `explanationPolicy` 独立取 `AFTER_ANSWER`、`AFTER_FINISH`、`AT_TIME`、`NEVER`；指定时间使用毫秒时间戳。整卷无论选择哪种策略，交卷前均不返回判分、答案或解析。练习须提交该题或交卷后才可能开放。
- 题目编辑中的 `practiceEnabled` 默认开启，答案/解析策略默认 `AFTER_ANSWER`，同时受题目发布状态控制。单题提交即结束本次练习，因此 `AFTER_ANSWER` / `AFTER_FINISH` 在此场景同时满足。关闭单题练习不影响已发布题集/试卷的组卷与作答；题集/试卷仍有独立开放策略。
- 学生响应使用 `answerVisible`、`explanationVisible` 控制字段：答案未开放时省略 `correctAnswers`、`correct`、逐题 `score`；解析未开放时省略 `explanation`；整卷已交且全部答案开放才返回总分。历史列表不提供隐藏正确率或错题数量。
- 开卷冻结题目内容、版本、顺序及模式；管理端之后调整答案/解析开放策略，对既有答题记录生效。题集下架后停止其学生端访问；再次发布后可继续访问本人旧记录。
- 管理端允许 root、admin、problem_admin；root / problem_admin 可管理全部，普通 admin 只管理自己创建的题目和题集，组卷也只引用自己的题目。学生写操作沿用 `submit` 权限，UID 仅取登录态。
- 每卷 1–50 题、每题 2–10 个选项。编辑使用版本校验；开卷请求按 UID 与 requestId 去重，作答和交卷通过事务行锁串行处理。
- 前端入口：题目页顶部“编程题 / 客观题”切换栏；客观题默认进入“单题练习”，保留“题集与试卷”和历史；手机单题列表直接显示练习按钮。管理端入口为“客观题与组卷”。本轮支持手工录题和组卷；计算机二级等外部套题的来源、导入格式与抓取适配尚未提供，未实现外部导入。
- 作答页复用编程题 `WorkspaceLayout`，题目与选项各占一张卡片，手机纵向排列；客观题使用独立布局偏好键 `objective-workspace-v1`。来源随题目版本显示，题集/试卷另标所属名称。上一题/下一题：单题按原列表筛选与 ID 倒序查询（只返回 `id/title/questionType`，边界为 `null`）；同一页面访问过的题目复用该次记录，新题才开独立记录。题集/整卷只切换当前快照内符合题型/错题筛选的题目，不新开卷。未保存选择需确认后丢弃，返回按钮恢复列表标签、页码与筛选条件；答案开放条件仍由服务端控制。

源码完成、隔离测试、真实数据库迁移、运行环境联调及用户验收分别记录于 `TODO.md`，不能将其视为相同状态。

### 混合试卷与结果填空（2026-10-16）

已确认：同一题既保留题库单题入口，也可被试卷引用；两处的答案、进度、成绩分别保存。开卷冻结题面、题目版本、顺序、分值、编程判题方式/计分规则，以及编程题测试数据和支持语言。之后编辑题库不改变已有答卷。答案/解析开放策略读取当前试卷配置，调整会影响已有答卷；下架暂停学生访问。

管理页面 `/admin/papers`、学生列表 `/papers`、答题 `/papers/run/:runID`。原客观题集接口与页面继续使用。试卷通过 `{kind: OBJECTIVE|PROGRAMMING, referenceId, score}` 引用现有题库，两类 ID 命名空间互相独立。每卷 1–50 题，不允许同种题目重复引用；不复制题库记录。选择/判断可设置本卷分值；填空题分值由各空之和决定。题库和试卷记录不混入 ICPC 比赛。

填空配置 `blanks: [{id, label, answers, score, trim, caseSensitive}]`：1–20 空，每空 1–20 个可接受答案，每空分值 1–1000 整数，单个答案最多 2000 字；默认去首尾空格、区分大小写。等价答案须管理员枚举，不自动推断表达式等价。作答 `selectedAnswers` 按空的顺序传值，允许重复答案；整卷保存可清空各空。学生端的 `blanks` 只含 id/label/score，标准答案与判分依开放策略返回。

编程模式 `LIVE`：答卷进行中可多次提交并看到本题判题结果，仅保存代码不判题；最高分 `BEST` 或最后一次 `LAST` 按开卷规则计分，每题最多 100 次提交。`AFTER_FINISH`：作答时只保存代码，交卷后将非空代码统一派发一次。ACM 全对得本题满分，OI 按原题 ioScore 比例折算，保留两位小数。交卷与提交通过行锁和请求标识去重，派发在数据库事务提交后进行。原提交频率和公开判题开关仍有效；整卷交卷按一次操作限频。

首版编程支持公开主题库、本地 default 标准判题；远程、特判、交互判题暂不列入可选项。每题测试文本最多 16 MiB；开卷会检查数据与语言是否齐全。JudgeServer 从不可变快照加载题面配置与测试数据，使用 `paper_<snapshotId>` 独立缓存；不写普通题库的用户 AC、训练或比赛记录。通用提交详情、分享、状态轮询、测试点详情和重判不能绕过套卷归属与开放规则；列表/题库/训练/用户统计排除套卷提交。

服务端系统异常/派发失败不结算为零分，答卷提供重试入口，仅重试本人的状态 4/10 提交，保留代码、提交归属和已交卷状态。正常 WA、CE、TLE 按判题结果计分。总分须交卷、答案开放且全部判题完成后才返回；`LIVE` 的逐次编程反馈为显式例外，管理页会提示。解析可单独开放。

```text
GET  /api/admin/papers                         # 管理分页，普通 admin 仅自己创建
GET  /api/admin/papers/program-questions        # keyword，最多 100 条可用本地编程题元信息
GET  /api/admin/papers/{id}                     # 编辑完整配置
POST /api/admin/papers                         # 创建/修改；修改带 id/version
GET  /api/papers                               # 已发布试卷分页
POST /api/papers/runs                          # {paperId, requestId}；冻结并开卷
GET  /api/papers/runs                          # 本人历史，不含隐藏成绩
GET  /api/papers/runs/{id}                     # 本人答卷和允许的反馈
PUT  /api/papers/runs/{id}/answers             # {key, selectedAnswers} 或 {key, language, code}
POST /api/papers/runs/{id}/program-submissions # LIVE: {key, language, code, requestId}
POST /api/papers/runs/{id}/finish              # 幂等交卷
POST /api/papers/runs/{id}/retry               # 重试本人的系统/派发失败代码
```

管理端沿用 root/admin/problem_admin，学生接口均须登录，写操作须 submit 权限。客观题引用沿用题目所有权规则，编程题仅引用公开题。整卷未保存修改有切题/离开/刷新保护，交卷先保存当前题；判题轮询不覆盖未保存代码与选项。套卷与客观题作答页共用底部常驻切题栏，滚动与卡片展开时仍显示上一题、当前题号/标题和下一题；展开卡片为导航预留空间，套卷切题保留展开状态。

### 正式混合比赛（2026-10-16）

主站比赛仍使用原有 `type=0 ACM / 1 OI`、可见性、报名及账号限制。管理员在原比赛 DTO 中传 `mixed: {paperId, scoreRule, answerPolicy, explanationPolicy, answerReleaseAt, explanationReleaseAt}`；没有 `mixed` 的比赛保持原编程题流程。编程计分读取原 OI 的 Highest/Recent 选择，分别归一化为 BEST/LAST；混合比赛强制即时判题，不允许赛后继续作答。管理页的题目入口转向混合卷配置。

`contest_paper(contest_id, paper_id, rules_json, template_json)` 保存独立比赛规则和全体参赛者共用的冻结题目、分值、语言及测试数据。开始前保存比赛可刷新模板，开始后或已有答卷时，只允许修改开放规则等不影响题目与计分的配置；赛制、试卷、计分方式、起止时间均冻结。沿用本地标准编程题限制，总模板 UTF-8 大小不超过 32 MiB。修改日常试卷或题库不会改变比赛。

`paper_run.contest_id` 可空，唯一索引 `(contest_id,uid)` 保证每人每场仅一份；普通套卷历史排除比赛答卷。开始、保存、提交和交卷沿用数据库行锁；通用套卷 API 访问比赛答卷同样检查实际服务器时间、身份、可见性与参赛权限，不能借旧接口绕过比赛截止。截止时后台每 5 秒扫描到期作答（每批 100 份），读取/继续答卷也会幂等交卷；无需浏览器在线，服务恢复后补处理。只计已保存的客观答案和已提交判题的编程代码；未提交的编程草稿不判题。

客观题交卷后可见分数，但标准答案与解析分别按比赛的 AFTER_END / AT_TIME / NEVER 控制，不能早于结束。比赛中的编程判题结果即时显示。开放规则读取当前比赛设置，与日常练习的开放设置独立。比赛编程提交复用冻结快照判题，但不写普通题库、训练及旧比赛排行榜记录；新的比赛榜单单独汇总。

ACM 排序：客观题分数降序 → 编程 AC 数降序 → 罚时升序，相同成绩并列。每个编程题取首次 AC 的服务端提交时刻与开赛时间之差，加 AC 前 WA/PE/TLE/MLE/RE 每次 20 分钟；CE、部分通过、系统失败不罚时，AC 后的提交不追加罚时。OI 排序：已交卷客观题分数与编程得分相加降序，同分并列。尚未交卷的客观题不计分；判题中或评测异常明确标记为未结算。创建者和 root 不参与排名；遵守原封榜截止时刻。首版榜单位于比赛内，不使用原外部滚榜。

```text
POST /api/contests/{cid}/mixed/run  # 登录+submit；开始/继续同一答卷，赛后只返回已有答卷
GET  /api/contests/{cid}/mixed/rank # 登录与比赛访问权限，返回 ACM 或 OI 榜单
```

答卷读写、判题和交卷继续使用上述 `/api/papers/runs/{id}...`，返回 contestId、contestType、serverNow、endTime；前端路径 `/contest/:contestID/mixed/run/:runID`，显示比赛返回按钮、倒计时和常驻切题栏。旧编程提交接口拒绝混合比赛 cid。当前 AI 仍是禁发占位 UI，并无服务端 AI 能力；未来接入 AI 时须服务端基于可信比赛身份禁用，不能以路由遮蔽代替服务端策略。

## 2026-10-16 站内 Rating

计算规则及当前验收状态见 [任务记录](TODO.md)。`problem.rating` 为可空整数 0–5000，`difficulty` 只保留旧业务兼容；不自动换算。客观题 Rating 存在版本内容 JSON，公开列表/作答只返回数值，不附标准答案。题面快照保留开卷时的 Rating，平时榜按当前校准 Rating 重放已有有效独立通过。`contest.rated` 默认 false，管理接口校验主站公开赛、开赛后锁定 Rating 开关及赛制/计分/时间。

`site_rating_profile` 保存两种榜单的当前值、最高值、最近变化和计分次数；`site_rating_history` 按用户/类型/来源唯一，保存前后值、变化、事件时间与比赛平均名次。`site_rating_lock` 的行锁串行化多副本结算。后台每分钟在事务中重放有效题目通过和按时间排序的已完成计分赛，再整体发布，异常时保留上一版快照；重判、删除及重新校准会修正历史和后续分数。适用于当前小规模集训队，扩大用户量后需改为增量重放。未参加计分的用户 Rating 返回 null，不虚构定级或排名。

```text
GET /api/rating/rank?type=practice|contest&currentPage=1&limit=30&searchUser=...
    # 匿名可读，最高每页100；搜索保留全局名次。records/total/current/size/updatedAt。
GET /api/rating/users/{uid}
    # 匿名可读，practiceRating/contestRating/Count/Peak及最近100条history。
GET /api/get-problem-list?...&ratingMin=1200&ratingMax=1800
    # 服务端区间筛选，不包含未评级题。原有题库/标签/分页条件保留。
```

个人主页接口同时返回 `practiceRating`、`contestRating`。前端榜单 `/rank/practice`、`/rank/contest`；旧 `/acm-rank`、`/oi-rank` 仅作地址兼容跳转，不改变单场比赛的 ACM/OI 成绩规则。
