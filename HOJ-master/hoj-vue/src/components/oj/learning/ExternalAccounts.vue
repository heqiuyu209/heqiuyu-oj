<template>
  <section class="external-accounts" aria-label="跨平台训练记录" :aria-busy="loading">
    <header><div><h2>跨平台训练记录</h2><p>按来源查看训练，把同一道原题合在一起计数。</p></div><el-button size="small" icon="el-icon-refresh" :loading="loading" :disabled="!!busy" @click="load(false)">刷新记录</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="snapshot">
      <div class="external-totals" aria-label="去重后的通过题数"><span>本站公开练习 <strong>{{ snapshot.summary.localSolved }}</strong></span><span>已验证外部来源 <strong>{{ snapshot.summary.externalSolved }}</strong></span><span class="unique-total">合计去重 <strong>{{ snapshot.summary.uniqueSolved }}</strong> 题</span><small>合并 {{ snapshot.summary.duplicatesRemoved }} 条重复记录{{ snapshot.summary.complete ? '' : '，外部历史仍在补全' }}</small></div>
      <div v-if="!snapshot.accounts.length" class="external-empty">{{ owner ? '还没有关联外部账号，先选择平台并添加用户名。' : '暂未公开已验证的外部账号。' }}</div>
      <article v-for="account in snapshot.accounts" :key="account.id" class="external-source">
        <div class="source-heading"><div><span class="source-platform">{{ platformName(account.platform) }}</span><a :href="account.profileUrl" target="_blank" rel="noopener noreferrer">{{ account.displayName || account.handle }} <small v-if="account.platform === 'NOWCODER'">· ID {{ account.handle }}</small> <i class="el-icon-top-right" /></a><span class="source-verification" :class="{ verified: account.verified }">{{ account.verified ? '归属已验证' : '仅本人可见 · 待验证' }}</span></div><span class="source-state" role="status">{{ state(account) }}</span></div>
        <dl class="source-numbers"><div><dt>通过 / 尝试</dt><dd>{{ value(account.solved) }} <span>/ {{ value(account.attempted) }}</span></dd></div><div><dt>{{ ratingLabel(account.platform) }} / {{ account.platform === 'NOWCODER' ? '已载入历史最高' : '最高' }}</dt><dd>{{ value(account.rating) }} <span>/ {{ value(account.maxRating) }}</span></dd></div><div><dt>{{ account.platform === 'NOWCODER' ? '题目难度' : account.platform === 'ATCODER' ? '账号通过估计难度 P75' : '个人通过难度 P75' }}</dt><dd>{{ value(account.representativeRating) }} <small v-if="account.platform === 'NOWCODER'">暂无可靠的题目评级来源</small><small v-else>{{ account.ratedSolved }} 道{{ account.platform === 'ATCODER' ? '估计难度' : '评级' }}样本</small></dd></div><div><dt>最近完整同步</dt><dd class="source-time">{{ date(account.lastSuccessAt) }}<small v-if="account.stale">{{ account.lastSuccessAt ? '等待更新，保留已有记录' : '等待首次同步' }}</small></dd></div></dl>
        <p v-if="account.platform === 'ATCODER'" class="source-notice">评级与评级赛历史来自 AtCoder 官方；提交记录及估计难度来自第三方 <a href="https://github.com/kenkoooo/AtCoderProblems" target="_blank" rel="noopener noreferrer">AtCoder Problems</a>。估计难度不等同于官方评级；来源不提供团队身份，P75 按该账号全部通过题计算。</p>
        <p v-if="account.platform === 'ATCODER' && account.metadata" class="source-notice">题目目录：{{ date(account.metadata.problemsSyncedAt) }}{{ account.metadata.problemsStale ? '（等待更新）' : '' }}；难度模型：{{ date(account.metadata.modelsSyncedAt) }}{{ account.metadata.modelsStale ? '（等待更新）' : '' }}。无可靠难度的题目不计入样本。</p>
        <p v-if="account.platform === 'NOWCODER'" class="source-notice">编程统计来自牛客官方公开记录和评级赛历史，包含来源页提供的比赛及赛后提交；下方分别展示“比赛与赛后训练”和“专项与试卷记录”。来源难度可在能力分布的“牛客”视图查看；缺少算法分类时留空，账号记录不能证明独立作答。</p>
        <p v-if="!account.historyComplete" class="source-notice">历史记录尚未全部拉取，当前题数可能不完整。</p>
        <p v-if="owner && account.lastError" class="source-notice source-error">{{ account.lastError }}</p>
        <p v-if="owner && account.verificationNote" class="source-notice">{{ account.verificationNote }}</p>
        <div v-if="owner" class="source-actions"><el-button v-if="!account.verified" size="small" :disabled="!!busy" @click="act('externalProof', account.id)">{{ account.proofCode ? '更换校验码' : '验证账号归属' }}</el-button><el-button size="small" :disabled="!!busy" @click="act('externalSync', account.id)">请求同步</el-button><remote-credential-settings v-if="account.verified" :account="account" /><el-button type="text" :disabled="!!busy" @click="unlink(account)">解除关联</el-button></div>
        <div v-if="owner && account.proofCode" class="source-proof"><strong>在 {{ platformName(account.platform) }} 公开资料中完成一次校验</strong><p>将以下校验码追加到 {{ proofField(account.platform) }}，保存后点击“检查校验码”。验证完成后可以移除该校验码。</p><code>{{ account.proofCode }}</code><div><span>有效至 {{ date(account.proofExpiresAt) }}</span><el-button size="small" type="primary" :loading="busy === 'externalVerify:' + account.id" :disabled="!!busy" @click="act('externalVerify', account.id)">检查校验码</el-button></div><small>检查会进入后台同步队列，每个账号一分钟可请求一次。{{ account.platform === 'ATCODER' ? 'AtCoder 验证仅证明校验时的账号归属，平台未提供可核验的永久用户编号。' : '' }}</small></div>
        <now-coder-contest-context v-if="account.platform === 'NOWCODER' && account.contestContext" :context="account.contestContext" :owner="owner" />
        <now-coder-practice-context v-if="account.platform === 'NOWCODER' && account.practiceContext" :context="account.practiceContext" :owner="owner" />
        <details v-if="account.attempted !== null" class="source-details"><summary>知识方向与最近评级赛 <span><template v-if="account.individualStatusVerified !== false && account.platform === 'CODEFORCES'">{{ value(account.individualSolved) }} 道个人通过题 · </template>{{ value(account.ratedContests) }} 场评级赛</span></summary><div class="source-depth"><div class="source-coverage"><h3>个人通过的知识方向</h3><p v-if="account.coverageAvailable === false" class="source-notice">来源未提供可靠的算法分类，暂不绘制知识方向分布。</p><div v-for="axis in account.coverage" :key="axis.code"><span>{{ axis.name }}</span><meter min="0" :max="coverageMaximum(account)" :value="axis.solved">{{ axis.solved }}</meter><strong>{{ axis.solved }}</strong></div></div><div class="source-history"><h3>最近评级赛</h3><p v-if="!account.ratingHistory.length">暂无公开评级记录</p><ol v-else><li v-for="event in account.ratingHistory" :key="event.contestId"><a :href="event.contestUrl || 'https://codeforces.com/contest/' + event.contestId" target="_blank" rel="noopener noreferrer">{{ event.title }}</a><span>第 {{ event.contestRank }} 名 · {{ event.newRating }} <small>{{ event.newRating - event.oldRating >= 0 ? '+' : '' }}{{ event.newRating - event.oldRating }}</small></span></li></ol></div></div></details>
      </article>
    </template>
    <form v-if="owner" class="external-link" @submit.prevent="link"><label for="cf-account-handle">添加外部账号</label><div><select v-model="platform" aria-label="账号平台" :disabled="!!busy"><option value="CODEFORCES">Codeforces</option><option value="ATCODER">AtCoder</option><option value="NOWCODER">牛客</option></select><el-input id="cf-account-handle" v-model="handle" :placeholder="handlePlaceholder" :maxlength="platform === 'NOWCODER' ? 12 : platform === 'ATCODER' ? 32 : 64" :disabled="!!busy" /><el-button native-type="submit" type="primary" :loading="busy === 'link'" :disabled="!!busy || !handle.trim() || !snapshot">关联账号</el-button></div><small>读取公开资料，无需密码。先验证归属，再计入公开统计；每人最多关联 5 个账号。</small></form>
    <p class="external-method">按明确来源编号及已核对的目录映射合并题目，相似标题不会自动合并。比赛、团队题及套卷的站内记录不在此统计；外部账号总题数可包含团队通过，能力分布的 CF 视图只使用个人提交。P75 为相应通过样本难度的第 75 百分位，样本不足时不据此判断能力，记录不代表无提示独立完成。各平台的评级、难度分别展示，不直接换算或相加。</p>
  </section>
