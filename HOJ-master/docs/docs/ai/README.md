# HOJ 智能编程学习系统

## 1. 项目目标

本项目面向 ICPC 集训队，目标是在 HOJ 的题库、训练、提交记录和学习资料之上，增加三个相互配合的系统：

1. **RAG 知识库系统**：管理内部题目、题解、讲义、PDF、Markdown、TXT 和视频转写内容。
2. **AI 教学问答系统**：只服务于编程学习，支持全局问答和题目上下文问答。
3. **AI 学习工作台**：独立于问答页面，提供学习计划、薄弱点分析、题目推荐和学习反馈。
4. **CF 远程提交 / VJ 系统**：先实现 Codeforces，后续再扩展洛谷和 AtCoder。

比赛期间 AI 功能完全禁用。RAG 只使用系统内部知识；模型调用是否能够联网由模型供应商和模型配置决定，不能把联网搜索结果当作 HOJ 知识库来源。

## 2. 已确认的产品边界

### AI 的身份

AI 是算法竞赛教练和学习助手，不是通用聊天机器人。回答应优先引导学生思考：

- 先确认题意和知识点；
- 再给出分层提示；
- 引导学生自己推导；
- 最后才在允许的场景下给出较完整的思路；
- 默认不直接替学生完成题目。

### 两个前端入口

- **AI 问答**：即时提问、题目上下文问答、提交代码分析、多轮对话。
- **学习工作台**：学习计划、今日任务、掌握度、薄弱点、推荐题目、错题复盘。

### 知识范围

- RAG 知识库只包含内部数据和管理员允许上传的资料。
- 普通题目、训练题目和内部课程资料可以进入知识库。
- 当前系统视为一个 ACM 集训队整体，不设计多租户团队知识库；但仍保留来源和权限字段，以便保护比赛题、私有题和管理员资料。

## 3. 推荐的总体形态

不要把 Python RAG 原型的代码直接复制到 HOJ 的 Java 代码中。推荐拆成两个服务：

```text
Vue2 前端
    │
    ▼
HOJ Spring Boot 后端
    ├── 用户认证、权限、比赛状态
    ├── 学习行为与学习工作台数据
    ├── AI 会话、额度、审计
    ├── CF 远程提交与 VJ 任务
    └── 调用 Learning OS RAG 服务
             ├── 文档解析、清洗、切片
             ├── Embedding
             ├── Qdrant 向量检索
             └── 多模型调用适配
```

HOJ 后端是唯一的业务入口。前端不能直接调用模型供应商、Qdrant、Codeforces 或保存 API Key。

## 4. 已有可复用基础

`D:/learning-OS-RAG` 已经具备以下原型能力：

- Clean Architecture 分层；
- Parser、Chunker、Embedder、VectorStore、Retriever、LLM 接口；
- Markdown 解析和递归切片；
- BGE-M3 Embedding；
- Qdrant 向量存储；
- OpenAI 兼容模型接口，可覆盖 OpenAI、DeepSeek 等；
- Anthropic 原生接口，可覆盖 Claude；
- CLI 和 Streamlit 原型。

后续需要补齐：PDF、TXT、视频转写、文档版本管理、异步任务、权限过滤、混合检索、重排、HOJ API、会话持久化和可观测性。

## 5. 文档阅读顺序

1. [TODO 总计划](./TODO.md)
2. [总体架构](./01-architecture.md)
3. [RAG 知识库](./02-rag-knowledge-base.md)
4. [AI 问答](./03-ai-qa.md)
5. [学习工作台](./04-learning-workbench.md)
6. [多模型供应商](./05-model-providers.md)
7. [安全、权限与比赛禁用](./06-security-and-governance.md)
8. [Codeforces 远程提交与 VJ](./07-codeforces-vj.md)
9. [数据模型与 API](./08-data-model-and-api.md)
10. [学习与验证路线](./09-learning-and-verification.md)

这些文档是设计和学习材料，不代表已经完成代码实现。
