# Git 常用操作指南

按场景查命令。适用于个人项目和常见团队协作；企业没有统一的 Git 命令标准，代码评审、CI 和分支保护同样重要。

本文默认在仓库根目录执行：

```powershell
cd D:\IdeaProjects\HOJ-master
```

本项目主分支叫 `master`；其他项目若叫 `main`，替换即可。示例中的文件路径、分支名、提交号按实际替换，不要把不同场景的命令连续执行。

## 1. 修改文件后：保存、提交、推送

先在编辑器保存文件，再执行：

```powershell
git status                         # 查看当前分支和改动
git diff                           # 查看未暂存修改
git add -- HOJ-master/docs/docs/git/01-gitlearning.md
git diff --cached                  # 检查本次准备提交的全部内容
git commit -m "docs: 精简 Git 操作指南"
git push                           # 已设置上游时，推送当前分支
```

第一次推送新分支用 `git push -u origin 分支名`，以后通常直接 `git push`。

**保存 → add 暂存 → commit 本地存档 → push 上传。** commit 提交整个暂存区；暂存后又改了文件，需要再次 add。

| 暂存范围 | 命令 |
| --- | --- |
| 一个文件 | `git add -- 文件路径` |
| 一个目录 | `git add -- 目录路径/` |
| 分段选择修改 | `git add -p` |
| 全仓库新增、修改、删除 | `git add -A` |

只有确认全部改动属于本次任务时，才用 `git add -A`。普通 diff 不展示未跟踪文件内容；新文件暂存后用 diff --cached 检查。

## 2. 本地项目常用命令

| 用途 | 命令 |
| --- | --- |
| 简短状态 | `git status -sb` |
| 当前分支 | `git branch --show-current` |
| 本地分支及上游 | `git branch -vv` |
| 远程仓库地址 | `git remote -v` |
| 最近提交 | `git log --oneline -10` |
| 所有分支历史图 | `git log --oneline --graph --decorate --all -20` |
| 某次提交内容 | `git show 提交号` |
| 某个文件的修改 | `git diff -- 文件路径` |
| 文件历史 | `git log --oneline -- 文件路径` |
| 重命名并暂存 | `git mv 旧路径 新路径` |
| 删除文件并暂存删除 | `git rm -- 文件路径` |

状态：`??` 未跟踪；` M` 工作区已修改；`M ` 修改已暂存；`A ` 新增已暂存。

分页界面按 `q` 退出；或用 `git --no-pager log --oneline -10`。PowerShell 出现 `>>` 通常是命令未输入完整，按 Ctrl+C 取消后重输。

## 3. 新项目与远程仓库

已有远程项目，在合适的父目录克隆；不要在原项目里面重复克隆：

```powershell
git clone https://github.com/heqiuyu209/heqiuyu-oj.git
```

全新本地项目：先进入项目目录，准备 .gitignore，再执行。远程应为空仓库。

```powershell
git init -b master
git config --local user.name "你的署名"
git config --local user.email "你的邮箱"
git add -A
git diff --cached
git commit -m "chore: 初始化项目"
git remote add origin 仓库地址
git push -u origin master
```

本项目已经初始化，不要重复执行。--local 只影响当前项目；--global 设置用户默认值。署名和邮箱不是登录凭据。

远程地址错误：`git remote set-url origin 正确地址`。HTTPS 按凭据管理器完成认证或使用 PAT，也可按公司要求使用 SSH；不把令牌写进 URL、代码或文档。

## 4. 团队日常：功能分支 → PR → 合并

开始任务前，先 commit 或 stash 未完成工作，保持工作区干净：

```powershell
git switch master
git pull --ff-only origin master
git switch -c feature/ai-chat
```

修改、保存并运行相关测试后：

```powershell
git status
git add -- 本次修改的文件路径
git diff --cached
git commit -m "feat: 添加编程问答入口"
git push -u origin feature/ai-chat
```

在 GitHub 创建 PR：feature/ai-chat → master。通过代码评审和 CI 后，按团队约定合并；创建 PR 本身不等于合并。

远程 PR 合并后：

```powershell
git switch master
git pull --ff-only origin master
git branch -d feature/ai-chat
```

