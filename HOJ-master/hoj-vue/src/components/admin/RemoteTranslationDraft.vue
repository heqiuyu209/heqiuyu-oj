<template>
  <section class="translation-helper" aria-label="AI 翻译草稿" :aria-busy="pending">
    <header><h3>AI 翻译草稿</h3><div><el-button type="text" :disabled="pending || loading" @click="loadSettings">刷新配置</el-button><el-button type="text" :disabled="pending" @click="configure">模型设置</el-button></div></header>
    <p>以已保存的原文生成{{ targetLanguage === 'en' ? '英文' : '中文' }}草稿，核对后再保存。样例、公式、代码、数值及资源限制由程序保留。</p>
    <p v-if="settings && settings.configured" class="translation-model">本次模型：{{ settings.model }} <span>{{ settings.baseUrl }}</span></p>
    <p v-else-if="!loading">先在 AI 助教的“模型设置”中配置本人的模型；保存配置不会生成译文。</p>
    <el-alert v-if="settings && settings.contestBlocked" title="当前处于参赛时段，AI 翻译暂时停用" type="info" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="translation-generate">
      <el-checkbox v-model="consent" :disabled="pending || loading || disabled || !usable">允许将当前原文中的待翻译文字发送给本人配置的模型</el-checkbox>
      <el-button size="small" :loading="pending || loading" :disabled="disabled || !consent || !usable" @click="generate">生成完整译文草稿</el-button>
    </div>
    <p class="translation-note">生成需要等待模型回复，可能产生你的模型服务费用。生成结果会保留在本次编辑中，原文和现有译文保持当前版本。</p>
    <div v-if="draft" class="translation-result" role="status">
      <div><strong>{{ draft.content.title }}</strong><p>由 {{ draft.model }} 生成，可放入下方编辑器逐段核对。</p></div>
      <el-button type="primary" size="small" :disabled="disabled || pending" @click="$emit('apply', draft)">应用草稿到编辑器</el-button>
    </div>
    <p v-if="applied" role="status" class="translation-applied">草稿已放入编辑器。请核对题意和语言，再点击“核对并保存译文”。</p>
    <ai-assistant v-if="settingsOpen" ref="assistant" class="translation-settings-assistant" @settings-changed="loadSettings" />
  </section>
</template>
<script>
import api from '@/common/api';
import { responseData, errorMessage } from '@/common/objective';
import AiAssistant from '@/components/oj/ai/AiAssistant';
export default {
  name: 'RemoteTranslationDraft',
  components: { AiAssistant },
  props: { problemKey: { type: String, required: true }, sourceHash: { type: String, required: true }, targetLanguage: { type: String, required: true }, disabled: { type: Boolean, default: false } },
  data: () => ({ settings: null, settingsOpen: false, loading: false, pending: false, consent: false, draft: null, applied: false, error: '', epoch: 0, disposed: false }),
  computed: {
    identity() { return [this.$store.getters.userInfo.uid, this.$store.getters.authEpoch, (this.$store.getters.userInfo.roleList || []).slice().sort().join(',')].join(':'); },
    context() { return [this.identity, this.problemKey, this.sourceHash, this.targetLanguage].join(':'); },
    usable() { return !!this.settings && this.settings.configured && !!this.settings.fingerprint && !this.settings.contestBlocked; },
  },
  watch: { context: { immediate: true, handler() { this.epoch++; this.settings = this.draft = null; this.pending = this.consent = this.applied = false; this.$emit('draft', false); this.loadSettings(); } } },
  beforeDestroy() { this.disposed = true; this.epoch++; this.$emit('draft', false); },
  methods: {
    current(epoch, context) { return !this.disposed && epoch === this.epoch && context === this.context; },
    configure() { this.settingsOpen = true; this.$nextTick(() => { if (this.$refs.assistant) this.$refs.assistant.openSettings(); }); },
    clearDraft() { this.draft = null; this.applied = false; this.$emit('draft', false); },
    markApplied() { this.draft = null; this.applied = true; this.$emit('draft', false); },
    async loadSettings() {
      if (this.pending || this.disposed) return;
      const epoch = ++this.epoch, context = this.context; this.loading = true; this.error = ''; this.consent = false;
      try { const settings = responseData(await api.aiSettings()); if (this.current(epoch, context)) this.settings = settings; }
      catch (error) { if (this.current(epoch, context)) this.error = errorMessage(error); }
      finally { if (this.current(epoch, context)) this.loading = false; }
    },
    async generate() {
      if (this.pending || this.loading || this.disabled || !this.consent || !this.usable) return;
      const epoch = ++this.epoch, context = this.context, fingerprint = this.settings.fingerprint;
      this.pending = true; this.error = ''; this.applied = false;
      try {
        const settings = responseData(await api.aiSettings()); if (!this.current(epoch, context)) return;
        this.settings = settings;
        if (settings.fingerprint !== fingerprint || !this.usable) { this.consent = false; this.error = '模型配置或使用权限已变化，请重新确认后生成。'; return; }
        const draft = responseData(await api.remoteTranslate({ problemKey: this.problemKey, sourceHash: this.sourceHash, modelFingerprint: fingerprint, allowModelSource: true }));
        if (!this.current(epoch, context)) return;
        if (draft.problemKey !== this.problemKey || draft.sourceHash !== this.sourceHash || draft.translationLanguage !== this.targetLanguage || !draft.content || draft.draft !== true) throw new Error('译文版本不匹配，请重新打开题目。');
        this.draft = draft; this.$emit('draft', true);
      } catch (error) { if (this.current(epoch, context)) this.error = errorMessage(error); }
      finally { if (this.current(epoch, context)) this.pending = false; }
    },
  },
};
</script>
<style scoped>
.translation-helper { margin: 20px 0; padding: 16px; border: 1px solid var(--line); border-left: 3px solid var(--accent); border-radius: 6px; }
header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }h3 { margin: 0; font-size: 15px; }
p { margin: 8px 0; color: var(--muted); font-size: 12px; line-height: 1.8; }
.translation-model { overflow-wrap: anywhere; }.translation-model span { margin-left: 8px; }.translation-model .el-button { margin-left: 10px; }
.translation-generate { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 14px; margin-top: 14px; }
.translation-generate .el-checkbox { display: inline-flex; white-space: normal; line-height: 1.8; min-width: 0; }
.translation-generate ::v-deep .el-checkbox__label { white-space: normal; line-height: 1.8; font-size: 12px; }
.translation-result { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 14px; margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--line); }
.translation-result strong { overflow-wrap: anywhere; }.translation-applied { color: var(--accent); }
.translation-settings-assistant ::v-deep .ai-launcher { display: none !important; }
@media(max-width:600px) { .translation-helper { padding: 12px; }.translation-generate { align-items: flex-start; } }
</style>
