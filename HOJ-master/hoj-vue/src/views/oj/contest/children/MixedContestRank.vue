<template>
  <el-card shadow="never" class="mixed-rank" v-loading="loading">
    <div class="rank-heading"><div><h2>混合赛排行榜</h2><p>{{ contest.type === 0 ? '客观题得分 ↓ · 编程题 AC 数 ↓ · 罚时 ↑' : '客观题与编程题总分 ↓' }}</p></div><el-button icon="el-icon-refresh" @click="load">刷新</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="sealed" title="当前处于封榜状态，显示封榜前的成绩。" type="info" :closable="false" />
    <p class="rank-notice">客观题交卷后计分；编程题采用已提交的判题结果。待判题或评测异常时，排名尚未结算。管理员与比赛创建者不参与排名。</p>
    <el-table :data="records" empty-text="暂无参赛成绩" style="width:100%">
      <el-table-column prop="rank" label="排名" width="70" /><el-table-column prop="name" label="参赛者" min-width="130" />
      <el-table-column prop="objectiveScore" label="客观题分数" min-width="110" />
      <template v-if="contest.type === 0"><el-table-column prop="ac" label="编程 AC" min-width="100" /><el-table-column label="罚时" min-width="110"><template #default="{ row }">{{ Math.floor(row.penalty / 60) }} 分 {{ row.penalty % 60 }} 秒</template></el-table-column></template>
      <template v-else><el-table-column prop="programScore" label="编程题分数" min-width="110" /><el-table-column prop="score" label="总分" min-width="90" /></template>
      <el-table-column label="状态" min-width="130"><template #default="{ row }"><el-tag :type="row.failed ? 'danger' : row.pending ? 'warning' : row.finished ? 'success' : 'info'">{{ row.failed ? '评测异常' : row.pending ? '判题中' : row.finished ? '已交卷' : '作答中' }}</el-tag></template></el-table-column>
    </el-table>
  </el-card>
</template>
<script>
import api from '@/common/api';
import { responseData, errorMessage } from '@/common/objective';
export default {
  name: 'MixedContestRank',
  data: () => ({ loading: false, error: '', records: [], sealed: false, request: 0, timer: null }), computed: { contest() { return this.$store.state.contest.contest; } },
  created() { this.load(); }, beforeDestroy() { this.request++; clearTimeout(this.timer); }, watch: { '$route.params.contestID'() { this.load(); } },
  methods: { async load() { clearTimeout(this.timer); const token = ++this.request; this.loading = true; this.error = ''; try { const data = responseData(await api.mixedContestRank(this.$route.params.contestID)); if (token === this.request) { this.records = data.records; this.sealed = data.sealed; } } catch (e) { if (token === this.request) this.error = errorMessage(e); } finally { if (token === this.request) { this.loading = false; this.timer = setTimeout(this.load, 15000); } } } },
};
</script>
<style scoped>
.mixed-rank { color: var(--text); border-radius: 14px; }.rank-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }.rank-heading h2 { margin: 8px 0; }.rank-heading p, .rank-notice { font-size: 13px; color: var(--muted); line-height: 1.8; }
</style>
