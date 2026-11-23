<template>
  <details class="practice-context">
    <summary>专项与试卷记录 <span>{{ summary }}</span></summary>
    <div class="practice-body">
      <p class="practice-status" role="status">{{ syncLabel }}<template v-if="context.lastSuccessAt"> · 最近完整同步 {{ date(context.lastSuccessAt) }}</template></p>
      <p v-if="owner && context.lastError" class="practice-error">{{ context.lastError }}</p>
      <p v-if="context.restricted" class="practice-note">牛客未公开此账号的做题记录，当前不展示专项与试卷数据。</p>
      <template v-else-if="context.available">
        <dl class="practice-numbers">
          <div><dt>有完成记录的试卷</dt><dd>{{ context.completedPapers }} <small>/ {{ context.totalPapers }} 份</small></dd></div>
          <div><dt>其中专项练习</dt><dd>{{ context.completedSpecialPapers }} <small>/ {{ context.specialPapers }} 份</small></dd></div>
        </dl>
        <p class="practice-note">按牛客公开的试卷记录统计；重做中的试卷保留已有完成记录。正确率按每份专项分别展示，不计入编程通过题数，也不代表独立作答或知识点掌握度。</p>
        <p v-if="!context.totalPapers" class="practice-note">暂无公开的专项或试卷记录。</p>
        <ol v-else>
          <li v-for="item in context.records" :key="item.paperId">
            <div class="paper-heading"><strong>{{ item.title }}</strong><span>{{ kind(item.kind) }} · {{ item.completed ? '当前记录已完成' : item.completedRecord ? '重做中，已有完成记录' : '暂无完成记录' }}</span></div>
            <p v-if="result(item)" class="paper-result">
              <span v-if="result(item).kind === 'SPECIAL' && result(item).accuracy !== null">来源正确率 <b>{{ result(item).accuracy }}</b></span>
              <span v-else-if="result(item).kind === 'PAPER' && result(item).score !== null">来源分数 <b>{{ result(item).score }}</b></span>
              <span>{{ item.completed ? '完成于' : '已有完成记录' }} {{ date(result(item).completedAt) }}</span>
            </p>
          </li>
        </ol>
        <p v-if="context.totalPapers > context.records.length" class="practice-note">此处展示最近 {{ context.records.length }} 份，汇总包含本轮完整同步的全部试卷。</p>
        <a class="practice-source" :href="context.sourceUrl" target="_blank" rel="noopener noreferrer">查看牛客原始记录 <i class="el-icon-top-right" /></a>
      </template>
      <p v-else class="practice-note">后台会单独同步此账号公开的专项与试卷记录。可通过“请求同步”更新；完整同步前保留等待状态。</p>
    </div>
  </details>
</template>
<script>
export default {
  name: 'NowCoderPracticeContext',
  props: { context: { type: Object, required: true }, owner: { type: Boolean, default: false } },
  computed: {
    summary() {
      if (this.context.restricted) return '来源未公开';
      return this.context.available ? `${this.context.completedPapers} / ${this.context.totalPapers} 份有完成记录` : '等待记录同步';
    },
    syncLabel() {
      return ({ RUNNING: '试卷记录正在同步', QUEUED: '试卷记录等待同步', BACKOFF: '试卷记录稍后重试', IDLE: this.context.stale ? '试卷记录待更新' : '试卷记录已同步' })[this.context.syncState] || '试卷记录等待同步';
    },
  },
  methods: {
    kind(value) { return ({ SPECIAL: '专项练习', PAPER: '试卷', INTERVIEW: '面试题' })[value] || '来源记录'; },
    date(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'; },
    result(item) { return item.completedRecord || (item.completed ? item : null); },
  },
};
</script>
<style scoped>
.practice-context { margin-top: 14px; font-size: 12px; }
summary { padding: 10px 0; color: var(--accent); cursor: pointer; }
summary span { margin-left: 12px; color: var(--muted); }
summary:focus-visible, a:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.practice-body { padding-top: 4px; }
.practice-note, .practice-status { color: var(--muted); line-height: 1.8; }
.practice-error { color: var(--danger, #dd625c); }
.practice-numbers { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin: 18px 0; }
dt { color: var(--muted); }
dd { margin: 8px 0 0; font-size: 24px; font-weight: 600; }
dd small { font-size: 12px; font-weight: 400; color: var(--muted); }
ol { list-style: none; padding: 0; margin: 0; }
li { padding: 14px 0; border-top: 1px solid var(--line); }
.paper-heading { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 8px 20px; }
.paper-heading strong { color: var(--text); font-size: 14px; overflow-wrap: anywhere; }
.paper-heading span, .paper-result { color: var(--muted); line-height: 1.8; }
.paper-result { display: flex; flex-wrap: wrap; gap: 6px 20px; margin: 8px 0 0; }
.paper-result b { color: var(--text); }
.practice-source { display: inline-block; color: var(--accent); margin-top: 12px; }
@media(max-width:600px) { summary span { display: block; margin: 8px 0 0; } }
</style>
