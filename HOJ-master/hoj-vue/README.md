# CDUTETC 前端

成理工程在线测评平台，基于 HOJ 的 Vue 2 前端迭代。保留原有路由、权限、编程判题和管理端录题流程；在题目页提供可调整的学习工作台。

源码仓库：[heqiuyu209/heqiuyu-oj](https://github.com/heqiuyu209/heqiuyu-oj)。实现范围、字段约定、验证证据与后续事项见 [UI 实现记录](UI-IMPLEMENTATION.md)。

## 本地运行

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

## 使用入口

- 导航右侧外观按钮：浅色、深色、跟随系统；普通导航、静态玻璃导航、悬浮玻璃导航；紧凑布局。悬浮模式滚动后收为胶囊，悬停、键盘聚焦或打开菜单时舒展。
- 打开题目：简洁双栏 / 学习模式、拖动卡片标题、调整分栏宽度和卡片高度、左右互换、展开 / Esc 恢复、重置布局。
- 编程区：语言列表仍由 HOJ 后端提供，自测在同一卡片内；草稿按账号、团队、训练、比赛、题目和语言隔离。
- 右下角 AI 助教：仅小弹窗，服务尚未开放。学习模式内保留能力画像、笔记、计划与推荐入口。
- 账号设置 → 平台账号：CF 使用原有资料接口登记；牛客、QOJ 暂存本地草稿，界面明确标注未完成绑定或同步。

本地草稿与偏好不做跨设备同步，清除浏览器数据或原 HOJ 退出登录流程可能清除缓存；重要代码请另存。新增题型、rating 及统计的服务端接入状态详见实现记录。
