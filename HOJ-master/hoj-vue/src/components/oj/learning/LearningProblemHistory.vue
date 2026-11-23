<template>
  <section v-if="owner" class="learning-history" aria-label="能力画像的题目依据">
    <header><div><h2>能力画像的题目依据</h2><p>逐题核对记录，查看同一道题在不同来源的练习情况。</p></div><el-button size="small" :aria-expanded="expanded" @click="toggle">{{ expanded ? '收起明细' : '查看题目明细' }}</el-button></header>
    <template v-if="expanded">
      <p class="history-scope">范围与能力图一致：本站公开单题练习，以及已验证外部账号的编程记录。CF 只纳入个人提交；各平台评级分别展示，记录不证明独立完成。</p>
      <form class="history-filters" @submit.prevent="search">
        <label>题源<el-select v-model="filters.source" :disabled="loading" @change="changed"><el-option v-for="source in sources" :key="source.value" :value="source.value" :label="source.label" /></el-select></label>
        <label>通过记录<el-select v-model="filters.status" :disabled="loading" @change="changed"><el-option value="ALL" label="全部" /><el-option value="SOLVED" label="有通过记录" /><el-option value="ATTEMPTED" label="暂未记录通过" /></el-select></label>
        <label>知识方向<el-select v-model="filters.groupCode" :disabled="loading" @change="changed"><el-option value="" label="全部方向" /><el-option v-for="(name, code) in groups" :key="code" :value="code" :label="name" /></el-select></label>
        <label class="history-keyword">题名或题号<el-input v-model="filters.keyword" :disabled="loading" maxlength="100" clearable placeholder="输入题名或题号" @input="changed" /></label>
        <el-checkbox v-model="filters.onlyDuplicates" :disabled="loading" @change="changed">仅看有合并记录的题</el-checkbox>
        <el-button native-type="submit" type="primary" size="small" :loading="loading">查询</el-button>
      </form>
      <el-alert v-if="error" :title="error" type="info" :closable="false"><el-button type="text" @click="load">重试</el-button></el-alert>
      <p v-if="loading" class="history-empty" role="status">正在核对题源与练习记录</p>
      <template v-else-if="result">
        <p class="history-totals">全部编程证据：通过 <strong>{{ result.totals.solved }}</strong> / {{ result.totals.attempted }} 题，共 {{ result.totals.sourceRecords }} 条来源题目记录；合并了 {{ result.totals.duplicatesRemoved }} 条重复通过记录。</p>
        <p class="history-freshness"><template v-if="!result.verifiedAccounts">尚无已验证的外部账号。</template><template v-else>已纳入 {{ result.verifiedAccounts }} 个已验证外部账号。<span v-if="!result.historyComplete">外部历史仍在补全。</span><span v-if="result.stale">来源记录待刷新，保留已同步数据。</span></template> 本次查询：{{ date(result.asOf) }}。筛选得到 {{ result.total }} 题。</p>
        <p v-if="!result.items.length" class="history-empty">没有符合条件的记录。可以调整条件，或先在上方关联、验证并同步外部账号。</p>
        <details v-for="row in result.items" :key="row.identity" class="history-task">
          <summary><strong>{{ row.title || row.identity }}</strong><span class="history-status" :class="{ passed: row.solved }">{{ row.solved ? '有通过记录' : '暂未记录通过' }}</span><p>{{ row.identity }} · {{ row.recordCount }} 条来源记录<span v-if="row.entries.length !== row.recordCount">，本次展示 {{ row.entries.length }} 条</span> · 最近练习 {{ date(row.lastAttemptAt) }}</p></summary>
          <p v-if="row.groups.length" class="history-groups">知识方向：{{ row.groups.map(group => group.name).join('、') }}</p>
          <div v-for="(entry, index) in row.entries" :key="entry.source + ':' + entry.problemKey + ':' + entry.handle + ':' + index" class="history-entry">
            <div class="history-entry-heading"><strong>{{ sourceName(entry.source) }}<span v-if="entry.handle"> / {{ entry.handle }}</span></strong><span>{{ entry.solved ? '有通过记录' : '暂未记录通过' }}</span></div>
            <router-link v-if="entry.source === 'HOJ' && entry.url" :to="entry.url">{{ entry.displayId }} · {{ entry.title || '查看题目' }}</router-link><a v-else-if="entry.url" :href="entry.url" target="_blank" rel="noopener noreferrer">{{ entry.displayId }} · {{ entry.title || '查看题目' }} <i class="el-icon-top-right" /></a><p v-else class="history-no-link">{{ entry.displayId }} · {{ entry.title || '题名未提供' }}</p>
            <p>{{ metric(entry.difficultyMetric) }} {{ entry.rating == null ? '未提供' : entry.rating }} · 已记录提交 {{ entry.submissions }} 次<span v-if="!entry.metadataAvailable"> · 当前目录未确认</span></p>
            <p>首次练习 {{ date(entry.firstAttemptAt) }}<br>最近练习 {{ date(entry.lastAttemptAt) }}<template v-if="entry.solved"><br>首次通过 {{ date(entry.firstSolvedAt) }}</template></p>
          </div>
        </details>
        <el-pagination v-if="result.total > 20" small layout="prev, pager, next" :page-size="20" :current-page="page" :total="result.total" :disabled="loading" @current-change="pageChanged" />
      </template>
      <p v-else-if="!error" class="history-empty">条件已调整，点击查询查看明细。</p>
      <p class="history-method">仅按明确题源编号和已确认的目录关系合并，相似题名不会合并。不同来源的原题号、账号和状态仍保留；没有记录可能是未同步，并不表示一定没做过。<router-link to="/objective?listTab=history">客观题记录</router-link><router-link to="/papers?tab=history">套卷答卷</router-link></p>
    </template>
  </section>
