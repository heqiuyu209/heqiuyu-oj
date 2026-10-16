<template>
  <section class="paper-page">
    <ProblemNavigation />
    <div class="paper-list">
      <header><p class="eyebrow">完整练习 · 独立成绩</p><h1>套卷练习</h1><p>在一份试卷中练习填空、选择和编程。套卷与单题练习分别记录进度。</p></header>
      <el-tabs v-model="tab" @tab-click="reset"><el-tab-pane label="试卷库" name="papers" /><el-tab-pane label="我的答卷" name="history" /></el-tabs>
      <form v-if="tab === 'papers'" class="search" @submit.prevent="reset"><el-input v-model="keyword" maxlength="100" placeholder="搜索试卷名称" clearable /><el-button type="primary" native-type="submit">搜索</el-button></form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <div v-loading="loading" class="paper-grid">
        <article v-for="row in rows" :key="row.id" class="paper-card">
          <div class="paper-icon"><i class="el-icon-document" /></div>
          <div class="paper-copy"><p>{{ tab === 'history' ? row.state === 'FINISHED' ? '已交卷' : '作答中' : row.questionCount + ' 道题' }}</p><h2>{{ row.title }}</h2><p v-if="tab === 'papers'" class="description">{{ row.description || '按试卷顺序练习，在同一份答卷中完成多种题型。' }}</p><p v-else>{{ new Date(row.createdAt).toLocaleString() }}</p>
            <el-tag v-if="tab === 'papers'" size="small" effect="plain">{{ row.programMode === 'LIVE' ? '编程题即时判题' : '交卷后统一判题' }}</el-tag>
          </div>
          <el-button type="primary" :loading="starting === row.id" :disabled="starting !== null" @click="open(row)">{{ tab === 'history' ? row.state === 'FINISHED' ? '查看答卷' : '继续作答' : '开始答卷' }} <i class="el-icon-arrow-right" /></el-button>
        </article>
        <p v-if="!loading && !rows.length" class="empty">{{ tab === 'history' ? '还没有答卷，选择一份试卷开始吧。' : '暂时没有已发布的试卷。' }}</p>
      </div>
      <el-pagination layout="prev, pager, next, total" :total="total" :page-size="20" :current-page="page" @current-change="changePage" />
    </div>
  </section>
</template>
<script>
import api from '@/common/api';
import ProblemNavigation from '@/components/oj/problem/ProblemNavigation';
import { requestId, responseData, errorMessage } from '@/common/objective';
export default {
  name: 'PaperList', components: { ProblemNavigation },
  data() { const q = this.$route.query; return { tab: q.tab === 'history' ? 'history' : 'papers', keyword: String(q.keyword || '').slice(0, 100), page: Math.max(1, Number(q.page) || 1), rows: [], total: 0, loading: false, starting: null, request: 0, nonces: {}, error: '' }; },
  computed: { uid() { return this.$store.getters.userInfo.uid; }, context() { return { tab: this.tab, keyword: this.keyword, page: String(this.page) }; } },
  watch: { uid() { this.request++; this.rows = []; this.nonces = {}; this.starting = null; this.reset(); } },
  created() { this.load(); }, beforeDestroy() { this.request++; },
  methods: {
    reset() { this.page = 1; this.load(); }, changePage(page) { this.page = page; this.load(); },
    async load() { const token = ++this.request; this.loading = true; this.error = ''; this.rows = []; try { const result = responseData(await (this.tab === 'history' ? api.paperRuns({ currentPage: this.page }) : api.papers({ currentPage: this.page, keyword: this.keyword }))); if (token === this.request) { this.rows = result.records; this.total = result.total; } } catch (e) { if (token === this.request) this.error = errorMessage(e); } finally { if (token === this.request) this.loading = false; } },
    async open(row) {
      if (this.starting !== null) return;
      const context = { ...this.context };
      if (this.tab === 'history') return this.$router.push({ path: '/papers/run/' + row.id, query: context });
      const token = this.request; this.starting = row.id; this.error = '';
      if (!this.nonces[row.id]) this.$set(this.nonces, row.id, requestId());
      try { const run = responseData(await api.paperStart({ paperId: row.id, requestId: this.nonces[row.id] })); if (token !== this.request) return; this.$delete(this.nonces, row.id); this.$router.push({ path: '/papers/run/' + run.id, query: context }); } catch (e) { if (token === this.request) this.error = errorMessage(e); } finally { if (token === this.request) this.starting = null; }
    },
  },
};
</script>
<style scoped>
.paper-page { max-width: 1440px; margin: 0 auto; padding: 28px 24px; color: var(--text); }
.paper-list { padding: 28px; background: var(--panel); border: 1px solid var(--line); border-radius: 16px; }
h1 { margin: 8px 0 12px; font-size: 30px; } header > p { color: var(--muted); line-height: 1.7; } .eyebrow { color: var(--accent); font-size: 12px; letter-spacing: .12em; }
.search { display: flex; gap: 12px; margin: 20px 0; } .search .el-input { max-width: 420px; }
.paper-grid { min-height: 120px; }.paper-card { display: flex; gap: 20px; align-items: center; padding: 24px 0; border-bottom: 1px solid var(--line); }.paper-icon { width: 54px; height: 64px; border-radius: 10px; background: var(--primary-soft); color: var(--accent); display: grid; place-items: center; font-size: 28px; flex-shrink: 0; }
.paper-copy { flex: 1; min-width: 0; }.paper-copy h2 { margin: 6px 0 10px; font-size: 21px; overflow-wrap: anywhere; }.paper-copy p { color: var(--muted); margin: 0; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }.paper-copy .el-tag { margin-top: 12px; }.paper-card .el-button { border-radius: 8px; }.el-pagination { margin-top: 24px; overflow-x: auto; }.empty { padding: 30px 0; color: var(--muted); }
@media(max-width: 650px) { .paper-page { padding: 16px 12px; }.paper-list { padding: 20px 16px; }.paper-card { flex-wrap: wrap; gap: 12px; }.paper-card .el-button { margin-left: 66px; }.paper-copy { min-width: calc(100% - 66px); } }
</style>
