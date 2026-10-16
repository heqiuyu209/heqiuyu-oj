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

- 题型：`SINGLE_CHOICE`、`MULTIPLE_CHOICE`、`TRUE_FALSE`；判断题选项固定为 `TRUE` / `FALSE`。每题 1 分，多选全部选对才得分。本轮不含部分得分配置。
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
