<template>
  <div class="cf-record-sync">
    <div class="cf-sync-controls">
      <template v-if="selected">
        <el-select v-if="accounts.length > 1" v-model="accountId" size="small" aria-label="同步的 Codeforces 账号" :disabled="loading" @change="schedule">
          <el-option v-for="account in accounts" :key="account.id" :label="account.handle" :value="account.id" />
        </el-select>
        <span v-else>{{ selected.handle }}</span>
        <el-button size="small" icon="el-icon-refresh" :loading="requesting" :disabled="loading || waiting && !scope" @click="refresh(true)">同步 CF 记录</el-button>
        <router-link :to="userHome">查看训练记录</router-link>
      </template>
      <span v-else-if="loading" role="status">正在读取关联账号</span>
      <el-button v-else-if="error" size="small" @click="refresh()">重新读取账号</el-button>
      <router-link v-else :to="userHome">关联并验证 CF 账号</router-link>
    </div>
    <CodeforcesBrowserSubmit
      v-if="browserSubmit && selected"
      :problem="problem" :account="selected" :code="code" :language="language" :disabled="disabled || loading"
      @synced="submissionSynced"
    />
    <p v-if="error" class="cf-sync-error" role="alert">{{ error }}</p>
    <p v-else-if="status" role="status">{{ status }}</p>
    <p v-if="latest" class="cf-last-submission">
      <span>本题最近一次提交</span>
      <a :href="latest.url" target="_blank" rel="noopener noreferrer">#{{ latest.id }}</a>
      <strong :class="{ accepted: latest.verdict === 'OK' }">{{ latestVerdict }}</strong>
    </p>
    <p v-else-if="!scope && selected && !loading && !waiting && selected.syncState === 'IDLE'">暂无已同步的本题提交</p>
  </div>
</template>

<script>
import api from '@/common/api';
import { codeforcesVerdictLabel } from '@/common/externalApi';
import { errorMessage, responseData } from '@/common/objective';
import CodeforcesBrowserSubmit from './CodeforcesBrowserSubmit.vue';

export default {
  name: 'CodeforcesPracticeSync',
  components: { CodeforcesBrowserSubmit },
  props: {
    problem: { type: String, required: true },
    scope: { type: Object, default: null },
    browserSubmit: Boolean,
    code: { type: String, default: '' },
    language: { type: String, default: '' },
    disabled: Boolean,
  },
  data: () => ({ accounts: [], accountId: null, loading: false, requesting: false, error: '', timer: null, disposed: false }),
  computed: {
    userHome() { return { name: 'UserHome', query: { username: this.$store.getters.userInfo.username } }; },
    selected() { return this.accounts.find(account => account.id === this.accountId); },
    latest() { return !this.scope && this.selected && this.selected.latestSubmission; },
    latestVerdict() { return this.latest && codeforcesVerdictLabel(this.latest.verdict); },
    waiting() {
      const account = this.selected;
      return !!account && (account.projectionPending || account.scheduledSoon || ['QUEUED', 'RUNNING'].includes(account.syncState));
    },
    status() {
      if (!this.selected) return '';
      if (this.requesting) return '正在请求同步 CF 记录';
      const { syncState, projectionPending, scheduledSoon, projectionAt } = this.selected;
      const progress = { QUEUED: '等待同步 CF 公开提交记录', RUNNING: '正在同步 CF 公开提交记录', BACKOFF: 'CF 暂时无法更新，系统稍后重试' }[syncState];
      if (progress) return progress;
      if (projectionPending) return '已获取 CF 记录，正在更新本站结果';
      if (scheduledSoon) return '已安排同步，完成后自动刷新结果';
      if (projectionAt) return '最近同步 ' + new Date(projectionAt).toLocaleString('zh-CN', { hour12: false });
      return this.browserSubmit ? '' : '在 CF 提交后，点「同步 CF 记录」。';
    },
  },
  created() { this.refresh(); },
  beforeDestroy() { this.disposed = true; clearTimeout(this.timer); },
  methods: {
    submissionSynced() {
      this.$emit('synced');
      this.refresh();
    },
    current(authEpoch) { return !this.disposed && authEpoch === this.$store.getters.authEpoch; },
    schedule() {
      clearTimeout(this.timer);
      if (this.waiting || this.selected && this.selected.syncState === 'BACKOFF') this.timer = setTimeout(() => this.refresh(), 10000);
    },
    async refresh(requestSync = false) {
      if (this.loading || this.disposed || requestSync && (!this.selected || this.waiting && !this.scope)) return;
      const authEpoch = this.$store.getters.authEpoch;
      clearTimeout(this.timer);
      this.loading = true;
      this.requesting = requestSync;
      this.error = '';
      try {
        const config = { hojInlineError: true, params: this.scope ? {} : { cfProblem: this.problem } };
        const previous = this.selected && this.selected.projectionAt;
        const response = requestSync
          ? await api.requestCodeforcesSync(this.accountId, { problem: this.problem, ...this.scope }, config)
          : await api.codeforcesSyncState(config);
        const result = responseData(response);
        if (!this.current(authEpoch)) return;
        this.accounts = result.accounts.filter(account => account.platform === 'CODEFORCES' && account.verified);
        if (!this.selected) this.accountId = this.accounts.length ? this.accounts[0].id : null;
        if (this.selected && !this.selected.projectionPending && this.selected.projectionAt && this.selected.projectionAt !== previous) {
          this.$emit('synced');
        }
      } catch (error) {
        if (this.current(authEpoch)) this.error = errorMessage(error);
      } finally {
        if (this.current(authEpoch)) {
          this.loading = false;
          this.requesting = false;
          this.schedule();
        }
      }
    },
  },
};
</script>

<style scoped>
.cf-record-sync { margin-top: 12px; }
.cf-sync-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; font-size: 13px; }
.cf-sync-controls .el-select { max-width: 100%; width: 10em; }
.cf-sync-controls .el-button { margin: 0; }
.cf-sync-controls > span { overflow-wrap: anywhere; }
.cf-sync-controls a { color: var(--accent, #2086a2); }
.cf-record-sync p { margin: 6px 0 0; color: var(--muted, #68828f); font-size: 12px; line-height: 1.7; }
.cf-record-sync .cf-sync-error { color: var(--danger, #b23b34); }
.cf-last-submission { display: flex; flex-wrap: wrap; gap: 0 8px; align-items: baseline; }
.cf-last-submission a { color: var(--accent, #2086a2); }
.cf-last-submission strong { color: var(--text, #294653); }
.cf-last-submission strong.accepted { color: var(--accent, #2086a2); }
</style>