PR 若用 squash/rebase 合并，-d 可能无法识别已合并；先核对 PR 和最终代码，不直接换成 -D。远程分支可在已合并 PR 页面删除；命令是 `git push origin --delete feature/ai-chat`，执行前确认团队不再需要它。

个人项目本地合并：切到接收改动的分支，执行 `git merge feature/ai-chat`，验证后推送。受保护主分支按 PR 流程合并。

## 5. 同步远程与解决冲突

| 命令 | 作用 |
| --- | --- |
| `git fetch origin` | 获取远程历史，不自动合并当前分支 |
| `git pull --ff-only` | 获取并仅允许快进更新，分叉时停止 |
| `git push` | 上传当前分支提交，不上传所有本地分支 |
| `git fetch --prune origin` | 清理远程已删除分支的本地跟踪引用，不删除本地分支 |

功能分支同步主分支，工作区干净后执行：

```powershell
git fetch origin
git switch feature/ai-chat
git merge origin/master
```

冲突处理：git status 找文件 → 编辑正确结果、去掉冲突标记 → 测试 → git add 暂存 → 继续操作。

| 正在进行的操作 | 解决并暂存后 | 放弃本次操作 |
| --- | --- | --- |
| merge | `git merge --continue` | `git merge --abort` |
| rebase | `git rebase --continue` | `git rebase --abort` |
| cherry-pick | `git cherry-pick --continue` | `git cherry-pick --abort` |
| revert | `git revert --continue` | `git revert --abort` |

推送提示 non-fast-forward / rejected，先 fetch 并检查历史，不能用强推代替解决分叉。

## 6. 撤销与恢复

每行都是独立操作，执行前先查看状态和差异。

| 场景 | 命令 | 影响 |
| --- | --- | --- |
| 取消暂存 | `git restore --staged -- 文件路径` | 保留文件修改 |
| 丢弃未暂存修改 | `git restore -- 文件路径` | 暂存区版本覆盖工作区，未提交内容可能丢失 |
| 恢复旧版文件 | `git restore --source=提交号 -- 文件路径` | 覆盖工作区该文件，检查后再 add、commit |
| 撤销已共享的普通提交 | `git revert 提交号` | 新建反向提交，不改写历史；合并提交需另行判断主线 |
| 修改最新未共享提交说明 | `git commit --amend -m "新说明"` | 改写最新提交，也会包含当前暂存内容 |
| 撤回最新未共享提交、保留暂存 | `git reset --soft HEAD~1` | 分支退一步，工作区和暂存区不变 |
| 撤回最新未共享提交、取消暂存 | `git reset --mixed HEAD~1` | 分支退一步，保留工作区文件 |
| 找回误操作前的提交 | `git reflog`，再 `git branch rescue/restore 提交号` | 新建恢复分支，不覆盖当前文件 |

