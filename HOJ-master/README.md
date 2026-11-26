# CDUTETC · 成理工程在线测评平台

本项目基于 HOJ，面向成理工程算法学习与 XCPC 集训。当前仓库：[heqiuyu209/heqiuyu-oj](https://github.com/heqiuyu209/heqiuyu-oj)。

2026-10-19：本分支已迭代卡片 UI、混合题与比赛、站内 Rating、用户 AI、来源同步及本地知识库；各阶段的实际实现、验证、部署与未完成项以 [任务记录](./docs/docs/ai/TODO.md) 为准。知识库已接通管理员维护、用户检索 / 原文和可选模型问答页面，Markdown / TXT 真实本地索引链路已验证；真实本人模型与远程 AC / WA 仍按用户选择延后。页面运行见 [前端说明](./hoj-vue/README.md)，原 UI 设计边界保留在 [UI 实施记录](./hoj-vue/UI-IMPLEMENTATION.md)。

以下保留 HOJ 上游功能介绍、历史文档和贡献者信息；其中 Demo、交流群和捐赠入口属于上游项目。部署本分支以仓库内 Compose 和当前运行说明为准。


<img src="./hoj-vue/public/cdutetc.webp" width="100" alt="CDUTETC 校徽" style="border-radius:50%" />

[![Java](https://img.shields.io/badge/Java-1.8-informational)](http://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.2.6.RELEASE-success)](https://spring.io/projects/spring-boot)
[![SpringCloud Alibaba](https://img.shields.io/badge/Spring%20Cloud%20Alibaba-2.2.1.RELEASE-success)](https://spring.io/projects/spring-cloud-alibaba)
[![MySQL](https://img.shields.io/badge/MySQL-8.0.19-blue)](https://www.mysql.com/)
[![Redis](https://img.shields.io/badge/Redis-5.0.9-red)](https://redis.io/)
[![Nacos](https://img.shields.io/badge/Nacos-1.4.2-%23267DF7)](https://github.com/alibaba/nacos)
[![Vue](https://img.shields.io/badge/Vue-2.6.11-success)](https://cn.vuejs.org/)
[![Github Star](https://img.shields.io/github/stars/HimitZH/HOJ?style=social)](https://github.com/HimitZH/HOJ)
[![Gitee Star](https://gitee.com/himitzh0730/hoj/badge/star.svg)](https://gitee.com/himitzh0730/hoj)

[//]: # ([![QQ Group 598587305]&#40;https://img.shields.io/badge/QQ%20Group-598587305-blue&#41;]&#40;https://qm.qq.com/cgi-bin/qm/qr?k=WWGBZ5gfDiBZOcpNvM8xnZTfUq7BT4Rs&jump_from=webapi&#41;)

简体中文 | [English](./README-EN.md)

## 一、总概

- 基于Vue和Spring Boot、Spring Cloud Alibaba构建的前后端分离，分布式架构的评测系统
- **支持多种评测语言：C、C++、C#、Python、PyPy、Go、Java、JavaScript、PHP、Ruby、Rust**
- **支持HDU、POJ、Codeforces（包括GYM）、AtCoder、SPOJ、LIBRE的Remote Judge评测**
- **支持移动端、PC端浏览，拥有讨论区与站内消息系统**
- **支持私有训练、公开训练（题单）和团队功能**
- **完善的评测功能：普通测评、特殊测评、交互测评、在线自测、子任务分组评测、文件IO**
- **完善的比赛功能：打星队伍、关注队伍、外榜、滚榜**

[//]: # ()
[//]: # (|               在线Demo               |                   在线文档                   |             Github&Gitee仓库地址             |           QQ群           |)

[//]: # (| :--------------------------------: | :--------------------------------------: | :--------------------------------------: | :---------------------: |)

[//]: # (| [https://hdoi.cn]&#40;https://hdoi.cn&#41; | [https://docs.hdoi.cn]&#40;https://docs.hdoi.cn&#41; | [https://github.com/HimitZH/HOJ]&#40;https://github.com/HimitZH/HOJ&#41;  [https://gitee.com/himitzh0730/hoj]&#40;https://gitee.com/himitzh0730/hoj&#41; | 598587305（已满）、743568562 |)

**注意：**

1. **建议使用Centos8以上或Ubuntu16.04以上的操作系统！！！不然判题机（judgeserver）可能无法正常启动**
2. **若一定要用Centos7系统，部署请先看文档说明：[https://docs.hdoi.cn/deploy/faq/](https://docs.hdoi.cn/deploy/faq/)**
3. **服务器配置尽可能使用2核4G以上，保证服务的正常启动与运行。**
4. **尽量不要使用突发性能或共享型的云服务器实例，有可能造成评测时间计量不准确。**
5. **有任何部署问题或项目bug请发issue或者加QQ群。**
6. **如果要对本项目进行商业化，请在页面底部的Powered by指向HOJ本仓库地址，顺便点上star收藏本项目对开发者的支持，谢谢。**

## 二、部署

本仓库本地开发：运行 `hoj-deploy/dev.ps1 up`，在 `http://localhost:8080/home` 边改前端边预览，详见 [Docker 热更新开发说明](hoj-deploy/README.md)。该模式复用本机已初始化的 OJ 服务与数据；下方链接为 HOJ 上游部署资料。

Codeforces 题面扩展安装、采集及本地和云端导入步骤见 [CPOS 题面导入教程](hoj-deploy/README.md#cpos-批量导入-codeforces-题面)。

部署文档：[基于docker-compose部署](https://docs.hdoi.cn/deploy/docker)

部署仓库：https://gitee.com/himitzh0730/hoj-deploy

## 三、更新

请在docker-compose.yml当前文件夹下执行`docker-compose pull`拉取最新镜像，然后重新`docker-compose up -d`即可。

## 四、上线日记

| 时间         | 内容                                       | 更新者           |
| ---------- | ---------------------------------------- | ------------- |
| 2020-10-26 | 正式开发                                     | Himit_ZH      |
| 2021-04-10 | 首次上线测试                                   | Himit_ZH      |
| 2021-04-15 | 判题调度2.0解决并发问题                            | Himit_ZH      |
| 2021-04-16 | 重构解耦JudgeServer判题逻辑，添加部署文档               | Himit_ZH      |
| 2021-04-19 | 加入rsync实现评测数据同步，修复一些已知的BUG               | Himit_ZH      |
| 2021-04-24 | 加入题目模板，修改页面页脚                            | Himit_ZH      |
| 2021-05-02 | 修复比赛后管理员重判题目导致排行榜失效的问题                   | Himit_ZH      |
| 2021-05-09 | 添加公共讨论区，题目讨论区，比赛评论                       | Himit_ZH      |
| 2021-05-12 | 添加评论及回复删除，讨论举报，调整显示时间。                   | Himit_ZH      |
| 2021-05-16 | 完善权限控制，讨论管理员管理，讨论删除与编辑更新。                | Himit_ZH      |
| 2021-05-22 | 更新docker-compose一键部署，修正部分bug             | Himit_ZH      |
| 2021-05-24 | 判题调度乐观锁改为悲观锁                             | Himit_ZH      |
| 2021-05-28 | 增加导入导出题目，增加用户页面的最近登录，开发正式结束，进入维护摸鱼       | Himit_ZH      |
| 2021-06-02 | 大更新，完善补充前端页面，修正判题等待超时时间，修补一系列bug         | Himit_ZH      |
| 2021-06-07 | 修正特殊判题，增加前台i18n                          | Himit_ZH      |
| 2021-06-08 | 添加后台i18n,路由懒加载                           | Himit_ZH      |
| 2021-06-12 | 完善比赛赛制，具体请看在线文档                          | Himit_ZH      |
| 2021-06-14 | 完善后台管理员权限控制，恢复CF的vjudge判题                | Himit_ZH      |
| 2021-06-25 | 丰富前端操作，增加POJ的vjudge判题                    | Himit_ZH      |
| 2021-08-14 | 增加spj对使用testlib的支持                       | Himit_ZH      |
| 2021-09-21 | 增加比赛打印功能、账号限制功能                          | Himit_ZH      |
| 2021-10-05 | 增加站内消息系统——评论、回复、点赞、系统通知的消息，优化前端。         | Himit_ZH      |
| 2021-10-06 | 美化比赛排行榜，增加对FPS题目导入的支持                    | Himit_ZH      |
| 2021-12-09 | 美化比赛排行榜，增加外榜、打星队伍、关注队伍的支持                | Himit_ZH      |
| 2022-01-01 | 增加公开训练和公开训练（题单）                          | Himit_ZH      |
| 2022-01-04 | 增加交互判题、重构judgeserver的三种判题模式（普通、特殊、交互）    | Himit_ZH      |
| 2022-01-29 | 重构remote judge，增加AtCoder、SPOJ的支持         | Himit_ZH      |
| 2022-02-19 | 修改首页前端布局和题目列表页                           | Himit_ZH      |
| 2022-02-25 | 支持PyPy2、PyPy3、JavaScript V8、JavaScript Node、PHP | Himit_ZH      |
| 2022-03-12 | 后端接口全部重构，赛外榜单增加缓存                        | Himit_ZH      |
| 2022-03-28 | 合并冷蕴提交的团队功能                              | Himit_ZH、冷蕴   |
| 2022-04-01 | 正式上线团队功能                                 | Himit_ZH、冷蕴   |
| 2022-05-29 | 增加在线调试、个人主页提交热力图                         | Himit_ZH      |
| 2022-08-06 | 增加题目标签的分类管理（二级标签）                        | Himit_ZH      |
| 2022-08-21 | 增加人工评测、取消评测                              | Himit_ZH      |
| 2022-08-30 | 增加OI题目的subtask、ACM题目的'遇错止评'模式            | Himit_ZH      |
| 2022-10-04 | 增加比赛奖项配置，增加ACM赛制的滚榜                      | Himit_ZH      |
| 2022-11-14 | 增加题目详情页专注模式，优化首页布局                       | Himit_ZH      |
| 2023-05-01 | 增加题目评测支持文件IO                             | Himit_ZH      |
| 2023-06-11 | 增加允许比赛结束后提交                              | Himit_ZH      |
| 2023-06-27 | 支持Ruby、Rust                              | Himit_ZH      |
| 2024-03-13 | 支持LibreOJ的远程评测                           | Himit_ZH、Nine |

## 五、部分截图

**以下截图页面均支持中英文国际化，点击底部的转换即可全网站转换，包括后台管理，同时浏览器会记住本次选择的语言**

### 1. 首页

> 首页页面 

![首页](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/7409e6b5def6438385ddd59589afeb83.png)



> 首页英文

![首页英文](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/f6792ddc05f34527bdf744fa4d6d5c88.png)

### 2. 站内消息

> 站内消息系统

![站内消息系统](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/a1a83ff01be84406954537e2ab78d999.png)



![站内消息系统](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/513e7e37f52f48518c2fa1bf14eeea99.png)

### 3. 题目

> 题目列表页

![题目列表页](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/0ee61f329e094592b0a0cff55d12b404.png)



> 题目详情页

![题目详情页](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/9f872dc1974f45c389e084f0e31a5217.png)

### 4. 训练

> 训练列表页

![训练列表页](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/58ac74824fcf4963810beea7ba1203b9.png)

> 训练题目列表页

![训练题目列表页](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/b366a6a628984995b57a49c565a2ec47.png)

### 5.  比赛

> 比赛列表页

![比赛列表页](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/00a0438a576d43edbab676b829a38922.png)





**比赛以西南科技大学某届新生赛截图为例**

> 比赛详情首页

![比赛详情页](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/50026bde6dd64cd5929b38f8ecc6e72e.png)

> 比赛题目列表页

![比赛题目列表](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/8646fc212b5c47e9b35e60634cfc8d6a.png)

> 比赛排行榜

- ACM比赛

  ![比赛排行榜](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/c50140e3b73d482d82ca6f13f47aa080.png)



- OI比赛

  ![oi排行榜](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/67f6262854bb44efa70c374f1f156166.png)




- 滚榜

  ![在这里插入图片描述](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/8f8258babd3f43f78802144e7ecf18fe.png)



### 6. 评测

> 提交列表页

![提交列表](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/20210609213021223.png)

### 7. 排行榜

> 排行榜

![排行榜](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/407ad16361f34b44a282b07af68825e0.png)

### 8. 团队

![团队列表页](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/7988504326c843ef94e937a2b4f32f03.png)

![团队题目列表页](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/2c05e44f5a464381b9a357aff37b0086.png)



### 9. 讨论

> 公共讨论区

![公共讨论区](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/20210513134216723.png)



> 评论组件

![评论组件](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/20210513142826730.png)

### 10. 个人

> 个人首页

![个人首页](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/7d3e99dbc6fe4739a0720fcc019b2b6e.png)



> 个人设置页

![个人设置](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/971566eeac674d388b9f5d6064286e14.png)



### 11. 管理后台

> 管理后台首页

![管理后端](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/9b9674c0f30a441bb200a32756f24d2c.png)



### 12. 手机端

> 部分手机端显示

![手机端](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/c7b3648217af4899bedf7f7d804968ba.png)



![评论区](https://cdn.jsdelivr.net/gh/HimitZH/HOJ/docs/docs/.vuepress/public/20210509233845230.png)

## 本地开发按需启停

在包含 compose.yaml 的应用目录执行：

```powershell
.\hoj-deploy\dev.ps1 start   # 顺序启动已有服务并等待就绪，保留容器和数据卷
.\hoj-deploy\dev.ps1 status  # 查看状态
.\hoj-deploy\dev.ps1 stop    # 用完停止整套 OJ，包含本地 RAG
```

本地服务统一按需启动；Docker Desktop 重开后不会单独拉起 RAG。日常使用 start / stop；首次部署或需要应用 Compose 配置变化时使用 dev.ps1 up。已有容器缺失时 start 会提示首次部署，不会自动重建；已完成的 RAG 身份初始化容器不会被日常 start 重新运行。

代码镜像更新继续使用 hoj-deploy/update.ps1；启停入口会沿用已有容器记录的 RAG、视频和重排覆盖配置，不需要手动删减 Compose 文件列表。停止容器不会删除资料、模型或命名卷。

## 本地视频知识库

管理员在“知识库资料 → 视频资料”上传 MP4 / WebM，等待本地转写后审阅字幕、保存入库，再选择版本发布。学生从前台知识库检索讲解并按引用时间回看。语音识别使用本地只读 Whisper small 权重，不需要聊天模型 Key；画面文字不参与检索。真实课堂字幕仍须核对。

已有库先执行 sqlAndsetting/knowledge-video.sql。启用视频时，在本地 .env.rag.local 中配置 HOJ_VIDEO_MODEL_DIR 为完整 faster-whisper-small 权重目录，并在原有三个 Compose 文件后加入 compose.video.yaml。后续更新也须保留这四个文件，以持续挂载私有媒体卷与模型。

```powershell
docker compose --env-file .env.rag.local -p hoj-master `
  -f compose.yaml -f compose.dev.yaml -f compose.rag.yaml -f compose.video.yaml up -d
```

接口、输入限制及授权播放见 [视频资料接口](docs/docs/ai/08-data-model-and-api.md#视频资料与授权播放2026-10-20)，实际实现、验证及待验收项以 [任务记录](docs/docs/ai/TODO.md) 为准。已有库只执行增量迁移，保留现有数据卷。