</template>
<script>
import api from '@/common/api';
import { responseData, errorMessage } from '@/common/objective';
const defaults = () => ({ source: 'ALL', status: 'ALL', groupCode: '', keyword: '', onlyDuplicates: false });
export default {
  name: 'LearningProblemHistory', props: { uid: { type: String, required: true } },
  data: () => ({ expanded: false, loading: false, result: null, error: '', request: 0, page: 1, filters: defaults(), groups: {}, sources: [{ value: 'ALL', label: '合并题源' }, { value: 'HOJ', label: '本站' }, { value: 'CODEFORCES', label: 'Codeforces' }, { value: 'ATCODER', label: 'AtCoder' }, { value: 'NOWCODER', label: '牛客' }] }),
  computed: {
    owner() { return this.$store.getters.isAuthenticated && this.$store.getters.userInfo.uid === this.uid; },
    identity() { return this.uid + ':' + (this.$store.getters.userInfo.uid || '') + ':' + this.$store.getters.isAuthenticated + ':' + this.$store.getters.authEpoch; },
  },
  watch: { identity: { immediate: true, handler() { this.request++; this.result = null; this.expanded = false; this.loading = false; this.error = ''; this.filters = defaults(); this.groups = {}; this.page = 1; } } },
  beforeDestroy() { this.request++; },
  methods: {
    sourceName(source) { return (this.sources.find(item => item.value === source) || {}).label || source; },
    metric(value) { return { HOJ_RATING: '本站题目 Rating', CODEFORCES_PROBLEM_RATING: 'CF 题目 Rating', ATCODER_PROBLEMS_ESTIMATE: 'AtCoder 难度估计', NOWCODER_SOURCE_RATING: '牛客来源 Rating' }[value] || '评级'; },
    date(value) { return typeof value === 'number' && value > 0 ? new Date(value).toLocaleString('zh-CN') : '时间未提供'; },
    toggle() { this.expanded = !this.expanded; this.request++; this.result = null; this.error = ''; this.loading = false; if (this.expanded) { this.page = 1; this.load(); } },
    changed() { this.request++; this.result = null; this.error = ''; this.loading = false; this.page = 1; },
    search() { this.page = 1; this.load(); },
    pageChanged(page) { this.page = page; this.load(); },
    async load() {
      if (!this.owner || !this.expanded) return;
      const token = ++this.request, identity = this.identity; this.loading = true; this.error = ''; this.result = null;
      try {
        const result = responseData(await api.learningProblemHistory({ ...this.filters, page: this.page }));
        if (token !== this.request || identity !== this.identity || !this.owner || !this.expanded) return;
        if (result.uid !== this.uid) { this.error = '登录状态已变化，请刷新后重试'; return; }
        this.result = result; this.groups = result.groups;
        if (!result.items.length && this.page > 1) { this.page = 1; return this.load(); }
      } catch (error) { if (token === this.request && identity === this.identity) this.error = errorMessage(error); }
      finally { if (token === this.request && identity === this.identity) this.loading = false; }
    },
  },
};
</script>
<style scoped>
.learning-history { scroll-margin-top: 92px; margin: 24px 0; padding: 24px; border: 1px solid var(--line); border-radius: 12px; color: var(--text); text-align: left; }.learning-history header { display: flex; gap: 16px; justify-content: space-between; align-items: center; }.learning-history h2 { margin: 0; font-size: 20px; }.learning-history header p, .history-scope, .history-freshness, .history-method { color: var(--muted); font-size: 12px; line-height: 1.8; }.learning-history header p { margin: 8px 0 0; }.learning-history header .el-button { flex-shrink: 0; }
.history-filters { display: flex; flex-wrap: wrap; gap: 12px; align-items: end; margin: 18px 0; }.history-filters > label:not(.el-checkbox) { display: flex; flex-direction: column; gap: 6px; font-size: 12px; color: var(--muted); }.history-filters .el-select { width: 150px; }.history-keyword { flex: 1; min-width: 160px; }.history-filters .el-checkbox { display: inline-flex; align-items: center; white-space: normal; margin: 0 0 8px; }.history-filters .el-button { margin: 0; }.history-totals { font-size: 13px; line-height: 1.8; }.history-totals strong { color: var(--accent); }.history-empty { padding: 18px 0; color: var(--muted); font-size: 13px; line-height: 1.8; }
.history-task { padding: 16px 0; border-top: 1px solid var(--line); }.history-task summary { cursor: pointer; font-size: 14px; overflow-wrap: anywhere; line-height: 1.7; }.history-task summary p { margin: 5px 0 0; font-size: 11px; color: var(--muted); }.history-status { float: right; margin-left: 12px; font-size: 12px; color: var(--muted); }.history-status.passed { color: var(--accent); }.history-groups { font-size: 12px; color: var(--muted); }.history-entry { padding: 14px 0 14px 12px; margin-left: 8px; border-top: 1px solid var(--line); }.history-entry-heading { display: flex; flex-wrap: wrap; gap: 8px; justify-content: space-between; margin-bottom: 8px; font-size: 12px; }.history-entry-heading span { color: var(--muted); font-weight: 400; }.history-entry a { color: var(--accent); font-size: 13px; overflow-wrap: anywhere; }.history-entry p { font-size: 12px; line-height: 1.8; color: var(--muted); margin: 7px 0; overflow-wrap: anywhere; }.history-method { padding-top: 14px; border-top: 1px solid var(--line); }.history-method a { margin-left: 12px; color: var(--accent); }.history-task summary:focus-visible, .learning-history a:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
@media(max-width:600px) { .learning-history { padding: 18px 12px; }.learning-history header { align-items: flex-start; flex-direction: column; gap: 8px; }.history-filters { gap: 12px 8px; }.history-filters > label:not(.el-checkbox) { width: calc(50% - 4px); min-width: 0; flex: none; }.history-filters .el-select { width: 100%; }.history-filters .history-keyword { width: 100%; }.history-status { float: none; display: block; margin-left: 0; }.history-entry { margin-left: 0; padding-left: 6px; } }
</style>
