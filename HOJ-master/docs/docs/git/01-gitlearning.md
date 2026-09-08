# Git 学习笔记

- git add：将选定的改动加入暂存区。
- git commit：将暂存区内容保存为本地版本。
- git push：将本地提交同步到远程仓库。


## 1. Git、仓库和三个区域

Git 是版本管理工具；GitHub 是托管远程仓库的平台。本地没有网络也可以提交和查看历史。

```text
编辑文件
  ↓
工作区 ── git add ──→ 暂存区 ── git commit ──→ 本地仓库
                                                │ git push
                                                ↓
                                             远程仓库
```

| 概念 | 含义 |
| --- | --- |
| 工作区 | 当前磁盘上可编辑的文件 |
| 暂存区 | 选定给下一次提交的内容快照 |
| 提交（commit） | 一次有作者、说明、父提交和文件快照的版本记录 |
| 分支（branch） | 指向某个提交的可移动指针 |
| HEAD | 通常指向当前分支，说明你当前所在的位置 |
| origin | 远程仓库的常用别名，不是分支名 |
| origin/master | 本地记录的远程 master 状态，通常通过 fetch 更新 |

注意：保存文件不等于提交；提交不等于上传；工作区干净不等于代码测试通过。执行 `git add` 后又修改文件，需要再次 add 才会把新修改放入下一次提交。

## 3. 查看当前状态

```powershell
git --version
git rev-parse --show-toplevel
git status
git status --short
git branch -avv
git remote -v
```

| 常见输出 | 含义 |
| --- | --- |
| No commits yet | 本地尚无提交历史 |
| Changes to be committed | 暂存区有待提交内容 |
| Changes not staged for commit | 跟踪文件有未暂存修改 |
| Untracked files | 未跟踪且未被忽略的文件 |
| working tree clean | 没有待提交改动或未忽略的未跟踪文件 |

`status --short` 的前两列分别描述暂存区和工作区：

```text
A  note.md      新文件已暂存
M  note.md      修改已暂存
 M note.md      修改未暂存
MM note.md      暂存后又修改过
?? note.md      未跟踪文件
```

`git branch` 中的 `*` 标记当前分支。`HEAD -> master` 表示当前位于 master。

## 4. 查看改动和版本历史

```powershell
# 工作区相对暂存区的改动
git --no-pager diff

# 暂存区相对最新提交的改动
git --no-pager diff --cached

# 只看笔记的未暂存修改
git --no-pager diff -- HOJ-master/docs/docs/git/01-gitlearning.md

# 暂存改动统计
git diff --cached --stat
git diff --cached --shortstat

# 最近十次提交和分支位置
git --no-pager log --oneline --graph --decorate -10

# 查看某次提交的文件统计
git --no-pager show --stat 9960391
```

普通 `git diff` 不会展示未跟踪文件的内容。新文件暂存后，可以用 `git diff --cached` 检查。

差异中的 `+` 表示新增行，`-` 表示删除行。首次纳入版本管理的文件会整份显示为新增，即使它在电脑上已经存在很久。

如果出现分页界面，按英文小写 `q` 退出，空格向下翻页。`git --no-pager ...` 可以避免进入分页器。PowerShell 出现 `>>` 是命令尚未输入完整；按 `Ctrl+C` 可以取消本次输入，再重新输入完整命令。

## 5. 日常提交流程

先修改文件并保存，再检查、暂存、提交：

```powershell
git status
git --no-pager diff -- HOJ-master/docs/docs/git/01-gitlearning.md
git add -- HOJ-master/docs/docs/git/01-gitlearning.md
git --no-pager diff --cached
git commit -m "docs: 完善 Git 常用操作指南"
git status
```

`commit` 会提交整个暂存区，不仅仅是刚才 add 的那个文件，因此提交前要查看 `diff --cached`。

常用暂存方式：

```powershell
# 指定范围预览，不实际暂存
git add --dry-run -- .gitignore HOJ-master/

# 暂存一个目录内的改动（包含新增、修改和删除）
git add -- HOJ-master/docs/docs/git/

# 交互式选择修改片段，熟悉基础操作后再使用
git add -p
```

`git add .` 会处理当前目录及子目录内未被忽略的改动；执行前先确认范围，避免把无关修改一并暂存。

提交说明应描述一件明确的事，例如：

- `docs: 补充 Git 分支学习笔记`
- `feat: 添加题目分层提示入口`
- `fix: 修复提交状态轮询异常`
- `refactor: 提取模型调用适配接口`
- `test: 添加比赛禁用 AI 的测试`
- `chore: 调整开发环境配置`

这些前缀是约定，不是 Git 强制要求。一次提交尽量完成一个可解释、可验证的改动。

## 6. 配置作者身份

```powershell
git config --get user.name
git config --get user.email
```

以下是配置示例，请把占位文字换成自己的信息：

```powershell
git config --local user.name "你的署名"
git config --local user.email "你的提交邮箱"
```

