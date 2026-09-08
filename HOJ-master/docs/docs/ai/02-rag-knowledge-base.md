# RAG 知识库实现文档

## 1. 知识来源

### HOJ 内部数据

- 题目：标题、描述、输入、输出、样例、限制、标签、难度。
- 训练：训练说明、题目列表、知识点和推荐顺序。
- 讨论：只同步公开且允许进入知识库的内容；不能把未经审核的讨论直接当作标准答案。
- 提交：默认只作为用户个人代码分析上下文，不作为公共知识库内容。
- 文档：管理员上传的算法讲义、课程资料、规范和内部总结。

### 文件类型

| 类型 | 入库方式 | 重要元数据 |
|---|---|---|
| Markdown | 解析标题层级和正文 | 文件、章节、主题 |
| TXT | 按段落和语言切片 | 文件、主题 |
| PDF | 提取文字；扫描件可选 OCR | 文件、页码、章节 |
| 视频 | 抽取音频并 ASR 转写 | 视频、讲者、开始/结束时间 |
| 代码 | 可选，按语言和函数切片 | 文件、语言、题目/课程 |

视频本身不直接向量化。正确流程是：视频 → 音频 → 语音识别 → 带时间戳的文本 → 切片 → Embedding。回答引用视频时应显示时间点，方便学生回看。

## 2. 文档生命周期

```text
UPLOADED → PARSING → CHUNKING → EMBEDDING → INDEXED
                         ↘ FAILED
```

同一个文档应通过内容 Hash 幂等。重新上传相同内容不应生成重复向量；内容变化则创建新版本，旧版本进入归档或删除状态。

## 3. 建议的数据对象

### Document

- `id`
- `name`
- `source_type`：HOJ_PROBLEM、TRAINING、UPLOAD、VIDEO_TRANSCRIPT 等
- `source_ref_id`：关联题目或训练 ID
- `visibility`：PUBLIC、TEAM、PRIVATE、CONTEST、ADMIN
- `status`
- `content_hash`
- `version`
- `created_by`

### Chunk

- `id`
- `document_id`
- `version`
- `content`
- `chunk_index`
- `heading_path`
- `page_number`
- `start_time_ms`、`end_time_ms`
- `problem_id`
- `tags`
- `visibility`
- `embedding_model`

向量库中的 payload 必须保留这些过滤字段。不要只保存一段没有来源的纯文本，否则无法做权限控制和引用展示。

## 4. 检索策略

第一版可以使用向量检索，但算法题通常包含关键词、符号和代码，后续应升级为：

```text
关键词/BM25 + 向量检索
       → 合并候选
       → 权限过滤
       → 重排
       → 去重和上下文压缩
       → 交给模型
```

查询时要加入结构化过滤：

- 当前题目 ID；
- 题目标签和训练 ID；
- 用户可见范围；
- 是否处于比赛；
- 文档类型和语言。

## 5. RAG 回答要求

系统提示词应要求模型：

1. 只把检索内容当作内部资料依据；
2. 区分“资料明确说明”和“模型根据算法知识推断”；
3. 不伪造题目、章节、页码或代码运行结果；
4. 优先教学和提问，引导学生推导；
5. 返回引用 ID，前端再渲染为可点击来源；
6. 资料不足时明确说“当前知识库没有足够依据”。

模型可以联网并不等于 RAG 可以联网。产品上要区分：

- **内部知识回答**：来自 RAG，可展示来源；
- **模型通用知识回答**：来自模型，可标记为非内部资料；
- **联网结果**：如果将来开放，必须单独标记来源和开关。

## 6. 复用 Learning OS 时的学习重点

建议按以下顺序阅读现有代码：

1. `domain/interfaces.py`：理解抽象接口；
2. `domain/models.py`：理解 Document、Chunk 和 SearchResult；
3. `application/ingest.py`：理解入库编排；
4. `infrastructure/container.py`：理解依赖注入和插件注册；
5. `infrastructure/parsers`、`chunkers`、`embedders`、`vector_stores`；
6. `web/app.py`：理解当前问答原型与正式服务的差距。

不要直接把 Streamlit 页面当作生产 API。它适合验证检索质量，正式系统应增加 HTTP API、鉴权、任务队列和持久化。

## 7. RAG 验收指标

- 召回正确章节的比例；
- 引用来源覆盖率；
- 无相关资料时的正确拒答率；
- 权限过滤零泄露；
- 入库任务可重试、可观测；
- 同一文档重复导入不重复计数。
