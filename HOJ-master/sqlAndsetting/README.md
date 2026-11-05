> 这是 HOJ 数据库初始化文件、增量结构与服务配置文件。

2026-11-05 CF账号分页一致性升级：已有库先执行 `external-cf-pagination.sql`，再更新业务后端。脚本仅向 `external_sync_job` 添加可空 `cf_head_hash CHAR(64)`，可重复执行，不清空游标、账号、提交或其他平台数据；新库的 `hoj.sql` / `external-learning.sql` 已包含此列。不要对旧库重跑完整初始化或删除数据卷。

新CF扫描按500条请求、相邻页重叠1条校验原始ID；遇到短尾页或增量checkpoint后，进入内部 `VERIFY_STATUS` 阶段重取首屏，核验后才更新成功时间、checkpoint并按全量语义清理未见提交。ghost参与位置校验但不计学习证据；空历史也要复核两次。漂移时保留缓存、换扫描世代并退避至少1分钟，从PROFILE重新开始；成功PROFILE/RATING会重置临时失败计数，不承诺跨多轮漂移退避逐轮增长。旧的无首屏指纹在途游标会自动重开。公开API不是原子快照，此机制校验遍历边界与首屏稳定性，不保证任意并发来源修改下的瞬时完整历史。

若回滚到不识别VERIFY_STATUS的旧后端，应先停止后端，再仅重排尚未完成的CF作业（保留checkpoint及全部缓存），之后启动旧镜像；无需删除新列。以下是回滚接续步骤，不属于正常升级时要执行的SQL：

```sql
UPDATE external_sync_job j JOIN external_account a ON a.id=j.account_id
SET j.stage='PROFILE',j.from_index=1,j.working_high_id=0,j.last_page_min_id=NULL,
    j.cf_head_hash=NULL,j.scan_token=UUID(),j.lease_token=NULL,j.lease_until=NULL,
    j.next_run_at=NOW(3),j.failures=0,j.last_error=NULL
WHERE a.platform='CODEFORCES' AND j.stage<>'DONE';
```

实际迁移、隔离MySQL并发测试及本地更新状态见[任务记录](../docs/docs/ai/TODO.md)，未执行的步骤不因出现在文档中而视为已执行。

2026-10-27 牛客专项与试卷记录升级：已有库在 `external-learning.sql` 之后执行可重复的 `nowcoder-practice.sql`，再更新业务后端与前端。仅新增 `external_nowcoder_practice_job`、`external_nowcoder_practice_record` 两表，按账号级联清理；不修改编程提交、通过题数、比赛成绩或 Rating。空库由 Compose 新增的 `06-nowcoder-practice.sql` 包装器加载同一 DDL；保留已有全部 SQL 挂载，不对旧库重跑 `hoj.sql` 或删除数据卷。已执行的迁移、MySQL 测试与本地部署证据见 [任务记录](../docs/docs/ai/TODO.md)，下述 10-21 首次初始化是新增两表之前的历史检查点。

2026-10-27 已从准确提交快照复验首次初始化：独立、断网、无主机端口 / 持久卷的 MySQL 创建 99 张表、51 张功能表、94 个完整关联的知识点。`verify-fresh-init.py` 现将 `nowcoder-practice.sql` 纳入 23 份可重复迁移，每份重放两次后列 / 索引 / 外键结构与自定义项保持，临时容器已清理。证据在 D:/learning-OS-RAG/data/hoj-app-release-20261027/fresh-init；这仍是数据库初始化与重放证据，不能替代全新整栈部署验收。

2026-10-21 最新首次初始化已在独立空 MySQL 数据目录验证：复用 Compose 实际 9 个 SQL 挂载和服务器参数，创建 HOJ 97 张表，其中 49 张新增功能表齐全（含训练清单），Nacos 库及初始管理员存在；94 个预置算法知识点的题目标签 / 训练分类 / 标签组关联完整。21 份可重复迁移各执行两轮后，列 / 索引 / 外键结构不变，自定义题目标签与训练分类保留。一次性 ALTER 脚本没有重放，只检查其字段已包含在新库。此检查验证数据库首次初始化，不代表全新前后端 / Nacos / Judge / RAG 整栈已验收。最新证据见任务记录；此前 7 挂载 / 96 表报告保留为历史。