`--local` 只影响当前仓库，`--global` 影响当前用户的默认配置。作者姓名和邮箱不是 GitHub 登录凭证。配置变更也不会自动修改旧提交的作者。

## 7. .gitignore：哪些文件不跟踪

本项目根目录忽略规则的示例：

```gitignore
.idea/
*.iml
.vscode/
target/
*.class
node_modules/
dist/
*.log
.env
.env.*
!.env.example
```

没有中间路径的目录模式（例如 `target/`）可以匹配该忽略文件作用范围内各层级的同名目录。`!` 表示取消忽略的例外。子目录也可能有自己的 `.gitignore`，最终结果要结合规则检查。

```powershell
# 查看某个路径被哪条规则忽略
git check-ignore -v -- .idea/workspace.xml

# 看 Git 已经跟踪的文件
git ls-files
```

忽略规则不会自动移除已跟踪或已暂存的文件。确实要停止跟踪 IDEA 配置、但保留本地文件时，可以使用：

```powershell
git rm -r --cached -- .idea
```

这里的 `--cached` 表示只从索引移除，不删除工作区文件。若之前已提交，需要再 commit 才能记录“停止跟踪”的变化；历史提交仍保留旧文件。

不要简单忽略所有 YAML、SQL 或 JAR：其中可能包含必要的配置模板、数据库结构和项目资源。凭据不应提交；若凭据已进入历史，仅修改 `.gitignore` 无法清除它，应先更换凭据，再单独处理历史。

## 8. 创建、切换和合并分支

推荐在工作区干净时切换分支。未提交改动可能随切换保留，也可能阻止切换，并不自动属于某个分支。

以下是新练习分支的示例，不要重复创建已存在的同名分支：

```powershell
git status
git switch master
git switch -c practice/git-notes
```

修改并提交笔记后，切回接收改动的分支，再合并：

```powershell
git switch master
git merge practice/git-notes
git --no-pager log --oneline --graph --decorate -10
```

- 创建分支时，两个分支先指向同一个提交。
- 在新分支 commit，只推动新分支。
- `merge` 把指定分支的历史合入当前分支。
- Fast-forward（快进）表示当前分支没有独立的新提交，可以直接向前移动。
- 两边都产生新提交时，可能创建合并提交，也可能需要处理冲突。
- 合并后两个分支不会永久自动同步，源分支也不会自动删除。

确认练习分支已合并、当前也不在该分支上时，可删除本地分支名称：

```powershell
git branch -d practice/git-notes
```

`-d` 有已合并检查；不要为了绕过提示随手换成 `-D`。删除本地分支不会删除同名远程分支。

## 9. 连接 GitHub 和首次推送

本项目已经配置过 origin，不需要再次执行 `remote add`。先查看：

```powershell
git remote -v
```

只有尚未配置 origin 时才使用：

```powershell
git remote add origin https://github.com/heqiuyu209/heqiuyu-oj.git
```

如果 origin 已存在且地址确实需要修改，使用：

```powershell
git remote set-url origin https://github.com/heqiuyu209/heqiuyu-oj.git
```

首次推送主分支并设置跟踪关系：

```powershell
git push -u origin master
```

之后在已配置跟踪关系的 master 上通常可直接执行 `git push`。推送新的功能分支时，例如：

```powershell
git push -u origin feature/ai-chat
```

这个例子要求本地已经存在该功能分支。推送 master 不会自动推送所有本地分支。

首次认证按 Git 凭据工具的提示完成。GitHub 账号网页密码不能直接用作 HTTPS Git 操作的密码；不要把令牌写进仓库 URL、源码或学习笔记。

默认分支由 GitHub 仓库设置控制。将默认分支改为 master 不会合并或删除 main；带 `/tree/main` 的链接仍会打开 main。

## 10. fetch、pull、push 和 clone

| 命令 | 作用 |
| --- | --- |
| fetch | 获取远程历史并更新远程跟踪引用，不自动合并到当前分支 |
| pull | 获取远程更新，再按选项和配置执行合并或变基 |
| push | 把指定本地分支的提交发送到远程并更新远程分支 |
| clone | 在一个新目录中创建远程仓库的本地副本 |

每天开始工作时，可以先查看远程变化：

```powershell
git status
git fetch origin
git --no-pager log --oneline master..origin/master
git --no-pager log --oneline origin/master..master
```

第一条 log 显示远程有而本地 master 没有的提交；第二条显示本地有而远程没有的提交。

工作区干净并位于 master 时，可以只允许快进更新：

```powershell
git switch master
git pull --ff-only origin master
```

如果提示无法快进，说明需要进一步检查分支历史，先不要强制推送。可用以下命令观察：

```powershell
git --no-pager log --oneline --graph --decorate --all -20
```

换电脑时，才在合适的父目录下克隆到新的文件夹：

```powershell
git clone https://github.com/heqiuyu209/heqiuyu-oj.git
```

不要在现有 HOJ 仓库里再克隆它自身。

## 11. 撤销、恢复和临时保存

先判断修改处在哪个阶段，再选命令。

### 11.1 取消暂存，保留修改

