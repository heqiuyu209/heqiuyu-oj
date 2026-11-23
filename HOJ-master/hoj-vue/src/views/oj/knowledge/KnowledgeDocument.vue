<template>
  <section :class="['knowledge-page knowledge-reader', { 'pdf-comparing': pdfPreviewOpen }]" @transitionend.self="onReaderEntered">
    <header class="knowledge-heading"><div><router-link to="/knowledge" class="knowledge-back"><i class="el-icon-arrow-left" /> 返回知识库</router-link><h1>{{ document ? document.title : '资料原文' }}</h1><p v-if="document" class="knowledge-muted">版本 {{ document.version }} · 当前已发布原文</p></div></header>
    <el-alert v-if="contestBlocked" title="你正在参赛，比赛结束后恢复知识库阅读。" type="info" :closable="false" show-icon />
    <el-alert v-else-if="accessError" :title="accessError" type="error" :closable="false"><el-button type="text" @click="loadKnowledgeAccess">重新核对</el-button></el-alert>
    <div v-else-if="!accessReady || loading" class="knowledge-empty" role="status"><i class="el-icon-loading" />正在读取当前原文</div>
    <div v-if="error && knowledgeAllowed" class="knowledge-surface knowledge-empty" role="alert"><i class="el-icon-document-delete" /><h3>这份引用暂不可用</h3><p>{{ error }}</p><el-button @click="loadDocument">重试</el-button><router-link to="/knowledge"><el-button type="primary">重新检索</el-button></router-link></div>
    <article v-if="document && knowledgeAllowed" class="knowledge-surface">
      <div v-if="document.format === 'pdf' && document.source" class="knowledge-pdf-source"><div><strong><i class="el-icon-document" /> {{ document.source.name }}</strong><p class="knowledge-muted">共 {{ document.source.pages }} 页 · {{ document.source.extractionMode === 'OCR_REVIEWED' ? '本地 OCR · 管理员校订文字' : document.source.extractionMode === 'OCR' ? '本地 OCR 识别文字，请留意公式与代码的识别误差' : document.source.extractionMode === 'NATIVE_REVIEWED' ? '原有文字 · 管理员校订' : '按原页码提取的文字' }}，版式以 PDF 原件为准。</p></div><el-button icon="el-icon-download" :loading="downloading" :disabled="downloading" @click="downloadOriginal">下载 PDF 原件</el-button></div>
      <p v-if="downloadError" class="knowledge-error" role="alert">{{ downloadError }}</p>
      <KnowledgeVideo v-if="document.format === 'video'" :key="knowledgeIdentity+':'+document.id+':'+document.version" :resource="{id:document.id,version:document.version}" :content="document.content" :source="document.source || {}" :allowed="knowledgeAllowed" :start-seconds="videoStart" />
      <div v-else :class="['knowledge-document-body', { 'pdf-comparison-layout': pdfPreviewOpen }]">
        <KnowledgePdfPreview v-if="document.format === 'pdf' && document.source" :key="knowledgeIdentity+':'+document.id+':'+document.version" :resource="{id:document.id,version:document.version}" :identity="knowledgeIdentity" :allowed="knowledgeAllowed" :pages="document.source.pages" :page="pdfPage" @page-change="selectPdfPage" @open-change="pdfPreviewOpen=$event" />
        <KnowledgeText ref="body" :content="document.content" :format="document.format" :source-range="citationRange" allow-links />
      </div>
    </article>
  </section>
</template>
<script>
import api from '@/common/api';
import knowledgeAccess from '@/common/knowledgeAccess';
import { responseData, errorMessage } from '@/common/objective';
import KnowledgeText from '@/components/oj/learning/KnowledgeText';
import KnowledgeVideo from '@/components/oj/learning/KnowledgeVideo';
import KnowledgePdfPreview from '@/components/oj/learning/KnowledgePdfPreview';
import { knowledgePdfBlob, saveKnowledgePdf } from '@/common/knowledgeDownload';
import '@/styles/knowledge.css';

