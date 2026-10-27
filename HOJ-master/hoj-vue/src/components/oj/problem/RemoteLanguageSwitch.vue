<template>
  <div v-if="detail && detail.available && detail.source" class="remote-language-switch">
    <div class="remote-language-actions"><span>题面</span><el-radio-group :value="language" size="small" @input="select"><el-radio-button label="local">题库题面</el-radio-button><el-radio-button :label="sourceCode">{{ sourceCode === 'zh' ? '中文' : 'English' }}</el-radio-button><el-radio-button v-if="detail.translation" :label="translationCode">{{ translationCode === 'zh' ? '中文' : 'English' }}</el-radio-button></el-radio-group><a :href="detail.sourceUrl" target="_blank" rel="noopener noreferrer">{{ detail.platform === 'NOWCODER' ? '牛客' : detail.platform === 'ATCODER' ? 'AtCoder' : 'Codeforces' }} <i class="el-icon-top-right" /></a></div>
    <p v-if="language !== 'local'">{{ language === sourceCode ? (detail.source.origin === 'SOURCE' ? '源站原文' : '管理员录入的原文') : '管理员核对的译文' }} · 原文版本 {{ detail.source.contentHash.slice(0, 10) }}</p>
    <p v-if="detail.translationStale">原文已更新，译文核对中，暂时可查看题库题面或原文。</p>
  </div>
</template>
<script>
import api from '@/common/api';
export default {
  name: 'RemoteLanguageSwitch', props: { pid: { type: Number, required: true } },
  data: () => ({ detail: null, language: 'local', epoch: 0, disposed: false }),
  computed: { sourceCode() { return this.detail && this.detail.sourceLanguage === 'zh-CN' ? 'zh' : 'en'; }, translationCode() { return this.sourceCode === 'zh' ? 'en' : 'zh'; } },
  watch: { pid: { immediate: true, handler() { this.load(); } } },
  beforeDestroy() { this.disposed = true; this.epoch++; },
  methods: {
    async load() { const epoch = ++this.epoch, pid = this.pid; this.detail = null; this.language = 'local'; try { const res = await api.publicRemoteStatement(pid); if (!this.disposed && epoch === this.epoch && pid === this.pid) this.detail = res.data.data; } catch (_) {} },
    select(language) { if (!this.detail || !['local', this.sourceCode, ...(this.detail.translation ? [this.translationCode] : [])].includes(language)) return; this.language = language; this.$emit('change', { pid: this.pid, language, content: language === 'local' ? null : (language === this.sourceCode ? this.detail.source : this.detail.translation).content }); },
  },
};
</script>
<style scoped>
.remote-language-switch { padding: 16px 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); margin: 18px 0; }.remote-language-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px 16px; }.remote-language-actions>span { color: var(--muted); font-size: 12px; }.remote-language-switch a { color: var(--accent); font-size: 12px; }.remote-language-switch p { color: var(--muted); font-size: 11px; line-height: 1.8; margin: 10px 0 0; }.remote-language-switch a:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
</style>
