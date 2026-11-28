<template>
  <div class="cf-browser-submit">
    <el-button type="primary" size="small" data-hoj-cf-submit :loading="busy" :disabled="disabled || reading || !code.trim() || active && !canRetry"
      @click="submit">{{ canRetry ? '重新连接 HOJCom' : '通过 HOJCom 提交' }}</el-button>
    <span v-if="message" class="cf-submit-message" role="alert">{{ message }}</span>
    <div v-if="task" class="cf-submit-status" role="status" aria-live="polite">
      {{ taskLabel }}
      <a v-if="sourceUrl" :href="sourceUrl" target="_blank" rel="noopener noreferrer">CF #{{ task.remoteId }}</a>
      <router-link v-if="task.submitId" :to="{ name: 'SubmissionDetails', params: { submitID: task.submitId } }">本站记录 #{{ task.submitId }}</router-link>
      <a v-if="task.state === 'NEEDS_REVIEW' && !sourceUrl" :href="historyUrl" target="_blank" rel="noopener noreferrer">查看 CF 提交记录</a>
      <el-button v-if="active" type="text" size="mini" :disabled="reading || busy" @click="refresh(true)">刷新状态</el-button>
    </div>
  </div>
</template>

<script>
import api from '@/common/api';
import { codeforcesVerdictLabel } from '@/common/externalApi';
import { errorMessage, requestId, responseData } from '@/common/objective';

const states = {
  QUEUED: '等待 HOJCom 接收任务', CLAIMED: '正在准备 CF 提交',
  SENT: '正在确认 CF 是否收到代码', VERIFYING: '已取得 CF 提交编号，正在核对判定',
  COMPLETED: 'CF 判题完成',
};
const errors = {
  LOGIN_REQUIRED: '请先在浏览器登录 Codeforces。',
  ACCOUNT_MISMATCH: '请在 CF 登录与 HOJ 绑定的账号。',
  SOURCE_UNAVAILABLE: '无法打开 CF 提交页。',
  SOURCE_ACCOUNT_UNREADABLE: '无法确认当前 CF 登录账号，请查看原站页面。',
  SOURCE_FORM_UNAVAILABLE: 'CF 提交表单未就绪，请查看原站页面。',
  SOURCE_HISTORY_UNAVAILABLE: '暂时无法读取 CF 提交记录。',
  LANGUAGE_UNAVAILABLE: 'CF 提交页没有所选的 C++ 标准。',
  BRIDGE_FAILED: 'HOJCom 无法准备这次提交。',
  EXPIRED: '这次提交准备超时。',
  RECEIPT_UNKNOWN: '未能核对 CF 提交编号，请先查看 CF 记录。',
  RECEIPT_MISMATCH: 'CF 记录与本次提交信息不一致。',
  RESULT_UNAVAILABLE: '暂时无法同步 CF 判定。',
  ACCOUNT_CHANGED: 'HOJ 绑定的 CF 账号已变更。',
  PROBLEM_CHANGED: 'HOJ 题库里的 CF 题号已变更。',
};
const pending = ['QUEUED', 'CLAIMED', 'SENT', 'VERIFYING'];
const channel = 'hoj-codeforces-submit-v1';
const bridgeErrors = {
  HOJ_NOT_CONNECTED: '请在 HOJCom 设置中连接当前 HOJ 站点。',
  USER_CLICK_REQUIRED: 'HOJCom 没有收到本次点击，请重新点击提交。',
  INTENT_EXPIRED: '本次连接已超时。',
  INVALID_TASK: 'HOJCom 无法读取本次任务。',
  CLAIM_FAILED: 'HOJCom 暂未确认本次任务。',
  CLAIM_IN_PROGRESS: 'HOJCom 正在接收本次任务。',
};

function askExtension(action, data = {}) {
  return new Promise(resolve => {
    const nonce = requestId();
    const origin = window.location.origin;
    const end = result => {
      clearTimeout(timer);
      window.removeEventListener('message', receive);
      resolve(result);
    };
    function receive(event) {
      const payload = event.data;
      if (event.source === window && event.origin === origin && payload && payload.channel === channel &&
          payload.direction === 'extension' && payload.nonce === nonce) end(payload.result);
    }
    const timer = setTimeout(() => end(null), action === 'start' ? 10000 : 3000);
    window.addEventListener('message', receive);
    window.postMessage({ channel, direction: 'page', action, nonce, ...data }, origin);
  });
}

