# CDUTETC 前端

成理工程在线测评平台，基于 HOJ 的 Vue 2 前端迭代。保留原有路由、权限、编程判题和管理端录题流程；在题目页提供可调整的学习工作台。

源码仓库：[heqiuyu209/heqiuyu-oj](https://github.com/heqiuyu209/heqiuyu-oj)。实现范围、字段约定、验证证据与后续事项见 [UI 实现记录](UI-IMPLEMENTATION.md)。

首页、训练、比赛、讨论、团队已适配统一卡片布局。查看 [五页深浅色 / 手机截图预览](review/cards-preview.html)（下载仓库后用浏览器打开），或 [截图索引](review/README.md)。截图使用测试数据，实际页面继续使用原 HOJ 接口。

## 本地运行

推荐使用 Docker 热更新环境，在 Git 根目录执行 `.\HOJ-master\hoj-deploy\dev.ps1 up`，打开 **http://localhost:8080/home**。保存 Vue/CSS/JS 后自动更新，不需要重建前端镜像。完整启停、依赖更新与切回生产预览的说明见 [本地 OJ 开发环境](../hoj-deploy/README.md)。

页面已更新却看不到过渡动画时，登录后打开「头像 → 主题与布局 → 动画效果 → 完整动画」，导航栏选择「悬浮玻璃」。未登录时使用导航右侧的外观按钮。默认「跟随系统」会遵循电脑的减少动态效果设置；「完整动画」只覆盖本站，不修改系统设置，刷新后保留。主题过渡还需要浏览器支持 View Transitions；不支持时直接切换颜色。

以下是直接在 Windows 运行前端的可选方式；如果已经使用 Docker 开发入口，无需重复启动：

在本目录执行。沿用 lockfile，不需要新增依赖。

```powershell
npm ci
$env:NODE_OPTIONS='--openssl-legacy-provider'
$env:CDUTETC_LOCAL_ASSETS='1'
npm run serve -- --host 127.0.0.1 --port 8066
```

访问 `http://127.0.0.1:8066/home`。`/api` 默认代理到 `http://localhost:6688`，需要已有 HOJ 后端。`CDUTETC_LOCAL_ASSETS=1` 从已安装的依赖打包 UI 运行库，适合本机验收；没有开启此开关时仍使用原有 CDN 配置。它不是离线数据模式。

Node 22 使用上述 OpenSSL 兼容参数。项目没有在本次 UI 改动中升级 Vue、Webpack 或依赖版本。

## 构建与验证

```powershell
npm run test:ui
$env:NODE_OPTIONS='--openssl-legacy-provider'
Remove-Item Env:CDUTETC_LOCAL_ASSETS -ErrorAction SilentlyContinue
npm run build
```

`test:ui` 检查缺失 rating、来源兼容、安全链接和草稿上下文隔离。生产输出在 `dist/`；构建不会更新正在运行的 Docker 前端。

启动上面的本地开发服务后，可运行隔离浏览器验收：

```powershell
$env:UI_REVIEW_URL='http://127.0.0.1:8066'
$env:UI_REVIEW_OUTPUT=Join-Path $env:TEMP 'cdutetc-ui-review'
# Chrome 不在默认 Windows 安装位置时指定 CHROME_PATH。
npm run test:browser
```

需要 Node 22 的内置 WebSocket 和已安装的 Chrome。脚本用全新的临时浏览器资料，拦截所有 `/api/*` 请求并返回固定测试数据，不向真实后端提交代码或修改账号。输出 JSON 和页面截图，用于 UI 回归；不代表真实登录、同步或判题已通过。

列表页的隔离数据和检查在 `tests/collection-fixtures.cjs`、`tests/collection-review.cjs`，由同一浏览器命令执行；覆盖五页双主题和手机布局、平板双列、长内容、分页筛选、加载失败重试及原登录门槛。测试文件不进入应用运行时。

外观检查 `tests/appearance-review.cjs` 同样由上述浏览器命令执行：覆盖通知旁一键主题扩散、头像外观面板、效果开关、比赛自动简化与恢复、设置持久化、手机边界、事件清理和 4 倍 CPU 降速下的功能；记录外观操作接口请求与静止采样，不等同于生产并发压测。

管理端主题专项检查使用 `node tests/admin-theme-review.cjs`。先通过临时环境变量 `HOJ_REVIEW_USERNAME`、`HOJ_REVIEW_PASSWORD` 提供本地管理员凭据，不要写入仓库。默认访问 `http://127.0.0.1:8080`，可用 `UI_REVIEW_URL` 修改；截图与 JSON 默认写入临时目录 `hoj-admin-review`，可用 `ADMIN_REVIEW_OUTPUT` 修改。脚本允许真实登录和原有只读会话查询，拦截其他 API 写入；仅在隔离浏览器中修改表单展示值，不保存。覆盖深浅主题、通知提示、开关、编辑器、弹层、手机菜单、前后台主题隔离、校徽加载页及减少动态效果。

导航与主题动效专项检查：`node tests/motion-review.cjs`，默认访问真实本地服务 `http://127.0.0.1:8080`，也可用 `UI_REVIEW_URL` 修改。需要 Node 22+ 和 Chrome，使用隔离访客会话，不替换接口响应或执行业务写入。覆盖双向按钮抛物线过渡、连续请求、键盘、系统跟随、减少动画及浏览器回退、导航收缩幅度/双阈值/悬停/焦点、桌面和手机断点及触屏横屏；通过临时条纹背景的像素采样检查真实玻璃模糊。JSON 与截图默认写入临时目录 `hoj-motion-review`，可用 `MOTION_REVIEW_OUTPUT` 指定。中间帧按实际 1400ms 过渡速度截取，未放慢浏览器播放。

## 使用入口

- 通知旁的太阳 / 月亮按钮：单击直接切换深浅主题，扩散从该按钮中心开始；使用快捷按钮后保存为明确的深色或浅色主题。
- 头像 → 主题与布局：主题跟随系统、标准效果 / 简洁模式、导航模式、卡片柔光、首页液态玻璃、菜单变形、紧凑间距和动画偏好。未登录时保留外观按钮；手机使用相同设置。
- 默认开启比赛中自动简化：进入带比赛 ID 的比赛页面（包括补题）使用普通导航，暂停主题扩散、菜单变形、柔光与玻璃模糊；离开后恢复原选择。用户可关闭自动简化；它不会修改工作台草稿、角色或比赛业务规则。
- 首页：保留参赛照片展示位、最近一周过题榜、公告、提交统计、近期比赛、最新题目和远程 OJ；训练/比赛/团队按屏幕显示三列、双列或单列卡片，讨论保持便于阅读的横向卡片。
- 打开题目：简洁双栏 / 学习模式、拖动卡片标题、调整分栏宽度和卡片高度、左右互换、展开 / Esc 恢复、重置布局。
- 编程区：语言列表仍由 HOJ 后端提供，自测在同一卡片内；草稿按账号、团队、训练、比赛、题目和语言隔离。
- 右下角 AI 助教：仅小弹窗，服务尚未开放。学习模式内保留能力画像、笔记、计划与推荐入口。
- 账号设置 → 平台账号：CF 使用原有资料接口登记；牛客、QOJ 暂存本地草稿，界面明确标注未完成绑定或同步。

本地草稿与偏好不做跨设备同步，清除浏览器数据或原 HOJ 退出登录流程可能清除缓存；重要代码请另存。新增题型、rating 及统计的服务端接入状态详见实现记录。

本轮外观不增加动画依赖、图片请求或后台接口调用。柔光只随鼠标移动更新，首页玻璃为静态小范围装饰，手机关闭玻璃模糊和折射。头像展开时临时监听定位和关闭事件，关闭或销毁时清理。300–400 人同时访问的服务容量仍需在生产构建和服务端环境中单独压测，本地热更新预览不是容量证明。