训练清单升级：已有库只执行可重复的 learning-training-plan.sql，再更新业务后端和前端；新增本人清单表，不修改原题目 / 答卷 / 判题记录。本机已重复应用两次。新库由 Compose 的 05-learning-training-plan.sql 包装加载同一 DDL，不需要手工运行或重跑 hoj.sql，不删除已有数据卷。具体权限、配额和幂等行为见 [训练清单接口](../docs/docs/ai/08-data-model-and-api.md#当前已实现本人训练清单2026-10-21)。

可用仓库脚本复验，无额外 Python 包依赖；前提是本机 Docker 可用且已有 Compose 指定的 MySQL 镜像。从 Git 根目录运行，`--output` 使用新的证据目录：

```powershell
python HOJ-master/sqlAndsetting/verify-fresh-init.py --output D:/learning-OS-RAG/data/hoj-fresh-init-next
```

脚本只启动唯一命名 / 标签的临时容器，禁用网络、不发布端口、使用 tmpfs、不挂载现有数据库卷；结束核对名称和标签后删除该临时容器，拒绝覆盖已有报告。Compose 渲染后的凭据只在内存中，不写入报告；测试使用独立随机密码。结果及精确源码哈希见 fresh-init.json / source-hashes.json；本次证据在 D:/learning-OS-RAG/data/hoj-fresh-init-evidence-20261021/portable，validated / cleaned=true。现有服务和数据卷没有重建。

2026-10-20 扫描 PDF：已有知识库五表的库先执行 `knowledge-ocr.sql`，给原件增加 extraction_mode，旧行默认 NATIVE；脚本已在本地 MySQL 连续执行两次。新库由 knowledge-base.sql 直接创建该字段。模式为 NATIVE / OCR / OCR_REVIEWED；OCR 校订生成新版本、保留原件及旧发布，不重建卷、不重跑 hoj.sql。新后端使用该字段，需先迁移再更新；详细契约与验证边界见 RAG 文档及 TODO。

2026-10-20 牛客比赛上下文：已有 external-learning.sql / nowcoder-learning.sql 的库先执行 `nowcoder-context.sql`，再更新业务后端与前端；JudgeServer 和 RAG 无需更新。脚本新增独立任务、参赛摘要、题号映射、来源提交关联四表，已在本地 MySQL 重复执行，不改原提交账本或总通过题数。Compose 空库初始化通过 `nowcoder-context-init.sql` 选择 hoj 并 SOURCE 同一份 DDL，独立空目录初始化验证见本文顶部。无需重跑 hoj.sql 或删除数据卷；独立每日扫描沿用 `hoj.external-sync.enabled` 开关。字段、隐私及计数边界见 [牛客比赛上下文](../docs/docs/ai/08-data-model-and-api.md#当前已实现牛客比赛上下文2026-10-20)，当前验证证据以任务记录为准。

2026-10-19 知识库资料业务：已有库先在 hoj schema 执行 `knowledge-base.sql`，可重复执行；新增 knowledge_document / knowledge_version / knowledge_command / knowledge_job，以及 PDF 原件表 knowledge_source，不重写已有题目或卷子。knowledge_source 以资料 / 版本为主键，MEDIUMBLOB 保存最多 8 MiB 的不可变原件及哈希 / 文件名 / 物理页信息，与版本和入库任务同事务写入；逻辑删除撤回访问并清理索引，原件仍随历史版本保留。新建 Compose 数据卷通过 `knowledge-base-init.sql` 包装器选择 hoj，再 SOURCE `/opt/hoj/knowledge-base.sql`，五张表的 DDL 只在一个文件维护，首次数据库初始化验证见本文顶部，现有卷未重建。不要在已有卷重跑 hoj.sql 或 nacos.sql。基础 Compose 默认不配置内部 RAG；本地已通过可选 `compose.rag.yaml` 接通常驻 CPU 服务、独立共享凭据卷与索引卷。资料索引成功后仍须管理员显式发布，用户模型 Key 不交给 Python。启用后的后续 `up` 必须保留第三份 overlay 及其本地路径环境文件，避免撤掉后端的服务配置，命令见 [RAG 运行说明](../docs/docs/ai/02-rag-knowledge-base.md#11-知识库页面与常驻本地索引已接通待用户验收)。实际验证和剩余范围见 [任务记录](../docs/docs/ai/TODO.md)。

2026-10-18 用户提交入口不新增 SQL；继续要求完成上述个人任务迁移。`request_hash` 已用于完整提交请求去重，旧记录可保持 NULL；AtCoder 仍要求可信目录中的比赛范围。先迁移、更新两端镜像，再启用后端入口与判题执行器；保留原密钥卷。当前仅本地事务、浏览器和控制源站样例通过，真实 CF / AtCoder 账号提交尚待验收；牛客未接入。协议见 [数据模型与 API](../docs/docs/ai/08-data-model-and-api.md)。

2026-10-17 会话授权更新无需新增 SQL：个人凭据的新密文使用 `s1:` 并绑定会话用途，旧 `v1:` 密码显示需重新授权，不可作为新个人任务的凭据；前缀不可互换。保存前只读核对源站登录账号，保存不触发代码提交；当前仍无本人真实会话或远程 AC / WA 在线验收。业务后端和前端应同步更新，保留原密钥卷，协议见 [数据模型与 API](../docs/docs/ai/08-data-model-and-api.md)。

个人远程任务（执行器与用户入口已接入，真实源站待验收）：已有库先执行 `personal-remote-tasks.sql`，再执行可重复的 `personal-remote-dispatch.sql`，后者补 `source_contest` 和 `request_hash`；新建库初始化段已有两列。源站比赛范围固定在任务内，旧任务缺少范围时停止，不推断或重投。任务不保存密码 / Cookie；账号锁按平台与规范 handle 保留，解绑不自动释放不确定提交。

业务后端与 JudgeServer 均通过 `hoj.personal.remote.enabled=true`（环境变量 `HOJ_PERSONAL_REMOTE_ENABLED=true`）启用独立执行器，默认关闭；先迁移，再更新业务后端与判题端。判题端需要连接与业务后端相同的 Redis，执行器的 CF 请求与公开同步共享门控；Redis 不可用时不请求源站。Compose 已设置本地连接并将 `hoj-local-remote-secrets` 挂载到判题端 `/hoj/secrets/remote:ro`；不得换成另一个密钥卷。自动 Cookie 轮换只更新加密会话，不改变授权版本或覆盖用户撤销。当前尚无用户提交入口、本人真实源站登录或远程 AC / WA 证据，不能将执行器部署成功当作功能完成。进度与验证见 [任务记录](../docs/docs/ai/TODO.md)。

个人提交凭据升级：在已有 `external-learning.sql` 的库中执行 `remote-credentials.sql`，增量创建 `remote_user_credential`，可重复执行，初始化末尾有相同段；CF 注册身份变化路径也依赖此表，应先迁移再更新业务后端。Compose 新增独立 `hoj-local-remote-secrets` 卷，包装密钥保存在 `/hoj/secrets/remote/master.key`，和 AI 密钥卷分别管理。已有密文而密钥丢失 / 损坏时拒绝解密及覆盖，不会静默生成替代密钥。解除关联级联删除相应密文；删除凭据只清空密文，保留版本以拒绝旧编辑，不修改管理员账号池。保存、源站登录、提交与在线验收分别记录，详见 [任务记录](../docs/docs/ai/TODO.md)。

2026-10-17 AtCoder 公开账号升级：在已执行 `external-learning.sql`、`remote-sources.sql` 的库中执行 `atcoder-learning.sql`，再更新业务后端和前端。增量创建账号时间戳游标、字符串比赛评级历史和全局题目元数据三表，并添加目录 / 难度模型后台任务；已在本地 MySQL 重复执行，`hoj.sql` 末尾包含相同结构，不重跑全量初始化。归属校验使用官方 Affiliation；官方算法评级与第三方 AtCoder Problems 提交 / 估计难度分开展示。目录每小时、模型每 6 小时更新，已验证账号每 15 分钟增量 / 每日全量核对；沿用共享门控、缓存保留和 `hoj.external-sync.enabled` 开关。实际验收与限制见 [任务记录](../docs/docs/ai/TODO.md)，不代表已接通 AtCoder 远程提交或双语抓题。

2026-10-17 CF 题目目录与双语版本升级：已有前述基础库先执行 `external-learning.sql`，再执行 `remote-sources.sql`，随后更新业务后端和前端。后者只创建 `remote_source_job`、`remote_catalog_problem`、`remote_problem_import`、`remote_statement_version`、`remote_statement_current` 五表及目录任务，可重复执行；本地 MySQL 已重复执行，原有题目和账号关联数量保持不变。`hoj.sql` 末尾包含相同结构，不需要重跑全量初始化或删除数据卷。

管理端“题库 → 远程题目”每小时同步 CF 公开目录，选题后排队获取；新入库默认不公开，管理员检查后在原题目编辑页开放。原文和中文分别保存版本，原文更新后旧译文停止公开，刷新不覆盖题库手工内容。当前 CF 实际题面请求返回 403，可在源站查看并录入原文；目录 API 已成功同步，不能把目录成功当作抓题 / 远程提交成功。目录、导入和公开账号统计共用独立 worker、Redis 门控及 `hoj.external-sync.enabled` 开关。迁移、部署与验收证据见 [任务记录](../docs/docs/ai/TODO.md)。

2026-10-17 CF 公开账号与统计升级：在已完成下述迁移的数据库执行 `external-learning.sql`，再更新业务后端和前端。脚本只创建 `external_account`、`external_sync_job`、`external_problem`、`external_submission`、`external_rating_event`，可以重复执行；本地 MySQL 已重复执行通过，未重跑 `hoj.sql` 或删除数据卷。初始化脚本末尾包含同一段结构。外部账号通过服务端当前 UID 管理，未验证关联只对本人可见；验证身份全站唯一，解除关联级联移除本人同步任务和来源证据，不删除本站成绩或全局题目元数据。没有保存远程平台密码、Cookie 或提交凭据。

CF 同步在独立后台线程执行，Redis 跨节点间隔至少 3 秒，失败保留已有记录并退避重试；Redis 不可用时停止外部请求。首次分页回填，已验证账号每 15 分钟增量同步、至少每天全量核对；未验证账号首次完成后只按本人请求同步。部署后端前先迁移表；可通过 `hoj.external-sync.enabled=false` 停止此后台任务。账号统计模块只同步公开资料、评级历史和作答统计，题面由上方独立导入队列处理；两者均不代提交。真实验证和后续平台缺口见 [任务记录](../docs/docs/ai/TODO.md)。

2026-10-17 学习画像与 AI 升级：在已完成客观题、混合卷和 Rating 迁移的库中，先执行 `algorithm-topics.sql` 和 `ai-assistant.sql`，再更新业务后端与前端。两份均为增量脚本，可重复执行；不要重跑 `hoj.sql`。前者补齐 8 组、94 个固定算法知识点及其题目标签 / 训练分类映射，不删除自定义项或作答记录；同名主站标签 / 分类优先复用。目录权威源为 DataBackup 的 `src/main/resources/algorithm-topics.json`，变更后用 `scripts/generate-algorithm-topics.py` 同步 SQL。新安装的 `hoj.sql` 已包含相同结构。

`ai-assistant.sql` 创建每用户模型设置 `ai_user_setting` 和公开赛看题记录 `ai_contest_entry`。后者识别无需密码报名的公开赛参赛者，不改变榜单、成绩或报名表；仅查看列表 / 榜单不建立记录。正式报名、比赛提交和混合赛答卷同样参与服务端 AI 限制判断，实际规则见任务记录。脚本不包含 API Key。

2026-10-19 虚拟计时升级：已有数据库先执行 `virtual-contests.sql`，再执行 `mixed-virtual-contest.sql`，然后更新后端。前者只新增 `contest_virtual_run`，后者给现有 `paper_run` 新增 nullable `virtual_run_id` 与唯一索引；均可重复执行，不清空原题目 / 报名 / 提交或模型配置。新建库的 `hoj.sql` 含对应结构，`paper.sql` 含新答卷字段。本人已结束编程 / 混合赛的计时窗口用于统一 AI 限制，按服务端保存的个人截止生效，无提前取消接口，不创建正式报名或比赛 Rating。混合虚拟答卷单独保存，正式 contest_id=NULL；普通编程虚拟窗口的独立结果与公开虚拟榜单仍未接入，不能把计时表视为完整比赛成绩模型。

Key 使用 AES-GCM 加密，包装密钥首次保存时生成在后端 `/hoj/secrets/ai/master.key`；Compose 使用独立命名卷 `hoj-local-ai-secrets`。换镜像保留此卷，不能只迁移数据库而丢弃密钥卷。存在已保存配置但包装密钥缺失时，后端拒绝解密 / 覆盖，不会静默生成替代密钥。删除全部模型配置后可重新配置；此说明不构成删除卷的授权。

尚未创建客观题表时，使用 MySQL 客户端在现有 HOJ 数据库中执行 `objective.sql`，再更新业务后端和前端。该脚本只创建客观题的四张表；重复执行不会清空表中数据，也不会升级已经存在的旧表结构。

已部署旧版客观题表、但 `objective_question` 尚无 `practice_enabled` 字段时，先执行一次 `objective-standalone.sql`，再更新后端。该脚本新增单题练习开关和作答题目引用，允许作答不引用题集；保留原题目、题集与记录。此 ALTER 脚本不能重复执行；检查 `information_schema.columns` 中的 `objective_question.practice_enabled` 与 `objective_run.question_id` 可确认升级结果。已有题目默认允许单题练习，旧题目 JSON 没有开放策略时按提交后开放处理；管理端可以关闭仅用于试卷的题目的单题入口。

全新安装使用 `hoj.sql`，其末尾包含相同客观题结构。已有数据库不要重跑 `hoj.sql`，其中包含原系统初始化操作。

混合试卷升级：先执行 `paper.sql` 创建 `paper`、`paper_run`、`paper_program_snapshot` 三张表，再执行一次 `paper-judge.sql` 为 `judge` 增加 `paper_run_id`、`paper_snapshot_id` 及组合索引。ALTER 脚本不能重复执行，升级前检查 `information_schema.columns`；全新 `hoj.sql` 已包含相同字段、索引和表。必须同时更新业务后端与 JudgeServer，旧判题服务无法读取冻结的试卷配置与数据。填空题扩展复用客观题版本 JSON，无额外结构迁移。

2026-10-15 已在本地 HOJ 的 MySQL 8.0.46 执行 `objective.sql`，四张客观题表均创建为 InnoDB，执行后均为空表。2026-10-16 Docker 重启后再次确认结构保留。按用户要求未备份本地数据。后端更新、后续联调与验收状态统一见 [任务记录](../docs/docs/ai/TODO.md)。

2026-10-16 追加执行 `objective-standalone.sql`：升级前题目 3 条、题集与作答均 0 条；升级后原题目 ID 1/2/3、标题及发布状态保留，三题 `practice_enabled` 均为 1，作答的题集与版本引用已允许为空，新增 `question_id` 列。未重跑初始化 SQL 或创建业务样例。

2026-10-16 追加执行 `paper.sql` 与 `paper-judge.sql`：已核对三张混合卷表、`judge` 两个新增字段及索引。保留原数据卷与三道客观题；实际账号联调创建的两个临时账号、题目、试卷、答卷和提交记录均已清理，复核临时账号、混合卷答卷及混合卷提交计数均为 0。未重跑初始化脚本。填空题无需额外 ALTER，业务后端和 JudgeServer 已同步更新。

正式混合比赛升级：已有旧版 `paper_run` 且尚无 `contest_id` 时，执行一次 `mixed-contest.sql`；它新增 `contest_paper`、可空的 `paper_run.contest_id` 和 `(contest_id,uid)` 唯一索引，不修改题库或普通答卷。ALTER 段不可重复执行，先检查字段与索引。全新安装使用 `hoj.sql`；若已有 HOJ 基础表、刚运行新版 `paper.sql`，其中已含比赛列与索引，只执行 `mixed-contest.sql` 的 CREATE TABLE 段。随后更新业务后端；JudgeServer 沿用混合卷快照判题协议。

2026-10-16 本地已追加执行混合比赛增量迁移，未备份或删除数据卷；部署与真实账号验收进度见任务记录。

站内 Rating 升级：在完成前述迁移的已有库中执行一次 `site-rating.sql`，它增加可空 `problem.rating`、默认关闭的 `contest.rated`，以及 Rating 的快照、历史和结算锁三张表；ALTER 段不可重复执行。全新安装已包含于 `hoj.sql`，无需另跑此 ALTER。2026-10-16 本地已应用，原题目保持未评级、原比赛保持不计 Rating；管理员可录入 0–5000 的题目评级，并在主站公开赛开赛前开启计分。后端和判题端共享题目实体，本次两者同步更新；部署验收进度见任务记录。 2026-10-22 取消平时 Rating：只结算 / 查询 contest 类型，原 practice 行留库且不更新、不公开；题目 rating 与比赛 rated 继续保留，无额外迁移。

2026-10-18 牛客公开账号升级：先完成 external-learning.sql，再执行 nowcoder-learning.sql，随后更新业务后端。新增 external_nowcoder_profile，保存数字用户 ID 对应的显示名、已核对普通练习汇总及分页扫描状态，不保存密码 / 会话；解除关联级联清理。脚本已在本地 MySQL 重复执行，与 hoj.sql 末尾对应段一致；不要对已有库重跑完整初始化。前端新增牛客选择和公开简介校验方向。首次 / 每日全量、验证后每 15 分钟增量及首页指纹复核；未验证完成后按本人请求同步。只覆盖普通练习和评级赛历史，竞赛提交页 / 专项练习 / 牛客目录与个人提交仍待接入；缺失题目难度与算法分类保持未知。详细边界见 API 与任务记录。

2026-10-18 牛客会话与提交前校验升级：本轮无新表迁移，沿用 external-learning.sql、nowcoder-learning.sql、remote-credentials.sql 和已有个人任务结构，更新共享 api 的后端 / 判题镜像及前端。已验证牛客账号可以授权本人浏览器会话，密文按 NOWCODER + 数字用户 ID 隔离；匿名设备 Cookie 不能验证登录。设置和只读 accounts / options 返回 submissionSupported=false，实际代码投递仍被服务端和界面阻止。公开题目语言元数据请求不带 Cookie；有效会话成功路径须用户后续实际体验，真实源站当前仅验证无效会话拒绝。完整状态和验证证据以任务记录为准。

2026-10-18 牛客个人投递升级（取代上一段的未开放状态）：在已有 personal-remote-tasks.sql、personal-remote-dispatch.sql 和上述牛客基础表的库中执行 nowcoder-remote.sql，再同步更新业务后端、JudgeServer 和前端。脚本新增 personal_remote_nowcoder，主键引用个人任务并级联删除；只保存六项非秘密来源快照、指纹、后台 job ID 与公开 submission ID，不包含会话、短期 token 或重复代码。可重复执行，hoj.sql 包含相同初始化段；不要重跑完整初始化或删除数据卷。

本版同时开启牛客 submissionSupported 和前端提交按钮，保留单次 POST 与身份队列约束。已知 job 可重启续查；完全丢失投递响应且无 job 的任务保留待核对，不能通过重投“恢复”。真实账号授权及源站 AC / WA 未验收，当前仅完成受控协议与本地接口验证。最终部署、测试数量及运行状态见 [任务记录](../docs/docs/ai/TODO.md)。

2026-10-18 AtCoder 目录选题与题面版本升级：无新增表，沿用 external-learning.sql、remote-sources.sql 与 atcoder-learning.sql。同步更新业务后端和前端；JudgeServer 本轮无代码变更。管理端远程题目增加 AtCoder 平台，复用已有每小时目录与每 6 小时难度模型，选题后抓取官方公开英文页并默认以 auth=2 入库；原文和中文版本仍分别保存。缺少 AC 静态语言时，导入仅关联 ME 语言供编辑器 / 本站自测，真实个人投递按源站当前表单选择语言，不新增猜测的远程语言编号。刷新不覆盖题库手工编辑，来源更新会隐藏旧译文；没有英文的旧题要求管理员补录英文。无需重跑初始化或清理数据卷，真实验收以 [任务记录](../docs/docs/ai/TODO.md) 为准。

2026-10-18 题面定期刷新升级：已有 remote-sources.sql 的库先运行 remote-statement-refresh.sql，再更新业务后端与前端。新增 nullable auto_refresh、last_checked_at 和 last_success_at，可重复执行；新建库 CREATE 结构与初始化段已同步。迁移按当前来源版本仅为未选择策略的已有导入设默认：SOURCE 开启每日检查、MANUAL 关闭，不重写任何题面或已有显式策略。首次官方导入默认开启，人工保存原文关闭，管理员可在“原文与译文”重新选择；更新内容保留题库手工编辑，失败保留已有版本。按顺序升级后可回滚旧后端，新增字段不影响旧读取；不得重跑全量初始化或删除数据卷。最终验证与部署见任务记录。

2026-10-18 牛客 ACM 目录与题面升级：已有 external-learning.sql、remote-sources.sql、remote-statement-refresh.sql 的库先执行 nowcoder-sources.sql，再更新业务后端和前端；JudgeServer 本轮无代码变更。新增 remote_nowcoder_problem（可信公开目录）、remote_nowcoder_scan（断点 / generation）与 remote_nowcoder_catalog_stage（全量暂存），以及 NOWCODER_PROBLEMS 任务种子。脚本可重复执行，不重置已有扫描或目录，hoj.sql 含相同初始化段。失败按原页续扫，完整分页和首页复核后才发布全量 / 标记移除；首批 50 题仅是可先选题的缓存。新增牛客普通 / 比赛中文原文导入、管理员英文译文与逐题每日检查；首次默认私有，不把来源 Rating 写作站内 Rating。无需重跑初始化或删除数据卷，真实全量完成状态与后端版本以 [任务记录](../docs/docs/ai/TODO.md) 为准。

2026-10-20 视频资料升级：已有 knowledge-base.sql 的库执行 knowledge-video.sql，再使用 compose.video.yaml 与本地 Whisper 权重启用视频。新增 knowledge_video_quota、knowledge_video_import、knowledge_video_source 三表，脚本不覆盖旧库或重置容量；本机已重复应用两次。新库经 knowledge-base-init.sql 自动包含该 DDL，Compose 同时挂载实际脚本。上传草稿、转写、审阅保存、索引、发布分别进行；原件存在私有卷，模型目录只读挂载。后续启动 / 更新须继续带 compose.video.yaml，避免丢失媒体挂载与处理配置。当前运行镜像、闭环与验收状态以任务记录为准，不删除已有数据卷，不对已有库重跑 hoj.sql。
