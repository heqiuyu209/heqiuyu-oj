
# 1. 项目目标

基于现有 HOJ（SpringCloud + Vue 分布式 OJ）进行二次开发，实现：

- 用户行为采集
    
- 用户画像系统
    
- 智能学习推荐
    
- AI 辅助训练 Agent
    
- 题目知识图谱
    
- 多平台题目聚合（Codeforces / AtCoder / 洛谷 等）
    
- 赛时隔离模式
    

同时保证：

- 不影响原有判题稳定性
    
- 比赛模式绝对纯净
    
- AI 不干扰正式竞赛
    
- 可渐进式开发
    

---

# 2. 系统总体架构

---

## 2.1 总体架构图

```text
┌─────────────────────────────────────┐
│              前端 Vue               │
│-------------------------------------│
│ 题目页 / 比赛页 / AI侧边栏 / 埋点SDK │
└─────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│          Spring Gateway             │
└─────────────────────────────────────┘
                  │
 ┌────────────────┼────────────────┐
 ▼                ▼                ▼

┌───────────┐ ┌────────────┐ ┌────────────┐
│ OJ核心服务 │ │ 行为采集服务 │ │ AI Agent服务 │
└───────────┘ └────────────┘ └────────────┘
        │              │               │
        ▼              ▼               ▼

┌───────────┐ ┌────────────┐ ┌────────────┐
│ 判题系统   │ │ 用户画像系统 │ │ 推荐系统     │
└───────────┘ └────────────┘ └────────────┘
        │              │               │
        ▼              ▼               ▼

┌─────────────────────────────────────┐
│            数据层                    │
│ MySQL / Redis / Elasticsearch       │
└─────────────────────────────────────┘

                  │
                  ▼

┌─────────────────────────────────────┐
│      外部平台适配层（VJ模式）         │
│ CF / AtCoder / 洛谷 / Clist         │
└─────────────────────────────────────┘
```

---

# 3. 系统模块划分

---

## 3.1 前端系统

---

## 功能模块

|模块|功能|
|---|---|
|OJ核心页面|原 HOJ|
|AI Sidebar|AI 助手|
|行为采集 SDK|埋点|
|用户能力图谱|可视化|
|推荐系统页面|推荐训练|
|学习路径页面|学习规划|
|比赛隔离模式|关闭 AI|

---

## 前端技术栈

推荐：

```text
Vue3
TypeScript
Pinia
Element Plus
Echarts
Axios
```

---

## 3.2 后端系统

---

## 微服务划分

|服务|作用|
|---|---|
|hoj-judge|原判题|
|hoj-user|用户|
|hoj-problem|题目|
|hoj-contest|比赛|
|hoj-behavior|行为采集|
|hoj-profile|用户画像|
|hoj-recommend|推荐|
|hoj-agent|AI Agent|
|hoj-sync|外部平台同步|
|hoj-analysis|代码分析|

---

# 4. 行为采集系统设计

---

## 4.1 行为数据采集

没有行为数据：

Agent 无法判断用户状态。

---

## 4.2 埋点系统设计

---

### 采集事件

#### 题目行为

```json
{
  "uid": 1001,
  "event": "open_problem",
  "pid": 2001,
  "timestamp": 1710000000
}
```

---

### 提交行为

```json
{
  "uid": 1001,
  "event": "submit",
  "pid": 2001,
  "lang": "cpp",
  "result": "WA",
  "time_cost": 1200
}
```

---

### 编辑器行为

```json
{
  "uid": 1001,
  "event": "editor_active",
  "duration": 600
}
```

---

### 查看题解

```json
{
  "uid": 1001,
  "event": "view_solution",
  "pid": 2001
}
```

---

## 4.3 前端埋点 SDK

---

## 设计原则

不能影响 OJ 性能。

因此：

```text
异步上报
批量发送
低频率
```

---

## SDK 示例

```javascript
track({
  event: "submit",
  pid: 1001,
  result: "WA"
});
```

---

## 4.4 数据流

```text
前端埋点
    ↓
Nginx
    ↓
behavior-service
    ↓
Redis Stream / Kafka
    ↓
异步消费
    ↓
MySQL / ES
```

