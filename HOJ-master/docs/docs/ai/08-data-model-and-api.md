# 数据模型与 API 草案

这份文档描述接口边界，不是要求一次性全部实现。先实现最小闭环，再逐步扩展字段。

2026-10-27 状态：以下“当前已实现”是实际接口，其余建议表 / 草案仍未实现。远程判题优先复用现有代码与表。新增接口沿用 `CommonResult(status, data, msg)`，不改变全站响应格式。部署与验证证据统一见 [任务记录](TODO.md)。

## 当前已实现：牛客专项与试卷记录（2026-10-27）

沿用本人 `GET /api/learning/accounts` 和已验证公开账号 `GET /api/learning/users/{uid}/external`，NOWCODER 账号增加 `practiceContext`；未验证关联只对本人可见，错误详情仅本人可见。同步仍使用本人账号的 `/sync`，不接收来源 URL、Cookie 或题目代码。个人页账号卡片的“专项与试卷记录”独立展示、独立轮询。

| 字段 | 含义 |
| --- | --- |
| available / paperListsComplete | 是否已有通过完整校验并发布的两个试卷列表；不可用不等于零份试卷 |
| totalPapers / completedPapers | 来源全部试卷列表份数 / 来源“有完成记录”列表份数；不是历次作答总数或去重题数 |
| specialPapers / completedSpecialPapers | 上述两个列表中 intelligent=true 的专项练习份数 |
| records / completedRecord | 全部列表最近最多 20 份记录；同试卷已有完成记录单独关联，重做中不抹去旧完成记录 |
| kind / completed / recordId / paperId | PAPER、SPECIAL 或 INTERVIEW；来源 status=1/2 视为完成；两个 ID 以字符串返回 |
| score / accuracy | 普通试卷完成分数 / 专项完成正确率，真实零值保留；未完成及面试记录不补造成绩 |
| syncState / stale / lastSuccessAt / restricted | 独立任务状态、缓存状态、完整发布时间与明确来源隐私限制 |
| attemptHistoryComplete / questionEvidenceAvailable / uniqueQuestionCount | 固定为 false / false / null；来源未证明完整作答账本、每题证据或知识掌握 |

只请求固定牛客公开主页与官方 `test-papers/{uid}`、`test-papers-count/{uid}`，分别同步 onlyFinish=false/true。两份列表不是按当前完成状态相互过滤：同一试卷可以在全部列表中重做未完成，而在完成列表中保留另一 recordId。先核对用户身份及 doQuestion 公开权限，再取两种计数；每份列表完整分页后逐页复查，再核对两种计数和最终权限才发布。跨投影核对 paperId、类型与 recordId 绑定；同记录内容以服务端 SHA-256 比较，避免数据库排序规则忽略大小写或 JSON 数值节点类型改变造成误判。来源没有原子版本号，此流程只证明已执行这些一致性校验。

新增 `external_nowcoder_practice_job`、`external_nowcoder_practice_record` 两表，按账号级联清理；90 秒租约、账号先加锁、分页暂存与发布指针隔离。来源变化重扫、暂时失败保留旧完整缓存；明确隐私关闭立即撤去旧明细。读取使用独立 READ_COMMITTED 事务并最终复查发布指针、revision 和来源可见性，避免继承外层旧快照。任务不依赖编程历史完成，首次关联即可调度；每天核对及本人请求同步，共享牛客 Redis 3 秒门控。

本模块不写 `external_submission`，不增加编程 solved、去重题数、能力雷达或站内 Rating。升级先执行可重复的 `sqlAndsetting/nowcoder-practice.sql`；空库由 Compose 的 `06-nowcoder-practice.sql` 包装加载同一 DDL。验证、部署与真实公开来源样例只在 [任务记录](TODO.md) 维护。

## 当前已实现：管理员 AI 译文草稿（2026-10-20）

入口：管理端“题库 → 远程题目 → 原文与译文”，选择译文语言后显示 AI 草稿面板；可在本面板打开原有模型设置组件。CF / AtCoder 原文 en → zh-CN，牛客原文 zh-CN → en，语言方向由服务端已有来源决定。生成使用当前管理员本人模型，不借用其他用户设置，不调用远程判题；真实本人模型与翻译语义质量尚待验收，代码 / 受控模型与部署证据见 [任务记录](TODO.md)。

`POST /api/admin/remote-sources/translation`，请求字段如下：

| 字段 | 要求 |
| --- | --- |
| problemKey | 既有 CF / AtCoder / 牛客目录编号；不接受任意源站地址，也不使用浏览器提供的题干 |
| sourceHash | 已保存原文的 64 位小写 SHA-256；调用前后须仍为该来源当前版本 |
| modelFingerprint | 本人 `GET /api/ai/settings` 新增的配置指纹；更新地址 / 模型 / 密钥或删除配置都会使旧绑定失效，不返回密钥 / 密文 |
| allowModelSource | 必须明确 true；未勾选不加载题面用于模型，不解密密钥，不请求模型 |

方法级 Shiro 登录与 root / admin / problem_admin 角色校验；来源检查另读取当前实际角色和启用状态，普通 admin 只能维护本人导入的题目。生成前、外发前及回复后重查来源可用性、维护权限与指纹；原统一 AI 服务按持久的正式 / 虚拟参赛状态在外发前后校验，共用全局 12 并发、本人一次在途及每分钟 6 次控制。配置指纹也在外发前后核对；请求中途更换 / 撤销不会返回旧模型草稿。响应 no-store。

模型只收到 title / description / input / output / hint 的自然语言及不透明保护标记。HTML 标签与属性、链接 / 图片、pre / code / var / math 等标记及已识别的 TeX 公式、数值由服务端保留；公式跨内联 HTML 也整体保护。样例字符串与时间 / 内存限制不交给模型，结果从原版本复制。模型不能生成新 HTML 元素，输出文字转义后重建并沿用原来源清洗器；标记须在各字段中原顺序各出现一次。严格拒绝重复 JSON 键、额外 / 缺失字段、尾随对象、无效 Unicode、增添公式 / 数字及不完整回复；不靠截取部分回复生成译文。没有证明语义等价或自动识别所有未标注的技术表达，仍需人工核对。

边界：原 HTML 合计最多 150000 字符、遍历深度最多 64（根节点计 0）；投影自然语言最多 10000 UTF-16 字符、保护标记最多 256 个、投影 JSON 最多 24000 字符。超限保留手工编辑流程，不静默省略段落。翻译预算为 8192 输出 tokens，普通聊天仍默认 2048；沿用既有明确参数拒绝时的兼容回退，且拒绝上游显式 length / content_filter / tool_calls 等非完整结束。上游预算含义依据 [Chat Completions 参数说明](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)，各兼容供应商实际支持仍由其服务决定。

返回 `draft=true`、原文 / 目标语言、problemKey、sourceHash、content、model 和 sentCharacters，无任何题面写入。生成成功不改变版本、译文指针或题库开放状态；点击“应用草稿”先重读源版本，手工修改被替换前确认。草稿放入编辑器后仍为未保存内容，最后经原 `.../statements/zh|en` 的“核对并保存”人工发布流程保存。未编辑的样例在编辑器往返中也沿用原字符串，保留字节与空白。关闭未保存草稿会提示；换号、同 UID 重新登录、换题 / 语言后拒绝旧回复。本轮无 SQL 迁移，RAG / JudgeServer 不需要更新。

## 当前已实现：牛客比赛上下文（2026-10-20）

沿用 `GET /api/learning/accounts`（本人）和 `GET /api/learning/users/{uid}/external`（已验证公开账号），NOWCODER 账号新增 `contestContext`，无新增浏览器抓取入口。未验证账号仍只对本人可见；请求同步沿用本人账号的 `/sync`，不接受源站 URL。实现与部署验收分别见 [任务记录](TODO.md)。

| 字段 | 含义 |
| --- | --- |
| available / listComplete | 是否已有完整、已发布的已结束参赛列表；false 不代表没有参赛 |
| totalContests / coveredContests / submissionCoverageComplete | 全部公开参赛摘要数 / 已核对个人公开明细的比赛数 / 明细是否覆盖全部摘要；团队或受限赛事使明细不完整 |
| syncState / lastSuccessAt / stale | 独立上下文任务的状态、完整发布时间和缓存状态；lastError 仅本人可见 |
| contests | 按开始时间展示最近 20 场；包含固定官方 url、个人 / 团队和已核对的 OFFICIAL / STAR（打星）类型，不根据提交时间推断虚拟身份 |
| rank / officialAccepted | 来源公开的参赛名次 / 通过摘要，团队场次明确标记团队；隐藏或缺失为 null |
| contestSolved / contestSubmissions | 官方 contestTypeList=contest 过滤、用户身份及全局题号映射核对后的个人比赛内通过 / 提交；按该赛来源记录计算，不据此更新站内 rating |
| postContestSolved | 原编程记录里该赛全局题目在结束后通过的去重题数，排除该赛已识别提交 ID；可能与比赛内或其他比赛重叠，不代表正式成绩、纯练习来源或独立作答。原记录同步中则为 null |
| detailState | DONE 有核对明细；TEAM 不把团队成绩当个人记录；RESTRICTED 不请求来源限制的明细，后两者明细计数均为 null |

官方 profile 的 practice-coding 记录本来就包含部分比赛 / 赛后提交。原 external_submission 是总题数的唯一提交账本；新增四表只保留独立上下文任务、参赛摘要、明确的比赛字母 → 全局题号映射及来源提交关联，不把比赛通过数再加到总题数。题目难度和算法分类缺证据仍为未知；专项与试卷记录由上一节独立模块提供，不进入编程计数。

首次编程历史完整后才调度，之后每日核对；与牛客其他只读请求共用 Redis 3 秒门控。各分页完整且首部指纹复核后切换 published_token；失败 / 页变化保留旧完整快照，已知的新来源隐私限制立即撤去旧明细展示。90 秒租约、当前读和账号先锁定保证旧请求不能回写新任务，解绑级联清理。数据库升级先执行 `sqlAndsetting/nowcoder-context.sql`，空库由 Compose 的独立初始化包装器加载同一 DDL，不能重跑整个 hoj.sql。

## 当前已实现：知识库资料与服务端问答（2026-10-19）

该层已在本地后端、Vue 页面和常驻 CPU 向量服务接通。用户入口 `/knowledge`，原文 `/knowledge/:documentID`，仅 root / admin 的维护入口 `/admin/knowledge`。MySQL 是发布 / 权限事实源，Python 内部索引不能直接发布资料；真实本人模型生成仍待验收。

| 接口 | 身份与行为 |
| --- | --- |
| `GET /api/admin/knowledge?page&keyword` | root / admin，分页管理列表及 indexConfigured |
| `POST /api/admin/knowledge/documents` | root / admin；title、format、content、visibility、requestId，创建待入库资料 |
| `GET /api/admin/knowledge/documents/{id}` | root / admin；版本元数据、入库状态及 revision，不整批返回正文 |
| `GET /api/admin/knowledge/documents/{id}/versions/{version}` | root / admin，读取指定版本正文 |
| `POST /api/admin/knowledge/documents/{id}/versions` | root / admin；同上传字段及 expectedRevision，新建不可变版本或合并相同内容的幂等请求 |
| `POST /api/admin/knowledge/documents/pdf` | root / admin；原始 application/pdf 请求体，query 为 title、visibility、requestId、fileName；解析后原件 / 正文 / 任务同事务保存 |
| `POST /api/admin/knowledge/documents/{id}/pdf-versions` | 同上，额外 expectedRevision；原始文件字节识别重试，不重新解释已成功请求 |
| `GET /api/admin/knowledge/documents/{id}/versions/{version}/original` | root / admin；活动资料的任意 PDF 版本原件，含未发布版本 |
| `POST /api/admin/knowledge/documents/{id}/publish` | expectedRevision、version；只发布已 INDEXED 版本，入库完成不自动发布 |
| `POST /api/admin/knowledge/documents/{id}/unpublish` | expectedRevision，立即撤回当前发布版本 |
| `PUT /api/admin/knowledge/documents/{id}/visibility` | expectedRevision、visibility，立即调整全资料的 PUBLIC / ADMIN 可见性 |
| `POST /api/admin/knowledge/documents/{id}/delete` | expectedRevision，逻辑删除、撤回并持久排队逐版本清理 |
| `POST /api/admin/knowledge/documents/{id}/versions/{version}/retry` | expectedRevision；重新排队入库 / 重建，不放行仍有有效租约的任务 |
| `GET /api/knowledge/documents?page&keyword` | 登录 + submit；只返回本人可见、当前已发布且已索引版本的目录，不返回草稿标题 / 正文 |
| `GET /api/knowledge/documents/{id}?version` | 同上；只返回当前已发布原文，撤回 / 更新后旧版本返回 404 |
| `GET /api/knowledge/documents/{id}/original?version` | 登录 + submit + 当前参赛限制；仅本人可见的当前已发布 PDF 原件，旧版本 / 撤回 / 隐藏后失效 |
| `POST /api/knowledge/search` | query、可选 documentIds、topK；只在服务端授权版本集合内检索，返回 server-generated 引用 |
| `POST /api/knowledge/answer` | `{search:{...},allowModelExcerpts:false,modelFingerprint?}`；默认纯检索，明确 true 后须附确认的模型指纹才回答当前片段 |

所有方法 no-store。维护角色仅 root / admin，problem_admin / 团队管理员没有维护权限。PUBLIC 仍要求登录和 submit，ADMIN 只给站点管理员。用户侧目录 / 原文 / 检索 / 问答均按服务端正式或虚拟参赛状态禁用；不能通过换页面 / 重登绕过。

