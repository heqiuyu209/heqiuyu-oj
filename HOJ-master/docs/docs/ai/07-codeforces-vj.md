# Codeforces 远程提交与 VJ 实现文档

## 1. 目标范围

第一阶段只实现 Codeforces。洛谷和 AtCoder 暂不接入，但代码结构预留远程平台适配器。

VJ 的核心不是简单显示一个外链，而是：

- 在 HOJ 中维护远程题目映射；
- 用户从 HOJ 发起提交；
- 后台把代码提交到 Codeforces；
- 轮询远程结果；
- 映射为 HOJ 可理解的状态；
- 在训练和排行榜中统计。

## 2. 抽象接口

```text
RemoteJudgeProvider
  login(account) -> session
  fetch_problem(remote_id) -> RemoteProblem
  submit(session, problem, language, code) -> RemoteSubmission
  query_result(session, submission_id) -> RemoteResult
```

以后新增洛谷或 AtCoder，只增加 Provider 和状态映射，不修改 VJ 业务主流程。

## 3. 远程题目模型

建议保存：

- 平台：`codeforces`；
- contest ID；
- problem index；
- 远程题目 ID 和 URL；
- 标题、标签、难度、时间限制、内存限制；
- HOJ 题目 ID 或 VJ 题目 ID；
- 题面同步时间和版本。

不要把 Codeforces 的题号直接当作 HOJ 主键。远程题目会重编号、跨 contest 重复 index，必须使用独立映射。

## 4. 提交流程

```text
用户提交
 → HOJ 鉴权和 VJ 权限检查
 → 校验语言映射和代码长度
 → 创建 REMOTE_PENDING 记录
 → 异步队列
 → Provider 提交 CF
 → 保存远程 submission id
 → 轮询结果
 → 映射状态
 → 更新 HOJ 训练记录/榜单
```

远程提交必须异步化。不能让浏览器请求一直等待 Codeforces 返回最终结果。

## 5. 状态映射

需要建立显式映射表，而不是使用字符串包含判断。例如：

- Accepted → AC；
- Wrong answer → WA；
- Time limit exceeded → TLE；
- Memory limit exceeded → MLE；
- Runtime error → RE；
- Compilation error → CE；
- 测试中/排队 → PENDING；
- 网络、账号或平台错误 → REMOTE_ERROR。

未知状态必须保留原始文本并映射为可追踪的 `REMOTE_UNKNOWN`，不能误判为通过。

## 6. 账号与合规风险

Codeforces 可能存在登录、验证码、访问频率和接口限制。实现前要确认：

- 使用官方允许的接口或合适的远程提交方式；
- 不在服务器日志保存密码和 Cookie；
- 账号凭据加密保存；
- 对同一账号限流；
- 平台不可用时允许用户重试；
- 不把 CF 账号密码和 HOJ 密码混用。

## 7. VJ 训练和排名

VJ 训练需要单独记录：

- 训练包含的远程题目；
- 用户是否注册；
- 首次通过时间；
- 提交次数和罚时规则；
- 是否允许重复提交；
- 排行榜使用 HOJ 规则还是 CF 规则。

第一版建议只实现“题目列表 + 提交 + 通过状态”，确认远程链路稳定后再做完整罚时和比赛榜单。
