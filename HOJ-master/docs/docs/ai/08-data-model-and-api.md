# 数据模型与 API 草案

这份文档描述接口边界，不是要求一次性全部实现。先实现最小闭环，再逐步扩展字段。

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

## 4. 远程提交

### 建议表

- `remote_provider`
- `remote_account`
- `remote_problem`
- `remote_submission`
- `vj_training`
- `vj_training_problem`
- `vj_training_record`

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