已有提交历史时：

```powershell
git restore --staged -- HOJ-master/docs/docs/git/01-gitlearning.md
```

文件内容不变，只退出下一次提交的准备列表。如果仓库还没有第一次提交，前面使用过的 `git rm --cached` 可以移除首次暂存的新文件；两者适用背景要分清。

### 11.2 丢弃未暂存修改

先查看差异并确认不需要保留，再执行：

```powershell
git --no-pager diff -- HOJ-master/docs/docs/git/01-gitlearning.md
git restore -- HOJ-master/docs/docs/git/01-gitlearning.md
```

第二条会用暂存区版本覆盖该文件的工作区修改。未提交内容未必能通过 Git 找回，因此它不是普通查看命令。

### 11.3 撤销已经提交的改动

优先使用 `revert` 创建一条反向提交，保留原历史。下面的 `HEAD` 表示最新提交，仅在确认要撤销最新提交、工作区干净时执行：

```powershell
git --no-pager show --stat HEAD
git revert --no-edit HEAD
```

不要为了学习而直接撤销当前重要提交。revert 也可能遇到冲突；合并提交的撤销需要额外理解，不照搬这个例子。

### 11.4 临时放下手头工作

```powershell
git stash push -u -m "临时保存：学习笔记未完成"
git stash list
```

`-u` 包含未跟踪文件，但不包含被忽略的文件。它在本地临时保存改动，并通常清理对应工作区内容，不是远程备份。

恢复最新一份临时保存：

```powershell
git stash apply
git status
```

`apply` 保留 stash 条目，确认恢复正确后才使用 `git stash drop` 删除最新条目。`pop` 则尝试恢复并在成功后删除条目；恢复过程中也可能有冲突。

### 11.5 查找误操作前的提交

```powershell
git --no-pager reflog -10
```

reflog 记录本地引用移动，能帮助找回部分误操作后的提交，但不是所有未保存内容的恢复工具，也不是永久备份。

暂时不要随意使用 `git reset --hard`、`git clean -fd`、`git push --force`：它们可能丢弃本地内容或覆盖远程历史。

## 12. 合并冲突怎么处理

冲突表示 Git 无法自动决定该保留哪段内容，并不代表仓库损坏。

例如合并时文件出现以下内容（为避免文档检查将示例误判为真实冲突，展示时统一缩进两格）：

```text
  <<<<<<< HEAD
  当前分支的内容
  =======
  被合并分支的内容
  >>>>>>> feature/ai-chat
```

处理顺序：

1. 用 `git status` 确认冲突文件。
2. 读懂两边改动，手动保留正确结果并删除冲突标记。
3. 检查或测试最终结果。
4. `git add -- 具体文件路径` 标记该文件已解决。
5. 对普通 merge 冲突，执行 `git commit` 完成合并。

如果决定取消正在进行的 merge，可执行 `git merge --abort`。最好在合并前保持工作区干净，避免原有未提交改动混入。

不要把 `merge --abort` 用到 rebase 或 revert 上；不同操作有各自的继续和取消命令，先读 `git status` 的提示。

## 13. 常见报错速查

| 提示 | 先检查什么 |
| --- | --- |
| not a git repository | 当前目录是否位于仓库内部，用 Get-Location 查看 |
| remote origin already exists | 用 git remote -v 查看现有地址，不重复 add |
| src refspec main does not match any | 分支是否实际叫 master，是否已有提交 |
| nothing to commit | 是没有修改，还是文件被忽略；检查 status 和 diff |
| non-fast-forward / rejected | 先 fetch 比较两边历史，不直接强推 |
| refusing to merge unrelated histories | 两套独立初始化历史；本项目旧 main 与 master 就属于这种情况，需要先决定保留方式 |
| local changes would be overwritten | 先提交或 stash 当前工作，再切换/合并 |
| Authentication failed / 403 | 账号、凭据和仓库写权限；不要发送令牌给他人 |
| Repository not found | 地址是否正确，以及当前身份是否有权访问私有仓库 |
| LF will be replaced by CRLF | 换行符转换提示，不一定是 add 失败；不要仅为消除警告批量改全项目 |

## 14. HOJ 后续开发的建议流程

准备开始一个新功能时，先更新主分支，再创建功能分支。以下以题目 AI 问答为例：

```powershell
git status
git switch master
git pull --ff-only origin master
git switch -c feature/ai-chat
```

之后按小步骤开发、检查并提交，验证通过后再合并。功能分支推送到 GitHub 后，也可以创建 Pull Request（PR）：请求将该分支合并到目标分支，便于查看差异和讨论；创建 PR 本身不等于已合并。

练习阶段我们可以采用本地合并：

```powershell
git switch master
git merge feature/ai-chat
git push origin master
```

以上要求功能分支已完成提交和验证、工作区干净，且主分支没有尚未处理的远程更新。遇到失败先看错误，不用强制命令绕过。

日常记住这个顺序：**查看状态 → 检查差异 → 暂存选定改动 → 检查暂存内容 → 提交 → 推送。**
