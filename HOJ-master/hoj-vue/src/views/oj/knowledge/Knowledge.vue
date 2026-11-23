<template>
  <section class="knowledge-page">
    <header class="knowledge-heading"><div><h1>知识库</h1><p>查阅算法讲义，带着来源理解问题。</p></div><router-link v-if="canMaintain" to="/admin/knowledge"><el-button icon="el-icon-folder-opened">维护资料</el-button></router-link></header>
    <el-alert v-if="contestBlocked" title="你正在参赛，比赛结束后恢复知识库检索与问答。" type="info" :closable="false" show-icon />
    <el-alert v-else-if="accessError" :title="accessError" type="error" :closable="false"><el-button type="text" @click="loadKnowledgeAccess">重新核对</el-button></el-alert>
    <div v-else-if="!accessReady" class="knowledge-empty" role="status"><i class="el-icon-loading" />正在核对访问权限</div>
    <div v-if="knowledgeAllowed" class="knowledge-layout">
      <aside class="knowledge-surface knowledge-library" aria-label="已发布资料">
        <div class="knowledge-library-heading"><h2>已发布资料</h2><el-button type="text" icon="el-icon-refresh" :disabled="directoryLoading" @click="loadDirectory">刷新</el-button></div>
        <form class="knowledge-toolbar" @submit.prevent="page=1;loadDirectory()"><el-input v-model="keyword" placeholder="查找资料标题" aria-label="资料标题" maxlength="200" clearable /><el-button native-type="submit" icon="el-icon-search" aria-label="筛选资料" /></form>
        <p v-if="directoryError" class="knowledge-error" role="alert">{{ directoryError }}<el-button type="text" @click="loadDirectory">重试</el-button></p>
        <div v-if="directoryLoading" class="knowledge-muted" role="status"><i class="el-icon-loading" /> 正在读取资料</div>
        <div v-else-if="!documents.length && !directoryError" class="knowledge-empty"><i class="el-icon-reading" /><h3>{{ keyword ? '没有匹配的资料' : '还没有已发布资料' }}</h3><p>{{ keyword ? '换个标题关键词试试。' : '管理员发布资料后，就能在这里阅读和检索。' }}</p></div>
        <ul v-else class="knowledge-documents"><li v-for="doc in documents" :key="doc.id"><el-checkbox :value="selectedIds.includes(doc.id)" :disabled="pending" :aria-label="'选择资料：'+doc.title" @change="toggleDocument(doc, $event)" /><div><router-link :to="{name:'KnowledgeDocument',params:{documentID:doc.id},query:{version:doc.version}}">{{ doc.title }}</router-link><small>版本 {{ doc.version }}</small></div></li></ul>
        <el-pagination v-if="total > 20" small layout="prev, pager, next" :total="total" :page-size="20" :current-page="page" @current-change="page=$event;loadDirectory()" />
      </aside>
      <main class="knowledge-work">
        <form class="knowledge-surface knowledge-query" @submit.prevent="submit">
          <h2>想查什么？</h2><el-input ref="question" v-model="question" type="textarea" :rows="4" :maxlength="2000" :disabled="pending" placeholder="例如：什么时候可以使用二分答案？" aria-label="知识库问题" />
          <div class="knowledge-scope"><el-radio-group v-model="scope" size="small" :disabled="pending"><el-radio-button label="all">全部资料</el-radio-button><el-radio-button label="selected">选中的资料 {{ selectedIds.length || '' }}</el-radio-button></el-radio-group><el-button v-if="selectedIds.length" type="text" :disabled="pending" @click="selectedIds=[];scope='all';clearResult()">清除选择</el-button></div>
          <p v-if="scope === 'selected' && !selectedIds.length" class="knowledge-muted">请先勾选至少一份资料，或切换为全部资料。</p>
          <el-checkbox v-model="allowModelExcerpts" @change="clearResult">允许将本次命中的资料片段发送给我配置的模型</el-checkbox>
          <p class="knowledge-muted">{{ allowModelExcerpts ? '仅发送当前问题和检索片段。模型使用 AI 助教中保存的本人设置。' : '当前只在本站检索资料，无需配置模型。' }}<button v-if="allowModelExcerpts" type="button" class="knowledge-link-button" @click="openModelSettings">模型设置</button></p>
          <p v-if="indexConfigured === false" class="knowledge-muted" role="status">资料检索暂未就绪，仍可阅读左侧已发布资料。</p>
          <div class="knowledge-query-action"><small v-if="pending" role="status">{{ allowModelExcerpts ? '正在检索并生成带引用的回答' : '正在检索资料' }}</small><el-button native-type="submit" type="primary" icon="el-icon-search" :loading="pending" :disabled="pending || !question.trim() || indexConfigured === false || scope === 'selected' && !selectedIds.length">{{ allowModelExcerpts ? '检索并问助教' : '检索资料' }}</el-button></div>
        </form>
        <p v-if="error" class="knowledge-error knowledge-surface" role="alert">{{ error }}</p>
        <section v-if="result" class="knowledge-surface knowledge-results" aria-live="polite">
          <p class="knowledge-muted knowledge-submitted">{{ submittedQuestion }}</p>
          <el-alert v-if="result.generationFailed" title="模型回答未完成，已保留检索资料" :description="result.generationError" type="warning" :closable="false" show-icon />
          <p v-if="result.generationFailed && result.excerptsSent" class="knowledge-muted">本次已尝试向你选择的模型发送资料片段，未获得有效回答。可以继续阅读下方片段和原文。</p>
          <template v-if="result.content">
            <h2>{{ result.answerable === false ? '资料依据不足' : result.answerCoverage === 'partial' ? '部分问题的回答' : result.modelUsed ? '结合资料的回答' : '检索结果' }}</h2>
            <KnowledgeAnswer v-if="Array.isArray(result.claims) && result.claims.length" :claims="result.claims" :citations="result.citations" :citation-route="citationRoute" />
            <template v-else><KnowledgeText :content="result.content" :format="result.modelUsed ? 'markdown' : 'text'" /><div v-if="result.citations && result.citations.length" class="knowledge-cited"><span>回答依据</span><router-link v-for="item in result.citations" :key="item.citationId" :to="citationRoute(item)">{{ item.citationId }} · {{ item.title }}</router-link></div></template>
          </template>
          <aside v-if="unansweredQuestions.length" class="knowledge-unanswered" aria-labelledby="knowledge-unanswered-title">
            <h3 id="knowledge-unanswered-title">暂缺依据的问题</h3>
            <ul><li v-for="(question, index) in unansweredQuestions" :key="index">{{ question }}</li></ul>
            <p>可以补充相关资料，或缩小问题范围后再问。</p>
          </aside>
          <h2 class="knowledge-results-title">参考片段 <span>{{ result.items.length }}</span></h2>
          <div v-if="!result.items.length" class="knowledge-empty"><i class="el-icon-search" /><h3>当前没有可用的参考片段</h3><p>换个问题，或扩大所选资料范围。</p></div>
          <article v-for="item in result.items" :key="item.chunkId" class="knowledge-excerpt"><header><span class="knowledge-citation-id">{{ item.citationId }}</span><router-link :to="citationRoute(item)">{{ item.title }}</router-link><small>版本 {{ item.version }}</small></header><p v-if="item.headingPath" class="knowledge-muted">{{ item.headingPath }}</p><p class="knowledge-excerpt-body">{{ item.excerpt }}</p><router-link :to="citationRoute(item)" class="knowledge-original">查看原文 <i class="el-icon-arrow-right" /></router-link></article>
        </section>
      </main>
    </div>
  </section>