上传正文最长 200,000 个 Unicode 码点，title 最长 200，format 为 markdown / text，requestId 为 8–64 位字母数字 / 下划线 / 短横线。服务端规范化换行并生成内容哈希，忽略客户端未定义的发布 / 索引字段。相同管理者创建请求按 requestId 幂等，后续版本请求按资料 / 管理者 / requestId 幂等；复用 nonce 更改内容返回 409。相同正文、标题、格式不生成重复版本，visibility 变化仍增加 revision。expectedRevision 用于冲突控制；旧幂等重试可找回原请求版本，不覆盖当前较新的编辑。

检索 query 最长 2,000 码点、topK 1–10，可选 documentIds 最多 100 个，空列表表示不检索任何资料，省略表示当前可见已发布集合；超过 500 份须先筛选，不截断全库。客户端不提供 allowedVersions。返回 items 的 citationId / documentId / version / chunkId / title / headingPath / excerpt / score / url 均由服务端校验生成，score 是向量相似度。页面用已验证 ID 构造本地原文路由 `/knowledge/{id}?version=...&chunk=...`，可附 heading 定位实际章节；原文仍由后端重新校验当前发布版本。页面不把相似度当作回答可信度。

allowModelExcerpts 默认 false，不读取本人模型设置、不解密 Key、不调用模型；仅检索不要求模型配置。为 true 时，服务端重新检索、核对当前权限，只发当前问题及命中片段，不发完整资料、聊天历史、画像或内部凭据。模型调用前后再次检查资料 revision / 发布 / 原文 / 参赛限制，失效返回 409，不展示迟到答案。真实本人模型仍按用户选择延后；当前实现 / 部署和验证状态见 TODO。

2026-10-21 段落原文依据协议：模型按当前系统提示返回 `{answerable:true,claims:[{content:"一段说明",evidence:[{citationId:"K1",quote:"对应片段内的连续原文"}]}]}`。每段 1–3 条依据，ID 必须来自当前命中列表，quote 须逐字出现在该 ID 的 excerpt 中，不能改写或拼接；同段重复 ID / 摘录组合失败，不同摘录可复用同一个 ID。最多 8 段，每段正文 2000 字、合计 8000 字；单条摘录 800 字、合计 6000 字，原始回复最多 24000 字。空白依据、缺少依据、附加链接 / 页码 / 字段、重复 JSON key、尾随 JSON / 文本及超过 8 层的嵌套均返回 502，不展示部分回答。

资料不足由模型声明 `{answerable:false,claims:[]}`，服务端替换成固定提示并清空已用 citations；无命中时不调用模型。true 搭配空 claims 或 false 搭配非空 claims 都是无效输出。来源 / 摘录校验只证明原文对应关系，不判定结论是否被原文蕴含；answerable 是模型声明，不是服务端语义正确性认证。仅给出引用 ID 的旧模型输出不再符合当前提示契约。

对外仍保留 modelUsed、excerptsSent、model、content、citations 和原检索 items，并新增 answerable / claims。content 由通过核验的各段正文拼接；每段 evidence 只含服务端接受的 citationId / quote，citations 去重后保留服务器原有的版本、页码、时间点和本地链接，模型不能提供这些元数据。前端按段展开原文依据，链接由已验证身份构造；视频起始时间同时写入 t 参数。纯检索请求与原问题 / 授权结构不变，无 SQL 迁移。

PDF 文件最多 8 MiB / 200 物理页 / 20 万提取码点，拒绝加密、全无文字、无效字体映射和超限文件。正文格式 pdf 仅能由服务端解析 / 复用原件生成，不能用 JSON 创建伪 PDF；改标题等元数据时，原版本上传接口传 format=pdf、sourceVersion，服务端读取该不可变来源，忽略浏览器 content；明确的 OCR 校订例外见下文。PDF 指纹含原始哈希 / 文件名 / 标题 / 可见性，避免解析器升级破坏 nonce 重试。版本正文附 source 元数据，绝不把 BLOB 放进 JSON。提取正文用 `\n\f\n` 分隔物理页，保留空页；PDF 内的 # 只是文字。PDF 引用额外返回服务端核对的 page（1–200），检查片段确实位于该页，再由页面附 page 定位。原件只提供受权 attachment 下载，no-store / nosniff、UTF-8 文件名，无公开文件路径；读取字节后再次检查当前权限 / 发布状态。

扫描 PDF（2026-10-20）：原始 PDF 上传新增显式 query 参数 ocr=true，默认 false。对应签名路径 /internal/v1/extract-pdf-ocr 与普通提取独立，不能通过未签名 header 改变模式。返回仍为 sourceHash / pages / content / emptyPages / parser 五字段，OCR parser 为 pdfium-tesseract-chi_sim-eng-v1。PDFium 200 DPI 整页渲染 + Tesseract chi_sim+eng，每页都处理，含文字页眉和图片正文的混排页。本地子进程不继承服务身份、用户 Key 或个人配置。

沿用 8 MiB / 200 页 / 20 万码点上限。Linux 限 768 MiB 虚拟内存 / 90 秒单进程 CPU / 64 MiB 单文件；渲染前限制每页 1600 万像素、单边 1 万像素。内部整体处理 80 秒、每页 OCR 最多 20 秒、父进程 85 秒墙钟并杀死进程组；HOJ 请求截止 105 秒，浏览器和 Nginx 等待 120 秒。上限不保证每份 200 页文件都能完成，超限整体失败，需要拆分重传，不提交部分正文或改变旧发布指针。

source.extractionMode / knowledge_source.extraction_mode 为 NATIVE / NATIVE_REVIEWED / OCR / OCR_REVIEWED。已有库的 OCR 列仍通过可重复 knowledge-ocr.sql 增加，旧行默认 NATIVE；NATIVE_REVIEWED 可直接使用已有 varchar(16)，不新增迁移。原生 / OCR / OCR_REVIEWED 的既有指纹逐字节兼容。

管理员在版本 JSON 上传中显式传 reviewedPdf=true、sourceVersion、完整规范分页 content、expectedRevision，可按页校订四种 PDF 来源。后端按存储的来源家族生成 NATIVE_REVIEWED 或 OCR_REVIEWED；旧 reviewedOcr=true 继续仅用于 OCR 家族，与中性标记校订同一内容的请求指纹一致。校验原物理页数、规范分页、UTF-8 和 PDF 30 万码点上限，重算空页，校订指纹包含规范正文哈希。同 nonce / 同内容可在旧 revision 下重放，改内容则 409；旧版 / 旧发布及原件字节和哈希保留，入库后仍须显式发布。

未显式校订时仍忽略浏览器正文，改标题沿用服务器正文和校订状态；不会把继承的来源标记写回请求对象，重复执行元数据命令也不会变成正文校订。原始 PDF 上传只按服务端提取选择 NATIVE / OCR，不能凭浏览器 review 标记伪设已校订。校订复用原件且不重新提取 / OCR；管理员和学生页面区分原生校订与 OCR 校订。人工校订入口不代表公式已自动恢复或全文语义准确；实际部署与验证见 [任务记录](TODO.md)。

