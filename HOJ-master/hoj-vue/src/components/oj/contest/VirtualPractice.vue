<template>
  <section v-if="eligible && authenticated" class="virtual-practice" aria-label="虚拟计时练习">
    <div class="virtual-practice-copy">
      <h3>虚拟计时练习 <span v-if="active" class="virtual-practice-clock" role="timer">{{ countdown }}</span></h3>
      <p v-if="active">本轮截止 {{ deadline }}。所有 AI 入口暂停，关闭页面不会暂停计时。</p>
      <p v-else-if="run">本轮计时已结束。可以继续普通赛后练习，或按原赛时长再练一轮。</p>
      <p v-else>按原比赛时长练习；开始后至截止，所有 AI 入口暂停。关闭页面不会暂停计时。</p>
      <p class="virtual-practice-note">{{ contest.mixedPaperId ? '虚拟答卷与原比赛、平时练习分别记录，不影响正式榜单或比赛 Rating。' : '提交沿用赛后练习记录；本入口暂不生成独立虚拟榜单，不更新比赛 Rating。' }}</p>
      <p v-if="error" role="alert" class="virtual-practice-error">{{ error }}</p>
    </div>
    <div class="virtual-practice-actions">
      <el-button v-if="run && run.paperRunId" size="small" type="primary" @click="openPaper">{{ active ? '继续虚拟答卷' : '查看本轮结果' }}</el-button>
      <el-button v-if="!active" size="small" :loading="starting || loading" :disabled="starting || loading" @click="start">{{ error ? '重试虚拟练习' : run ? '再练一轮' : '开始虚拟计时练习' }}</el-button>
      <el-tag v-else effect="plain">计时进行中</el-tag>
    </div>
  </section>
</template>
<script>
import api from '@/common/api';
import { responseData, errorMessage, requestId } from '@/common/objective';
export default {
  name: 'VirtualPractice',
  props: { contestId: { type: [String, Number], required: true }, contest: { type: Object, required: true } },
  data: () => ({ run: null, loading: false, starting: false, error: '', epoch: 0, nonce: null, clock: 0, receivedAt: 0 }),
  computed: {
    authenticated() { return this.$store.getters.isAuthenticated; },
    eligible() { return this.contest.status === 1; },
    identity() { return [this.contestId, this.$store.getters.userInfo.uid || '', this.authenticated, this.eligible].join(':'); },
    remaining() { return this.run ? Math.max(0, Math.ceil((this.run.endsAt - this.run.serverNow - (this.clock - this.receivedAt)) / 1000)) : 0; },
    active() { return !!this.run && this.remaining > 0; },
    countdown() { const n = this.remaining; return [Math.floor(n / 3600), Math.floor(n / 60) % 60, n % 60].map(v => String(v).padStart(2, '0')).join(':'); },
    deadline() { return this.run ? new Date(this.run.endsAt).toLocaleString() : ''; },
  },
  watch: {
    identity: { immediate: true, handler() { this.epoch++; this.run = null; this.error = ''; this.loading = false; this.starting = false; this.nonce = null; if (this.eligible && this.authenticated) this.load(); } },
    remaining(next, previous) { if (previous > 0 && next === 0) { this.notifyPolicy(); this.load(); } },
  },
  mounted() { this.timer = window.setInterval(() => { this.clock = performance.now(); }, 1000); },
  beforeDestroy() { this.epoch++; window.clearInterval(this.timer); },
  methods: {
    notifyPolicy() { window.dispatchEvent(new Event('hoj-ai-access-changed')); },
    openPaper() { if (this.run && this.run.paperRunId) this.$router.push('/contest/' + this.contestId + '/mixed/run/' + this.run.paperRunId); },
    accept(result) { this.clock = this.receivedAt = performance.now(); this.run = result.run; },
    async load() {
      if (!this.eligible || !this.authenticated || this.starting) return;
      const epoch = ++this.epoch; this.loading = true; this.error = '';
      try { const data = responseData(await api.virtualContestRun(this.contestId)); if (epoch === this.epoch) this.accept(data); }
      catch (error) { if (epoch === this.epoch) this.error = errorMessage(error); }
      finally { if (epoch === this.epoch) this.loading = false; }
    },
    async start() {
      if (!this.eligible || !this.authenticated || this.active || this.starting || this.loading) return;
      const epoch = ++this.epoch; this.starting = true; this.error = ''; if (!this.nonce) this.nonce = requestId();
      try {
        const data = responseData(await api.startVirtualContest(this.contestId, { requestId: this.nonce }));
        if (epoch !== this.epoch) return;
        this.accept(data); this.nonce = null; this.notifyPolicy();
        if (this.run && this.run.paperRunId) this.openPaper();
      } catch (error) { if (epoch === this.epoch) this.error = errorMessage(error); }
      finally { if (epoch === this.epoch) this.starting = false; }
    },
  },
};
</script>
<style scoped>
.virtual-practice { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin: 20px 0 4px; padding: 18px 0 0; border-top: 1px solid var(--line); color: var(--text); text-align: left; }
.virtual-practice-copy { min-width: 0; }.virtual-practice h3 { margin: 0 0 8px; font-size: 16px; line-height: 1.6; }.virtual-practice p { margin: 5px 0; font-size: 13px; line-height: 1.7; }.virtual-practice-clock { display: inline-block; margin-left: 12px; color: var(--accent); font-variant-numeric: tabular-nums; }.virtual-practice-note { color: var(--muted); }.virtual-practice-error { color: var(--danger, #d94b4b); }.virtual-practice-actions { display: flex; flex-wrap: wrap; gap: 8px; flex-shrink: 0; }.virtual-practice-actions .el-button { margin: 0; }
@media(max-width:600px) { .virtual-practice { flex-direction: column; align-items: flex-start; gap: 10px; }.virtual-practice-clock { display: block; margin-left: 0; } }
</style>