</template>
<script>
import { readAiModel } from '@/common/aiModelConsent';
import api from '@/common/api';
import knowledgeAccess from '@/common/knowledgeAccess';
import { responseData, errorMessage } from '@/common/objective';
import KnowledgeText from '@/components/oj/learning/KnowledgeText';
import KnowledgeAnswer from '@/components/oj/learning/KnowledgeAnswer';
import '@/styles/knowledge.css';
export default {
  name: 'Knowledge', mixins: [knowledgeAccess], components: { KnowledgeText, KnowledgeAnswer },
  data: () => ({ documents: [], total: 0, page: 1, keyword: '', directoryLoading: false, directoryError: '', directoryRequest: 0, indexConfigured: null, question: '', scope: 'all', selectedIds: [], allowModelExcerpts: false, pending: false, result: null, error: '', submittedQuestion: '', searchRequest: 0 }),
  computed: {
    canMaintain() { return (this.$store.getters.userInfo.roleList || []).some(role => role === 'root' || role === 'admin'); },
    unansweredQuestions() {
      if (!this.result || this.result.generationFailed || !['partial', 'insufficient'].includes(this.result.answerCoverage)) return [];
      const questions = this.result.unansweredQuestions;
      return Array.isArray(questions) && questions.every(question => typeof question === 'string' && question.trim()) ? questions : [];
    },
  },
  methods: {
    clearResult() { this.searchRequest++; this.pending = false; this.result = null; this.error = ''; this.submittedQuestion = ''; },
    clearKnowledgeData() { this.clearResult(); this.directoryRequest++; this.documents = []; this.total = 0; this.directoryLoading = false; this.directoryError = ''; this.indexConfigured = null; this.question = ''; this.allowModelExcerpts = false; this.scope = 'all'; this.selectedIds = []; this.page = 1; this.keyword = ''; },
    onKnowledgeReady() { this.loadDirectory(); },
    async loadDirectory() {
      if (!this.knowledgeAllowed) return;
      const epoch = this.knowledgeEpoch, request = ++this.directoryRequest; this.directoryLoading = true; this.directoryError = '';
      try { const data = responseData(await api.knowledgeDirectory({ page: this.page, keyword: this.keyword })); if (!this.knowledgeCurrent(epoch) || request !== this.directoryRequest) return; this.documents = data.items; this.total = data.total; this.indexConfigured = data.indexConfigured; }
      catch (error) { if (!this.knowledgeCurrent(epoch) || request !== this.directoryRequest) return; this.directoryError = errorMessage(error); this.documents = []; this.handleDenied(error); }
      finally { if (epoch === this.knowledgeEpoch && request === this.directoryRequest) this.directoryLoading = false; }
    },
    toggleDocument(doc, checked) {
      if (this.pending) return;
      if (checked && this.selectedIds.length >= 100) { this.$message.info('一次最多选择 100 份资料。'); return; }
      this.selectedIds = checked ? [...this.selectedIds, doc.id] : this.selectedIds.filter(id => id !== doc.id); this.scope = 'selected'; this.clearResult();
    },
    citationRoute(item) {
      if (!/^[a-f0-9]{32}$/.test(item.documentId) || !Number.isInteger(item.version) || item.version < 1 || !/^[a-f0-9]{64}$/.test(item.chunkId)) return { name: 'Knowledge' };
      const query = {
        version: item.version,
        chunk: item.chunkId,
        heading: item.headingPath || undefined,
        page: Number.isInteger(item.page) && item.page >= 1 && item.page <= 200 ? item.page : undefined,
        t: Number.isInteger(item.startMs) && item.startMs >= 0 && item.startMs <= 7200000 ? item.startMs / 1000 : undefined,
      };
      if (Number.isInteger(item.sourceStart) && Number.isInteger(item.sourceEnd)
          && item.sourceStart >= 0 && item.sourceEnd > item.sourceStart) {
        query.from = item.sourceStart;
        query.to = item.sourceEnd;
      }
      return { name: 'KnowledgeDocument', params: { documentID: item.documentId }, query };
    },
    openModelSettings() { window.dispatchEvent(new CustomEvent('hoj-ai-open-settings')); },
    handleDenied(error) {
      const status = error && ((error.data && error.data.status) || (error.response && error.response.data && error.response.data.status));
      if (status === 401 || status === 403) { const message = errorMessage(error); this.invalidateKnowledge(); this.accessError = message; }
    },
    async submit() {
      if (!this.knowledgeAllowed || this.pending || !this.question.trim() || this.indexConfigured === false || this.scope === 'selected' && !this.selectedIds.length) return;
      const epoch = this.knowledgeEpoch, request = ++this.searchRequest, query = this.question.trim(); this.pending = true; this.result = null; this.error = '';
      const search = { query, topK: 5 }; if (this.scope === 'selected') search.documentIds = this.selectedIds.slice();
      try {
        const consent = this.allowModelExcerpts === true;
        const modelFingerprint = consent ? await readAiModel(() => this.knowledgeCurrent(epoch) && request === this.searchRequest) : undefined;
        if (!this.knowledgeCurrent(epoch) || request !== this.searchRequest || consent && !modelFingerprint) return;
        const result = responseData(await api.knowledgeAnswer({ search, allowModelExcerpts: consent, modelFingerprint }));
        if (!this.knowledgeCurrent(epoch) || request !== this.searchRequest) return; this.result = result; this.submittedQuestion = query;
      }
      catch (error) { if (!this.knowledgeCurrent(epoch) || request !== this.searchRequest) return; this.error = errorMessage(error); this.handleDenied(error); }
      finally { if (epoch === this.knowledgeEpoch && request === this.searchRequest) this.pending = false; }
    },
  },
};
</script>
<style scoped>
.knowledge-layout { display: grid; grid-template-columns: minmax(255px, 310px) minmax(0, 1fr); gap: 24px; align-items: start; }
.knowledge-work { display: grid; gap: 24px; min-width: 0; }
h2 { font-size: 19px; margin: 0 0 18px; color: var(--text); }
.knowledge-library-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.knowledge-library-heading h2 { margin: 0; }
.knowledge-library .knowledge-toolbar { margin: 15px 0 12px; flex-wrap: nowrap; gap: 8px; }
.knowledge-documents { padding: 0; list-style: none; margin: 0; }
.knowledge-documents li { display: flex; gap: 12px; padding: 17px 0; border-bottom: 1px solid var(--line); }
.knowledge-documents li:last-child { border-bottom: 0; }
.knowledge-documents li div { min-width: 0; }
.knowledge-documents a { display: block; color: var(--text); line-height: 1.7; overflow-wrap: anywhere; }
.knowledge-documents small { display: block; color: var(--muted); margin-top: 6px; }
.knowledge-scope { margin: 18px 0; display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.knowledge-query .knowledge-muted { margin: 10px 0; }
.knowledge-query-action { display: flex; justify-content: flex-end; align-items: center; gap: 18px; margin-top: 20px; }
.knowledge-query-action small { color: var(--muted); }
.knowledge-link-button { border: 0; background: none; color: var(--accent); cursor: pointer; padding: 0 0 0 10px; font-size: inherit; }
.knowledge-submitted { padding-bottom: 16px; border-bottom: 1px solid var(--line); overflow-wrap: anywhere; }
.knowledge-results-title { margin: 28px 0 0; }
.knowledge-results-title span { font-size: 13px; color: var(--muted); padding-left: 8px; }
.knowledge-unanswered { margin-top: 22px; padding: 18px 20px; border: 1px solid var(--line); border-radius: 12px; background: var(--soft); overflow-wrap: anywhere; }
.knowledge-unanswered h3 { margin: 0 0 10px; font-size: 15px; color: var(--text); }
.knowledge-unanswered ul { margin: 0; padding-left: 22px; line-height: 1.9; }
.knowledge-unanswered p { margin: 12px 0 0; color: var(--muted); font-size: 13px; line-height: 1.8; }
.knowledge-excerpt { padding: 24px 0; border-bottom: 1px solid var(--line); }
.knowledge-excerpt:last-child { border: 0; padding-bottom: 0; }
.knowledge-excerpt header { display: flex; align-items: baseline; flex-wrap: wrap; gap: 10px; }
.knowledge-excerpt header a { color: var(--text); font-weight: 600; }
.knowledge-excerpt header small { color: var(--muted); }
.knowledge-citation-id { font-size: 12px; color: var(--accent); border: 1px solid var(--line); border-radius: 5px; padding: 2px 6px; }
.knowledge-excerpt-body { white-space: pre-wrap; line-height: 1.9; overflow-wrap: anywhere; margin: 12px 0; }
.knowledge-original { font-size: 13px; }
.knowledge-cited { display: flex; flex-wrap: wrap; gap: 8px 16px; border-top: 1px solid var(--line); margin-top: 20px; padding-top: 16px; font-size: 13px; }
.knowledge-cited span { color: var(--muted); }
@media (max-width: 900px) { .knowledge-layout { grid-template-columns: 1fr; } .knowledge-work { grid-row: 1; } .knowledge-query-action { flex-wrap: wrap; } }
</style>
