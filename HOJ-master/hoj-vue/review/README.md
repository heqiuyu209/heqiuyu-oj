# UI 验收截图

2026-10-14，Chrome 隔离浏览器，桌面 1440 × 1080 / 手机 390 × 844（首页另有全页截图）。页面运行的是本仓库 Vue 源码；接口数据由 `tests/browser-review.cjs` 与 `tests/collection-fixtures.cjs` 拦截生成。截图中的题目、用户、排行榜为测试数据，不是线上成绩。

执行结果见 [browser-review.json](browser-review.json)，`validated=true`，无未捕获异常。运行方式与验证边界见 [前端 README](../README.md) 和 [实现记录](../UI-IMPLEMENTATION.md)。

## 五页卡片布局

下载后在浏览器打开 [可切换截图预览](cards-preview.html)，选择页面、浅色/深色、桌面/手机。它仅展示截图；真实页面需要运行前端和后端。

| 页面 | 浅色桌面 | 深色桌面 | 浅色手机 | 深色手机 |
| --- | --- | --- | --- | --- |
| 首页 | [全页](source-cards-home-light-full.png) | [全页](source-cards-home-dark-full.png) | [查看](source-cards-home-light-390.png) | [查看](source-cards-home-dark-390.png) |
| 训练 | [查看](source-cards-training-light-1440.png) | [查看](source-cards-training-dark-1440.png) | [查看](source-cards-training-light-390.png) | [查看](source-cards-training-dark-390.png) |
| 比赛 | [查看](source-cards-contest-light-1440.png) | [查看](source-cards-contest-dark-1440.png) | [查看](source-cards-contest-light-390.png) | [查看](source-cards-contest-dark-390.png) |
| 讨论 | [查看](source-cards-discussion-light-1440.png) | [查看](source-cards-discussion-dark-1440.png) | [查看](source-cards-discussion-light-390.png) | [查看](source-cards-discussion-dark-390.png) |
| 团队 | [查看](source-cards-group-light-1440.png) | [查看](source-cards-group-dark-1440.png) | [查看](source-cards-group-light-390.png) | [查看](source-cards-group-dark-390.png) |

结构化结果中的 `checks.collections` 记录 20 组页面/主题/尺寸、900px 平板双列、长标题和分类、卡片底部主题一致性、分页与筛选、空结果、失败重试等结果。另验证访客点击需登录、原团队创建对话框可打开。原有导航、角色菜单、工作台等回归仍执行。浏览器保留原 vxe tooltip 和 Vue Router 废弃属性提示，没有分页类型警告；这些检查不等于真实后端联调或用户验收。

## 悬浮玻璃导航：滚动后的浅色 / 深色状态

九个入口始终保留，滚动后收拢，鼠标或键盘进入时展开。校徽保留原色，右侧是界面外观和登录/注册。

![浅色胶囊导航](source-nav-light-capsule.png)

![深色胶囊导航](source-nav-dark-capsule.png)

## 登录后的头像菜单

管理员菜单显示“管理后台”，普通用户隐藏该项。打开菜单时导航保持舒展。

![桌面管理员菜单](source-nav-dark-account.png)

![手机普通用户菜单](source-nav-mobile-dark-account.png)

## 首页：原内容与 XCPC 照片展示位

![首页](source-home.png)

## 深色工作台：调整过顺序与宽度，自测留在编程卡片

![深色学习工作台](source-problem-dark.png)

## 同一题目页的填空作答区：判分服务待接入

![填空题](source-objective-fill_blank.png)

## 手机题目页

![手机题目页](source-mobile--problem-P1000.png)