function citationOffset(value) {
  if (typeof value === 'string' && /^(0|[1-9]\d{0,5})$/.test(value)) return Number(value);
  return Number.isInteger(value) ? value : null;
}

export default {
  name: 'KnowledgeDocument', mixins: [knowledgeAccess], components: { KnowledgeText, KnowledgeVideo, KnowledgePdfPreview },
  computed: {
    videoStart() { const raw = this.$route.query.t; const value = typeof raw === 'number' ? raw : typeof raw === 'string' && /^[0-9]{1,4}(\.[0-9]{1,3})?$/.test(raw) ? Number(raw) : 0; return Number.isFinite(value) && value >= 0 && value <= 7200 ? value : 0; },
    citationRange() {
      const source = this.document, query = this.$route.query;
      if (!source || !['markdown', 'text'].includes(source.format) || Number(query.version) !== source.version) return null;
      const start = citationOffset(query.from), end = citationOffset(query.to);
      if (start === null || end === null || start < 0 || end <= start || end > source.content.length) return null;
      if (/[\uDC00-\uDFFF]/.test(source.content.charAt(start)) || /[\uDC00-\uDFFF]/.test(source.content.charAt(end))) return null;
      return { start, end };
    },
  },
  data: () => ({ pdfPage: 1, pdfPreviewOpen: false, document: null, error: '', loading: false, documentRequest: 0, downloading: false, downloadError: '' }),
  watch: { '$route.fullPath'() { this.clearKnowledgeData(); if (this.knowledgeAllowed) this.loadDocument(); } },
  methods: {
    clearKnowledgeData() { this.pdfPage = 1; this.pdfPreviewOpen = false; this.documentRequest++; this.document = null; this.error = ''; this.loading = false; this.downloading = false; this.downloadError = ''; },
    onKnowledgeReady() { this.loadDocument(); },
    onReaderEntered() {
      const epoch = this.knowledgeEpoch, request = this.documentRequest;
      this.$nextTick(() => this.locateCitation(epoch, request));
    },
    async loadDocument() {
      if (!this.knowledgeAllowed) return;
      const id = this.$route.params.documentID, raw = this.$route.query.version;
      if (!/^[a-f0-9]{32}$/.test(id) || raw != null && (!/^[1-9][0-9]{0,9}$/.test(raw) || Number(raw) > 2147483647)) { this.error = '资料标识或版本无效，请返回知识库重新检索。'; return; }
      const epoch = this.knowledgeEpoch, request = ++this.documentRequest; this.loading = true; this.document = null; this.error = '';
      try { const data = responseData(await api.knowledgeDocument(id, raw == null ? undefined : Number(raw))); if (this.knowledgeCurrent(epoch) && request === this.documentRequest) { this.document = data; this.$nextTick(() => this.locateCitation(epoch, request)); } }
      catch (error) { if (this.knowledgeCurrent(epoch) && request === this.documentRequest) this.error = errorMessage(error); }
      finally { if (epoch === this.knowledgeEpoch && request === this.documentRequest) this.loading = false; }
    },
    locateCitation(epoch, request) {
      if (!this.knowledgeCurrent(epoch) || request !== this.documentRequest || !this.document || !this.$refs.body) return;
      if (this.document.format === 'pdf') {
        const raw = this.$route.query.page;
        // Vue Router keeps numbers for in-app links; a page reload produces strings.
        const number = typeof raw === 'number' ? raw : typeof raw === 'string' && /^[1-9][0-9]{0,2}$/.test(raw) ? Number(raw) : null;
        if (Number.isInteger(number) && number >= 1 && number <= this.document.source.pages) {
          this.pdfPage = number;
          const page = this.$refs.body.$el.querySelector('[data-pdf-page="' + number + '"]');
          if (page) { page.focus({ preventScroll: true }); page.scrollIntoView({ block: 'start' }); }
        }
        return;
      }
      const root = this.$refs.body.$el;
      const target = this.citationRange && (this.document.format === 'text'
        ? root.querySelector('[data-knowledge-citation-target]')
        : this.markdownCitationBlock(root, this.citationRange));
      if (target) {
        target.setAttribute('data-knowledge-citation-target', '');
        if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1');
        target.focus({ preventScroll: true });
        target.scrollIntoView({ block: 'start' });
        return;
      }
      const wanted = this.$route.query.heading;
      if (typeof wanted !== 'string' || wanted.length > 2000) return;
      let path = [];
      for (const heading of root.querySelectorAll('h1,h2,h3,h4,h5,h6')) {
        const level = Number(heading.tagName.slice(1)); path = path.filter(item => item.level < level); path.push({ level, text: heading.getAttribute('data-knowledge-heading') || heading.textContent });
        if (path.map(item => item.text).join(' / ') === wanted) { heading.style.scrollMarginTop = '120px'; heading.scrollIntoView({ block: 'start' }); break; }
      }
    },
    markdownCitationBlock(root, range) {
      const text = this.document.content;
      const first = text.slice(range.start, range.end).search(/\S/);
      if (first < 0) return null;
      const line = text.slice(0, range.start + first).split(/\r\n?|\n/).length - 1;
      let target = null, smallest = Infinity;
      for (const element of root.querySelectorAll('[data-knowledge-lines]')) {
        const [start, end] = element.dataset.knowledgeLines.split(':').map(Number);
        if (start <= line && line < end && end - start <= smallest) {
          target = element.closest('pre') || element;
          smallest = end - start;
        }
      }
      return target;
    },
    selectPdfPage(page) {
      if (!this.document || !this.document.source || !Number.isInteger(page) || page < 1 || page > this.document.source.pages) return;
      this.pdfPage = page;
      this.$nextTick(() => { const target = this.$refs.body && this.$refs.body.$el.querySelector('[data-pdf-page="'+page+'"]'); if (target) { target.focus({preventScroll:true}); target.scrollIntoView({block:'nearest'}); } });
    },
    async downloadOriginal() {
      if (!this.knowledgeAllowed || !this.document || this.downloading) return;
      const epoch = this.knowledgeEpoch, request = this.documentRequest, source = this.document;
      this.downloading = true; this.downloadError = '';
      try {
        const blob = await knowledgePdfBlob(await api.knowledgeOriginal(source.id, source.version));
        if (this.knowledgeCurrent(epoch) && request === this.documentRequest && this.document === source) saveKnowledgePdf(blob, source.source.name);
      } catch (error) { if (this.knowledgeCurrent(epoch) && request === this.documentRequest) this.downloadError = errorMessage(error); }
      finally { if (this.knowledgeCurrent(epoch) && request === this.documentRequest) this.downloading = false; }
    },
  },
};
</script>
<style scoped>
.knowledge-reader { max-width: 980px; }
.knowledge-reader.pdf-comparing { max-width: 1560px; }
.pdf-comparison-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 26px; align-items: start; }
.pdf-comparison-layout > .knowledge-pdf-preview { position: sticky; top: 100px; }
@media (max-width: 1000px) { .pdf-comparison-layout { grid-template-columns: minmax(0, 1fr); gap: 12px; } .pdf-comparison-layout > .knowledge-pdf-preview { position: static; } }
.knowledge-back { display: inline-block; font-size: 14px; margin-bottom: 20px; }
.knowledge-reader article { padding: 32px 42px; }
.knowledge-pdf-source { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 16px; padding-bottom: 18px; border-bottom: 1px solid var(--line); overflow-wrap: anywhere; }
.knowledge-reader .knowledge-empty .el-button { margin: 12px 5px 0; }
@media (max-width: 760px) { .knowledge-reader article { padding: 20px; } }
</style>