---

# 5. 用户画像系统

---

## 5.1 用户画像目标

建立：

```text
用户能力模型
```

---

## 5.2 能力维度

---

### 算法维度

```json
{
  "dp": 75,
  "graph": 63,
  "math": 40,
  "greedy": 80
}
```

---

### 行为维度

```json
{
  "debug_ability": 50,
  "optimization_ability": 30,
  "independent_thinking": 70
}
```

---

### 编码维度

```json
{
  "modern_cpp": 80,
  "code_style": 65,
  "template_usage": 72
}
```

---

## 5.3 用户画像更新机制

---

### 实时更新

触发：

- AC
    
- WA
    
- TLE
    
- 查看题解
    

---

### 周期更新

每天凌晨：

```text
重新计算画像
```

避免实时压力。

---

# 6. 题目知识图谱

---

## 6.1 题目标签系统

---

### 标签结构

```json
{
  "pid": 1001,
  "tags": [
    "dp",
    "tree",
    "binary_search"
  ],
  "difficulty": 1600
}
```

---

## 6.2 标签来源

---

### 来源1：Codeforces

利用：

```text
CF API
```

获取：

- tag
    
- rating
    

---

### 来源2：AtCoder

AtCoder 没有官方完整 API。

推荐：

```text
AtCoder Problems API
```

---

### 来源3：Clist

使用：