</template>
<script>
import api from '@/common/api';
import { responseData, errorMessage } from '@/common/objective';
import RemoteCredentialSettings from './RemoteCredentialSettings.vue';
import NowCoderContestContext from './NowCoderContestContext.vue';
import NowCoderPracticeContext from './NowCoderPracticeContext.vue';
export default {
  name: 'ExternalAccounts',
  components: { RemoteCredentialSettings, NowCoderContestContext, NowCoderPracticeContext },
  props: { uid: { type: String, required: true } },
  data: () => ({ snapshot: null, loading: false, error: '', busy: '', handle: '', platform: 'CODEFORCES', generation: 0, timer: null, disposed: false }),
  computed: {
    handlePlaceholder() { return this.platform === 'NOWCODER' ? '牛客主页中的数字用户 ID' : this.platform === 'ATCODER' ? 'AtCoder 用户名，如 chokudai' : 'CF 用户名，如 tourist'; },
    actor() { return this.$store.getters.isAuthenticated ? this.$store.getters.userInfo.uid || '' : ''; },
    owner() { return !!this.actor && this.actor === this.uid; },
    context() { return this.uid + ':' + this.actor + ':' + this.$store.getters.authEpoch; },
  },
  watch: { context: { immediate: true, handler() { this.generation++; this.busy = ''; this.handle = ''; this.platform = 'CODEFORCES'; this.load(true); } } },
  beforeDestroy() { this.disposed = true; this.generation++; clearTimeout(this.timer); },
  methods: {
    value(value) { return value === null || value === undefined ? '—' : value; },
    platformName(platform) { return ({ CODEFORCES: 'Codeforces', ATCODER: 'AtCoder', NOWCODER: '牛客' })[platform] || platform; },
    ratingLabel(platform) { return ({ CODEFORCES: 'CF Rating', ATCODER: '算法 Rating', NOWCODER: '牛客 Rating' })[platform] || 'Rating'; },
    proofField(platform) { return platform === 'NOWCODER' ? '牛客个人资料的公开简介中' : platform === 'ATCODER' ? 'AtCoder Settings → My Profile → Affiliation（所属）字段' : 'CF Settings → Social → Organization（组织）字段'; },
    date(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'; },
    state(account) { return ({ QUEUED: '等待同步', RUNNING: '正在同步', BACKOFF: '稍后重试', IDLE: account.stale ? '记录待更新' : '已同步' })[account.syncState] || '等待同步'; },
    coverageMaximum(account) { return Math.max(1, ...account.coverage.map(axis => axis.solved)); },
    schedule() {
      clearTimeout(this.timer);
      this.timer = null;
      if (this.owner && this.snapshot && this.snapshot.accounts.some(a => a.syncState !== 'IDLE' || a.contestContext && a.contestContext.syncState !== 'IDLE' || a.practiceContext && a.practiceContext.syncState !== 'IDLE')) this.timer = setTimeout(() => this.load(false), 10000);
    },
    async load(reset) {
      if (this.disposed) return;
      clearTimeout(this.timer); const generation = ++this.generation, context = this.context;
      if (reset) this.snapshot = null;
      this.loading = true; this.error = '';
      try {
        const result = responseData(await (this.owner ? api.externalAccounts() : api.externalProfile(this.uid)));
        if (generation !== this.generation || context !== this.context) return;
        this.snapshot = result;
      } catch (error) { if (generation === this.generation && context === this.context) this.error = errorMessage(error); }
      finally { if (generation === this.generation && context === this.context) { this.loading = false; this.schedule(); } }
    },
    async mutate(key, send) {
      if (this.disposed || !this.owner || this.busy) return;
      clearTimeout(this.timer); const generation = ++this.generation, context = this.context;
      this.busy = key; this.loading = false; this.error = '';
      try {
        const result = responseData(await send());
        if (generation !== this.generation || context !== this.context) return;
        this.snapshot = result;
        if (key === 'link') this.handle = '';
      } catch (error) { if (generation === this.generation && context === this.context) this.error = errorMessage(error); }
      finally { if (generation === this.generation && context === this.context) { this.busy = ''; this.schedule(); } }
    },
    link() { if (this.handle.trim()) return this.mutate('link', () => api.externalLink(this.handle.trim(), this.platform)); },
    act(method, id) { return this.mutate(method + ':' + id, () => api[method](id)); },
    async unlink(account) {
      const context = this.context;
      try { await this.$confirm('解除 ' + account.handle + ' 的关联？本站缓存的该账号记录会移除，原平台上的记录不受影响。', '解除关联', { type: 'warning' }); } catch (_) { return; }
      if (!this.disposed && context === this.context && this.snapshot && this.snapshot.accounts.some(a => a.id === account.id)) return this.act('externalUnlink', account.id);
    },
  },
};
</script>
<style scoped>
.external-accounts { scroll-margin-top: 100px; }
.external-accounts { margin: 24px 0; padding: 24px; border: 1px solid var(--line); border-radius: 12px; text-align: left; color: var(--text); }.external-accounts header { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; }.external-accounts h2 { margin: 0; font-size: 20px; }.external-accounts header p, .external-method, .external-link small { font-size: 12px; line-height: 1.8; color: var(--muted); }.external-totals { display: flex; align-items: baseline; flex-wrap: wrap; gap: 10px 24px; padding: 18px 0; border-bottom: 1px solid var(--line); font-size: 13px; color: var(--muted); }.external-totals strong { font-size: 19px; color: var(--text); }.external-totals .unique-total, .external-totals .unique-total strong { color: var(--accent); }.external-totals small { font-size: 12px; }.external-empty { color: var(--muted); padding: 25px 0; font-size: 13px; }.external-source { padding: 24px 0 18px; border-bottom: 1px solid var(--line); }.source-heading, .source-heading>div { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }.source-heading { justify-content: space-between; }.source-heading a { font-size: 16px; font-weight: 600; overflow-wrap: anywhere; }.source-heading a, .source-history a { color: var(--accent); }.source-platform, .source-verification, .source-state { font-size: 12px; color: var(--muted); }.source-platform { font-weight: 600; color: var(--text); }.verified { color: var(--accent); }.source-numbers { display: grid; grid-template-columns: 1fr 1.15fr 1.2fr 1.4fr; gap: 16px; margin: 24px 0 14px; }.source-numbers>div { min-width: 0; }dt { font-size: 12px; color: var(--muted); }dd { margin: 8px 0 0; font-size: 21px; font-weight: 600; }dd span { font-size: 14px; color: var(--muted); font-weight: 400; }dd small { display: block; font-size: 11px; color: var(--muted); font-weight: 400; margin-top: 6px; }.source-time { font-size: 12px; line-height: 1.7; }.source-notice { font-size: 12px; line-height: 1.7; color: var(--muted); }.source-error { color: var(--danger, #dd625c); }.source-actions { display: flex; flex-wrap: wrap; gap: 8px; }.source-actions .el-button { margin: 0; }.source-proof { margin: 16px 0 0; padding: 16px; background: var(--soft); border-left: 3px solid var(--accent); font-size: 13px; line-height: 1.8; }.source-proof p { max-width: 64em; margin: 8px 0; }.source-proof code { display: block; overflow-wrap: anywhere; user-select: all; padding: 8px 0; color: var(--accent); }.source-proof>div { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px; }.source-proof small { color: var(--muted); }.source-details { margin-top: 20px; font-size: 12px; }.source-details summary { cursor: pointer; padding: 8px 0; color: var(--accent); }.source-details summary span { margin-left: 12px; color: var(--muted); }.source-depth { display: grid; grid-template-columns: 1fr 1.3fr; gap: 32px; margin-top: 10px; }.source-depth>div { min-width: 0; }.source-depth h3 { font-size: 13px; margin: 10px 0 16px; }.source-coverage>div { display: grid; grid-template-columns: 5em minmax(0,1fr) 2em; align-items: center; gap: 12px; margin: 12px 0; }.source-coverage strong { text-align: right; font-weight: 500; }meter { width: 100%; height: 8px; }meter::-webkit-meter-bar { background: var(--soft); border: 0; }meter::-webkit-meter-optimum-value { background: var(--accent); }.source-history ol { list-style: none; padding: 0; max-height: 280px; overflow-y: auto; }.source-history li { border-bottom: 1px solid var(--line); padding: 9px 0; }.source-history li a { display: block; overflow-wrap: anywhere; }.source-history li span { display: block; margin-top: 6px; color: var(--muted); }.source-history small { margin-left: 8px; }.external-link { margin-top: 24px; }.external-link label { font-size: 13px; font-weight: 600; }.external-link>div { display: flex; gap: 10px; max-width: 500px; margin: 10px 0 8px; }.external-link .el-input { min-width: 0; }.external-method { border-top: 1px solid var(--line); padding-top: 16px; margin: 20px 0 0; }.source-details summary:focus-visible, a:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
@media(max-width:1000px) { .source-numbers { grid-template-columns: 1fr 1fr; }.source-depth { grid-template-columns: minmax(0,1fr); gap: 12px; } }
.external-link>div { max-width: 680px; }.external-link select { flex-shrink: 0; min-height: 40px; padding: 0 10px; border: 1px solid var(--line); border-radius: 4px; color: var(--text); background: var(--panel, var(--soft)); font: inherit; }.external-link select:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }.source-notice a { color: var(--accent); }
@media(max-width:600px) { .external-accounts { padding: 16px 12px; }.external-accounts header { flex-wrap: wrap; }.source-numbers { gap: 20px 12px; }.external-totals { gap: 12px 18px; }.external-link>div { flex-wrap: wrap; }.source-details summary span { display: block; margin: 8px 0 0; }.source-heading { align-items: flex-start; }.source-heading>div { gap: 8px; } }
</style>
