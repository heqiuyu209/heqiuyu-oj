> 这是 HOJ 数据库初始化文件、增量结构与服务配置文件。

尚未创建客观题表时，使用 MySQL 客户端在现有 HOJ 数据库中执行 `objective.sql`，再更新业务后端和前端。该脚本只创建客观题的四张表；重复执行不会清空表中数据，也不会升级已经存在的旧表结构。

已部署旧版客观题表、但 `objective_question` 尚无 `practice_enabled` 字段时，先执行一次 `objective-standalone.sql`，再更新后端。该脚本新增单题练习开关和作答题目引用，允许作答不引用题集；保留原题目、题集与记录。此 ALTER 脚本不能重复执行；检查 `information_schema.columns` 中的 `objective_question.practice_enabled` 与 `objective_run.question_id` 可确认升级结果。已有题目默认允许单题练习，旧题目 JSON 没有开放策略时按提交后开放处理；管理端可以关闭仅用于试卷的题目的单题入口。

全新安装使用 `hoj.sql`，其末尾包含相同客观题结构。已有数据库不要重跑 `hoj.sql`，其中包含原系统初始化操作。

2026-10-15 已在本地 HOJ 的 MySQL 8.0.46 执行 `objective.sql`，四张客观题表均创建为 InnoDB，执行后均为空表。2026-10-16 Docker 重启后再次确认结构保留。按用户要求未备份本地数据。后端更新与验收状态见 `docs/docs/ai/TODO.md`；登录、管理员录题与发布、练习提交、整卷交卷及指定时间开放仍需真实账号联调。

2026-10-16 追加执行 `objective-standalone.sql`：升级前题目 3 条、题集与作答均 0 条；升级后原题目 ID 1/2/3、标题及发布状态保留，三题 `practice_enabled` 均为 1，作答的题集与版本引用已允许为空，新增 `question_id` 列。未重跑初始化 SQL 或创建业务样例。
