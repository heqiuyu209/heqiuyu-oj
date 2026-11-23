<template>
  <section v-if="owner" class="saved-training" aria-label="我的训练清单">
    <header class="saved-training-header">
      <div><h3>我的训练清单</h3><p>把一组题练完，再开始下一组。</p></div>
      <el-button size="small" :loading="loading" :disabled="busy" @click="load">刷新进度</el-button>
    </header>
    <div class="saved-training-tabs" role="group" aria-label="清单状态">
      <button v-for="tab in tabs" :key="tab.value" type="button" :aria-pressed="state === tab.value" :disabled="busy" @click="switchState(tab.value)">{{ tab.label }}</button>
    </div>
    <p class="saved-training-note">进度来自当前可见的单题练习及已验证账号的同步记录，可能有延迟或缺失；客观题成绩未开放时不显示通过。题目与顺序保留，题面使用当前版本。</p>
    <el-alert v-if="error" :title="error" type="info" :closable="false" />
    <p v-if="loading" role="status" class="saved-training-empty">正在读取清单与练习记录</p>
    <p v-else-if="!items.length && !error" class="saved-training-empty">{{ state === 'ACTIVE' ? '还没有进行中的清单。可以先保存上方推荐的一组题。' : '暂时没有归档清单。' }}</p>
    <details v-for="(plan, index) in items" v-else :key="plan.id" class="saved-training-plan" :open="index === 0">
      <summary><span class="saved-training-name">{{ plan.name }}</span><span class="saved-training-progress">{{ plan.solved }} / {{ plan.items.length }} 有通过记录</span></summary>
      <div class="saved-training-actions">
        <span>{{ sourceName(plan.filters.source) }} · 保存于 {{ date(plan.createdAt) }}</span>
        <el-button type="text" :disabled="busy" @click="rename(plan)">改名</el-button>
        <el-button type="text" :disabled="busy" @click="change(plan, plan.state === 'ACTIVE' ? 'ARCHIVED' : 'ACTIVE')">{{ plan.state === 'ACTIVE' ? '归档' : '继续训练' }}</el-button>
        <el-button type="text" :disabled="busy" @click="remove(plan)">删除</el-button>
      </div>
      <ol class="saved-training-list">
        <li v-for="row in plan.items" :key="row.id">
          <template v-if="row.available">
            <div class="saved-training-question">
              <button v-if="row.kind === 'OBJECTIVE'" type="button" :disabled="starting !== null || busy" @click="start(row)">{{ row.title }}</button>
              <router-link v-else-if="row.kind === 'PROGRAMMING'" :to="row.url">{{ row.title }}</router-link>
              <a v-else :href="row.url" target="_blank" rel="noopener noreferrer">{{ row.title }} <i class="el-icon-top-right" /></a>
              <span class="saved-training-status" :class="{ passed: row.status === 'SOLVED' }">{{ statuses[row.status] }}</span>
            </div>
            <p class="saved-training-meta">{{ row.displayId }} · {{ sourceName(row.source) }} · {{ row.source === 'ATCODER' ? '难度估计' : 'Rating' }} {{ row.rating === null ? '未评级' : row.rating }}</p>
            <p v-if="row.savedReasons && row.savedReasons.length" class="saved-training-reason">保存时推荐依据：{{ row.savedReasons.join('；') }}。</p>
          </template>
          <p v-else class="saved-training-unavailable">当前不可练习，题目信息与入口已隐藏。</p>
        </li>
      </ol>
    </details>
    <el-pagination v-if="total > 10" small layout="prev, pager, next" :total="total" :page-size="10" :current-page="page" :disabled="busy || loading" @current-change="pageChanged" />
  </section>
