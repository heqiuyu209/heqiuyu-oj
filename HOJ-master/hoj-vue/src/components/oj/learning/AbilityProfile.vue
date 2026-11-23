<template>
  <section class="ability-profile" :class="{ 'is-compact': compact }" v-loading="loading" aria-label="知识点能力分布">
    <header><div><h2>知识点能力分布</h2><p>从实际作答看训练覆盖与已通过难度</p></div><div class="ability-modes" role="group" aria-label="图表指标"><button v-for="item in modes" :key="item.value" type="button" :disabled="item.value === 'difficulty' && !!(profile && profile.coverageOnly)" :aria-pressed="mode === item.value" @click="mode = item.value">{{ item.label }}</button></div></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button type="text" @click="load">重新加载</el-button></el-alert>
    <template v-else-if="profile">
      <div class="ability-sources" role="group" aria-label="画像数据来源"><button v-for="item in sourceOptions" :key="item.value" type="button" :aria-pressed="source === item.value" @click="selectSource(item.value)">{{ item.label }}</button></div>
      <p class="ability-scope" role="status">{{ scopeDescription }}<span v-if="externalSource && !profile.verifiedAccounts"> · 暂无已验证账号</span><span v-else-if="externalSource && !profile.historyComplete"> · 历史仍在补全</span><span v-else-if="['RUNNING', 'QUEUED'].includes(profile.syncState)"> · 外部记录正在同步，统计尚未核对完成</span><span v-else-if="profile.syncState === 'BACKOFF'"> · 来源同步稍后重试，当前展示缓存</span><span v-else-if="profile.stale"> · 数据待刷新</span></p>
      <div v-if="!hasEvidence" class="ability-empty"><i class="el-icon-data-analysis" /><strong>还没有可用于此视图的练习记录</strong><p>完成公开单题练习，或在本页关联并验证外部账号后，这里会显示已同步的证据。本站比赛与整卷成绩另行记录。</p><router-link to="/problem">前往题库练习</router-link></div>
      <template v-else>
        <div class="ability-overview"><span>编程通过 <strong>{{ profile.overall.solved }}</strong> / {{ profile.overall.attempted }} 题</span><span v-if="supportsConcepts">客观题答对 <strong>{{ profile.overall.objectiveSolved }}</strong> / {{ profile.overall.objectiveAttempted }} 题</span><span v-if="!profile.coverageOnly">有难度数据的通过题 <strong>{{ profile.overall.ratedSolved }}</strong> 题</span><span v-if="!profile.coverageOnly && profile.overall.representativeRating !== null">通过难度 P75 <strong>{{ profile.overall.representativeRating }}</strong></span></div>
        <p v-if="!profile.coverageAvailable" class="ability-unmapped">这些记录尚无可用的算法分类，因此暂不绘制知识方向分布。已通过题数与来源难度仍按实际数据保留，不用零分表示能力。</p>
        <div v-else class="ability-content">
          <figure>
            <svg viewBox="0 0 480 410" role="img" :aria-label="chartLabel">
              <title>{{ chartLabel }}</title>
              <polygon v-for="ring in 4" :key="'ring' + ring" :points="ringPoints(ring / 4)" class="radar-ring" />
              <g v-for="(axis, index) in profile.axes" :key="'axis-' + axis.code">
                <line x1="240" y1="200" :x2="point(index, 1).x" :y2="point(index, 1).y" class="radar-spoke" />
                <text :x="point(index, 1.27).x" :y="point(index, 1.27).y" text-anchor="middle" class="axis-label">{{ axis.name }}<tspan :x="point(index, 1.27).x" dy="17" class="axis-value">{{ value(axis) === null ? '暂无评级' : value(axis) + (mode === 'coverage' ? ' 题' : '') }}</tspan></text>
              </g>
              <text v-for="ring in 4" :key="'scale' + ring" x="246" :y="200 - 130 * ring / 4" class="scale-label">{{ maximum * ring / 4 }}</text>
              <polygon v-if="complete" :points="dataPoints" class="radar-area" />
              <path v-else :d="knownSegments" class="radar-path" />
              <template v-for="(axis, index) in profile.axes"><circle v-if="value(axis) !== null" :key="'value-' + axis.code" :cx="point(index, value(axis) / maximum).x" :cy="point(index, value(axis) / maximum).y" r="4" class="radar-point"><title>{{ axis.name }}：{{ value(axis) }}{{ mode === 'coverage' ? ' 题' : '' }}</title></circle></template>
            </svg>
            <figcaption>{{ mode === 'difficulty' ? '取当前来源已通过编程题难度的第 75 百分位；缺少数据处留空。' : '仅统计已归入知识方向的不同通过题；同题重复通过只计一次。' }}</figcaption>
          </figure>
          <div class="ability-evidence"><h3>各方向的作答依据</h3><div class="ability-table-wrap"><table><thead><tr><th>知识方向</th><th>编程通过 / 尝试</th><th v-if="!profile.coverageOnly">难度 P75</th><th v-if="supportsConcepts">客观题答对 / 作答</th></tr></thead><tbody><tr v-for="axis in profile.axes" :key="axis.code"><th scope="row">{{ axis.name }}</th><td>{{ axis.solved }} / {{ axis.attempted }}</td><td v-if="!profile.coverageOnly">{{ axis.representativeRating === null ? '—' : axis.representativeRating }}<small>{{ confidence(axis) }}</small></td><td v-if="supportsConcepts">{{ axis.objectiveSolved }} / {{ axis.objectiveAttempted }}</td></tr></tbody></table></div></div>
        </div>
      </template>
      <p class="ability-method">{{ methodDescription }} {{ profile.overall.unmappedSolved }} 道通过题尚未归入知识方向。<template v-if="!profile.coverageOnly">{{ profile.overall.unratedProgrammingSolved }} 道编程通过题暂无难度数据。</template>一题可属于多个方向；样本少时不据此判断强弱，现有记录不能证明未使用提示。</p>
    </template>
  </section>