五表定义仅在 sqlAndsetting/knowledge-base.sql；新增 knowledge_source 绑定版本保存原件，逻辑删除保留历史原件但撤回下载。Compose 空卷通过 knowledge-base-init.sql 包装器执行，已有库直接在 hoj schema 运行增量脚本，尚未实际重建空卷。持久任务租约 180 秒，不在 HTTP 中持有行锁；旧 token / 过期结果不可完成任务。暂时性入库失败最多三次自动尝试，可由管理员重试；正文解析拒绝的 HTTP 400 记录为 INVALID_CONTENT，首次失败即停止自动重试，修改为新版本后再入库。删除清理持续退避重试。基础 Compose 默认禁用内部索引；本地已用 compose.rag.yaml 接通服务和独立凭据文件，未配置时仍诚实返回 503。当前真实上传 / 索引 / 发布 / 页面 / 重启证据与早期 INDEXED 权限夹具分开，见 [RAG 第 11 节](02-rag-knowledge-base.md#11-知识库页面与常驻本地索引已接通待用户验收) 与 [TODO](TODO.md)。

## 当前已实现：算法目录、个人画像和用户模型

| 接口 | 身份与行为 |
| --- | --- |
| `GET /api/learning/topics` | 公开；返回固定目录与当前题目标签 / 训练分类 ID，目录不是官方统一考试大纲 |
| `GET /api/learning/users/{uid}/profile` | 公开；只使用可公开的站内独立练习证据，无题目答案 / 私有记录 |
| `GET /api/learning/users/{uid}/evidence` | 公开、no-store；本站合法练习与已验证外部账号的来源画像及合并覆盖，见下节 |
| `GET /api/ai/settings` | 登录且账号正常；仅返回本人 `configured,hasKey,baseUrl,model,contestBlocked,protocol,fingerprint` |
| `PUT /api/ai/settings` | 同上；接收 `baseUrl,model,apiKey`，同地址留空保留 Key，换地址必须重新填写 |
| `DELETE /api/ai/settings` | 同上；仅删除本人模型配置 |
| `POST /api/ai/chat` | 登录、正常账号与 `submit` 权限；服务端本人身份、参赛检查和限流，不接收客户端 UID / 参赛状态作为授权依据 |
| `POST /api/ai/problem-context` | 同上；按已有题目 / 本人答卷权限预览题面，无需模型配置，不调用模型，详见题面上下文 |
| `GET /api/ai/recommendations` | 同上；生成本人规则候选，五秒读取冷却，无需模型配置，不调用模型 |
| `POST /api/ai/recommendations/explain` | 同上；核对当前候选后调用本人模型，只接受既有候选的解释和排序 |

`algorithm_topic` 映射稳定 code、名称、8 组分类与现有 tag / category ID，94 项权威目录位于 DataBackup 资源 `algorithm-topics.json`。自定义标签保留，固定项改名 / 删除在服务端拒绝。画像没有新增成绩快照表，读取当前合法公开练习；返回 `scope=PUBLIC_STANDALONE_PRACTICE`、统计时间和真实样本。8 轴分别为编程尝试 / 通过 / 已评级通过 / 已评级通过题 P75 与客观题尝试 / 通过，缺评级为 null。`assistanceTracked=false`、`crossPlatformDeduplicated=false` 明确当前不能判断外部提示或跨站重复题；多人共享题目标签会使各轴数量有交集，不应将轴计数相加当总题量。

2026-10-24 管理端补证：自定义标签组可新增/修改/删除，删组后既有标签保留并解除组关系；训练可绑定自定义或固定分类。沿用原删除语义：删除被引用的自定义训练分类会解除关联并保留训练，读取返回 trainingCategory=null。编辑页安全显示空分类，管理员须明确重选后保存；切换到其他训练或创建页清空旧身份/分类，未加载完成不允许保存，迟到读取或分类列表不会覆盖新表单。方法级权限和固定项服务端保护保持；真实HTTP/Vue及构建证据见 [任务记录](TODO.md)。

训练管理所有权：`PUT /api/admin/training` 与 `PUT /api/admin/training/change-training-status` 先读取已有训练，只允许root或存储作者操作，不用DTO/query中的author确认身份。状态路由仍接收旧author参数以兼容前端，但忽略其授权含义；仅更新已有记录，不执行upsert。空更新ID或不存在记录返回业务400，越权返回403且不改训练、注册或分类；既有方法级管理角色门禁保留。作者转移及团队管理策略本轮未调整，实际回归和本地更新见 [任务记录](TODO.md)。

模型设置使用 `ai_user_setting(uid,base_url,model,key_ciphertext,updated_at)`，AES-GCM 密文绑定用户、规范地址和模型。包装密钥在独立卷内；配置 DTO 的 Key 只写不读，无请求体 / 认证头日志。所有 AI 成功响应 `Cache-Control:no-store`。仅支持 HTTPS 公网域名的 OpenAI 兼容 Chat Completions（443 / 8443），在连接时解析并检查全部 IP，禁私网 / 特殊地址、代理和重定向。Anthropic 原生协议、模型列表探测和联网尚未实现；内部 RAG 已接通前述知识库接口，真实本人模型质量仍待验收。

聊天请求为 `{messages:[{role:'user'|'assistant',content}],modelFingerprint,includeProfile:false,includeExternalProfile:false}`，最多 7 轮历史加当前提问、严格交替且以 user 结束，单次提问 8000 字符、总文本 32000 字符；客户端不能传 system。返回 `{content,model,modelFingerprint,profileIncluded,externalProfileIncluded}`，不持久化聊天。默认不附带画像；includeProfile=true 默认仅本人站内公开画像，再显式选择 includeExternalProfile=true 才附带 evidence 接口范围的本人公开 / 已验证外部证据。单独开启外部标记而未开启画像返回 400。服务器不接收客户端 UID 或画像正文，绝不自动拼接未开放答案。每用户最多 1 个进行中请求、每分钟 6 次，单节点最多 12 个，上游整体超时 60 秒；无失败自动重提，仅对明确拒绝 `max_tokens` 参数的兼容错误改用 `max_completion_tokens` 重试一次。业务错误包括未登录 401、参赛 / 账号 / 权限 403、配置 / 文本 400、限流 429、上游连接 / 超时 502、服务不可用 503；不能将 HTTP 200 误当业务成功。

2026-10-21 模型配置绑定：聊天、推荐解释、明确外发片段的知识库问答都必须额外携带本人 GET settings 返回的 64 位 modelFingerprint（请求字段名；来源是 settings.fingerprint）。缺失 / 格式错误为 400，和当前配置不一致为 409；在服务端接纳时、解密前、最接近模型调用前及返回后复核，成功模型响应另附 modelFingerprint。指纹绑定地址、模型与随机加密密文，不返回 Key 或密文；即使同样字段重新保存，也需以新指纹重新确认。配置已删除而尚未开始的请求按未配置返回 400；进行中的请求观察到删除或变化则丢弃回复并释放并发槽位，不自动换服务重发。已发给上游的材料无法收回，此机制不声称原子取消外部网络请求。

普通聊天发送页面已读取的指纹，发现设置变化会清空旧历史、草稿、画像勾选与暂存 Key，迟到响应不能重新挂载；同指纹的后台刷新保留对话。推荐解释和知识库问答仅在用户点击时读取最新本人设置，并在本页 / 会话仍有效时携指纹发送。仅检索资料、获取规则候选和保存训练清单无需模型指纹，也不读取 Key 或请求模型。AI 译文草稿沿用原有模型指纹 / 题面身份双重复核。无数据库迁移，更新需同步前后端；旧客户端缺指纹会被拒绝，刷新页面后再操作。

正式参赛由服务端查询当前有效报名、比赛提交、混合赛答卷和 `ai_contest_entry(uid,cid,entered_at)`；公开比赛无密码注册，普通用户获得授权题面时幂等记录，不在列表 / 榜单时记录。未参赛管理员查看题面不记录，已有实际参赛证据仍受限。检查只在真实比赛起止期间生效，提前交卷不提前解除；模型请求前后再次检查。浏览器路由不决定禁用，其他用户可使用。2026-10-19 增加下节虚拟计时的持久窗口，编程赛与混合虚拟答卷均按本人起止时间加入同一 guard。普通练习卷不能凭标题或客户端参数当作虚拟参赛。未来聊天历史、RAG、联网与工具 API 也需复用 guard。

## 当前已实现：题面上下文（2026-10-21）

编程题、客观题作答、混合卷当前题增加“问助教”，全站原有助教入口也能识别这些页面的当前题。打开窗口默认不读取 / 附带题面；明确勾选后从服务端预览，发送时只携带引用和指纹，服务端重新投影。手动提问 / 粘贴代码仍可使用；自动附带不包含个人代码、标准答案、解析或隐藏测试。

```text
POST /api/ai/problem-context
     # reference 三种互斥形式：
     # {kind:PROGRAMMING,problemId:"显示题号",groupId?,contestId?}
     # {kind:OBJECTIVE_RUN,runId,questionKey:"客观题数字ID"}
     # {kind:PAPER_RUN,runId,questionKey:"OBJECTIVE:ID"|"PROGRAMMING:ID"}
     # 返回 {reference,material,fingerprint}；64位 SHA-256，no-store。
POST /api/ai/chat
     # 在原请求中可选 problemContext:{reference,fingerprint}，省略则不读题。
     # 成功另附 problemContextIncluded；附题面时另附 problemFingerprint。
```

引用复用 ProblemManager / ContestManager / ObjectiveManager / PaperManager 的当前权限；团队成员、本人答卷、题集 / 试卷发布与比赛权限均沿用，不能仅凭题目 ID 读保密题。普通题使用题库原文，答卷使用当前选中项的冻结题面；不默默换成新库题版本或翻译版本。竞赛题读取可能按原业务建立参赛证据，投影返回前再查统一 AiAccessGuard；正式 / 虚拟参赛用户不可通过预览入口绕过禁用。预览本身不要求模型设置、不解密 Key、不调用模型，也不触发代码判题。

material 按明确白名单构造：题名、来源、题型、题干；编程题附输入 / 输出 / 样例（主题库另附时间 / 内存限制）；客观题只逐项复制 option.id/text 与 blank.id/label。即使原作答读取已开放成绩 / 答案，投影仍不复制判分、正确选项、填空答案、解析、提交记录、代码、测试数据或内部路径。答题 / 成绩变化不改变题面指纹，避免成为正确性旁路。只保留题面原文；HTML / Markdown 不执行、不请求其中链接，窗口用转义文本供核对，模型材料明确视为不可信数据。

单字段与整份投影最多 24,000 个 Java 字符，引用加投影序列化最多 64 KiB；过长返回 400，提示用户摘取要讨论的部分，不截断或拼造内容。指纹绑定规范引用及实际投影；发送接纳时、模型调用前后重读权限与投影，不一致为 409、不可访问沿用 403 / 404。读取已发出的题面 / 上游材料无法追溯回收，校验不声称原子取消外部请求。

前端内存注册表只存页面 / 登录代次和题目引用，不存题干、答案、代码或 Key；旧页面销毁不能清除新页面的注册。换题（包括同路径中的答卷题目）、账号、模型、路由或附带选项变化时清空旧历史与预览；普通结果轮询不重置同题对话。预览 / 回复到达后核对代次和选题，迟到结果丢弃。题面冲突 / 撤权会清除旧材料、保留提问，不自动重发；用户可勾选或点“刷新题面”重新核验。手机窗口预留顶部导航空间，关闭按钮有实际点击命中回归。无 SQL 迁移，仅更新业务后端与前端，真实本人模型效果继续按用户选择延后。

## 当前已实现：编程赛个人虚拟计时（2026-10-19）

已结束编程赛页面增加“虚拟计时练习”，沿用原赛后题目与提交，不生成独立虚拟榜单、不更新比赛 Rating。混合比赛另接独立答卷及个人截止，见下节；普通编程虚拟窗口的独立提交归档 / 结果和公开虚拟榜单仍未实现。

新增表 contest_virtual_run 及 virtual-contests.sql，须先执行增量再更新后端；hoj.sql 包含相同 DDL。记录 id、本人 uid、cid、request_id、标题快照、started_at / ends_at；不存代码、题目答案或模型信息。开始时间由服务端产生，截止为开始时间加原比赛 end_time-start_time，原比赛后续改时 / 隐藏不缩短已有 AI 限制。时间边界为 started_at <= now < ends_at，无需定时任务清空记录；不提供提前取消 / 缩短窗口的用户接口。

| 接口 | 行为 |
| --- | --- |
| `GET /api/contests/{cid}/virtual-run` | 登录且具 submit 权限；复核本人账号及比赛可见 / 私有 / 保护 / 团队 / 账号限制，返回本人该比赛最近窗口或 run=null，不创建参赛 |
| `POST /api/contests/{cid}/virtual-run` | 同上；请求使用 `{requestId}`，8～64 位字母 / 数字 / 下划线 / 连字符；不接收客户端时间或 UID 作为依据 |

返回 `{run:{id,contestId,title,startedAt,endsAt,serverNow,state:'RUNNING'|'ENDED',kind:'PROGRAMMING'|'MIXED',paperRunId:null|number}}`。成功和业务错误均 no-store。首次开始必须是已结束、已有编程题或已配置混合卷且当前有权限的比赛，正式参赛中不能另开窗口。未授权 403、匿名 401；未知比赛 404、不同比赛的活跃窗口 / 请求标识冲突 409。原赛管理员可沿用维护权限访问比赛，参赛后没有 AI 豁免。

事务先锁本人 user_info 行，再读本人窗口，保证同用户跨比赛开始串行，REPEATABLE READ 的首次一致性读取位于行锁之后。窗口历史不使用缺失记录的范围锁，避免不同用户在空索引上相互阻塞；新开始时用锁定读取获取当前比赛元数据，避免等锁期间的旧快照。相同 requestId 找回同一结果，包括已结束的旧结果；同一比赛已有活跃窗口时，新 nonce 也返回原窗口，不延长截止；其他活跃比赛返回 409。截止后用新的 nonce 才能明确再练一轮。

统一 AiAccessGuard 检查本人持久虚拟窗口，聊天、规则推荐和推荐解释均受限，跨页面、重新登录或伪造请求 UID / cid 不放宽。模型返回前再次检查，计时开始后不发布迟到回复。页面倒计时按服务端 serverNow 与本地单调时钟估算，不承担授权判断；更换账号 / 路由隔离迟到读取，开始失败重试保留 nonce。状态变化事件只让助教清理旧上下文并重新请求服务端，不携带客户端允许 / 禁用结论。

验证入口：VirtualContestManagerTest、VirtualContestAuthorizationTest、VirtualContestMySqlTest、AiAssistantManagerTest；隔离页面使用 PAPER_REVIEW_CASE=virtual；真实本地接口以 HOJ_LOCAL_VIRTUAL_TEST=1 运行 tests/virtual-contest-local-integration.cjs。后者用明确命名的合成记录模拟截止，时间边界另由受控时钟单元测试核对，不宣称完整等待了一小时。构建 / 部署证据见任务记录。

## 当前已实现：混合虚拟答卷（2026-10-19）

已有库在 virtual-contests.sql 后执行可重复的 mixed-virtual-contest.sql，再更新后端；增量只给 paper_run 加 nullable virtual_run_id 及唯一索引 uk_paper_run_virtual，保留旧答卷。新建库的 paper.sql / hoj.sql 含相同字段和索引。虚拟答卷的 contest_id 始终为 NULL，独立关联个人窗口；正式比赛每人一份的唯一约束、榜单与比赛 Rating 仍只读取原 contest_id 条件。普通套卷历史排除 virtual_run_id，不把虚拟答卷算作日常套卷练习。

POST /api/contests/{cid}/virtual-run 在同一事务创建窗口与混合答卷，复制原比赛已冻结的题目 / 程序测试快照，每轮使用独立 paper_program_snapshot。快照失败则一起回滚，重复 nonce 或相同活跃窗口返回原 paperRunId。题面、标准答案、程序语言 / 测试数据及计分规则使用本轮冻结版本；赛制另存 Snapshot.virtualContestType。原题库编辑或原练习卷撤销发布不替换该答卷的内容，但访问时仍校验当前比赛权限、保护赛报名和本人归属。

复用现有 /api/papers/runs/{id} 的读取、answers、program-submissions、finish 与 retry 接口；返回额外 virtual=true、virtualRunId、原 contestId 和个人 endTime / serverNow。保存和新编程提交受个人截止控制，截止时拒绝修改；MixedContestDeadlineJob 每五秒处理到期正式与虚拟答卷，未打开页面或没有登录 principal 也会锁定已保存内容并交卷。程序仍即时判题，草稿不自动变成判题提交；错误任务恢复沿用原私有套卷流程。

反馈继续接受管理员调整：NEVER 始终隐藏；AFTER_END 对应个人截止；AT_TIME 取个人截止与管理员当前绝对开放时间中较晚者。结果以 AT_TIME / NEVER 和实际 releaseAt 返回。提前交卷可以查看按 BEST / LAST 规则计算的分数，不会提前开放标准答案 / 解析或解除 AI。即使原比赛答案已经公开，本轮仍须等到个人截止；管理员将当前规则改为 NEVER 后，已有虚拟答卷后续读取也会隐藏。虚拟答案政策与题目快照是不同职责，不把题目版本冻结误作忽略权限 / 规则更新。

编程提交仍以 paper_run_id / paper_snapshot_id 归属本次答卷，经过 CONTEST_JUDGE 权限和原提交间隔限制，不进入普通题目提交详情、公开分享或站内单题练习统计。原正式答卷、排名与平时练习分别保留。已结束混合赛题目入口提供虚拟开始 / 继续 / 本轮结果，原比赛答卷仍可单独查看；虚拟页使用已有卡片与上一题 / 下一题导航，并明确标注个人计时。此阶段没有公开虚拟榜单。

验证：MixedContestManagerTest 增加个人截止、正式 / 平时隔离、当前开放策略与冻结题目、保护赛权限、OI LAST 计分检查；VirtualContestManagerTest 覆盖窗口 / 答卷原子回滚；原套卷、正式混合赛、Rating 与 AI 回归见任务记录。隔离页面 PAPER_REVIEW_CASE=mixed-virtual；真实本地 tests/mixed-contest-local-integration.cjs 同时设置 HOJ_LOCAL_MIXED_CONTEST_TEST=1 与 HOJ_LOCAL_MIXED_VIRTUAL_TEST=1，使用自动清理的临时账号 / 比赛，复用本地演示卷 3，包含本地 AC / WA / TLE；不向外站提交代码、不调用模型。原赛时钟与个人过期使用明确临时记录模拟，提交等待只读取该临时用户的真实限流截止，不清除限流。

下文会话、文档、学习计划和多平台表均为后续设计，不能据此认为已有这些功能。

## 当前决定：取消平时 Rating（2026-10-22）

用户取消平时 Rating 及计算逻辑，只保留比赛 Rating。日常学习继续按实际作答展示通过题数、知识点覆盖及各来源通过难度；题目 rating 仍是难度元数据。客观题正确性进入能力统计前，继续执行当前答案开放规则；原答卷与冻结题面不变。

不再遍历公开编程通过和客观题单题答卷生成用户 Rating，也不再为此追踪助教首次使用。上一阶段新增但尚未部署的 learning_assistance 及其初始化挂载已撤去；题面授权、模型版本绑定和比赛期间服务端 AI 禁用仍保留。已有 practice 快照 / 历史只留库，公开 API 不返回、不重算、不删除。没有新迁移。

当前 Rating 契约统一见本文“站内比赛 Rating”；验证 / 部署状态以 [任务记录](TODO.md) 当前迭代为准。

## 当前已实现：跨平台能力证据与明确来源去重（2026-10-18）

新增公开 `GET /api/learning/users/{uid}/evidence`，复用本站用户状态与客观题反馈检查，成功 / 业务错误均 no-store。返回 `version=verified-sources-v1`、uid、scope、asOf、`assistanceTracked=false`、`crossPlatformDeduplicated=true`，以及 combined 和 sources（HOJ / CODEFORCES / ATCODER / NOWCODER）。没有新成绩表或迁移；读取已有公共练习、来源记录与目录。未验证账号、其他用户记录、当前不可公开的本站题目不进入外部画像，也不返回证明码、账号凭据、答案或解析。

combined 只合并题目覆盖：按当前合法公开练习和已验证外部记录的明确题源编号去重，尝试 / 通过为不同题数；一个来源键多次提交或多个已验证同平台账号不重复计题。duplicatesRemoved 为本站通过条目与每个已验证账号的通过题源条目之和减去唯一通过题数，不按原始提交次数计算。跨方向可以重叠，轴数量不能相加作为总题量。不同平台的 Rating 不合成一个 P75，combined.coverageOnly=true，ratedSolved / representativeRating / unratedProgrammingSolved 为 null，客观题仍单独保留已开放反馈的本站概念证据。

sources 分别保留本站题目 Rating、CF 官方题目 Rating、AtCoder Problems 第三方难度估计、牛客公开目录来源 Rating，各自按通过题计算 P75，缺失为 null。优先当前可用目录元数据；没有目录时 CF / AT 可使用既有来源提交缓存，牛客没有可信目录则不补造题目难度。CF 只使用可确认个人的提交，团队通过可留在原账号总数，但不进入个人能力分布；AT / 牛客标记 ACCOUNT_ATTRIBUTED，不宣称独立完成。牛客编程提交范围为 PUBLIC_PROFILE_CODING，包含部分比赛 / 赛后提交。来源返回 verifiedAccounts、historyComplete、stale 与 syncState（UNLINKED / IDLE / QUEUED / RUNNING / BACKOFF）；未 DONE 的刷新即使近期成功过也标 stale，historyComplete 仍指曾完成历史核对。综合覆盖同样返回汇总 syncState / stale；失败、同步中均保留已有证据，不把未验证账号纳入状态。规则推荐 basis 保留所选题源状态，并以 coverageSyncState / coverageStale 记录决定训练方向的跨平台覆盖状态；两种状态都参与推荐指纹，状态变化后旧 AI 解释需重新确认。主动勾选的 AI 外部画像保留同一状态，不将刷新中的缓存描述成最新完整能力。

算法分类复用固定目录的精确别名；CF 使用来源标签。2026-10-24 补全：AT / 牛客的已有外部证据可复用同一可信来源身份下的全站公开、非团队、远程本站题分类，不要求当前用户曾在本站提交。分类供体只提供当前标签，不增加任何尝试、通过、来源记录或难度样本；不按题名或难度猜分类，也不把 AT/NC 的旧 external_problem.tags_json 当作撤销后的回退。画像、本人明细与推荐共用 PublicSourceClassification 的当前公开读取；私有化、删标签或可信关联撤销后，下次按当前关系重算分类。没有任何可用分类时 coverageAvailable=false，不绘制全零雷达图；已有部分分类时明确标注 unmappedSolved。各平台难度样本少时不据此判断强弱。

统计别名由 ProblemIdentityResolver 批量解析：已知 AtCoder 目录中的唯一大小写对应、牛客固定官方 manifest 与匹配题面共同确认的 native problemId 可统一统计；牛客映射还要求来源可用与有效 questionId。普通 / 比赛 / 专项的原题号、提交上下文和版本仍分别保存。未知关系、未知大小写别名及仅同标题不合并。原外部账号 summary 也使用该解析；整体账号总数与能力图的 CF 个人提交范围有差异时，界面明确说明。

2026-10-24 补全：CF 数字题序的本地显示号不能猜拆比赛号与题序。仅当前远程题存在唯一 remote_problem_import→problem.id 映射、明确 CF 编号合法且显示号精确一致时，把本地统计身份关联到原编号；后续刷新 FAILED 不抹掉已发布的身份。缺失 / 冲突 / 改名 / 非远程保持本站独立记录。此规则用于画像、个人明细和推荐排除，不改原提交上下文。

个人页提供“综合覆盖 / 本站 / Codeforces / AtCoder / 牛客”切换，综合视图禁用混合难度；无账号、历史补全、数据待刷新和缺分类分别提示。换用户清空旧画像并隔离迟到响应。旧 `/profile` 返回值及默认 AI 的 `includeProfile` 范围仍为本人站内公开画像；2026-10-19 增加独立勾选的外部画像和下节规则推荐，外部记录不自动发送至模型。

验证：LearningEvidenceManagerTest、原 LearningProfile / 三平台账号回归；真实接口 `tests/learning-evidence-local-integration.cjs`（HOJ_LOCAL_LEARNING_EVIDENCE_TEST=1，仅合成本地归属，不是真实平台认证），以及扩展后的 learning-local-integration.cjs（覆盖客观题 NEVER → 开放反馈）。隔离浏览器 PAPER_REVIEW_CASE=learning，真实浏览器与最终部署证据见任务记录。

## 当前已实现：本人编程证据明细（2026-10-21）

`GET /api/learning/problem-history` 读取当前登录人，不接受目标 uid；方法级 Authentication / submit 和读取前后正常账号检查，业务响应 no-store。不调用模型 / 源站，不依赖 AI 配置，不受 AI 参赛停用影响，没有新表。它沿用能力图的本站公开单题编程练习、已验证外部账号范围；排除本站私有 / 团队 / 本人出题 / 比赛 / 套卷 / 未结算记录，CF 外部证据仅个人提交。客观题与套卷保留各自历史入口，不声称这是全部作答历史。

参数为 `source=ALL|HOJ|CODEFORCES|ATCODER|NOWCODER`（默认 ALL）、`status=ALL|SOLVED|ATTEMPTED`（最后一项指当前筛选来源暂未记录通过）、可选固定大方向 groupCode、最长 100 字符 keyword、onlyDuplicates（默认 false）及 page（默认 1、1～100000）。固定每页 20 个唯一题；先完整读取有效证据、复用 ProblemIdentityResolver 明确关系合并，再筛选和分页，没有候选前 N 条截断。按所选来源的最近练习时间降序、同时间按身份键排序。

返回 uid / scope / asOf / page / pageSize / total / items / groups，以及未受筛选影响的 totals：attempted / solved 是编程唯一题数，sourceRecords / solvedSourceRecords 是站内 PID 或已验证账号原题源的题目记录数，`duplicatesRemoved=solvedSourceRecords-solved`，不是原始提交数。items 中 identity 为规范身份，entries 保留每个选中来源的原题号、公开标题 / 链接、已验证 handle、solved、submissions 及 firstAttemptAt / lastAttemptAt / firstSolvedAt（毫秒、未知为 null）。recordCount 保留合并前全来源条目数，来源筛选可能只显示其中部分；通过状态依选中的来源，不把另一来源通过算作本站通过。

每个 entry 的 difficultyMetric / rating 分别使用 HOJ_RATING、CODEFORCES_PROBLEM_RATING、ATCODER_PROBLEMS_ESTIMATE、NOWCODER_SOURCE_RATING，与图表相同地处理可用目录 / 来源缓存；不同平台不共用尺度，缺失保持 null。metadataAvailable 表示当前目录确认状态，没有有效来源编号时不编造 CF / AT 链接；牛客原生 / 比赛题号链接分别保留。分类只用合法本站标签与 CF 精确标签；AT/NC 的已有外部记录与能力图同样可复用公开同源本站分类，而供体不作为新 entry 或 sourceRecord。分类撤销后不从 AT/NC 旧提交标签缓存补回。相似题名不合并，不猜测分类。MySQL 既有不区分大小写的题源键可能已聚合为同一原编号，而其他明确别名与不同账号仍保留条目。

verifiedAccounts / historyComplete / stale 说明已验证来源数、扫描补全及旧缓存状态；没有记录不代表未做过，assistanceTracked=false 不证明独立完成。接口不返回证明码、会话、源码或答案。界面只在本人主页展开时读取；换人 / 令牌代次 / 收起会隔离迟到响应，失败清除旧列表并支持重试，分页和筛选均由服务端确定。验证证据与限制见 TODO 当前迭代；测试夹具不等于真实平台授权。

## 当前已实现：训练候选与模型解释（2026-10-19）

个人主页的本人视图增加“下一组训练”。GET /api/ai/recommendations 接收 source=HOJ|CODEFORCES|ATCODER|NOWCODER（默认 HOJ）、可选 groupCode、minRating / maxRating（0～1,000,000）、includeUnrated（默认 true）。登录和 submit 权限由控制器校验，正常账号和当前参赛状态由同一 AiAccessGuard 在读取前后校验；Redis 五秒读取冷却。规则生成不依赖用户模型配置，也不发起模型或源站请求。前端按用户保留短暂读取时间，快速回到本人主页时等待冷却后再发请求；延迟期间换号、换条件或离开页面会取消旧读取，不缓存候选或凭据，服务端限流仍为最终约束。

候选仅使用当前公开单题 / 已开放客观题及可用的公开来源目录，排除本人出题、私有 / 团队题、未发布 / 不允许练习的客观题；只读取公开标题、分类、来源和评级。客观题 SQL 只投影 JSON tagIds 与 rating，不取答案、解析或题干。题目评级保留 0–5000 的 JSON 整数，缺失 / null / 无效值保持未评级；includeUnrated=false 仍保留符合数值范围的已评级客观题。已保存清单使用同一当前公开元数据读取器，评级校准后更新显示，不改练习结果。排除已有可关联的公开单题尝试、已验证外部提交（任何判题结果）、已开始的单题客观练习，复用明确来源映射去重；这不表示已证明所有未知转载题都没见过。CF 数字题序如 921/01 保留前导零，链接由固定域名和真实目录编号组成；牛客仅在 native ID / questionId 对应已确认时使用普通题的已知来源评级，提交 / 题面上下文仍各自保留。

2026-10-24 补全：AtCoder / 牛客候选可继承本站公开、非团队、无团队归属的同源远程题的已保存 XCPC 标签分组；标签自身也须为全站标签。使用上述可信身份解析，不按题名或难度推断；多份公开供体的已知分组取并集。每次候选及保存清单元数据读取重新核对，私有化、删标签或别名不再成立后下次失效。未知 / 自定义但不在固定词汇映射内的分类继续为空，basis.classificationAvailable 反映本批是否有实际分类。前端两平台已开放 groupCode 筛选；未覆盖分类可不设方向、仅按来源难度选择。只继承 groups，原题源身份、URL 和难度不变。同日后续已将公开分类读取与画像/明细统一，但不扩大用户答题计数的证据范围；实际构建和接口/Vue证据见 [任务记录](TODO.md)。

每次最多六题。本来源的已评级通过 P75 只用于该来源；默认区间为 P75 - 200 至 P75 + 400，下界不小于 0。无本来源评级样本时，以本批未尝试候选中的最低已知评级至其 +200 起步；全无评级则不补造分值。手动范围优先，双边界按中点排序、单边界按该边界；未评级仅在 includeUnrated=true 时保留。知识方向优先使用与题型对应的已开放证据：编程看编程尝试 / 通过，客观题看概念作答 / 答对；有未通过记录和样本不足只作为练习方向，不宣称薄弱或掌握概率。兼顾方向覆盖，同一来源身份不重复；有编程候选时最多配两道概念题，只有概念题时可列六道。相同用户 / 日期 / 数据保持排序稳定，同等候选使用日期散列分散。

返回 filters（原始条件）、basis（实际区间、来源难度类型、样本、新鲜度和选择范围）、items（服务器候选 ID / kind / source / title / displayId / url / rating / groups / reasons，客观题另有 questionId）、groups、version=coverage-distance-v1 和 fingerprint。读取池有资源上限：本站编程与客观题各 5,000 条、每个外部目录 20,000 条；空结果只表示本次条件没有选到候选，不宣称源站不存在题目。规则可由后端复现；未覆盖分类保持未知，AT / 牛客不通过题名猜知识点。

POST /api/ai/recommendations/explain 接收 `{filters,fingerprint,candidateIds,modelFingerprint,includeProfile:false,includeExternalProfile:false}`。列表必须与本人当前规则候选及顺序完全一致；数据、标题、权限、映射或可用性改变返回 409，需刷新。模型调用前后都重新核对候选，并检查参赛状态，防止比赛开始或题目转私有后返回旧建议。解释和聊天共用每用户并发 / 每分钟预算与全局容量，沿用个人加密 Key、固定接口协议与安全连接策略。候选和规则依据在点击时发送；完整画像仍需单独勾选，不自动发送外部记录。

模型只返回 summary 和所有候选各一次的 `{id,reason}`，可以重排；服务端拒绝未知、重复、遗漏 ID、无效 JSON 和超长解释，使用服务器原候选的标题 / 链接等字段，忽略模型自报链接。模型输出以纯文本显示，不变成 HTML 或可执行操作，也不触发代码提交。返回 fingerprint / model / profileIncluded / externalProfileIncluded；失败保留规则列表，模型没配置也能直接开始普通 / 客观练习。本文不等于真实模型验收通过，实际构建、运行证据与尚未验收项见任务记录。

## 当前已实现：本人训练清单（2026-10-21）

个人主页“下一组训练”可显式保存本组，下面提供进行中 / 已归档清单、改名、归档、恢复、删除和刷新进度。保存固定题目身份集合与顺序、当时筛选 / 推荐依据；题面仍读取当前可见版本，不复制成试卷快照。不自动调用模型、提交代码或写入手工通过状态。跨周日历、每日计划、笔记和复盘仍未实现。

```text
GET  /api/learning/training-plans?state=ACTIVE|ARCHIVED&page=1
POST /api/learning/training-plans
     # {requestId,name,selection:{filters,fingerprint,candidateIds}}
GET  /api/learning/training-plans/{id}
PUT  /api/learning/training-plans/{id}
     # {expectedRevision,state:ACTIVE|ARCHIVED|DELETED,name?}
```

四个方法各自要求登录与 submit 权限，UID 来自当前登录态，管理员也不能读取其他用户清单；Cache-Control:no-store。沿用 AiAccessGuard 正式 / 虚拟参赛限制，读写前后复核。服务端重新验证当前完整推荐及顺序，只保存服务端公开候选。名称最多 80 个 Unicode 字符，每份 1～6 题、快照最多 64 KiB；每人最多 20 份 ACTIVE、200 份未删除清单。写入在独立事务先锁正常用户行，再建立新的 REPEATABLE READ 一致性视图；不锁其他用户可能共享的空索引间隙。恢复也检查配额，所有更新要求当前 revision，冲突返回 409。

requestId 为 8～64 位字母数字 / 下划线 / 连字符；同用户、同标识及同请求指纹重试返回已有记录，即使练习后推荐已变化也不重复创建。改变命令复用标识返回 409。删除清空名称与候选快照，保留请求墓碑；旧保存重试只返回 id / DELETED，不能复活。网络失败时前端保留原保存标识；更改名称或重新获取候选后创建新标识。登录身份、令牌代次或页面变化后丢弃旧响应。

读取按已保存身份定向查询，不依赖推荐池的前 5,000 / 20,000 条限制。题目撤回、私有、关闭单题练习或变为本人的题目时，只返回 id / available=false / UNAVAILABLE，不返回保存的题名、评级、理由或入口；恢复可见后按当前元数据展示。可见题附保存时推荐依据及 SOLVED / ATTEMPTED / NO_RECORD；SOLVED 来自公开独立练习范围的通过记录和已验证外部账号记录，复用明确来源身份去重。客观题只在当前反馈策略允许且冻结答卷全对时投影通过，答案 / 解析不进入清单。NO_RECORD 不表示源站一定没有记录；外部历史可能未补全或尚未同步，也不证明独立作答或掌握程度。

数据库增量为 learning-training-plan.sql，Compose 空库通过独立 init 包装自动加载。实际构建、MySQL 并发、HTTP 和受控界面证据见 TODO，源码存在不等于用户验收完成。

## 当前已实现：CF 公开账号与来源统计（2026-10-17）

个人主页的“跨平台训练记录”接通 CF 公开数据，和上面的站内 8 轴画像分别展示。AI 的可选画像仍只发送站内证据，不自动发送外部账号资料。账号关联不需要平台密码；关联存在不代表归属，用户需在 CF 公开 Organization 字段放入本站随机校验码，30 分钟内由后端只读核对，完成后可以移除。`verified_identity` 按平台全站唯一，归属验证失败不发布；同名账号注册时间变化时撤销验证并重建缓存。未验证关联、校验码与错误细节只返回当前本人，公开端只返回正常用户已验证来源。所有管理端点使用方法级登录注解，所有变更都以 Shiro 本人 UID 查所有权，不接受客户端指定 UID。

```text
GET    /api/learning/accounts                 # 本人全部关联及私有校验状态
POST   /api/learning/accounts                 # {platform:'CODEFORCES'|'ATCODER'|'NOWCODER',handle}，每人最多5个
DELETE /api/learning/accounts/{id}            # 本人解除；级联删除同步任务、提交/评级缓存
POST   /api/learning/accounts/{id}/proof      # 本人生成/更新一次性校验码
POST   /api/learning/accounts/{id}/verify     # 本人排队重新读取对应平台公开资料核对；非同步网络请求
POST   /api/learning/accounts/{id}/sync       # 本人请求后台同步，和verify共用每账号60秒冷却
GET    /api/learning/users/{uid}/external     # 公开，仅已验证关联；Cache-Control:no-store
```

增量结构权威脚本为 `external-learning.sql`。`external_account` 保存公开标识、验证和成功时间，`external_sync_job` 保存 PROFILE → RATING → STATUS 阶段、500 条分页游标、检查点、完整扫描令牌和租约。领取/落库是短事务，网络请求在事务外；90 秒租约可恢复，解除关联/新请求后忽略旧租约响应。固定请求 CF 官方 HTTPS 地址，无 Cookie、API 签名、源码、代理或重定向；profile 禁用历史 handle 自动查找，响应上限 5 MiB，总超时 20 秒。独立守护线程每 5 秒尝试一项请求，Redis 统一 3 秒门控；失败按 1 分钟起指数退避，最长 6 小时，缓存不清零。

首次扫描所有公开提交；完成后已验证账号每 15 分钟增量扫描，至少扫最近两页后才可在越过检查点时停止，短页直接结束。每日完整扫描结束后才清理消失的提交；分页失败保留游标，重复页面幂等更新 verdict，可反映重判。上游偏移分页不是同一时刻快照，并发新提交或很早的重判由后续增量/每日核对收敛，不保证瞬时一致。未验证账号首扫后不自动常驻同步。

每来源按平台题目标识聚合尝试与通过，重复 AC 不增加题量；保留 CF 当前/最高 Rating、最近 20 场评级变化及已识别知识方向覆盖。团队通过仍可列入来源通过数，但 P75 和个人知识方向只用单成员且无 teamId 的通过记录；ghost 不计。缺少评级保持 null，缺失样本不按 0 算。此统计并不证明没有外部提示。

合计只使用合法公开本站练习和已验证外部来源，通过 `cf:contest:{contestId}:{index}` 合并明确相同原题；CF 和 Gym 的 contestId 已全局区分。只有本站远程题号符合 `CF-<contestId><index>` / `GYM-...` 时才映射，否则保留 `hoj:<id>`，自定义 CF 题集使用独立 `cf:set:<name>:<index>`。不按标题推断，也没有跨比赛同题/跨平台转载关系表。`summary` 返回 localSolved、externalSolved、uniqueSolved、duplicatesRemoved、complete、identityRule；complete 只描述已验证来源历史补全/失败状态，实时新鲜程度另看各来源 stale 和 lastSuccessAt。

AtCoder / 牛客公开统计、CF 题目目录及 CF / AtCoder 个人提交已分阶段接通，具体范围以下文当前实现为准；统一外部能力图、牛客个人提交等缺口见任务记录。

## 当前已实现：牛客公开账号与普通练习统计（2026-10-18）

关联接口接受 NOWCODER，handle 为个人主页中的数字用户 ID（1～12 位，不能以 0 开头），显示名称另存，不以昵称匹配身份。用户把 30 分钟校验码放到公开简介，由固定牛客 ACM 个人主页只读核验；页面脚本必须返回唯一、匹配的数字 ID 和个人账号标记。团队账号、身份不匹配或来源格式变化均保留旧缓存并报告未完成。未验证数据只对本人可见，同平台归属唯一的约束沿用原账号逻辑。

增量脚本为 nowcoder-learning.sql，依赖 external-learning.sql；external_nowcoder_profile 以账号 ID 为主键并级联删除，保存显示名、已发布来源汇总与扫描中的分页摘要。没有凭据、密码或 Cookie。固定 HTTPS GET 公开主页、rating-history?uid=、practice-coding?page=&pageSize=50&languageCategoryFilter=-1&orderType=DESC&search=&statusTypeFilter=-1；禁用重定向 / 自动重试 / Cookie jar，2 MiB 解压后上限、20 秒总期限，复用独立 worker，并增加跨实例牛客 3 秒 Redis 门控。忙时释放租约稍后处理，不累计源站失败。

PROFILE → RATING → STATUS → VERIFY → DONE。官方 Rating 的整数小数形式（如 947.0）精确验证，拒绝小数舍入、无效时间和重复赛事；最高值仅指已载入的评级赛历史，maximumRatingScope=AVAILABLE_RATED_HISTORY，不冒充源站全部历史最高。普通练习每页 50 条，最多 5000 页；按真实提交时间检查顺序，同秒内提交 ID 可不递减，提交 ID 仍保持唯一。首次 / 每日全量；已验证账号每 15 分钟增量，至少两页及距上次成功前 48 小时的重叠窗口，未验证完成后按本人请求更新。完整分页后重取首页，核对第一页证据指纹、总页数和来源汇总；分页变化重扫，覆盖条数 / 去重题数不一致转完整扫描，核对完成后才清理消失记录，同秒边界的未覆盖记录保留到完整核对。网络不持有数据库行锁，旧租约不可落库。

当前 statisticsScope=PUBLIC_PROFILE_CODING，原 REGULAR_PRACTICE_ONLY 名称已停用；公开编程记录可能包含比赛及赛后提交，不能统称纯日常练习。比赛上下文与专项试卷分别见前文。提交去重后计算 attempted / solved，metadata 的 challengedCount / solvedCount / submissionCount 为已核对来源汇总，不能从汇总凭空生成单题证据。来源不证明无提示独立完成，individualSolved=null、individualStatusVerified=false；题目评级使用当前可信公开目录的来源 Rating，分类可复用同源公开本站题的已保存标签，缺证据仍保持未知，详见外部学习证据接口。各平台 Rating 不换算。

普通题 nowcoder:acm:{id}、竞赛题 nowcoder:contest:{cid}:{index}、32 位练习题 ID nowcoder:practice:{hex} 分别保存；明确来源编号以及经官方 manifest 与题面共同确认的 native problemId 可用于统计映射，不凭相似题名合并。牛客 ACM 目录、题目导入及普通题 / 竞赛题个人投递已分阶段接入，恢复与真实验收边界见后文和第 4 节。CF / AtCoder 现有授权和个人提交保持原逻辑。

验证入口：NowCoderDataClientTest、NowCoderManagerTest、ExternalSyncWorkerTest；浏览器 PAPER_REVIEW_CASE=nowcoder，真实本地 HTTP 为 tests/nowcoder-local-integration.cjs（HOJ_LOCAL_NOWCODER_TEST=1，可额外 HOJ_NOWCODER_LIVE_TEST=1，只读未验证公开账号）。真实手动修改本人简介的体验仍待用户验收；未向源站提交任何代码。

## 当前已实现：牛客会话授权与个人提交（2026-10-18，代码与模拟验证）

牛客已验证来源可以使用原 submission-credentials 端点保存本人手动登录后的浏览器会话。RemoteSession 要求非空 t，保留 t / csrf_token / NOWCODERUID / NOWCODERCLINETID / acw_tc 五种已审核 Cookie；匿名响应本身也会设置设备 Cookie，因此仅有 NOWCODERUID 或客户端昵称不能证明已登录。保存前只请求固定 https://ac.nowcoder.com/，使用同一牛客 Redis 3 秒门控及账号 10 秒冷却、15 秒期限 / 2 MiB 解压后上限 / 无重定向或重试。NowCoderPage 只读取页面脚本中的字面量赋值，不执行 JavaScript；必须有唯一 isLogin=true，以及 globalInfo 对象 / 单独赋值中的同一数字 ownerId，并与已验证关联一致。公开 curUser 是“被查看的人”，不能代替登录人。失效 401、身份不符 403、无法识别的新格式 502；失败保留旧授权。

会话继续使用 s1: AES-GCM，AAD 同时绑定用途、关联 ID、本站 UID、NOWCODER 和数字来源 ID；所有权、版本、撤销墓碑和源站 Cookie 原密文比较逻辑不变，不显示原会话。设置的 submissionSupported 对 CF / AtCoder / 牛客均为 true；submissionAvailable 另要求功能开启、本人已验证和存在会话，不代表源站当前可用。真实手动登录后的成功保存尚无用户验收证据；受控 HTML / 事务测试验证成功路径，真实源站只验证了明确无效会话被拒绝。

个人提交 accounts / options 原接口现在识别明确 NC-{数字题号} 和 NC-{比赛号}-{题号}，复用站内普通题 / 训练 / 比赛的权限校验；NC-PRACTICE-{32位ID} 尚未接入此预检。题目页读取匹配的 pageInfo.questionId / problemId / contestId，团队主体不接受；随后从固定 questionbank.nowcoder.com/api/qmp/question/detail?id=...&version=3&sceneType=3001 读取该题语言，严格核对响应题号、成功码和语言格式。该元数据请求绝不携带登录 Cookie 或 Authorization，也不接受其 Set-Cookie 更新会话。每次网络请求前复查当前授权；账号冷却每次操作领取一次，牛客多次 GET 按共享频率门等待，网络不持有数据库行锁。

语言为该题当前元数据与已审核的官方 ACM 编辑器类别交集，不直接使用题库通用默认语言。官方 2.0.114 编辑器中的 V8 / SQLite / MySQL 不属于 ACM 类别，均排除；新语言需先核对类别，显示名称从当前题目元数据读取，不硬编码名称或将难度替代语言证据。官方脚本： https://static.nowcoder.com/acm/nowcoder/2.0.114/javascripts-acm/page/terminal/code/main.entry.js 。研究时对照了 https://github.com/ketherworks/nowcoder-mcp-server/tree/c0fa3796611d7684ac369900da1c04c03bc87a02 的协议组织，未安装 / 执行该项目；实际类别以官方脚本为准。

options 生成短期语言选择及私有来源快照；能力开启后，牛客界面可提交本人账号，保持没有公共账号切换。独立 NowCoderJudgeProtocol 使用固定 OAuth / JSON 判题端点，NowCoderTaskJournal 原子保存来源任务与判分；通用 HTML post 仍拒绝牛客，避免绕过专用协议。投递与失联恢复边界统一见第 4 节。

验证入口另含 NowCoderJudgeProtocolTest、NowCoderTaskJournalTest、NowCoderTaskJournalMySqlTest、NowCoderTaskRunnerTest；浏览器 PAPER_REVIEW_CASE=nowcoder|nowcoder-personal|credentials|personal-remote。本地接口使用 tests/nowcoder-session-local-integration.cjs 与 personal-remote-entry-local-integration.cjs，后者运行时必须暂停 JudgeServer，注入合成来源 offer 后检查入队并清理。本人真实账号提交与完全丢失投递响应、无 job 的自动精确对账仍未验收；牛客目录 / 导入、比赛上下文及专项试卷统计已分阶段接入，分别以本文对应“当前已实现”章节及任务记录为准，不属于此远程投递验证的证明范围。

## 当前已实现：AtCoder 公开账号与题目元数据（2026-10-17）

复用上述本人管理端点、最多 5 个关联、按平台唯一的已验证身份与公开过滤；提交 `platform=ATCODER`。归属校验通过官方英文算法资料的 Affiliation 字段，30 分钟校验码只对本人可见。`identityContinuity=HANDLE_ONLY`：资料没有可核验的永久用户 ID / 注册时间，校验只证明当时归属，不虚构注册时间；CF 原有注册时间身份保护保持不变。

评级与评级赛历史读取固定 `atcoder.jp/users/{handle}?lang=en&contestType=algo` 和 `history/json?contestType=algo`，只保留 IsRated 事件。比赛标识为字符串，存入 `external_atcoder_rating`；`ratingHistory[].contestUrl` 是该平台官方比赛地址。提交来自 [AtCoder Problems 公开接口](https://github.com/kenkoooo/AtCoderProblems/blob/main/doc/api.md)，是第三方数据而非官方提交 API。无远程登录、Cookie、认证头、代理或重定向；支持 gzip 并限制解压后响应，账号数据 5 MiB / 25 秒，目录与模型 32 MiB / 45 秒。所有外部工作共用隔离 worker、Redis 3 秒门控与失败退避。

`external_atcoder_cursor` 保存 inclusive `from_second` 和本次 `scan_start_second`。每页最多 500 条、升序时间戳；满页后从末尾同一秒重新读取。该秒仍满 500 条时先检查 `[from_second,from_second+1)` 的来源 count；只有与已见记录数一致才跳到下一秒，超出分页能力则明确失败且保留未完成状态，不能静默漏数。首次 / 每日全量；已验证账号每 15 分钟从最新缓存提交的前 48 小时开始核对，未验证首次后按本人请求。只有成功完成扫描才清理其覆盖区间内消失的提交；失败保留缓存和持久游标。来源不是瞬时快照，早期重判依赖每日全量收敛。

`external_atcoder_problem` 从 problems.json 每小时同步明确 task ID、contest ID、题号、标题、指纹、版本与可用状态；problem-models.json 每 6 小时更新估计难度。负 / 低难度按上游算法 `round(d>=400 ? d : 400/exp(1-d/400))` 转换，实验模型或缺失难度保持未知，不填 0。完整有效快照原子发布，失败保留旧值。难度不自动覆盖本站题目 Rating；面板返回 `metadata` 中两类成功时间及过期状态。

统计按 `atcoder:task:{task_id}` 去重，保留来源题号大小写；本站明确 `AC-<task_id>` 可映射。旧抓题器把题号转小写时，仅在来源目录存在唯一明确题号时恢复规范 ID，不按标题合并。`dataSource=ATCODER_OFFICIAL_AND_ATCODER_PROBLEMS`、`difficultySource=ATCODER_PROBLEMS_ESTIMATE`；由于来源不提供团队 / 独立作答证明，`individualStatusVerified=false,individualSolved=null,representativeScope=ACCOUNT_ACCEPTED`，P75 使用该账号全部已通过且有可靠估计难度的题。来源没有可靠算法分类，`coverageAvailable=false,coverage=[]`；页面说明缺口，不生成零分能力图。不同平台评级不换算或相加。

`atcoder-learning.sql` 是三张新增表及两项 `remote_source_job` 任务的权威结构；先执行 `external-learning.sql`、`remote-sources.sql`，再执行该脚本。增量可重复运行，初始化末尾包含同一段。该统计阶段之外的个人提交和管理端导入已继续接入，范围见下节与第 4 节。

## 当前已实现：AtCoder 目录选题与双语题面（2026-10-18）

管理端“题库 → 远程题目”增加 Codeforces / AtCoder 切换。AtCoder 复用 external_atcoder_problem 每小时目录及每 6 小时难度模型，不复制为 CF 数字比赛号。筛选、显示使用明确 task ID 和 AtCoder Problems 难度估计，未知保留 null；新入库 problem.rating 留空，不把第三方估计当作站内 Rating。

目录主键为 `atcoder:task:{task_id}`，站内显示题号为 `AC-{task_id}`。个人提交从显示题号取得原生 task ID，再按规范目录键查询实际 contest_id；不能将显示题号直接当作目录主键，也不从 task ID 前缀猜比赛。返回的 key / task_id 再以精确字符串核对，避免 MySQL 大小写不敏感比较改变原生身份；目录缺失 / 下架或比赛归属变化时停止。该约定由真实 AtCoderCatalogManager 生成目录的 MySQL / HTTP 模拟流程验证；真实本人会话仍按用户选择延后。

新增 /api/admin/remote-sources/atcoder 下的 GET 目录、POST /sync、POST /imports、GET /statements、POST /statements/en 与 /zh；沿用六项 Shiro 管理角色 / 身份检查、本人编辑权和 no-store。导入复用 remote_problem_import 及 remote_statement_*，无需新增表；已有库需先执行 atcoder-learning.sql。只有受信目录提供真实 contest_id 与 task_id；不按 task 前缀猜比赛，不接收客户端 URL。异步获取前固定目录指纹，提交版本前行锁复核，元数据改变或旧租约不能发布。

官方公开英文页使用固定 atcoder.jp、无 Cookie / Authorization、无重定向和自动重试、4 MiB / 25 秒上限；核对 og:url 和唯一 lang-en，保留题干 / 约束 / 表格 / 公式、输入输出、完整配对样例和样例解释。来源脚本不执行，图片只保留 HTTPS AtCoder 域名；缺英文的旧题提示人工录入英文原文，不把日文标为 English。AtCoder 官方页与本人提交共用平台 3 秒门控，忙时释放导入租约，不消耗失败次数。

首次导入创建 AC-{task_id}，默认 auth=2，沿用来源 AC 标签；若未配置 AC 静态语言，仅给编辑器 / 本站自测关联已有 ME 语言。实际个人远程语言仍按题目表单读取，不由编辑器名称或旧语言编号推断。后续刷新新增原文版本，不覆盖题库手工编辑；中文绑定原文 hash，原文改变后旧译文不再公开。学生页已对 CF / AC 显示题库 / English / 中文切换及正确来源名，比赛 / 团队限制不变。中文仍由管理员核对录入，AI 自动翻译未实现。

验证入口：AtCoderStatementClientTest、RemoteSourceManagerTest、AtCoderRemoteSourceAuthorizationTest 与 ExternalSyncWorkerTest；浏览器 PAPER_REVIEW_CASE=atcoder-sources|remote-sources，本地接口 tests/atcoder-source-local-integration.cjs，HOJ_LOCAL_ATCODER_SOURCE_TEST=1；可另开 HOJ_ATCODER_STATEMENT_LIVE_TEST=1，只读导入官方 Product 页并清理创建的私有题目。用户真实远程提交验收与其区分，最终证据见任务记录。

## 当前已实现：牛客 ACM 目录与双语题面（2026-10-18）

管理端远程题目增加“牛客 ACM”。`nowcoder-sources.sql` 在已有 external-learning / remote-sources / remote-statement-refresh 基础上新增 remote_nowcoder_problem、remote_nowcoder_scan 和 remote_nowcoder_catalog_stage，并幂等新增 NOWCODER_PROBLEMS 任务。游标和 generation 持久保存，重启 / 失败保留进度；HEAD 先发布首页，FULL 逐页暂存，VERIFY 复核首页指纹、来源总数和唯一 ID 数后才全量发布并标记移除。显示题号不保证数值有序，不能据此判断翻页错误。每小时检查首页，完整核对超过 24 小时或首页变化才重新全扫；这些是到期计划，不保证即时完成。管理端同时显示已发布数、来源报告数、scanPage 和 lastFullSuccessAt。

八项接口位于 `/api/admin/remote-sources/nowcoder`：GET 目录、POST /sync、POST /lookup、POST /imports、POST /refresh、GET /statements、POST /statements/zh 和 /en。全部要求当前登录管理角色并设置 no-store；编辑 / 获取沿用本人维护权。lookup 的 sourceId 接受 NC218144 / NC-218144、11244/A / NC-11244-A 或固定源站规范 URL，拒绝额外 query / fragment、外部主机与 UUID 专项地址。只接收精确题号，不接收任意抓取 URL。手动同步十分钟冷却；正常扫描或活跃租约拒绝重复启动，无活跃租约的 BACKOFF 可保留原 generation 续扫。

普通题键 `nowcoder:acm:{id}`，题库显示 NC-{id}；比赛键 `nowcoder:contest:{cid}:{index}`，显示 NC-{cid}-{index}。比赛题先读取官方 problem-list，核对 contestId、唯一题序、完整题数和实际 problemId，再核对题面 questionId / contestId / problemId；普通和比赛上下文各自保留，不用同标题归并。公开目录 sourceRating 与站内 rating 分离，后者不自动赋值。首次入库 auth=2，仅关联已有 ME 语言供编辑器 / 本站自测，个人远程语言仍按源站读取。

固定公开主机 ac.nowcoder.com、4 MiB / 25 秒、禁重定向 / 自动重试 / 登录头和 Cookie，复用牛客 3 秒来源门控。只接受完整非 PDF 的普通编程题面；脚本不执行，清洗描述 / 输入输出 / 提示，保留完整样例空白与官方公式，图片仅 HTTPS 牛客域名。中文为 zh-CN 原文，英文为 en 管理员核对译文，两个保存接口按该映射执行；刷新、逐题每日检查和译文过期机制沿用共享版本表。后台回调先复核租约与当前权限，失效回调不能写入目录或版本；题库手工编辑保持不被原文更新覆盖。学生页按 sourceLanguage / translationLanguage 显示中文 / English，私有及比赛 / 团队限制保持生效；AI 自动翻译尚未接入。

验证入口：NowCoderStatementClientTest、NowCoderCatalogManagerTest、NowCoderCatalogMySqlTest、NowCoderSourceManagerTest、NowCoderRemoteSourceAuthorizationTest 和共享来源 / worker 回归。浏览器 PAPER_REVIEW_CASE=nowcoder-sources；本地 tests/nowcoder-source-local-integration.cjs 需 HOJ_LOCAL_NOWCODER_SOURCE_TEST=1，另开 HOJ_NOWCODER_SOURCE_LIVE_TEST=1 仅只读导入官方普通 / 比赛题并清理临时数据。完整真实目录核验、部署及证据状态见任务记录，不以首批目录或测试通过替代全量完成。

## 当前已实现：来源题面的每日检查（2026-10-18）

CF / AtCoder 的 remote_problem_import 追加 auto_refresh（首次未选择时可空）、last_checked_at 和 last_success_at；沿用 next_run_at 和原 lease 字段，不建立第二套抓取队列。已有库先执行可重复的 remote-statement-refresh.sql，再更新业务后端；新建库的 remote-sources.sql / hoj.sql 含相同字段。迁移只对未设置策略的已有版本按 SOURCE / MANUAL 设置默认值，不重写题面或覆盖显式选择。

官方首次成功获取默认每天检查，人工保存英文原文会关闭自动检查；中文保存不改变策略。两个来源新增 POST /api/admin/remote-sources/{codeforces|atcoder}/refresh，接受 problemKey 与必需 Boolean autoRefresh；沿用 Shiro、当前正常账号、题目所有权及 no-store。已保存原文后可启停；策略调整会清除旧租约及待执行检查，从当前时间重新排 24 小时，迟到的旧结果不得发布。修改原文同样使旧回调失效，后台 IO 不持有数据库事务。

claim 复用现有 FOR UPDATE 排他租约，优先处理显式请求及进行中的重试，再领取到期 DONE / FAILED 自动检查；source worker 与源站频率门继续共用。成功内容未变复用版本，只推进最近检查 / 成功时间及下一周期；改变才发布新版本，保留题库手工题面并使旧译文停止公开。失败按 1 / 2 分钟退避，三次后保留原文并等待下一天；下一周期重置失败预算。来源移出目录或请求人 / 维护权变化后停止自动检查，需管理员重新操作。

adminView 返回私有 refresh 对象（enabled、intervalHours=24、lastCheckedAt、lastSuccessAt、nextCheckAt、lastError），学生接口不返回配置 / 内部错误；目录行增加 translationStale。管理端“原文与译文”显示开关、最近检查 / 最近成功 / 下次检查及失败说明，开关请求保留正在编辑的译文，账号 / 平台切换仍隔离迟到响应。定时周期是到期时间，实际执行还受队列与来源限频影响。

验证含 H2 的稳定版本 / 重试 / 权限回归及独立临时 MySQL 库的 REPEATABLE READ 测试：两个 worker 只有一个领取任务，关闭策略后旧事务不能回写。真实运行中的 Java 8 worker 已完成官方 Product 的首次导入及再次定时检查，无变化不产生第二个版本；验收只将本测试临时题目提前到期，未推进其他导入记录。详见任务记录及 tests/atcoder-source-local-integration.cjs。

## 当前已实现：来源更新与未编辑题库副本（2026-10-21）

CF / AtCoder / 牛客的后续 SOURCE 获取如改变原文，会比较上一版原文与题库副本的标题、正文、输入、输出、提示、样例、时间和内存限制。八项全部精确一致时，同一事务更新题库八项内容及修改时间；默认题面和列表标题因此跟随原文。比较保留大小写与空白差异，不能用数据库的大小写不敏感比较认定未编辑。Rating、标签、作者、语言关联、开放状态及其他本站规则不随此步骤修改。

任一项已经不同，保留整份题库副本，新原文照常发布并使旧译文失效。没有上一版原文的既有题目也保留；无法证明是未编辑副本时，不从其他历史版本猜测覆盖。管理员手工保存原文仍只维护来源版本并关闭自动检查，不自动改写题库。相同原文重复获取不改变题库修改时间。管理员界面已说明该策略，学生仍可在题库题面 / 来源原文之间切换。

worker 沿用来源目录与导入任务的锁，在比较副本之前对 problem 行作 FOR UPDATE 当前读，防止 REPEATABLE READ 旧快照覆盖等待期间提交的人工修改。原文版本 / 发布指针、题库更新和导入任务结算同事务，任一失败整体回滚。没有新增接口、表或迁移；验证与运行版本见 [任务记录](TODO.md)。

## 当前已实现：个人平台提交凭据（2026-10-17，代提交尚未开放）

已验证 CF / AtCoder 关联改为手动浏览器会话授权。`GET /api/learning/accounts/{id}/submission-credentials` 返回 `{accountId,platform,handle,configured,version,updatedAt,ownershipVerified,credentialType,reauthorizationRequired,sessionCheckedAt,submissionAvailable:false}`；PUT 接收写入专用 `{cookieHeader,version,authorized:true}`，不再接收密码；DELETE 接收查询参数 `version`。三方法均要求登录、正常站内账号及本人所有权；授权还要求当前归属已验证，拒绝客户端 UID。响应 no-store，从不返回 Cookie、密码或密文。原始 Cookie 最长 8192 个 ASCII 字符，拒绝换行 / 控制字符 / 重复名；仅保留平台会话与访问验证 Cookie，丢弃跟踪 / 偏好项。CF 必须含 JSESSIONID，AtCoder 必须含 REVEL_SESSION。

保存前只读请求固定 HTTPS 受保护设置页，关闭重定向、自动重试、共享 Cookie jar；15 秒总期限、2 MiB 解压后上限，共享来源 Redis 频率门和每账号 10 秒冷却。源站登录导航必须给出唯一身份并与绑定 handle 匹配；公共个人页不能证明登录。登录失效 / 无法识别 401，账号不符 / 归属未验证 403，源站连接或格式失败 502，验证挑战 / 暂不可用 503，频率限制 429。源站页面和错误正文不返回客户端。网络核对不持有数据库行锁，保存前重新核对站内状态、归属、平台 / handle 及单调版本；失败保留原授权。`sessionCheckedAt` 只表示上次保存时核对，不承诺会话持续有效。当前尚无真实源站会话的在线验收。

`remote-credentials.sql` 为权威增量结构，本次无表结构迁移。`remote_user_credential(account_id,credential_ciphertext,credential_version,updated_at)` 以关联 ID 为主键及级联外键；AES-GCM 使用随机 nonce，并将用途、账号 ID、UID、平台与规范 handle 放入 AAD。会话密文 `s1:` 额外绑定 BROWSER_SESSION 用途；旧 `v1:` 密码保留但标为需重新授权，不可进入个人任务，前缀互换不能通过 GCM 校验。独立密钥卷不与 AI 密钥混用；已有密文时缺失密钥不能被覆盖。只读解密不创建锁文件，便于后续判题服务只读挂载密钥；目前判题服务仍未挂载该卷。删除保留空密文与单调版本，解除关联则级联删除整行；CF 注册时间身份变化清除旧密文且递增版本。内部读取函数再次用存储的 judge UID 检查归属、验证状态及任务凭据版本，当前尚未接入远程任务分发。

判题诊断对象排除密码、Cookie、CSRF、token 与源码；Judge 的远程密码字段不参与公开 Jackson 序列化。数据库初始化 / 远程登录失败日志去除密码，并停用判题服务 HTTP wire / header 日志。旧提交路径已移除 `judge.vjudge_password` 写入，旧结果重查按原用户名从当前管理员配置取凭据，缺失则停止；历史值没有全库清除，旧管理员账号池也没有迁移为个人凭据。个人凭据不进入旧提交表或管理员池。

## 个人远程任务协议（执行器与用户提交入口已接入，真实源站待验收）

`personal-remote-tasks.sql` 是持久任务及远程身份锁的权威结构。`personal_remote_task` 关联站内 judge（删除级联）和本人关联（解绑置空），快照保留原账号编号、规范平台 / handle、凭据版本、实际源站语言 ID、代码 / 判题版本指纹和本人请求 ID。`personal_remote_lane(platform,handle_key,active_submit_id)` 跨账号关联编号保留排他占用，未知源站受理状态不能靠解绑重绑绕过。

共享 `PersonalRemoteTaskStore` 管理事务和状态，`PersonalRemoteTaskRunner` 驱动源站适配，JudgeServer `PersonalRemoteWorker` 独立调度，默认关闭、需显式启用；对外接口见第 4 节。调用方须先通过站内题目 / 比赛 / 训练权限检查，在同一数据库事务创建 judge、比赛 / 训练记录与入队。后台每次请求前通过共享 Redis 门控并检查正常账号、当前归属、会话版本、原代码 / 判题版本及固定源站比赛范围。账号锁使用 FOR UPDATE 当前读，避免 MySQL REPEATABLE READ 重用旧快照。源站会话轮换按原密文比较加密写回，不覆盖撤销；只能由本人明确恢复同身份新会话，不重置 POST 次数。增量 `personal-remote-dispatch.sql` 补固定 `source_contest` 与用户请求幂等 `request_hash`；牛客另需 nowcoder-remote.sql 来源快照与 job 账本。

状态为 QUEUED → PREPARING → SUBMITTING → RECONCILING / POLLING → COMPLETED；代码 POST 前持久化基线 ID、次数 1 与起始时间。90 秒租约过期后，SUBMITTING 只恢复为 RECONCILING，旧 token 不得继续操作。查询失败指数退避，跨过 POST 边界超过 10 次进入 NEEDS_REVIEW，仍占用同一远程身份；正常待判不消耗失败次数。POST 前可取消 / 安全失败并释放占用。实际表单提供任务语言 ID；源站 ID 必须由适配器结合源题 / 用户 / 语言 / 时间和完整代码确认唯一匹配，不接受浏览器上报或响应最大 ID。源站创建时间须位于持久化 POST 起点前 2 秒至后 2 分钟（含边界），同时不超本机当前时间后 2 分钟；该创建窗口不会随查询重试后移，恢复 / 待判查询本身可以在很久以后进行。超出创建窗口的相同代码不认领；窗口只是本地关联策略，不能证明源站处理延迟，无法核实时进入人工核对。最终结果、站内 AC / 比赛记录和释放占用在同一事务，停止任务也事务结算，旧判题版本不得落库。现有远程赛题 AC=100 / 其余=0 约定继续使用；源站部分分映射仍未实现。个人任务被排除在旧自动重判、用户重提、管理重判 / 手判 / 取消及公共账号队列之外。站内提交入口和 UI 已接入，真实源站在线验收仍未完成，证据与下一步以 [任务记录](TODO.md) 为准。

## 当前已实现：CF 目录、导入队列和双语版本（2026-10-17）

管理端入口 `/admin/problems?type=remote`。下列管理接口均使用方法级登录及 root / admin / problem_admin 角色校验，普通管理员仅可维护自己导入的题目；后台任务再次根据实际数据库检查账号、角色及归属，不能只信任排队时的前端状态。

```text
GET  /api/admin/remote-sources/codeforces?page=1&keyword=4A&ratingMin=800&ratingMax=1600
     # 30条/页，records、total、status；未评级保留null，区间筛选排除null
POST /api/admin/remote-sources/codeforces/sync
     # 排队更新公开目录；10分钟冷却；活动租约期间拒绝覆盖
POST /api/admin/remote-sources/codeforces/imports
     # {problemKey:'cf:contest:4:A'}；每题60秒冷却，每人最多5个待获取任务
GET  /api/admin/remote-sources/codeforces/statements?problemKey=cf:contest:4:A
POST /api/admin/remote-sources/codeforces/statements/en
POST /api/admin/remote-sources/codeforces/statements/zh
     # {problemKey,sourceHash,content:{title,description,input,output,examples,hint,timeLimit,memoryLimit}}
     # sourceHash为打开编辑器时的当前原文指纹，首个原文为null；过期原文或译文写入返回409
GET  /api/problems/{pid}/statements
     # 匿名；只允许公开、非团队远程题；旧导入尚无版本时available:false
```

`remote-sources.sql` 是五张增量表的权威结构，初始化末尾包含相同内容。`remote_source_job` 保存目录调度、租约、成功 / 失败状态及新增 / 变更 / 移出计数。`remote_catalog_problem` 按来源 ID 保存题号、title、rating、标签、SHA-256 指纹、revision 和 available；只在完整有效响应后原子替换可用目录，不清除历史来源证据。不用标题匹配去重。CF `problemset.problems` 官方 API 返回元数据而非题面：自动每小时更新，固定 HTTPS、无凭据 / Cookie / 跳转；响应最多 32 MiB、总超时 45 秒。

`remote_problem_import` 保存每题 QUEUED / RUNNING / BACKOFF / DONE / FAILED、请求身份、90 秒租约、失败和题库引用。公开题面通过固定 CF 页面普通 GET 获取，4 MiB / 25 秒上限；不处理或绕过访问挑战，最多 3 次失败，失败间隔 1 / 2 分钟。目录、导入和账号统计使用同一隔离 worker 并轮流执行，保留共享 Redis 3 秒门控；关闭 `hoj.external-sync.enabled` 会停用这三类同步。网络在事务外执行，发布时重新核对权限与租约；新题复用原 Problem / 语言 / 标签关联，默认隐藏，缺少 CF 语言配置时回滚。

`remote_statement_version` 按来源 / 语言 / 内容及原文指纹保存不可变版本，`remote_statement_current` 保存当前原文和已核对中文的指针；重复内容可复用旧版本，指针单独更新。HTML 清理脚本、事件、危险协议和非 CF 图片，将 CF 的三美元公式标记转换为当前 HOJ 支持的单美元标记，样例保留原文本行。中文绑定当前原文指纹，原文变化后 `translationStale=true`，公开接口不返回旧中文；管理员仍可核对编辑后重新保存。当前只支持管理员录入 / 核对译文，没有自动 AI 翻译。

刷新版本不覆盖题库已有 title / description 等手工字段。学生默认题库题面，明确切换后仅替换显示内容和限制，不改题号、代码或提交身份；比赛 / 团队题面不加载该接口，原快照逻辑保留。来源更新成功、实际 HTML 抓取和手工录入分别验收：本地目录 11,457 条已同步，实际公开题面仍返回 403，失败提示和手工中英路径通过，不能据此宣称线上自动抓题或远程提交已完成。

## 1. AI 会话

### 建议表

- `ai_conversation`：用户、标题、入口、题目/训练关联、状态、创建时间。
- `ai_message`：会话、角色、内容、模型、Token、耗时、状态。
- `ai_citation`：消息、文档/Chunk、来源展示信息。
- `ai_feedback`：消息、用户评价、原因。
- `ai_usage`：用户、供应商、模型、Token、费用估算、时间。

### 个人提交 API（2026-10-18 已实现，本地联调通过；真实源站提交待验收）

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
POST /api/personal-remote/accounts
POST /api/personal-remote/options
POST /api/personal-remote/submissions
GET  /api/personal-remote/submissions/{id}
GET  /api/personal-remote/requests/{requestId}
POST /api/personal-remote/submissions/{id}/reconcile
POST /api/personal-remote/submissions/{id}/cancel
```

七个操作分别执行 Shiro 身份校验，响应 `Cache-Control: no-store`；UID 仅取当前登录用户。`accounts/options` 复用原题库 / 团队 / 训练 / 比赛提交权限，不创建判题记录。请求范围为 `pid` 和可选 `cid/tid/gid`，比赛与训练不能同时指定；正式混合赛仍走答卷入口，不走本接口。来源从实际题目和可信 AtCoder 目录解析，客户端不能提供源站 URL。

`options` 接收本人 `accountId`，只读抓取真实源站语言表单，源站 IO 不持有数据库事务。返回 `offerId/languages/expiresIn`；服务端 Redis 保存 5 分钟的语言选择，绑定 UID、账号、授权版本和实际来源 / 题目范围，不向浏览器返回 CSRF 或 Cookie。`submissions` 增加 `offerId/languageId/requestId/code`，UTF-8 代码上限 65535 字节。源站标签写入 `judge.language`，独立语言 ID 写入任务；编辑器原语言仍用于高亮和本站自测。

新提交先按执行器相同顺序锁定身份队列、账号 / 正常用户，再锁定实际题目；复用原提交创建方法，并在同一事务写入 judge、训练 / 比赛记录及个人任务。`request_hash` 对完整代码、账号、语言和请求范围做 SHA-256；同一 UID 与请求编号重复返回原 submitId，不同内容返回 409。MySQL REPEATABLE READ 下使用锁后的当前读检测并发重复；入队失败回滚 judge。源站会话撤销 / 换版使旧 offer 失效。本人显式提交之前不 POST 代码，公共账号入口继续独立。

`requests` 用于网络超时 / 刷新后找回原请求，不新建提交；浏览器只额外保存请求元数据，不在任务存储中保存会话或代码。状态接口只返回任务允许字段及原 `JudgeManager` 过滤后的判题结果，遵守 OI 隐藏策略；隐藏时去除源站提交编号，创建 / 取消 / 核对回执始终不返回该编号。`cancel` 只允许源站 POST 前取消，`reconcile` 只核对已投递结果，均限本人，不绕过一次 POST 边界。

会话授权继续使用 `/api/learning/accounts/{id}/submission-credentials`；CF、AtCoder 和牛客均返回 `submissionSupported=true`。`submissionAvailable` 仅在后端功能开启、归属已验证且有 `s1:` 会话时为真，不表示会话仍有效或真实源站 AC / WA 已验收。三平台均尚缺用户本人真实会话成功提交的验收证据；用户选择先完成代码与模拟验证。

牛客普通题 `NC-{题号}` 和竞赛题 `NC-{比赛号}-{题序}` 使用固定新判题 API；选项预检将内部 questionId、实际 problemId、contestId 与当前题目语言写入服务端 offer。创建 judge 的同一事务新增 `personal_remote_nowcoder`，冻结账号、源题、比赛和语言六字段及指纹；其中只保存非秘密来源信息、后台 job ID 和公开 submission ID，不保存短期 token 或额外代码。后台 job ID 与公开提交编号分别记录，不能互相推算。部署前必须执行可重复的 `nowcoder-remote.sql`。

牛客每次投递或轮询重新核对首页登录身份，并从固定网关取短期 token；网关仅携带审核登录 Cookie，固定判题主机仅接收 token，不接收 Cookie / Authorization。代码 POST 前持久化一次投递屏障；重启有 job ID 时继续查询，最终来源编号、正常判分及站内成绩在同一事务写入。未知状态、自测结果、不同 job / 来源身份及缺少最终公开编号均不当作 AC；隐藏状态保持等待。判题时长 / 内存及源站部分分尚未映射，比赛仍沿用远程题 AC=100 / 其余=0。

提交前先读取本人来源历史，将已见最大公开 submission ID 作为基线，与一次 POST 屏障一起持久化；没有历史时基线为 0，历史读取失败不投递代码。已知 job 返回的公开编号必须严格大于基线，且不能改变已确认的编号；违反任一条件转 `NEEDS_REVIEW / AMBIGUOUS_RESULT`，释放执行租约并保留身份队列，不落分、不再次 POST。旧 worker 的失效租约及不同 job / 上下文仍按原冲突校验处理。该边界不能替代丢失 job 时的完整代码核对。

投递响应完全丢失且没有 job ID 时，牛客仅只读检查本人来源记录；当前无法通过受保护详情证明完整代码一致，任务转 `NEEDS_REVIEW / POST_UNCERTAIN` 并保留身份队列，不重发、不根据相近题号 / 时间猜判。已知 job 的人工恢复继续轮询。这是明确保留的恢复缺口，模拟正常路径通过不代表所有失联情形已自动恢复。

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

答卷读写、判题和交卷继续使用上述 `/api/papers/runs/{id}...`，返回 contestId、contestType、serverNow、endTime；前端路径 `/contest/:contestID/mixed/run/:runID`，显示比赛返回按钮、倒计时和常驻切题栏。旧编程提交接口拒绝混合比赛 cid。AI 已接入服务端助教与可信参赛检查，具体限制见本文开头；混合赛提前交卷不提前解除 AI 限制。

## 站内比赛 Rating（2026-10-22 收简）

`problem.rating` 继续为可空整数 0–5000，供题库筛选、题面显示和学习难度统计；`difficulty` 只保留旧业务兼容，不自动换算。客观题 Rating 位于版本内容 JSON，题面快照保留开卷时数值。练习通过不产生用户 Rating。`contest.rated` 默认 false，管理接口校验主站公开赛、开赛后锁定 Rating 开关及赛制 / 计分 / 时间。

`site_rating_profile` 只为 contest 维护当前值、最高值、最近变化和计分场次；`site_rating_history` 保存比赛前后值、变化、结束时间与平均名次。`site_rating_lock` 行锁串行化多副本结算。每分钟在事务中按时间重放已结束且判题 / 答卷完成的正式计分赛，只替换 contest 行，异常保留上一版快照；已有 practice 行原样保留，不参与结算或公开查询。比赛初始 1400，至少两位有效参赛者才计分，未参加计分赛返回 null。比赛重判继续修正后续 Rating。

```text
GET /api/rating/rank?type=contest&currentPage=1&limit=30&searchUser=...
    # 匿名可读，type 默认 contest，其他类型拒绝；最高每页100，搜索保留全局名次。
    # records/total/current/size/updatedAt。
GET /api/rating/users/{uid}?type=contest
    # 匿名可读，type 可省略，均只返回 contestRating/contestCount/contestPeak。
    # history 只含 contest，取最近100条；practice 或未知类型拒绝。
GET /api/get-problem-list?...&ratingMin=1200&ratingMax=1800
    # 题目难度区间筛选仍保留，不包含未评级题；原题库 / 标签 / 分页条件保留。
```

个人主页接口只保留 contestRating，日常能力图 / 题目明细使用原学习接口。排名页 /rank/contest；/rank、/rank/practice、/acm-rank、/oi-rank 统一跳转此页。导航直接进入比赛榜，不改变单场比赛的 ACM / OI 成绩规则。

2026-10-16 审查修复：原每 20 分钟的 Pending 恢复任务仍检查超过 15 分钟未更新的提交，套卷分流到专用事务恢复入口。该入口重新加行锁检查状态/时间及原快照归属，更新版本与恢复时间，提交事务后派发原提交；不改原提交时间、代码、答卷或快照，不重新读取题库或要求在线用户。并发扫描只恢复一次，已完成或新 Pending 不处理；普通管理员重判入口继续拒绝套卷提交。首页周提交统计增加相同的 `paper_run_id IS NULL` 隔离条件，缓存使用新键以立即避开旧口径数据。

## 2026-10-16 管理端录题与组卷

题库入口 `/admin/problems` 汇总编程题与客观题；`/admin/questions/create` 统一选择编程、单选、多选、判断、填空。原编程编辑与比赛专用录题地址保留，`/admin/objective` 转到题库客观题页。新建题目默认仅题库保存；录入完整合法题目后可关闭单题开放并加入题集、试卷和比赛。此处“保存到题库”仍执行完整题目校验，并非未完成表单的自动草稿。

2026-10-17 操作流程：客观题分为题目内容、答案与解析、练习开放；题集分为基本信息、选择与排序、开放设置；混合试卷分为基本信息、选题与分值、规则与开放。步骤间只修改当前表单，最后一步才提供保存按钮；完整题目和容器仍通过原接口保存，服务端校验不变。题目预览不显示标准答案；答案编辑步骤保留题干和选项供核对。保存失败保留表单及嵌套录题已加入的引用，默认仍不开放日常练习。

客观题、题集、试卷及内嵌编程录题以当前登录身份和内存快照判断是否修改。关闭父编辑器时同时检查嵌套编辑器；取消放弃后保留全部输入，确认放弃只关闭未保存编辑，已保存的新题仍在题库。页面切换复用相同检查，浏览器刷新注册原生离开提醒；保存中阻止关闭与切页，成功保存不再被误判为未保存。未实现半填表单的自动草稿，浏览器原生离开提醒依赖浏览器支持。

`/admin/papers` 统一列出 `objective_set` 与 `paper`，按类型、标题、权限分页；旧记录与学生作答地址保持原数据来源，不搬迁作答。新建题集采用逐题客观题训练，新建试卷统一使用可混合题型的 `paper`；已有客观题整卷记录继续编辑。题集和试卷都可在编辑器内新建题目并自动加入，编程题只用于混合试卷。试卷结构预览不创建学生答卷；已开放记录可跳转学生列表查看。

```text
GET /api/admin/papers/collections?currentPage=1&keyword=...&kind=PRACTICE|PAPER
    # root/admin/problem_admin；普通 admin 仅自己的记录；每页20，跨两来源统一分页。
    # records: id/title/source(OBJECTIVE|PAPER)/mode/questionCount/published；不返回答案或题目配置 JSON。
POST /api/admin/problem
    # 原状态与校验保持；成功 data 现在返回创建后的 Problem（含数据库 id），供组卷自动加入。
GET /api/admin/papers/program-questions?keyword=...
    # 本地标准、非团队题：公开题 + 当前管理员有权引用的保密题；附 auth 元数据。
```

客观题单题开放要求 `published && practiceEnabled`；新建界面用一个开关同时控制两项。编程题单题开放使用 `auth=1`，新建关闭时 `auth=2`。发布题集 / 试卷只控制容器访问，不再要求先公开其中题目；未发布试卷仍可由有权管理员用于比赛配置。客观题引用校验创建者，编程题的私有/比赛题引用校验作者或 root/problem_admin；学生不可通过任意题目 ID 调用私有快照入口。

学生仅从已发布试卷、已发布题集或有参赛权限的比赛内访问持久化引用，服务端冻结题面/答案/测试数据，沿用原开放与判分规则。套卷与比赛的内部快照入口允许保密题，独立题目 API 保留可见性校验。比赛保存时再次检查引用权限，固定模板后沿用原冻结规则。之后独立开放单题练习不改变已有答卷或比赛模板。

## 视频资料与授权播放（2026-10-20）

视频沿用知识库资料 / 不可变版本 / 入库 / 显式发布。原件位于私有共享卷，元数据不返回服务器路径或 assetId；`knowledge_video_source` 随资料版本保存原件哈希、时长、模型和 ASR / ASR_REVIEWED 来源。上传产生独立 `knowledge_video_import`，在完成前不会创建占位知识库版本或替换已有发布。管理员操作仅 root / admin 可用，具体部署与验证状态以 TODO 为准。

```text
GET  /api/admin/knowledge/video-imports
POST /api/admin/knowledge/video-imports
     # 原始 MP4/WebM 请求体；查询参数 title/visibility/requestId/fileName/language。
     # 可选 targetDocumentId/expectedRevision；单个文件最多 1 GiB、2 小时。
GET  /api/admin/knowledge/video-imports/{id}
POST /api/admin/knowledge/video-imports/{id}/retry
POST /api/admin/knowledge/video-imports/{id}/cancel
     # expectedRevision；retry 可带 restart=true，明确清除全部转写检查点。
POST /api/admin/knowledge/video-imports/{id}/save
     # title/visibility/requestId/importRevision/expectedRevision；可选 asNew。
     # reviewedVideo=true 时才采用 content；否则使用服务端原始转写。
POST /api/admin/knowledge/documents/{id}/versions
     # format=video、sourceVersion：复用服务端原件；明确 reviewedVideo 才校订字幕。

POST/DELETE /api/admin/knowledge/video-imports/{id}/video/access
GET         /api/admin/knowledge/video-imports/{id}/video
POST/DELETE /api/admin/knowledge/documents/{id}/versions/{version}/video/access
GET         /api/admin/knowledge/documents/{id}/versions/{version}/video
POST/DELETE /api/knowledge/documents/{id}/video/access?version=N
GET         /api/knowledge/documents/{id}/video?version=N
```

上传与保存均有请求指纹。任务按 30 秒检查点推进，持续校验原件 / 模型与有期限租约，过期或旧任务不能追加字幕。模型变化需明确从头转写。全局 / 每位原上传管理员分别最多 12 / 4 份未保存草稿；完成或失败仍占容量，保存 / 取消仅释放一次。处理管理员被撤权则停止，可由当前管理员接续；审计中的原上传者不改变。锁定容量行与读取角色使用当前读，真实 MySQL REPEATABLE READ 并发、旧快照、重复保存及撤权检查已有独立测试。

私有媒体目录每 60 秒扫描最多 128 个条目，游标跨轮次推进。没有登记的原件和上传临时文件保留至少 24 小时；已取消且没有任何资料版本引用的原件可立即重试清理。未取消任务和所有视频版本（包括逻辑删除后的审计版本）始终保护原件。上传进程持有跨进程文件锁直到登记事务结束；回收使用独立事务的当前读复核引用，事务结束后才隔离重命名并删除文件，失败留下的隔离文件由后续轮次接续。不递归、不跟随符号链接、不处理非服务生成的文件名。登记提交结果不确定时保留原件并要求使用原请求重试，避免删除已经提交的任务原件。本轮无新增接口或 SQL 迁移，实际部署与验证见 TODO。

播放授权 POST 必须携带正常 Authorization，返回固定本地媒体 URL 与 expiresIn=120，并写入 HttpOnly、SameSite=Strict Cookie。Cookie 只包含随机句柄；Redis 按当前视频路径保存短期授权，JWT 不进入 URL 或可读 Cookie。只有精确的视频 GET 路由接受此 Cookie，其他接口 / POST / HEAD 仍要求原有认证。续期复用当前用户同一资源的句柄，使已开始的范围请求继续观察续期；前端对同资源授权 / 清理串行执行，避免旧登录响应覆盖新会话。DELETE 仅清理浏览器播放授权，不删除视频原件。

普通用户只能播放当前可见且已入库的发布版本；管理员可播放活动资料的历史版本或 READY 草稿，已保存草稿须从资料版本播放。原件在打开后、首次输出前及每 1 MiB / 1 秒重新检查账号、当前角色、发布 / 可见范围、参赛和会话；已发出的字节无法收回。撤权后停止继续发送，不向已提交的视频响应拼接 JSON。单范围、后缀范围与 If-Range 按 [RFC 9110 第 14 节](https://www.rfc-editor.org/rfc/rfc9110.html#name-range-requests) 处理，无法满足的范围返回 416；多范围忽略后完整返回，内存读取上限为 64 KiB。

规范字幕为 `{"schema":1,"durationMs":80000,"segments":[{"startMs":1000,"endMs":5000,"text":"讲解正文"}]}`。时间不重叠，正文最多 20 万字符、5,000 段，每段文字最多 4,000 字。用户引用包含服务端从实际字幕核验的 startMs / endMs 和 `t=秒` 链接；模型或客户端传入的时间不能直接成为引用。页面每页显示 50 段，时间按钮回看原视频；管理员编辑仅修改文字，原件和原始转写保留。保存、索引完成及发布是分开的状态。
