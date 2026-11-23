<template>
  <section class="collection-admin">
    <header><div><h1>题集与试卷</h1><p>题集适合逐题训练，试卷支持混合题型整卷作答，也可交给比赛使用。</p></div><div class="actions"><el-button icon="el-icon-collection" @click="$refs.sets.newSet()">新建题集</el-button><el-button type="primary" icon="el-icon-document-add" @click="$refs.papers.create()">新建试卷</el-button></div></header>
    <div class="purpose-grid"><div><strong>题集 · 逐题练习</strong><span>组织客观题与填空题，每题独立提交。</span></div><div><strong>试卷 · 整卷作答</strong><span>组合编程题、选择题和填空题，按卷设分。</span></div><div><strong>比赛 · 独立规则</strong><span>先保存试卷，再到比赛设置中选择；无需开放日常练习。</span></div></div>
    <form @submit.prevent="reset"><el-input v-model="keyword" clearable maxlength="100" placeholder="搜索题集或试卷名称" /><el-select v-model="kind" clearable placeholder="全部类型" @change="reset"><el-option label="题集" value="PRACTICE" /><el-option label="试卷" value="PAPER" /></el-select><el-button native-type="submit">搜索</el-button></form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-table v-loading="loading" class="desktop-collections" :data="rows" empty-text="暂无记录，从新建题集或新建试卷开始">
      <el-table-column prop="title" label="名称" min-width="240" /><el-table-column label="类型" width="140"><template slot-scope="{row}">{{ row.mode === 'PRACTICE' ? '题集' : row.source === 'PAPER' ? '整套试卷' : '客观题试卷' }}</template></el-table-column><el-table-column prop="questionCount" label="题数" width="80" />
      <el-table-column label="日常开放" width="140"><template slot-scope="{row}"><el-tag :type="row.published ? 'success' : 'info'">{{ row.published ? '已开放' : '未开放' }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="200"><template slot-scope="{row}"><el-button type="text" @click="edit(row)">编辑与发布</el-button><el-button v-if="row.published" type="text" @click="preview(row)">查看学生页</el-button></template></el-table-column>
    </el-table>
    <div v-loading="loading" class="mobile-collections"><article v-for="row in rows" :key="row.source + ':' + row.id"><h2>{{ row.title }}</h2><p>{{ row.mode === 'PRACTICE' ? '题集' : '试卷' }} · {{ row.questionCount }} 题 · {{ row.published ? '日常已开放' : '日常未开放' }}</p><el-button size="small" type="primary" plain @click="edit(row)">编辑与发布</el-button><el-button v-if="row.published" size="small" @click="preview(row)">查看学生页</el-button></article><p v-if="!loading && !rows.length">暂无记录，从新建题集或新建试卷开始。</p></div>
    <el-pagination layout="prev, pager, next, total" :page-size="20" :total="total" :current-page="page" @current-change="changePage" />
    <paper-admin ref="papers" editor-only @saved="load" @error="error = $event" />
    <objective-admin ref="sets" editor-only @set-saved="load" @error="error = $event" />
  </section>
</template>
<script>
import api from '@/common/api';
import { responseData, errorMessage } from '@/common/objective';
import PaperAdmin from './PaperAdmin';
import ObjectiveAdmin from './ObjectiveAdmin';
export default {
  name: 'CollectionAdmin', components: { PaperAdmin, ObjectiveAdmin },
  data: () => ({ page: 1, keyword: '', kind: '', total: 0, rows: [], error: '', loading: false, request: 0 }),
  computed: { uid() { return this.$store.getters.userInfo.uid; } },
  watch: { uid() { this.rows = []; this.reset(); } },
  created() { this.load(); }, beforeDestroy() { this.request++; },
  methods: {
    reset() { this.page = 1; this.load(); }, changePage(page) { this.page = page; this.load(); },
    async load() { const token = ++this.request; this.loading = true; this.error = ''; try { const data = responseData(await api.adminCollections({ currentPage: this.page, keyword: this.keyword, kind: this.kind })); if (token === this.request) { this.rows = data.records; this.total = data.total; } } catch (e) { if (token === this.request) this.error = errorMessage(e); } finally { if (token === this.request) this.loading = false; } },
    edit(row) { if (row.source === 'PAPER') this.$refs.papers.edit(row.id); else { this.$refs.sets.tab = 'sets'; this.$refs.sets.edit(row); } },
    preview(row) { this.$router.push({ path: row.source === 'PAPER' ? '/papers' : '/objective', query: { keyword: row.title, ...(row.source === 'PAPER' ? {} : { listTab: 'sets' }) } }); },
  },
};
</script>
<style scoped>
.mobile-collections { display: none; }.mobile-collections article { padding: 20px; margin-bottom: 12px; background: var(--panel, #fff); border: 1px solid var(--line); border-radius: 12px; }.mobile-collections h2 { font-size: 17px; margin: 0 0 10px; overflow-wrap: anywhere; }.mobile-collections article p { margin-bottom: 16px; }
@media(max-width:680px) { .desktop-collections { display: none; }.mobile-collections { display: block; } }
.collection-admin { padding: 24px; color: var(--text); }.collection-admin header { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 24px; }.collection-admin h1 { margin: 0 0 10px; font-size: 26px; }.collection-admin p { color: var(--muted); line-height: 1.8; margin: 0; }.actions { display: flex; flex-shrink: 0; gap: 12px; }.actions .el-button { margin: 0; }.purpose-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin: 0 0 28px; }.purpose-grid > div { background: var(--soft); border: 1px solid var(--line); border-radius: 12px; padding: 20px; }.purpose-grid strong, .purpose-grid span { display: block; }.purpose-grid span { color: var(--muted); line-height: 1.8; margin-top: 8px; font-size: 13px; }.collection-admin form { display: flex; gap: 12px; margin-bottom: 20px; }.collection-admin form .el-input { max-width: 360px; }.el-pagination { margin-top: 24px; overflow-x: auto; }
@media(max-width:800px) { .collection-admin { padding: 12px; }.collection-admin header, .collection-admin form { flex-wrap: wrap; }.purpose-grid { grid-template-columns: 1fr; gap: 8px; }.purpose-grid > div { padding: 14px; } }
</style>