</template>
<script>
import api from '@/common/api';
import { responseData, errorMessage, requestId } from '@/common/objective';
export default {
  name: 'SavedTrainingPlans',
  props: { uid: { type: String, required: true }, refresh: { type: Number, default: 0 } },
  data: () => ({ state: 'ACTIVE', page: 1, total: 0, items: [], error: '', loading: false, busy: false, request: 0, starting: null, startIds: {}, tabs: [{ value: 'ACTIVE', label: '进行中' }, { value: 'ARCHIVED', label: '已归档' }], statuses: { SOLVED: '有通过记录', ATTEMPTED: '有练习记录', NO_RECORD: '暂无记录' } }),
  computed: {
    owner() { return this.$store.getters.isAuthenticated && this.$store.getters.userInfo.uid === this.uid; },
    identity() { return this.uid + ':' + (this.$store.getters.userInfo.uid || '') + ':' + this.$store.getters.isAuthenticated + ':' + this.$store.getters.authEpoch; },
  },
  watch: {
    identity: { immediate: true, handler() { this.request++; this.items = []; this.total = 0; this.error = ''; this.busy = false; this.loading = false; this.starting = null; this.startIds = {}; this.state = 'ACTIVE'; this.page = 1; if (this.owner) this.load(); } },
    refresh() { if (this.owner) { this.state = 'ACTIVE'; this.page = 1; this.load(); } },
  },
  beforeDestroy() { this.request++; },
  methods: {
    sourceName(source) { return { HOJ: '本站', CODEFORCES: 'Codeforces', ATCODER: 'AtCoder', NOWCODER: '牛客' }[source] || source; },
    date(value) { return new Date(value).toLocaleDateString('zh-CN'); },
    switchState(state) { if (state !== this.state && !this.busy) { this.state = state; this.page = 1; this.load(); } },
    pageChanged(page) { this.page = page; this.load(); },
    async load() {
      if (!this.owner) return;
      const token = ++this.request, identity = this.identity;
      this.loading = true; this.items = []; this.error = '';
      try {
        const result = responseData(await api.trainingPlans({ state: this.state, page: this.page }));
        if (token !== this.request || identity !== this.identity || !this.owner) return;
        this.items = result.items; this.total = result.total;
        if (!this.items.length && this.page > 1) { this.page = 1; return this.load(); }
      } catch (error) { if (token === this.request && identity === this.identity) this.error = errorMessage(error); }
      finally { if (token === this.request && identity === this.identity) this.loading = false; }
    },
    async change(plan, state, name) {
      if (!this.owner || this.busy) return;
      const identity = this.identity, token = this.request; this.busy = true; this.error = '';
      try {
        responseData(await api.updateTrainingPlan(plan.id, { state, expectedRevision: plan.revision, ...(name === undefined ? {} : { name }) }));
        if (identity === this.identity && token === this.request && this.owner) await this.load();
      } catch (error) { if (identity === this.identity && token === this.request) { this.error = errorMessage(error); const status = error.data && error.data.status || error.response && error.response.data && error.response.data.status; if (status === 403) this.items = []; } }
      finally { if (identity === this.identity) this.busy = false; }
    },
    async rename(plan) {
      const identity = this.identity;
      try {
        const result = await this.$prompt('80 字以内', '修改清单名称', { inputValue: plan.name, inputValidator: value => value && value.trim() && Array.from(value.trim()).length <= 80 || '请填写 80 字以内的名称', confirmButtonText: '保存', cancelButtonText: '取消' });
        if (identity === this.identity && this.owner) await this.change(plan, plan.state, result.value.trim());
      } catch (error) { /* A dismissed dialog has no side effects. */ }
    },
    async remove(plan) {
      const identity = this.identity;
      try { await this.$confirm('删除“' + plan.name + '”？练习记录仍会保留。', '删除训练清单', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }); if (identity === this.identity && this.owner) await this.change(plan, 'DELETED'); }
      catch (error) { /* A dismissed dialog has no side effects. */ }
    },
    async start(row) {
      if (!this.owner || this.starting !== null || this.busy) return;
      const identity = this.identity, token = this.request; this.starting = row.id;
      if (!this.startIds[row.id]) this.$set(this.startIds, row.id, requestId());
      try { const run = responseData(await api.objectiveStart({ questionId: row.questionId, requestId: this.startIds[row.id] })); if (identity === this.identity && token === this.request && this.owner) this.$router.push('/objective/run/' + run.id); }
      catch (error) { if (identity === this.identity && token === this.request) this.error = errorMessage(error); }
      finally { if (identity === this.identity) this.starting = null; }
    },
  },
};
</script>
<style scoped>
.saved-training { margin-top: 32px; padding-top: 24px; border-top: 2px solid var(--line); text-align: left; color: var(--text); }
.saved-training-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }.saved-training-header h3 { margin: 0; font-size: 18px; }.saved-training-header p, .saved-training-note { color: var(--muted); font-size: 12px; line-height: 1.8; }.saved-training-header p { margin: 6px 0 0; }
.saved-training-tabs { display: flex; gap: 20px; margin-top: 16px; border-bottom: 1px solid var(--line); }.saved-training-tabs button { background: none; border: 0; border-bottom: 2px solid transparent; padding: 10px 0; font: inherit; font-size: 13px; color: var(--muted); cursor: pointer; }.saved-training-tabs button[aria-pressed="true"] { border-color: var(--accent); color: var(--accent); font-weight: 600; }
.saved-training-note { max-width: 70em; }.saved-training-empty { color: var(--muted); font-size: 13px; padding: 16px 0; }.saved-training-plan { border-bottom: 1px solid var(--line); padding: 16px 0; }.saved-training-plan summary { display: list-item; cursor: pointer; line-height: 1.8; }.saved-training-name { font-weight: 600; overflow-wrap: anywhere; }.saved-training-progress { color: var(--muted); font-size: 12px; margin-left: 16px; white-space: nowrap; }
.saved-training-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin: 12px 0; }.saved-training-actions>span { font-size: 11px; color: var(--muted); margin-right: auto; }.saved-training-actions .el-button { margin: 0; padding: 4px; font-size: 12px; }.saved-training-list { padding-left: 24px; margin: 0; }.saved-training-list li { padding: 14px 0 14px 6px; border-top: 1px solid var(--line); }.saved-training-list li::marker { color: var(--muted); font-size: 12px; }.saved-training-question { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }.saved-training-question a, .saved-training-question button { color: var(--accent); font: inherit; font-size: 14px; font-weight: 600; overflow-wrap: anywhere; text-align: left; }.saved-training-question button { padding: 0; background: none; border: none; cursor: pointer; }.saved-training-status { flex-shrink: 0; color: var(--muted); font-size: 11px; }.saved-training-status.passed { color: var(--accent); font-weight: 600; }.saved-training-meta { font-size: 11px; color: var(--muted); margin: 7px 0; }.saved-training-reason { font-size: 12px; line-height: 1.8; color: var(--muted); margin: 7px 0 0; overflow-wrap: anywhere; }.saved-training-unavailable { color: var(--muted); font-size: 12px; margin: 0; }.saved-training button:focus-visible, .saved-training a:focus-visible, .saved-training summary:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
@media(max-width:600px) { .saved-training-progress { display: block; margin-left: 0; }.saved-training-question { flex-wrap: wrap; }.saved-training-actions { gap: 8px; }.saved-training-actions>span { flex-basis: 100%; } }
</style>