export default {
  name: 'CodeforcesBrowserSubmit',
  props: {
    problem: { type: String, required: true },
    account: { type: Object, required: true },
    code: { type: String, required: true },
    language: { type: String, required: true },
    disabled: Boolean,
  },
  data: () => ({ task: null, busy: false, reading: false, message: '', timer: null, disposed: false, attempt: null, handoff: null }),
  computed: {
    active() { return this.task && pending.includes(this.task.state); },
    draft() { return { code: this.code, language: this.language, accountId: this.account.id, problem: this.problem }; },
    canRetry() {
      return !!(this.task && this.task.state === 'QUEUED' && this.handoff && this.handoff.retry &&
        this.handoff.id === String(this.task.id) && Object.keys(this.draft).every(key => this.handoff.draft[key] === this.draft[key]));
    },
    taskLabel() {
      const { state, error, verdict, sendStarted } = this.task;
      if (state === 'COMPLETED' && verdict) return codeforcesVerdictLabel(verdict);
      if (state === 'FAILED') {
        if (error === 'DUPLICATE_CODE') return 'CF 已拒绝相同代码的重复提交。';
        const status = sendStarted === false ? '代码未发往 CF。' : '提交未完成。';
        return status + (errors[error] || '');
      }
      return errors[error] || states[state] || '请查看 CF 提交记录。';
    },
    contest() { return /^CF-([1-9][0-9]*)(?=[a-z])/i.exec(this.problem); },
    historyUrl() { return this.contest ? 'https://codeforces.com/contest/' + this.contest[1] + '/my' : 'https://codeforces.com/'; },
    sourceUrl() {
      return this.contest && this.task && /^[0-9]+$/.test(this.task.remoteId || '')
        ? 'https://codeforces.com/contest/' + this.contest[1] + '/submission/' + this.task.remoteId : '';
    },
  },
  created() { this.refresh(); },
  beforeDestroy() { this.disposed = true; clearTimeout(this.timer); },
  methods: {
    remember(task) {
      const previous = this.task;
      this.task = task;
      if (this.handoff && (!task || task.state !== 'QUEUED' || this.handoff.id !== String(task.id))) {
        if (this.handoff.retry) this.message = '';
        this.handoff = null;
      }
      if (task && task.state === 'COMPLETED' && (!previous || previous.state !== task.state || previous.id !== task.id)) this.$emit('synced');
    },
    schedule() {
      clearTimeout(this.timer);
      if (!this.disposed && this.active) this.timer = setTimeout(() => this.refresh(), 5000);
    },
    async refresh(manual = false) {
      if (this.reading || this.busy || this.disposed) return;
      this.reading = true;
      try {
        const response = this.task ? await api.codeforcesBrowserSubmission(this.task.id) : await api.latestCodeforcesBrowserSubmission(this.problem);
        if (!this.disposed) this.remember(responseData(response));
      } catch (error) {
        if (manual && !this.disposed) this.message = errorMessage(error);
      } finally {
        this.reading = false;
        this.schedule();
      }
    },
    async handOver(attempt, permit) {
      const ack = await askExtension('start', { id: attempt.id, claim: attempt.claim, permit });
      if (this.disposed) return;
      if (ack && ack.ok) {
        this.handoff = null;
        await this.readHandoff(attempt.id);
        return;
      }
      attempt.retry = true;
      this.message = ((ack && bridgeErrors[ack.reason]) || 'HOJCom 暂未响应。') + '可重新连接本次任务。';
      await this.readHandoff(attempt.id);
    },
    async readHandoff(id) {
      const response = await api.codeforcesBrowserSubmission(id);
      if (!this.disposed) this.remember(responseData(response));
    },
    async submit() {
      if (this.disposed || this.disabled || this.busy || this.reading || this.active && !this.canRetry || !this.code.trim()) return;
      this.message = '';
      if (!/^(?:GNU\s+)?(?:C|G)\+\+\s*(17|20|23)\b/i.test(this.language)) {
        this.message = '请选择 C++17、C++20 或 C++23。';
        return;
      }
      this.busy = true;
      try {
        const extension = await askExtension('prepare');
        if (this.disposed) return;
        if (!extension || !extension.ok || !extension.permit) {
          this.message = (extension && bridgeErrors[extension.reason]) ||
            'HOJCom 暂未响应。请重新加载扩展，再刷新题页。';
          return;
        }
        if (extension.submitMode !== 'background') {
          this.message = '当前 HOJCom 不支持后台提交。请更新扩展，再刷新题页。';
          return;
        }
        if (this.canRetry) {
          const handingOver = this.handoff;
          await this.readHandoff(handingOver.id);
          if (!this.disposed && this.canRetry) await this.handOver(handingOver, extension.permit);
          return;
        }
        const draft = this.draft;
        if (!this.attempt || Object.keys(draft).some(key => this.attempt[key] !== draft[key])) {
          const bytes = window.crypto.getRandomValues(new Uint8Array(32));
          const claim = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
          this.attempt = { ...draft, claim, requestId: requestId() };
        }
        const task = responseData(await api.createCodeforcesBrowserSubmission(this.attempt));
        const claim = this.attempt.claim;
        this.attempt = null;
        if (this.disposed) return;
        this.remember(task);
        if (task.state !== 'QUEUED') return;
        this.handoff = { id: String(task.id), claim, draft, retry: false };
        await this.handOver(this.handoff, extension.permit);
      } catch (error) {
        if (!this.disposed) this.message = errorMessage(error);
        if (error.data && error.data.status) this.attempt = null;
      } finally {
        this.busy = false;
        this.schedule();
      }
    },
  },
};
</script>

<style scoped>
.cf-browser-submit { margin: 12px 0 0; }
.cf-submit-message, .cf-submit-status { display: block; margin-top: 6px; font-size: 13px; line-height: 1.55; }
.cf-submit-message { color: var(--danger, #b23b34); }
.cf-submit-status a, .cf-submit-status button { margin-left: 8px; }
</style>
