<template>
  <details class="contest-context">
    <summary>比赛与赛后训练 <span>{{ context.available ? context.totalContests + ' 场已结束比赛' : '等待比赛记录同步' }}</span></summary>
    <div class="context-body">
      <p class="context-status" role="status">{{ syncLabel }}<template v-if="context.lastSuccessAt"> · 最近完整同步 {{ date(context.lastSuccessAt) }}</template></p>
      <p v-if="owner && context.lastError" class="context-error">{{ context.lastError }}</p>
      <template v-if="context.available">
        <p class="context-note">已关联 {{ context.coveredContests }} / {{ context.totalContests }} 场个人公开提交。比赛记录已计入账号总题数，此处不再累加。赛后同题通过可能与比赛内通过或其他比赛重叠，不代表正式成绩或独立完成。</p>
        <p v-if="!context.totalContests" class="context-note">暂无公开的已结束参赛记录。</p>
        <ol v-else>
          <li v-for="contest in context.contests" :key="contest.contestId">
            <div class="contest-heading"><a :href="contest.url" target="_blank" rel="noopener noreferrer">{{ contest.title }}</a><span>{{ contest.team ? '团队参赛' : '个人参赛' }} · {{ participation(contest.participation) }}</span></div>
            <p class="contest-time">{{ date(contest.startMs) }} — {{ date(contest.endMs) }}</p>
            <dl>
              <div><dt>来源{{ contest.team ? '团队' : '参赛' }}名次</dt><dd>{{ value(contest.rank) }}</dd></div>
              <div><dt>来源{{ contest.team ? '团队' : '参赛' }}通过</dt><dd>{{ value(contest.officialAccepted) }}</dd></div>
              <div><dt>比赛内通过 / 提交</dt><dd>{{ value(contest.contestSolved) }} <small>/ {{ value(contest.contestSubmissions) }}</small></dd></div>
              <div><dt>赛后同题通过</dt><dd>{{ value(contest.postContestSolved) }}</dd></div>
            </dl>
            <p v-if="contest.detailState !== 'DONE'" class="context-note">{{ contest.detailState === 'TEAM' ? '团队成绩不作为个人提交明细，暂不统计个人比赛内及赛后通过。' : '来源限制查看提交明细，仅展示公开的参赛摘要。' }}</p>
            <p v-else-if="contest.postContestSolved === null" class="context-note">账号编程记录正在更新，赛后同题通过待本轮同步完成后计算。</p>
          </li>
        </ol>
        <p v-if="context.totalContests > context.contests.length" class="context-note">此处展示最近 {{ context.contests.length }} 场，覆盖数量按全部已同步比赛计算。</p>
      </template>
      <p v-else class="context-note">账号编程历史完整同步后，将继续关联公开的已结束比赛。可通过“请求同步”刷新；同步完成前不显示推测的比赛数据。</p>
    </div>
  </details>
</template>
<script>
export default {
  name: 'NowCoderContestContext',
  props: { context: { type: Object, required: true }, owner: { type: Boolean, default: false } },
  computed: {
    syncLabel() {
      return ({ RUNNING: '比赛记录正在同步', QUEUED: '比赛记录等待同步', BACKOFF: '比赛记录稍后重试', IDLE: this.context.stale ? '比赛记录待更新' : '比赛记录已同步' })[this.context.syncState] || '比赛记录等待同步';
    },
  },
  methods: {
    value(value) { return value === null || value === undefined ? '—' : value; },
    date(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'; },
    participation(value) { return ({ OFFICIAL: '正式参赛', STAR: '打星参赛' })[value] || '参赛类型未知'; },
  },
};
</script>
<style scoped>
.contest-context { margin-top: 18px; font-size: 12px; }
summary { padding: 10px 0; color: var(--accent); cursor: pointer; }
summary span { margin-left: 12px; color: var(--muted); }
summary:focus-visible, a:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.context-body { padding-top: 4px; }
.context-note, .context-status, .contest-time { color: var(--muted); line-height: 1.8; }
.context-error { color: var(--danger, #dd625c); }
ol { list-style: none; padding: 0; margin: 12px 0 0; }
li { padding: 18px 0; border-top: 1px solid var(--line); }
.contest-heading { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 8px 20px; }
.contest-heading a { color: var(--accent); font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
.contest-heading span { color: var(--muted); }
.contest-time { margin: 8px 0 14px; }
dl { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; margin: 0; }
dt { color: var(--muted); line-height: 1.7; }
dd { margin: 6px 0 0; font-size: 18px; font-weight: 600; }
dd small { font-size: 12px; font-weight: 400; color: var(--muted); }
@media(max-width:800px) { dl { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media(max-width:600px) { summary span { display: block; margin: 8px 0 0; } }
</style>
