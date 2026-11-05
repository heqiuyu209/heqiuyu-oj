<template>
  <section class="knowledge-pdf-preview" aria-label="PDF 原件对照">
    <div class="pdf-preview-heading">
      <strong><i class="el-icon-document" /> PDF 原页</strong>
      <el-button v-if="!opened" size="small" icon="el-icon-reading" :disabled="!available" @click="open">查看原页</el-button>
      <el-button v-else type="text" icon="el-icon-close" @click="close">收起原件</el-button>
    </div>
    <template v-if="opened">
      <nav class="pdf-preview-pages" aria-label="原件页码">
        <el-button size="small" icon="el-icon-arrow-left" :disabled="page <= 1" aria-label="上一页原件" @click="$emit('page-change', page - 1)" />
        <label>第 <el-input-number :value="page" :min="1" :max="pages" :controls="false" size="small" aria-label="PDF 原件页码" @change="selectPage" /> / {{ pages }} 页</label>
        <el-button size="small" icon="el-icon-arrow-right" :disabled="page >= pages" aria-label="下一页原件" @click="$emit('page-change', page + 1)" />
      </nav>
      <p v-if="loading" class="pdf-preview-state" role="status"><i class="el-icon-loading" /> 正在读取 PDF 原件</p>
      <div v-else-if="error" class="pdf-preview-state" role="alert"><p>{{ error }}</p><el-button size="small" @click="load">重新读取原件</el-button></div>
      <p v-else-if="unsupported" class="pdf-preview-state">此浏览器无法预览 PDF，请下载原件查看。</p>
      <iframe v-else-if="url" :key="previewUrl" :src="previewUrl" :title="'PDF 原件，第 '+page+' 页'" class="pdf-preview-frame" />
      <p class="pdf-preview-note">页码与提取文字对应；PDF 内部滚动不会改变校订页码。也可下载原件在阅读器中查看。</p>
    </template>
  </section>
</template>
<script>
import api from '@/common/api';
import { knowledgePdfBlob } from '@/common/knowledgeDownload';
import { errorMessage } from '@/common/objective';
export default {
  name: 'KnowledgePdfPreview',
  props: {
    resource: { type: Object, required: true }, identity: { type: String, required: true },
    allowed: { type: Boolean, default: false }, page: { type: Number, default: 1 },
    pages: { type: Number, required: true },
  },
  data: () => ({ opened: false, loading: false, error: '', url: '', request: 0,
    unsupported: typeof navigator !== 'undefined' && navigator.pdfViewerEnabled === false }),
  computed: {
    available() { return this.allowed && /^[a-f0-9]{32}$/.test(this.resource.id) && Number.isInteger(this.resource.version) && this.resource.version > 0 && Number.isInteger(this.pages) && this.pages >= 1 && this.pages <= 200; },
    sourceKey() { return [this.identity, this.resource.id, this.resource.version, !!this.resource.maintenance, this.allowed].join(':'); },
    previewUrl() { return this.url + '#navpanes=0&page=' + this.page + '&zoom=page-width'; },
  },
  watch: { sourceKey() { this.close(); } },
  beforeDestroy() { this.release(); },
  methods: {
    release() { this.request++; if (this.url) URL.revokeObjectURL(this.url); this.url = ''; this.loading = false; this.error = ''; },
    close() { this.release(); this.opened = false; this.$emit('open-change', false); },
    open() { if (!this.available) return; this.opened = true; this.$emit('open-change', true); if (!this.url && !this.loading && !this.unsupported) this.load(); },
    selectPage(value) { if (Number.isInteger(value) && value >= 1 && value <= this.pages) this.$emit('page-change', value); },
    async load() {
      if (!this.available || !this.opened || this.loading) return;
      const request = ++this.request, key = this.sourceKey;
      this.loading = true; this.error = '';
      try {
        const blob = await knowledgePdfBlob(await api.knowledgeOriginal(this.resource.id, this.resource.version, !!this.resource.maintenance));
        if (request !== this.request || key !== this.sourceKey || !this.available || !this.opened) return;
        if (this.url) URL.revokeObjectURL(this.url);
        this.url = URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }));
      } catch (error) { if (request === this.request && key === this.sourceKey && this.available) this.error = errorMessage(error); }
      finally { if (request === this.request && key === this.sourceKey) this.loading = false; }
    },
  },
};
</script>
<style scoped>
.knowledge-pdf-preview { min-width: 0; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; margin: 18px 0; background: var(--surface); }
.pdf-preview-heading, .pdf-preview-pages { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 16px; }
.pdf-preview-pages { border-top: 1px solid var(--line); flex-wrap: wrap; justify-content: center; }
.pdf-preview-pages label { display: flex; align-items: center; gap: 8px; font-size: 14px; }
.pdf-preview-pages .el-input-number { width: 65px; }
.pdf-preview-frame { display: block; width: 100%; height: 66vh; min-height: 420px; border: 0; background: #e5e9ec; }
.pdf-preview-state { padding: 24px 16px; min-height: 160px; color: var(--text); }
.pdf-preview-note { padding: 0 16px 12px; margin: 10px 0 0; font-size: 12px; color: var(--muted); line-height: 1.6; }
@media (max-width: 760px) { .pdf-preview-frame { height: 55vh; min-height: 320px; } .pdf-preview-pages { gap: 8px; } }
</style>