[Clist API](https://clist.by/api/v4/doc/?utm_source=chatgpt.com)

获取：

- rating
    
- contest
    
- tag
    

---

## 6.3 多平台题目同步（VJ模式）

---

### 推荐方案

不要：

```text
爬虫爬页面
```

风险极高。

应该：

### 优先使用官方 API

---

#### Codeforces

[Codeforces API](https://codeforces.com/apiHelp?utm_source=chatgpt.com)

允许：

- 获取题目
    
- 获取提交
    
- 获取比赛
    

风险低。

---

#### AtCoder

官方 API 不完整。

建议：

[AtCoder Problems API](https://kenkoooo.com/atcoder/resources/?utm_source=chatgpt.com)

---

#### 洛谷

不建议爬取题目正文。

风险：

- 版权
    
- 反爬
    
- 法律问题
    

推荐：

```text
仅允许用户手动绑定训练记录
```

不要同步题面。

---

## 6.4 法律风险规避

---

## 必须遵守

---

### 1. 不存储题面

不要：

```text
复制整个题目
```

而是：

```text
跳转原平台
```

---

### 2. 不镜像提交系统

不要：

```text
自己判 CF 原题
```

否则风险高。

---

### 3. 使用公开 API

优先：

- 官方 API
    
- 开放数据集
    

---

### 4. 标注来源

例如：

```text
Source: Codeforces Round #xxx
```

---

# 7. AI Agent 系统

---

## 7.1 核心原则

---

## 正确结构

```text
行为分析
→
状态识别
→
规则引擎
→
知识图谱
→
LLM生成建议
```

---

## 7.2 Agent 状态机

---

### 状态定义

```text
思考
编码
调试
卡住
放弃
```

---

## 7.3 卡点识别

---

### 思路问题

特征：

```text
多次WA
代码变化小
长时间停留
```

---

### 优化问题

特征：

```text
持续TLE
复杂度未下降
```

---

### 实现问题

特征：

```text
RE
CE
数组越界
```

---

## 7.4 AI 提示策略

---

### 非侵入式

不要：

```text
自动弹窗轰炸
```

推荐：

```text
侧边栏提示
```

---

### 渐进式提示

第一层：

```text
提醒方向
```

第二层：

```text
提示算法
```

第三层：

```text
提示关键点
```

不要直接给题解。

---

# 8. 代码分析系统

---

## 8.1 为什么必须分析 AST

因为：

```text
提交结果不够
```

无法真正判断：

- 思路
    
- 代码质量
    
- 能力水平
    

---

## 8.2 推荐技术

---

### C++

推荐：

```text
tree-sitter
clangd
libclang
```

---

## 8.3 分析内容

|分析项|用途|
|---|---|
|for嵌套|复杂度|
|STL使用|熟练度|
|lambda|现代C++|
|DFS/BFS模板|算法识别|
|状态数组|DP识别|

---

# 9. 推荐系统

---

## 9.1 第一阶段推荐

不要一开始做：

```text
协同过滤
```

---

### 推荐：

```text
知识点缺陷推荐
```

---

## 9.2 推荐逻辑

---

### 基础公式

```text
推荐分 =
弱项权重
×
题目匹配度
×
难度适应度
```

---

### 难度控制

推荐：

```text
当前能力 ±200
```

---

## 9.3 学习路径

---

### 示例

```text
前缀和
→ 差分
→ 树状数组
→ 线段树
```

---

# 10. 比赛隔离模式（非常重要）

---

## 10.1 隔离

比赛期间：

```text
AI 必须完全关闭
```

---

## 10.2 隔离策略

---

### 比赛模式

进入比赛：

```text
contest_mode = true
```

---

### 自动关闭

关闭：

- AI Sidebar
    
- 推荐
    
- 行为分析提示
    
- 自动建议
    
- 智能搜索
    

---

### 前端隔离

比赛页面：

```text
不加载 AI SDK
```

---

### 后端隔离

API 网关：

```text
拦截 AI 请求
```

---

## 10.3 比赛后开放

比赛结束：

```text
恢复分析
```

允许：

- 赛后复盘
    
- AI讲解
    
- 卡点分析
    

---

# 11. 数据库设计

---

## 11.1 用户画像表

```sql
CREATE TABLE user_profile (
    uid BIGINT PRIMARY KEY,
    dp_score INT,
    graph_score INT,
    math_score INT,
    optimization_score INT,
    updated_at DATETIME
);
```

---

## 11.2 行为事件表

```sql
CREATE TABLE behavior_event (
    id BIGINT PRIMARY KEY,
    uid BIGINT,
    pid BIGINT,
    event_type VARCHAR(32),
    payload JSON,
    created_at DATETIME
);
```

---

## 11.3 推荐记录表

```sql
CREATE TABLE recommend_record (
    id BIGINT PRIMARY KEY,
    uid BIGINT,
    pid BIGINT,
    reason TEXT,
    created_at DATETIME
);
```

---

# 12. 开发流程

---

## Phase 1：数据闭环（最重要）

---

### 目标

建立：

```text
行为数据 → 数据库
```

---

### 开发内容

- 埋点 SDK
    
- 行为服务
    
- 用户画像基础版
    
- 标签系统
    

---

## Phase 2：推荐系统

---

### 开发内容

- 能力评分
    
- 标签推荐
    
- 学习路径
    

---

## Phase 3：AI Agent

---

### 开发内容

- 卡点识别
    
- 状态机
    
- AI 提示
    

---

## Phase 4：代码分析

---

### 开发内容

- AST
    
- 风格分析
    
- 复杂度分析
    

---

## Phase 5：高级智能化

---

### 开发内容

- RAG
    
- 长期记忆
    
- 个性化学习规划
    

---

# 13. 推荐技术栈

---

## 后端

|模块|技术|
|---|---|
|微服务|SpringCloud|
|AI服务|Python FastAPI|
|MQ|Kafka|
|缓存|Redis|
|搜索|Elasticsearch|

---

## 前端

|模块|技术|
|---|---|
|前端|Vue3|
|状态管理|Pinia|
|图表|Echarts|

---

## AI

|功能|模型|
|---|---|
|对话|GPT|
|代码分析|DeepSeek-Coder|
|Embedding|bge-m3|
|rerank|bge-reranker|

---

# 14. 风险点

---

## 14.1 最大风险

## 14.2 正确顺序

```text
先数据
再画像
再推荐
最后Agent
```

---

## 14.3 不建议

---

### 不建议一开始：

- 上 LangChain
    
- 上 AutoGPT
    
- 上 多Agent
    
- 上 MCP
    

---

# 15. 最终目标

最终形成：

```text
OJ
+
学习系统
+
训练系统
+
AI教练
```
