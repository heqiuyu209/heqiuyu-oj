> 这是 HOJ 数据库初始化文件、增量结构与服务配置文件。

尚未创建客观题表时，使用 MySQL 客户端在现有 HOJ 数据库中执行 `objective.sql`，再更新业务后端和前端。该脚本只创建客观题的四张表；重复执行不会清空表中数据，也不会升级已经存在的旧表结构。

已部署旧版客观题表、但 `objective_question` 尚无 `practice_enabled` 字段时，先执行一次 `objective-standalone.sql`，再更新后端。该脚本新增单题练习开关和作答题目引用，允许作答不引用题集；保留原题目、题集与记录。此 ALTER 脚本不能重复执行；检查 `information_schema.columns` 中的 `objective_question.practice_enabled` 与 `objective_run.question_id` 可确认升级结果。已有题目默认允许单题练习，旧题目 JSON 没有开放策略时按提交后开放处理；管理端可以关闭仅用于试卷的题目的单题入口。

全新安装使用 `hoj.sql`，其末尾包含相同客观题结构。已有数据库不要重跑 `hoj.sql`，其中包含原系统初始化操作。

混合试卷升级：先执行 `paper.sql` 创建 `paper`、`paper_run`、`paper_program_snapshot` 三张表，再执行一次 `paper-judge.sql` 为 `judge` 增加 `paper_run_id`、`paper_snapshot_id` 及组合索引。ALTER 脚本不能重复执行，升级前检查 `information_schema.columns`；全新 `hoj.sql` 已包含相同字段、索引和表。必须同时更新业务后端与 JudgeServer，旧判题服务无法读取冻结的试卷配置与数据。填空题扩展复用客观题版本 JSON，无额外结构迁移。

2026-10-15 已在本地 HOJ 的 MySQL 8.0.46 执行 `objective.sql`，四张客观题表均创建为 InnoDB，执行后均为空表。2026-10-16 Docker 重启后再次确认结构保留。按用户要求未备份本地数据。后端更新、后续联调与验收状态统一见 [任务记录](../docs/docs/ai/TODO.md)。

2026-10-16 追加执行 `objective-standalone.sql`：升级前题目 3 条、题集与作答均 0 条；升级后原题目 ID 1/2/3、标题及发布状态保留，三题 `practice_enabled` 均为 1，作答的题集与版本引用已允许为空，新增 `question_id` 列。未重跑初始化 SQL 或创建业务样例。

2026-10-16 追加执行 `paper.sql` 与 `paper-judge.sql`：已核对三张混合卷表、`judge` 两个新增字段及索引。保留原数据卷与三道客观题；实际账号联调创建的两个临时账号、题目、试卷、答卷和提交记录均已清理，复核临时账号、混合卷答卷及混合卷提交计数均为 0。未重跑初始化脚本。填空题无需额外 ALTER，业务后端和 JudgeServer 已同步更新。

正式混合比赛升级：已有旧版 `paper_run` 且尚无 `contest_id` 时，执行一次 `mixed-contest.sql`；它新增 `contest_paper`、可空的 `paper_run.contest_id` 和 `(contest_id,uid)` 唯一索引，不修改题库或普通答卷。ALTER 段不可重复执行，先检查字段与索引。全新安装使用 `hoj.sql`；若已有 HOJ 基础表、刚运行新版 `paper.sql`，其中已含比赛列与索引，只执行 `mixed-contest.sql` 的 CREATE TABLE 段。随后更新业务后端；JudgeServer 沿用混合卷快照判题协议。

2026-10-16 本地已追加执行混合比赛增量迁移，未备份或删除数据卷；部署与真实账号验收进度见任务记录。

站内 Rating 升级：在完成前述迁移的已有库中执行一次 `site-rating.sql`，它增加可空 `problem.rating`、默认关闭的 `contest.rated`，以及 Rating 的快照、历史和结算锁三张表；ALTER 段不可重复执行。全新安装已包含于 `hoj.sql`，无需另跑此 ALTER。2026-10-16 本地已应用，原题目保持未评级、原比赛保持不计 Rating；管理员可录入 0–5000 的题目评级，并在主站公开赛开赛前开启计分。后端和判题端共享题目实体，本次两者同步更新；部署验收进度见任务记录。
