<template>
  <el-card class="mixed-entry" shadow="never">
    <span class="eyebrow"><i class="el-icon-files" /> 混合题型比赛</span><h2>{{ contest.title }}</h2>
    <p>选择、判断、填空与编程题在同一份答卷中作答。编程题即时判题；客观题交卷后计分，答案与解析按管理员设置在赛后开放。</p>
    <p class="rank-rule">{{ contest.type === 0 ? '排名先看客观题得分，再看编程题 AC 数和罚时。' : '排名按客观题与编程题的总分排序。' }}</p>
    <p>每场比赛一份答卷，离开后可继续。请及时保存答案并提交代码判题，截止后自动交卷。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-button type="primary" icon="el-icon-edit-outline" :loading="loading" @click="enter">进入我的比赛答卷</el-button>
  </el-card>
</template>
<script>
import api from '@/common/api';
import { responseData, errorMessage } from '@/common/objective';
export default {
  name: 'MixedContestEntry',
  data: () => ({ loading: false, error: '' }), computed: { contest() { return this.$store.state.contest.contest; } },
  methods: { async enter() { if (this.loading) return; const cid = this.$route.params.contestID; this.loading = true; this.error = ''; try { const run = responseData(await api.mixedContestRun(cid)); if (cid === this.$route.params.contestID) this.$router.push('/contest/' + cid + '/mixed/run/' + run.id); } catch (e) { this.error = errorMessage(e); } finally { this.loading = false; } } },
};
</script>
<style scoped>
.mixed-entry { padding: 24px; color: var(--text); border-radius: 14px; }.eyebrow { color: var(--accent); }.mixed-entry h2 { margin: 18px 0; font-size: 26px; }.mixed-entry p { max-width: 820px; color: var(--muted); line-height: 1.9; }.mixed-entry .rank-rule { color: var(--text); }.mixed-entry .el-button { margin-top: 20px; border-radius: 9px; min-height: 44px; }
</style>
