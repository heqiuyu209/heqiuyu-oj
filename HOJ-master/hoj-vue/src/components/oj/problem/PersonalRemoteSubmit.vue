<template>
  <section class="personal-remote-submit" aria-label="远程提交">
    <div class="remote-heading">
      <strong><i class="el-icon-connection" /> {{ platformName }} 提交</strong>
      <el-radio-group v-if="!isNowCoder" v-model="mode" size="mini" :disabled="busy" @change="modeChanged">
        <el-radio-button label="personal">本人账号</el-radio-button>
        <el-radio-button label="public">公共账号</el-radio-button>
      </el-radio-group>
    </div>
    <p v-if="mode === 'public'" class="remote-note">使用站点提供的远程判题账号，点击下方提交按钮。</p>
    <template v-else>
      <div v-if="!actor" class="remote-empty"><span>登录后，使用自己的平台账号提交。</span><el-button size="small" @click="login">登录</el-button></div>
      <template v-else>
        <el-alert v-if="error" :title="error" type="warning" :closable="false" class="remote-error" />
        <div class="remote-fields">
          <label>平台账号
            <el-select v-model="accountId" :loading="loadingAccounts" :disabled="busy" placeholder="选择已关联账号" size="small" @change="accountChanged">
              <el-option v-for="account in accounts" :key="account.id" :label="account.handle + (account.authorized ? '' : account.ownershipVerified ? '（待授权）' : '（待验证归属）')" :value="account.id" />
            </el-select>
          </label>
          <label>源站语言
            <el-select v-model="languageId" :loading="loadingLanguages" :disabled="busy || !offerId" placeholder="先读取源站语言" size="small">
              <el-option v-for="(label, id) in languages" :key="id" :label="label" :value="id" />
            </el-select>
          </label>
        </div>
        <div class="remote-actions">
          <div class="remote-tools">
            <el-button size="small" :loading="loadingLanguages" :disabled="busy || !selected || !selected.authorized" @click="loadLanguages">读取语言</el-button>
            <RemoteCredentialSettings v-if="selected && selected.ownershipVerified" :key="selected.id" :account="selected" @changed="refreshAccounts" />
            <router-link :to="{ name: 'UserHome', query: { username: $store.getters.userInfo.username } }">关联与验证账号</router-link>
            <el-button v-if="!accounts.length && !loadingAccounts" type="text" size="small" @click="refreshAccounts">刷新</el-button>
          </div>
          <el-button type="primary" size="small" :loading="busy" :disabled="!canSubmit" @click="submit">{{ submissionSupported === false ? '提交暂未开放' : pending && !submitId ? '按原请求重试' : '提交到 ' + platformName }}</el-button>
        </div>
        <p v-if="isNowCoder && submissionSupported === false" class="remote-note remote-preflight">牛客提交当前暂未开放，可先核对账号与源站语言。</p>
        <p v-else-if="isNowCoder" class="remote-note remote-preflight">牛客使用本人账号和源站语言提交。若源站是否受理尚未确认，本次提交会保留待核对，请先核对状态。</p>
        <p class="remote-note">源站语言用于本次提交；下方语言用于代码高亮和本站自测。</p>
        <div v-if="pending || task" class="remote-progress" role="status" aria-live="polite">
          <div><strong>{{ progressLabel }}</strong><span v-if="submitId" class="remote-id">#{{ submitId }}</span></div>
          <p v-if="task && task.error_code">{{ taskError }}</p>
          <p v-else-if="pending && !submitId">提交尚未确认，请先核对原请求。重试会沿用同一个请求编号。</p>
          <div class="remote-progress-actions">
            <el-button type="text" size="small" :disabled="busy" @click="check">核对状态</el-button>
            <el-button v-if="task && task.state === 'NEEDS_REVIEW'" type="text" size="small" :disabled="busy" @click="reconcile">核对源站结果</el-button>
            <el-button v-if="task && Number(task.submit_attempts) === 0 && ['QUEUED', 'PREPARING'].includes(task.state)" type="text" size="small" :disabled="busy" @click="cancel">取消本次提交</el-button>
          </div>
        </div>
      </template>
    </template>
  </section>