HEAD~1 需要存在父提交。已推送且他人可能使用的历史优先用 revert。reset 各模式见 [Git 官方文档](https://git-scm.com/docs/git-reset)。

高风险命令：git reset --hard 会丢弃修改；git clean -fd 删除未跟踪文件（先用 git clean -nd 预览）；git push --force 可能覆盖他人历史。reflog 不能保证找回未提交内容。

## 7. 临时切任务：stash

```powershell
git stash push -u -m "问答功能未完成"
git stash list
# 完成临时任务，回到原分支后恢复
git stash apply 'stash@{0}'
git status
# 确认恢复正确后，才删除这份临时记录
git stash drop 'stash@{0}'
```

-u 包含未跟踪文件，不包含忽略文件。apply 保留记录；pop 恢复成功后删除记录。stash 只存在本地，不随 push 上传；恢复可能有冲突。

## 8. 常用进阶操作

按需选用，改写历史前先保持工作区干净。

| 用途 | 命令 |
| --- | --- |
| 把指定提交应用到当前分支 | `git cherry-pick 提交号` |
| 将个人功能分支重放到主分支之上 | 先 `git fetch origin`，再 `git rebase origin/master` |
| 整理最近三个未共享提交 | `git rebase -i HEAD~3`；reword 改说明，squash/fixup 合并提交 |
| 新增独立工作目录和分支 | `git worktree add -b fix/login ../hoj-login master` |
| 查看工作目录列表 | `git worktree list` |
| 查看指定行的修改来源 | `git blame -L 10,30 -- 文件路径` |
| 查询文字增删历史 | `git log -S "关键文字" -- 文件路径` |
| 为已验证提交打标签 | `git tag -a v0.1.0 -m "本地部署版本" 提交号` |
| 推送指定标签 | `git push origin v0.1.0` |

推送标签不一定触发部署，取决于 CI 配置。共享标签不要随意移动。

rebase/amend 会改变提交号。仅在团队允许、确认无人依赖的个人远程分支上，才考虑 `git push --force-with-lease origin 分支名`；它校验预期远程状态，但不代替协作确认。主分支不强推。

定位哪个提交引入 bug：

```powershell
git bisect start
git bisect bad
git bisect good 已知正常的提交号
# 测试 Git 选出的版本，再执行 git bisect good 或 git bisect bad
# 定位完成或中途放弃后，回到原分支
git bisect reset
```

## 9. docs 可以推送到 GitHub

**可以。** Git 不限制 docs 目录，文档通常与代码一起管理；上传文档与部署文档网站是两件事。

当前根目录 .gitignore 中有一行 `docs`，会忽略各层级匹配的目录。若要提交新增文档，删除这一行；保留 node_modules/、dist/ 等依赖和产物规则。

忽略规则不影响已跟踪文件：这份指南已经被跟踪，其修改仍可提交。

```powershell
git check-ignore -v -- HOJ-master/docs/docs/git/新文档.md
git ls-files -- HOJ-master/docs/
```

前者查忽略来源，后者查已跟踪文件。取消忽略后，在根目录检查并提交：

```powershell
git status --short -- .gitignore HOJ-master/docs/
git add -- .gitignore HOJ-master/docs/
git diff --cached --stat
git diff --cached
git commit -m "docs: 更新项目文档"
git push
```

不用 git add -f 绕过规则。源码、构建配置、脱敏 SQL、锁文件和文档按需要跟踪；真实密钥、个人数据、数据库数据目录和依赖目录不提交。

.gitignore 不会自动停止跟踪旧文件。确需停止跟踪且保留本地文件，用 `git rm --cached -- 文件路径`，再提交；历史版本仍然存在。凭据泄露先吊销或更换，再协调清理历史。Git 不跟踪空目录，可以添加 README。

## 10. 企业常见协作约定

- 一个任务一个短期分支，一次提交解决一件事，避免无关改动。
- 分支示例：feature/ai-chat、fix/login、docs/git-guide。
- 提交示例：feat: 添加学习计划、fix: 修复登录失败、docs: 更新说明；其他常见前缀有 refactor、test、chore。这是团队约定。
- 主分支通过 PR/MR 合并；描述目的、测试结果，必要时附截图和数据库变更说明。
- 要求评审和 CI 通过，限制主分支直接推送、强推和删除。配置方法见 [GitHub 分支保护](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)。
- 按公司要求启用提交签名、关联工单、管理发布标签；不必为了“企业标准”强行添加长期 develop 分支。

## 11. 报错速查

| 提示 | 处理 |
| --- | --- |
| not a git repository | 检查当前路径，进入仓库 |
| nothing to commit | 检查是否保存、被忽略或已经提交 |
| no upstream branch | git push -u origin 当前分支名 |
| remote origin already exists | 查看 remote -v，需要时用 remote set-url |
| src refspec main does not match any | 检查分支叫 main 还是 master，以及是否已有提交 |
| local changes would be overwritten | 先 commit 或 stash，再切换/合并 |
| Authentication failed / 403 | 检查账号、认证凭据和仓库权限 |
| refusing to merge unrelated histories | 检查两套独立历史的来源，不直接强行合并 |
| LF will be replaced by CRLF | 换行转换提示，按项目 .gitattributes 约定处理 |
| GitHub 看不到最新文件 | 检查 commit、push 是否成功，网页是否选中正确分支 |

命令参数可用 `git 命令 -h` 查看，例如 `git stash -h`。
