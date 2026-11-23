<template>
  <section v-if="owner" class="learning-recommendations" :class="{ 'is-compact': compact }" aria-label="下一组训练">
    <header><h2>下一组训练</h2><p>先看推荐依据，再选择要练的题目。更新列表不会调用模型。</p></header>
    <details :open="!compact">
      <summary v-show="compact" class="recommendation-filter-summary">筛选推荐</summary>
      <form class="recommendation-filters" @submit.prevent="load">
        <label class="recommendation-field">题源<el-select v-model="filters.source" :disabled="pending" @change="sourceChanged"><el-option v-for="item in sources" :key="item.value" :value="item.value" :label="item.label" /></el-select></label>
        <label class="recommendation-field">知识方向<el-select v-model="filters.groupCode" :disabled="pending" @change="changed"><el-option value="" label="按画像选择" /><el-option v-for="(name, code) in groups" :key="code" :value="code" :label="name" /></el-select></label>
        <label class="recommendation-field">最低 {{ ratingLabel }}<el-input-number v-model="filters.minRating" :min="0" :max="1000000" :controls="false" :disabled="pending" placeholder="留空按画像" @change="changed" /></label>
        <label class="recommendation-field">最高 {{ ratingLabel }}<el-input-number v-model="filters.maxRating" :min="0" :max="1000000" :controls="false" :disabled="pending" placeholder="留空按画像" @change="changed" /></label>
        <el-checkbox v-model="filters.includeUnrated" :disabled="pending" @change="changed">保留未评级题</el-checkbox>
        <el-button native-type="submit" type="primary" :loading="loading" :disabled="pending">更新推荐</el-button>
      </form>
    </details>
    <el-alert v-if="error" :title="error" type="info" :closable="false" />
    <div v-if="loading" class="recommendation-empty" role="status"><i class="el-icon-loading" /> 正在匹配训练记录与题目</div>
    <template v-else-if="plan">
      <p class="recommendation-basis">{{ rangeDescription }}<span v-if="['RUNNING', 'QUEUED'].includes(syncState)">；训练记录正在同步，推荐依据尚未核对完成</span><span v-else-if="syncState === 'BACKOFF'">；来源同步稍后重试，当前使用缓存</span><span v-else-if="plan.basis.stale || plan.basis.coverageStale">；来源记录待刷新</span><span v-if="plan.basis.historyComplete === false">；外部历史仍在补全</span>。未知分类与评级保持留空，记录不能证明未使用提示。</p>
      <p v-if="['ATCODER', 'NOWCODER'].includes(filters.source)" class="recommendation-basis">知识方向来自本站已公开同源题目的分类；暂无分类的题目可按当前题源难度选择。</p>
      <p v-if="!plan.items.length" class="recommendation-empty">本次未找到符合条件的新题。可以调整范围、切换题源，或先更新已关联账号的记录。</p>
      <template v-else>
        <p v-if="explanation" class="recommendation-summary"><strong>AI 建议</strong> {{ explanation.summary }}</p>
        <ol class="recommendation-list">
          <li v-for="row in visibleItems" :key="row.id">
            <div class="recommendation-title"><button v-if="row.kind === 'OBJECTIVE'" type="button" :disabled="starting !== null" @click="start(row)">{{ row.title }}</button><router-link v-else-if="row.kind === 'PROGRAMMING'" :to="row.url">{{ row.title }}</router-link><a v-else :href="row.url" target="_blank" rel="noopener noreferrer">{{ row.title }} <i class="el-icon-top-right" /></a><span class="recommendation-rating">{{ ratingLabel }} {{ row.rating === null ? '未评级' : row.rating }}</span></div>
            <p class="recommendation-meta">{{ row.displayId }}<span v-if="row.kind === 'OBJECTIVE'"> · 客观题</span><span v-for="group in row.groups" :key="group.code"> · {{ group.name }}</span></p>
            <p class="recommendation-reason">{{ row.reasons.join('；') }}。</p>
            <p v-if="row.aiReason" class="recommendation-ai-reason">{{ row.aiReason }}</p>
          </li>
        </ol>
        <form class="recommendation-save" @submit.prevent="save">
          <label for="training-plan-name">保存这组训练</label>
          <el-input id="training-plan-name" v-model="saveName" maxlength="80" placeholder="给这组训练起个名字" :disabled="saving" @input="saveNonce = null" />
          <el-button native-type="submit" :loading="saving" :disabled="!saveName.trim()">保存本组</el-button>
          <p>保留本组题目与顺序，之后从下方清单继续。</p>
        </form>
        <el-alert v-if="saveError" :title="saveError" type="info" :closable="false" />
        <div class="recommendation-model">
          <div><el-checkbox v-model="includeProfile" :disabled="pending">向模型附带我的站内公开画像</el-checkbox><el-checkbox v-if="includeProfile" v-model="includeExternalProfile" :disabled="pending">同时附带已验证外部画像</el-checkbox><p>点击解释时，候选题和推荐依据将发送至你配置的模型服务。画像仅在勾选后附带；没有模型时仍可直接练习。</p></div>
          <el-button :loading="pending" :disabled="loading" @click="explain">让 AI 解释推荐</el-button>
        </div>
        <el-alert v-if="aiError" :title="aiError" type="info" :closable="false" />
      </template>
    </template>
    <SavedTrainingPlans :uid="uid" :refresh="savedRevision" />
  </section>