</template>
<script>
import api from '@/common/api';
import { responseData, errorMessage } from '@/common/objective';
export default {
  name: 'AbilityProfile',
  props: { uid: { type: String, required: true }, compact: Boolean },
  data: () => ({ bundle: null, source: 'ALL', loading: false, error: '', request: 0, mode: 'coverage', modes: [{ value: 'difficulty', label: '通过难度' }, { value: 'coverage', label: '编程覆盖' }] }),
  computed: {
    profile() { const view = this.bundle && (this.source === 'ALL' ? this.bundle.combined : this.bundle.sources.find(item => item.source === this.source)); return view ? { ...view, uid: this.bundle.uid } : null; },
    sourceOptions() { return [{ value: 'ALL', label: '综合覆盖' }, { value: 'HOJ', label: '本站' }, { value: 'CODEFORCES', label: 'Codeforces' }, { value: 'ATCODER', label: 'AtCoder' }, { value: 'NOWCODER', label: '牛客' }]; },
    externalSource() { return !['ALL', 'HOJ'].includes(this.source); },
    supportsConcepts() { return ['ALL', 'HOJ'].includes(this.source); },
    scopeDescription() { return { ALL: '按明确题源编号合并覆盖，各平台难度分别查看', HOJ: '本站题目 Rating · 公开单题练习', CODEFORCES: 'Codeforces 题目 Rating · 已验证账号的个人提交', ATCODER: 'AtCoder Problems 难度估计 · 已验证账号记录', NOWCODER: '牛客来源 Rating · 已验证账号的公开编程记录' }[this.source]; },
    methodDescription() { return this.source === 'ALL' ? '综合图按明确来源题号及已核对的目录映射去重，合并 ' + this.profile.overall.duplicatesRemoved + ' 条来源重复记录；CF 只纳入个人提交，AtCoder 与牛客按账号归属记录。不同平台的难度不混算。' : this.source === 'HOJ' ? '本站公开单题练习按站内 ID 去重，不含本人出题、比赛与整卷记录。' : '仅使用已验证账号的公开记录，同一来源题目按编号去重；AtCoder 与牛客的账号记录不能证明独立完成。来源元数据和历史可能仍有缺口。'; },
    hasEvidence() { return this.profile.overall.attempted + this.profile.overall.objectiveAttempted > 0; },
    maximum() {
      const max = Math.max(...this.profile.axes.map(axis => this.value(axis) || 0));
      return this.mode === 'difficulty' ? Math.max(2000, Math.ceil(max / 500) * 500) : Math.max(4, Math.ceil(max / 4) * 4);
    },
    complete() { return this.profile.axes.every(axis => this.value(axis) !== null); },
    dataPoints() { return this.profile.axes.map((axis, index) => this.coordinate(index, this.value(axis) / this.maximum)).join(' '); },
    knownSegments() {
      return this.profile.axes.map((axis, index, axes) => {
        const next = (index + 1) % axes.length;
        return this.value(axis) === null || this.value(axes[next]) === null ? '' : 'M' + this.coordinate(index, this.value(axis) / this.maximum) + 'L' + this.coordinate(next, this.value(axes[next]) / this.maximum);
      }).join(' ');
    },
    chartLabel() { return this.mode === 'difficulty' ? this.scopeDescription + '，各算法方向通过难度的第 75 百分位，缺失表示尚无样本' : this.scopeDescription + '，各算法方向已通过编程题的数量'; },
  },
  watch: { uid: { immediate: true, handler() { this.load(); } } },
  beforeDestroy() { this.request++; },
  methods: {
    async load() {
      const token = ++this.request; this.bundle = null; this.error = ''; this.loading = !!this.uid;
      if (!this.uid) return;
      try {
        const result = responseData(await api.learningEvidenceProfile(this.uid));
        if (token !== this.request) return;
        this.bundle = result; this.selectSource('ALL');
      } catch (error) { if (token === this.request) this.error = errorMessage(error); }
      finally { if (token === this.request) this.loading = false; }
    },
    selectSource(source) { this.source = source; this.mode = this.profile && !this.profile.coverageOnly && this.profile.overall.ratedSolved ? 'difficulty' : 'coverage'; },
    value(axis) { return this.mode === 'difficulty' ? axis.representativeRating : axis.solved; },
    point(index, ratio) { const angle = index * Math.PI * 2 / this.profile.axes.length - Math.PI / 2; return { x: 240 + Math.cos(angle) * 130 * ratio, y: 200 + Math.sin(angle) * 130 * ratio }; },
    coordinate(index, ratio) { const p = this.point(index, ratio); return p.x + ',' + p.y; },
    ringPoints(ratio) { return this.profile.axes.map((_, index) => this.coordinate(index, ratio)).join(' '); },
    confidence(axis) { return axis.ratedSolved < 3 ? '评级样本不足（' + axis.ratedSolved + ' 题）' : axis.ratedSolved < 10 ? '少量样本（' + axis.ratedSolved + ' 题）' : axis.ratedSolved + ' 道评级题'; },
  },
};
</script>
<style scoped>
.ability-sources { display: flex; flex-wrap: wrap; gap: 6px 18px; border-bottom: 1px solid var(--line); margin-top: 20px; }.ability-sources button { color: var(--muted); background: transparent; border: 0; border-bottom: 2px solid transparent; padding: 10px 2px; font: inherit; font-size: 13px; cursor: pointer; }.ability-sources button[aria-pressed=true] { color: var(--accent); border-bottom-color: var(--accent); font-weight: 600; }.ability-sources button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }.ability-scope, .ability-unmapped { color: var(--muted); font-size: 12px; line-height: 1.8; }.ability-scope { margin: 12px 0; }.ability-unmapped { padding: 18px 0; }.ability-modes button:disabled { opacity: .45; cursor: default; }
.ability-profile { margin: 24px 0; padding: 24px; border: 1px solid var(--line); border-radius: 12px; color: var(--text); text-align: left; min-height: 150px; }.ability-profile header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }.ability-profile h2 { margin: 0; font-size: 20px; }.ability-profile header p, figcaption, .ability-method { color: var(--muted); font-size: 12px; line-height: 1.8; }.ability-modes { display: flex; padding: 4px; background: var(--soft); border-radius: 8px; flex-shrink: 0; }.ability-modes button { font: inherit; font-size: 13px; color: var(--muted); border: 0; padding: 8px 12px; border-radius: 6px; background: transparent; cursor: pointer; }.ability-modes button[aria-pressed=true] { color: var(--accent); background: var(--panel, #fff); }.ability-modes button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.ability-overview { display: flex; flex-wrap: wrap; gap: 12px 24px; margin-top: 20px; font-size: 13px; color: var(--muted); }.ability-overview strong { color: var(--text); font-size: 18px; }.ability-content { display: grid; grid-template-columns: minmax(280px, 1fr) minmax(0, 1.3fr); gap: 20px; align-items: center; }.ability-content figure { margin: 0; }.ability-content svg { display: block; width: 100%; }.radar-ring { fill: none; stroke: var(--line); }.radar-spoke { stroke: var(--line); }.axis-label { fill: var(--text); font-size: 13px; }.axis-value, .scale-label { fill: var(--muted); font-size: 11px; }.radar-area { fill: rgba(33,126,157,.15); stroke: var(--accent); stroke-width: 2; }.radar-path { fill: none; stroke: var(--accent); stroke-width: 2; }.radar-point { fill: var(--accent); stroke: var(--panel, #fff); stroke-width: 2; }.ability-evidence h3 { font-size: 15px; margin: 0 0 12px; }.ability-table-wrap { overflow-x: auto; }table { width: 100%; border-collapse: collapse; font-size: 12px; }th, td { padding: 10px 6px; text-align: left; border-bottom: 1px solid var(--line); white-space: nowrap; }thead th { color: var(--muted); font-weight: 500; }td small { display: block; color: var(--muted); margin-top: 5px; font-size: 11px; }.ability-method { margin: 24px 0 0; padding-top: 16px; border-top: 1px solid var(--line); }.ability-empty { padding: 32px 0 24px; text-align: center; }.ability-empty i, .ability-empty strong { display: block; margin-bottom: 14px; }.ability-empty i { font-size: 30px; color: var(--accent); }.ability-empty p { line-height: 1.8; color: var(--muted); }.ability-empty a { color: var(--accent); }
.ability-content, .ability-content figure, .ability-evidence { min-width: 0; }
.ability-profile.is-compact { margin: 0; padding: 0; border: 0; }
.is-compact header { flex-wrap: wrap; gap: 4px 12px; }
.is-compact .ability-modes { flex-wrap: wrap; max-width: 100%; }
.is-compact .ability-content { grid-template-columns: minmax(0, 1fr); gap: 12px; }
.is-compact .ability-content figure { width: 100%; max-width: 480px; margin: auto; }
@media(max-width:1050px) { .ability-content { grid-template-columns: minmax(0, 1fr); }.ability-content figure { max-width: 480px; width: 100%; margin: auto; } }
@media(max-width:600px) { .ability-profile { padding: 16px 12px; }.ability-profile header { align-items: flex-start; flex-direction: column; }.ability-overview { gap: 12px; } }
</style>