</template>
<script>
import api from '@/common/api';
import RemoteCredentialSettings from '@/components/oj/learning/RemoteCredentialSettings.vue';
import { responseData, errorMessage, requestId } from '@/common/objective';
import { readPreference, savePreference } from '@/common/appearance';

const terminal = ['COMPLETED', 'FAILED', 'CANCELLED'];
// This local hint detects an accidentally changed draft. The server always checks the full SHA-256 payload.
const draftHint = code => { let a = 2166136261, b = 5381; for (let i = 0; i < code.length; i++) { a = Math.imul(a ^ code.charCodeAt(i), 16777619); b = Math.imul(b, 33) ^ code.charCodeAt(i); } return code.length + ':' + (a >>> 0) + ':' + (b >>> 0); };
export default {
  name: 'PersonalRemoteSubmit',
  components: { RemoteCredentialSettings },
  props: { scope: { type: Object, required: true }, code: { type: String, default: '' }, sourceProblem: { type: String, default: '' }, disabled: Boolean },
  data: () => ({ mode: 'personal', submissionSupported: null, accounts: [], accountId: null, languages: {}, languageId: '', offerId: '', error: '', busy: false, loadingAccounts: false, loadingLanguages: false, pending: null, submitId: null, task: null, verdict: null, resultHidden: false, epoch: 0, languageEpoch: 0, accountEpoch: 0, statusEpoch: 0, timer: null, disposed: false }),
  computed: {
    actor() { return this.$store.getters.isAuthenticated ? this.$store.getters.userInfo.uid || '' : ''; },
    context() { return JSON.stringify([this.actor, this.scope.pid, this.scope.cid || 0, this.scope.tid || 0, this.scope.gid || 0]); },
    storageKey() { return 'personal-remote:' + this.context; },
    isNowCoder() { return this.sourceProblem.startsWith('NC-'); },
    platformName() { return this.isNowCoder ? '牛客' : this.sourceProblem.startsWith('AC-') ? 'AtCoder' : 'Codeforces'; },
    selected() { return this.accounts.find(account => account.id === this.accountId); },
    activeTask() { return this.task && !terminal.includes(this.task.state); },
    canSubmit() { return !!(this.submissionSupported === true && this.actor && !this.disabled && !this.busy && !this.loadingLanguages && this.selected && this.selected.authorized && this.offerId && this.languageId && this.code.trim() && !this.activeTask && (!this.pending || this.submitId || this.pending.accountId === this.accountId && this.pending.languageId === this.languageId && this.pending.draft === draftHint(this.code))); },
    progressLabel() {
      if (!this.task) return '等待确认提交';
      if (this.task.state === 'COMPLETED') return this.resultHidden ? '判题完成，结果按比赛规则开放' : ({ 0: 'Accepted', '-1': 'Wrong Answer', '-2': 'Compile Error', 1: 'Time Limit Exceeded', 2: 'Memory Limit Exceeded', 3: 'Runtime Error', 4: 'System Error' }[this.verdict] || '判题完成');
      return { QUEUED: '已排队', PREPARING: '正在准备提交', SUBMITTING: '正在提交到源站', RECONCILING: '正在核对源站是否受理', POLLING: '等待源站判题', NEEDS_REVIEW: '需要核对源站结果', FAILED: '提交前已停止', CANCELLED: '本次提交已取消' }[this.task.state] || '正在核对状态';
    },
    taskError() { return { AUTH_REQUIRED: '源站会话已失效，请重新登录并授权，再核对结果。', CREDENTIAL_CHANGED: '会话授权已变化，请核实当前授权后核对结果。', ACCOUNT_REVOKED: '账号关联已变化，请重新关联并验证同一个源站账号。', SOURCE_BLOCKED: '源站要求验证，请在源站完成验证并重新授权。', LANGUAGE_CHANGED: '源站语言已变化，请重新读取语言后再提交。', AMBIGUOUS_RESULT: '找到多个可能的源站提交，暂未计入本站成绩。', SOURCE_CHANGED: '题目来源或提交版本已变化，本次结果不会覆盖新的记录。', READ_UNAVAILABLE: '暂时无法读取源站结果，系统会退避重试；停止后可手动核对。', POST_UNCERTAIN: '源站是否受理尚未确认；系统只查询已有结果，不会重复投递代码。缺少可靠任务标识时，需要进一步核查。', MANUAL_REVIEW: '源站格式发生变化，需要核查后才能继续。' }[this.task.error_code] || '本次提交已停止，请核对账号授权或源站状态。'; },
  },
  watch: { context: { immediate: true, handler() { this.reset(); } } },
  beforeDestroy() { this.disposed = true; this.epoch++; clearTimeout(this.timer); },
  methods: {
    current(epoch, context) { return !this.disposed && epoch === this.epoch && context === this.context; },
    modeChanged() { this.$emit('active', this.mode === 'personal'); },
    login() { this.$store.dispatch('changeModalStatus', { visible: true }); },
    reset() {
      this.epoch++; this.languageEpoch++; this.accountEpoch++; this.statusEpoch++; clearTimeout(this.timer);
      this.submissionSupported = null; this.accounts = []; this.accountId = null; this.languages = {}; this.languageId = ''; this.offerId = ''; this.error = ''; this.busy = false; this.loadingAccounts = false; this.loadingLanguages = false; this.task = null; this.submitId = null; this.pending = null; this.verdict = null; this.resultHidden = false;
      this.modeChanged();
      if (this.actor) { const saved = readPreference(this.storageKey, null); if (saved && saved.requestId) { this.pending = saved; this.submitId = saved.submitId || null; this.check(); } this.refreshAccounts(); }
    },
    accountChanged() { this.languageEpoch++; this.loadingLanguages = false; this.offerId = ''; this.languages = {}; this.languageId = ''; this.error = ''; },
    async refreshAccounts() {
      const epoch = this.epoch, context = this.context, request = ++this.accountEpoch; this.loadingAccounts = true;
      try { const data = responseData(await api.personalRemoteAccounts(this.scope)); if (!this.current(epoch, context) || request !== this.accountEpoch) return;
        this.submissionSupported = data.submissionSupported !== false; this.accounts = data.accounts; if (!this.accounts.some(item => item.id === this.accountId)) { this.accountId = this.pending && this.accounts.some(item => item.id === this.pending.accountId) ? this.pending.accountId : this.accounts.length ? this.accounts[0].id : null; this.accountChanged(); }
        else this.accountChanged();
      } catch (error) { if (this.current(epoch, context) && request === this.accountEpoch) this.error = errorMessage(error); }
      finally { if (this.current(epoch, context) && request === this.accountEpoch) this.loadingAccounts = false; }
    },
    async loadLanguages() {
      if (this.busy || this.loadingLanguages || !this.selected || !this.selected.authorized) return;
      const epoch = this.epoch, context = this.context, request = ++this.languageEpoch, accountId = this.accountId;
      this.loadingLanguages = true; this.error = ''; this.offerId = ''; this.languages = {}; this.languageId = '';
      try { const data = responseData(await api.personalRemoteOptions({ ...this.scope, accountId })); if (!this.current(epoch, context) || request !== this.languageEpoch || accountId !== this.accountId) return;
        this.submissionSupported = data.submissionSupported !== false; this.offerId = data.offerId; this.languages = data.languages;
        this.languageId = this.pending && data.languages[this.pending.languageId] ? this.pending.languageId : Object.keys(data.languages)[0] || '';
      } catch (error) { if (this.current(epoch, context) && request === this.languageEpoch) this.error = errorMessage(error); }
      finally { if (this.current(epoch, context) && request === this.languageEpoch) this.loadingLanguages = false; }
    },
    remember() { savePreference(this.storageKey, this.pending ? { ...this.pending, submitId: this.submitId } : null); },
    async submit() {
      if (!this.canSubmit) return;
      const epoch = this.epoch, context = this.context;
      if (!this.pending || this.submitId) { this.pending = { requestId: requestId(), accountId: this.accountId, languageId: this.languageId, draft: draftHint(this.code) }; this.submitId = null; this.task = null; }
      this.statusEpoch++; clearTimeout(this.timer); this.remember(); this.busy = true; this.error = '';
      const input = { ...this.scope, accountId: this.pending.accountId, languageId: this.pending.languageId, requestId: this.pending.requestId, offerId: this.offerId, code: this.code };
      try { const data = responseData(await api.personalRemoteSubmit(input)); if (!this.current(epoch, context)) return; this.apply(data); this.check(); }
      catch (error) { if (this.current(epoch, context)) {
        this.error = errorMessage(error);
        const status = error && (error.data && error.data.status || error.response && error.response.data && error.response.data.status || error.response && error.response.status);
        if ([400, 403].includes(status) && !this.submitId) { this.pending = null; this.remember(); }
        else this.schedule();
      } }
      finally { if (this.current(epoch, context)) this.busy = false; }
    },
    apply(data) { const finished = this.task && terminal.includes(this.task.state); this.submitId = data.submitId; this.task = data.task; this.verdict = data.verdict; this.resultHidden = !!data.resultHidden; this.remember(); if (!finished && terminal.includes(this.task.state)) this.$emit('finished', data); },
    schedule() { clearTimeout(this.timer); if (this.pending && (!this.task || !terminal.includes(this.task.state) && this.task.state !== 'NEEDS_REVIEW')) this.timer = setTimeout(() => this.check(), 3000); },
    async check() {
      if (!this.pending) return;
      const epoch = this.epoch, context = this.context, request = this.pending.requestId, id = this.submitId, statusEpoch = ++this.statusEpoch;
      clearTimeout(this.timer);
      try { const data = responseData(await (id ? api.personalRemoteStatus(id) : api.personalRemoteRequest(request))); if (!this.current(epoch, context) || statusEpoch !== this.statusEpoch || !this.pending || this.pending.requestId !== request) return; this.apply(data); }
      catch (error) { if (this.current(epoch, context) && statusEpoch === this.statusEpoch) this.error = errorMessage(error); }
      finally { if (this.current(epoch, context) && statusEpoch === this.statusEpoch && this.pending && this.pending.requestId === request) this.schedule(); }
    },
    async reconcile() { await this.action('reconcile'); },
    async cancel() { await this.action('cancel'); },
    async action(action) {
      if (this.busy || !this.submitId) return;
      const epoch = this.epoch, context = this.context; this.statusEpoch++; clearTimeout(this.timer); this.busy = true; this.error = '';
      try { const data = responseData(await (action === 'cancel' ? api.personalRemoteCancel(this.submitId) : api.personalRemoteReconcile(this.submitId))); if (this.current(epoch, context)) { this.apply(data); this.check(); } }
      catch (error) { if (this.current(epoch, context)) this.error = errorMessage(error); }
      finally { if (this.current(epoch, context)) this.busy = false; }
    },
  },
};
</script>
<style scoped>
.personal-remote-submit { border-bottom: 1px solid var(--line, #dce5eb); scroll-margin-top: 90px; padding: 4px 0 18px; margin-bottom: 18px; color: var(--text, #294653); }
.remote-heading,.remote-actions,.remote-tools,.remote-progress-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.remote-heading,.remote-actions { justify-content: space-between; }
.remote-heading strong { font-size: 15px; }
.remote-fields { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr); gap: 14px; margin: 16px 0 12px; }
.remote-fields label { display: grid; gap: 6px; font-size: 13px; color: var(--muted, #68828f); }
.remote-fields .el-select { width: 100%; min-width: 0; }
.personal-remote-submit /deep/ .el-input.is-disabled .el-input__inner { background: var(--soft); color: var(--muted); border-color: var(--line); }
.remote-tools a { font-size: 13px; color: var(--accent, #2086a2); }
.remote-note,.remote-progress p { margin: 10px 0 0; font-size: 12px; line-height: 1.7; color: var(--muted, #68828f); }
.remote-error { margin-top: 12px; }
.remote-progress { margin-top: 14px; padding: 12px 14px; border-left: 3px solid var(--accent, #2086a2); background: var(--soft, #f1f7fa); border-radius: 0 6px 6px 0; }
.remote-id { margin-left: 12px; color: var(--muted, #68828f); font-size: 12px; }
.remote-empty { display: flex; justify-content: space-between; align-items: center; padding-top: 14px; font-size: 13px; }
@media(max-width: 680px) { .remote-fields { grid-template-columns: 1fr; gap: 10px; } .remote-actions { align-items: stretch; } .remote-actions > .el-button { width: 100%; } .remote-heading { gap: 12px; } }
</style>