</template>
<script>
import api from '@/common/api';
import { responseData, errorMessage, requestId } from '@/common/objective';
import SavedTrainingPlans from './SavedTrainingPlans';
import { readAiModel } from '@/common/aiModelConsent';
const defaultFilters = () => ({ source: 'HOJ', groupCode: '', minRating: undefined, maxRating: undefined, includeUnrated: true });
// Keep read timing across profile remounts; never cache a user's recommendations or credentials.
const nextReadAt = new Map();
export default {
  name: 'LearningRecommendations', props: { uid: { type: String, required: true }, compact: Boolean },
  components: { SavedTrainingPlans },
  data: () => ({ filters: defaultFilters(), plan: null, groups: {}, explanation: null, error: '', aiError: '', loading: false, pending: false, request: 0, explainRequest: 0, includeProfile: false, includeExternalProfile: false, starting: null, startIds: {}, saveName: '下一组训练', saveNonce: null, saving: false, saveError: '', savedRevision: 0, sources: [{ value: 'HOJ', label: '本站' }, { value: 'CODEFORCES', label: 'Codeforces' }, { value: 'ATCODER', label: 'AtCoder' }, { value: 'NOWCODER', label: '牛客' }] }),
  computed: {
    owner() { return this.$store.getters.isAuthenticated && this.$store.getters.userInfo.uid === this.uid; },
    identity() { return this.uid + ':' + (this.$store.getters.userInfo.uid || '') + ':' + this.$store.getters.isAuthenticated + ':' + this.$store.getters.authEpoch; },
    ratingLabel() { return this.filters.source === 'ATCODER' ? '难度估计' : 'Rating'; },
    visibleItems() { return this.explanation ? this.explanation.items : this.plan.items; },
    syncState() { return this.plan ? this.plan.basis.coverageSyncState || this.plan.basis.syncState : null; },
    rangeDescription() { const b = this.plan.basis; return b.minRating === null && b.maxRating === null ? '暂缺评级样本，先按知识方向选择' : (b.automaticRange ? '本次自动范围：' : '本次筛选范围：') + this.ratingLabel + ' ' + (b.minRating === null ? '不限' : b.minRating) + ' 至 ' + (b.maxRating === null ? '不限' : b.maxRating); },
  },
  watch: {
    identity: { immediate: true, handler() { this.reset(); if (this.owner) this.load(); } },
    includeProfile(value) { if (!value) this.includeExternalProfile = false; },
  },
  beforeDestroy() { this.reset(); },
  methods: {
    async save() {
      if (!this.owner || this.saving || !this.plan || !this.plan.items.length || !this.saveName.trim()) return;
      const token = this.request, identity = this.identity;
      if (!this.saveNonce) this.saveNonce = requestId();
      const data = { requestId: this.saveNonce, name: this.saveName.trim(), selection: { filters: this.plan.filters, fingerprint: this.plan.fingerprint, candidateIds: this.plan.items.map(row => row.id) } };
      this.saving = true; this.saveError = '';
      try {
        const saved = responseData(await api.saveTrainingPlan(data));
        if (token !== this.request || identity !== this.identity || !this.owner) return;
        this.savedRevision++;
        if (saved.state === 'DELETED') this.saveError = '这次保存的清单已删除。修改名称后可重新保存。';
        else this.$message.success('训练清单已保存，可从下方继续练习');
      } catch (error) { if (token === this.request && identity === this.identity) this.saveError = errorMessage(error); }
      finally { if (token === this.request && identity === this.identity) this.saving = false; }
    },
    reset() { this.request++; this.explainRequest++; this.plan = null; this.explanation = null; this.error = ''; this.aiError = ''; this.loading = false; this.pending = false; this.includeProfile = false; this.includeExternalProfile = false; this.starting = null; this.startIds = {}; this.filters = defaultFilters(); this.saveName = '下一组训练'; this.saveNonce = null; this.saving = false; this.saveError = ''; },
    changed() { this.request++; this.explainRequest++; this.plan = null; this.explanation = null; this.loading = false; this.pending = false; this.starting = null; this.error = ''; this.aiError = ''; this.saveNonce = null; this.saving = false; this.saveError = ''; },
    sourceChanged() { this.filters.groupCode = ''; this.filters.minRating = undefined; this.filters.maxRating = undefined; this.changed(); },
    async load() {
      if (!this.owner || this.pending || this.loading) return;
      const token = ++this.request, identity = this.identity, uid = this.uid, params = { ...this.filters }; this.plan = null; this.explanation = null; this.error = ''; this.aiError = ''; this.loading = true; this.saveNonce = null; this.saving = false; this.saveError = '';
      const delay = Math.min(5200, Math.max(0, (nextReadAt.get(uid) || 0) - performance.now()));
      if (delay) await new Promise(resolve => setTimeout(resolve, delay));
      if (token !== this.request || identity !== this.identity || !this.owner) return;
      nextReadAt.set(uid, performance.now() + 5200);
      const isCurrent = () => token === this.request && identity === this.identity && this.owner;
      try {
        const result = responseData(await api.aiRecommendations(params));
        if (!isCurrent()) return;
        this.plan = result;
        this.groups = result.groups;
      } catch (error) {
        if (isCurrent()) this.error = errorMessage(error);
      } finally {
        nextReadAt.set(uid, Math.max(nextReadAt.get(uid) || 0, performance.now() + 5200));
        if (isCurrent()) this.loading = false;
      }
    },
    async explain() {
      if (!this.owner || !this.plan || !this.plan.items.length || this.pending) return;
      const token = ++this.explainRequest, request = this.request, plan = this.plan; this.pending = true; this.aiError = '';
      try {
        const modelFingerprint = await readAiModel(() => token === this.explainRequest && request === this.request && this.owner && this.plan === plan);
        if (!modelFingerprint) return;
        const reply = responseData(await api.aiExplainRecommendations({ filters: plan.filters, fingerprint: plan.fingerprint, candidateIds: plan.items.map(row => row.id), modelFingerprint, includeProfile: this.includeProfile, includeExternalProfile: this.includeProfile && this.includeExternalProfile }));
        if (token !== this.explainRequest || request !== this.request || !this.owner || this.plan !== plan) return;
        if (reply.fingerprint !== plan.fingerprint) { this.aiError = '推荐已更新，请重新获取候选题'; return; }
        this.explanation = reply;
      } catch (error) { if (token === this.explainRequest && request === this.request) this.aiError = errorMessage(error); }
      finally { if (token === this.explainRequest) this.pending = false; }
    },
    async start(row) {
      if (this.starting !== null || !this.owner) return;
      const request = this.request; this.starting = row.id; if (!this.startIds[row.id]) this.$set(this.startIds, row.id, requestId());
      try { const run = responseData(await api.objectiveStart({ questionId: row.questionId, requestId: this.startIds[row.id] })); if (request === this.request && this.owner) this.$router.push('/objective/run/' + run.id); }
      catch (error) { if (request === this.request) this.error = errorMessage(error); }
      finally { if (request === this.request) this.starting = null; }
    },
  },
};
</script>
<style scoped>
.recommendation-save { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 18px 0; }.recommendation-save label { font-size: 13px; font-weight: 600; }.recommendation-save .el-input { flex: 1; min-width: 140px; max-width: 340px; }.recommendation-save p { flex-basis: 100%; margin: 0; font-size: 12px; color: var(--muted); line-height: 1.8; }
.learning-recommendations { margin: 24px 0; padding: 24px; border: 1px solid var(--line); border-radius: 12px; color: var(--text); text-align: left; }.learning-recommendations h2 { margin: 0; font-size: 20px; }.learning-recommendations header p, .recommendation-basis, .recommendation-model p { color: var(--muted); font-size: 12px; line-height: 1.8; }.recommendation-filters { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; margin: 20px 0; }.recommendation-filters .recommendation-field { display: flex; flex-direction: column; gap: 6px; font-size: 12px; color: var(--muted); }.recommendation-filters .el-select { width: 160px; }.recommendation-filters .el-input-number { width: 145px; }.recommendation-filters .el-checkbox { margin: 0 8px 10px 0; }.recommendation-filters>.el-button { margin: 0; }.recommendation-model { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; border-top: 1px solid var(--line); padding-top: 18px; }.recommendation-model>div { min-width: 0; }.recommendation-model .el-checkbox { margin: 0 16px 8px 0; }.recommendation-model>.el-button { flex-shrink: 0; }.recommendation-model p { margin: 0 0 12px; max-width: 60em; }.recommendation-list { padding-left: 28px; margin: 10px 0 0; }.recommendation-list li { padding: 18px 0 18px 8px; border-top: 1px solid var(--line); }.recommendation-list li::marker { color: var(--muted); font-size: 13px; }.recommendation-title { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px 16px; }.recommendation-title a, .recommendation-title button { color: var(--accent); font: inherit; font-size: 15px; font-weight: 600; overflow-wrap: anywhere; text-align: left; }.recommendation-title button { border: 0; background: transparent; padding: 0; cursor: pointer; }.recommendation-rating { color: var(--muted); font-size: 12px; white-space: nowrap; }.recommendation-meta { color: var(--muted); font-size: 11px; margin: 7px 0; }.recommendation-reason, .recommendation-ai-reason, .recommendation-summary { font-size: 13px; line-height: 1.8; margin: 8px 0 0; overflow-wrap: anywhere; }.recommendation-ai-reason, .recommendation-summary { padding-left: 12px; border-left: 2px solid var(--accent); }.recommendation-summary { margin: 12px 0 20px; }.recommendation-empty { padding: 22px 0; color: var(--muted); font-size: 13px; line-height: 1.8; }.recommendation-title a:focus-visible, .recommendation-title button:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.learning-recommendations.is-compact { margin: 0; padding: 0; border: 0; }
.recommendation-filter-summary { margin: 10px 0; color: var(--accent); font-size: 13px; cursor: pointer; }
.recommendation-filter-summary:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.is-compact .recommendation-filters .recommendation-field { flex: 1 1 130px; width: auto; min-width: 0; }
.is-compact .recommendation-filters .el-select,
.is-compact .recommendation-filters .el-input-number { width: 100%; }
.is-compact .recommendation-model { flex-wrap: wrap; gap: 8px; }
.is-compact .el-checkbox { max-width: 100%; white-space: normal; line-height: 1.7; }
.is-compact .recommendation-save .el-input { min-width: min(140px, 100%); }
.is-compact .el-button { max-width: 100%; white-space: normal; line-height: 1.35; }
@media(max-width:600px) { .learning-recommendations { padding: 18px 12px; }.recommendation-model { flex-direction: column; gap: 8px; }.recommendation-filters { gap: 12px 8px; }.recommendation-filters .recommendation-field { min-width: 0; width: calc(50% - 4px); }.recommendation-filters .el-select, .recommendation-filters .el-input-number { width: 100%; }.recommendation-model .el-checkbox { white-space: normal; line-height: 1.7; }.recommendation-list { padding-left: 22px; } }
</style>
