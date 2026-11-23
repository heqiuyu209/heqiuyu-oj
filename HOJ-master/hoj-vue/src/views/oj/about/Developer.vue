<template>
  <div class="system-info">
    <el-card>
      <BrandLogo />
      <h1>成理工程在线测评平台</h1>
      <p>为算法学习、程序设计课程与 XCPC 集训提供题库、训练、比赛和程序测评。</p>
      <div class="system-links"><a :href="brand.projectUrl" target="_blank" rel="noopener noreferrer"><i class="fa fa-github" /> 项目源码</a><a :href="brand.projectUrl + '/tree/master/HOJ-master/docs/docs'" target="_blank" rel="noopener noreferrer">项目文档</a><a :href="brand.projectUrl + '/tree/master/HOJ-master/hoj-springboot'" target="_blank" rel="noopener noreferrer">API 源码</a></div>
    </el-card>
    <el-row :gutter="20" class="system-details">
      <el-col :md="12" :xs="24"><el-card><h2>学习与比赛</h2><p>题目、编程、自测与学习记录整合在同一个工作台。支持简洁双栏、学习模式，以及布局调整。</p><p>比赛 Rating 与练习 Rating 分开保留；分数尚未标定时显示“待定”。AI、能力统计与跨平台同步按实际接入状态显示。</p></el-card></el-col>
      <el-col :md="12" :xs="24"><el-card><h2>开源致谢</h2><p>本项目基于 HOJ 开源项目开发，保留原有判题、比赛、权限与管理功能。</p><p><a href="https://github.com/HimitZH/HOJ" target="_blank" rel="noopener noreferrer">HOJ 上游项目</a> · Himit_ZH 与全部贡献者</p><p>团队功能贡献者：冷蕴。训练与多平台账号交互参考 <a href="https://github.com/Whalica/OJ_Insight" target="_blank" rel="noopener noreferrer">OJ Insight</a>。</p></el-card></el-col>
    </el-row>
  </div>
</template>
<script>
import BrandLogo from '@/components/oj/common/BrandLogo';
import { BRAND } from '@/common/brand';
export default { components: { BrandLogo }, data: () => ({ brand: BRAND }) };
</script>
<style scoped>
.system-info { max-width: 1160px; margin: auto; }
.system-info h1 { font-size: 30px; }
.system-info h2 { font-size: 20px; }
.system-info p { line-height: 1.9; color: var(--muted); max-width: 65em; }
.system-links { display: flex; gap: 24px; flex-wrap: wrap; margin-top: 24px; }
.system-details { margin-top: 22px; }
.system-details .el-card { margin-bottom: 20px; }
</style>
