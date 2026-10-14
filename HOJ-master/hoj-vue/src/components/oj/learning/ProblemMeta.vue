<template>
  <div class="problem-origin" aria-label="题目来源与分数">
    <a v-if="url" :href="url" target="_blank" rel="noopener noreferrer" @click.stop>{{ source }} <i class="el-icon-top-right" /></a>
    <span v-else>{{ source }}</span>
    <span v-if="detail && problem.source">{{ problem.source }}</span>
    <span :title="ratingTitle" class="rating-chip">CF 标尺 {{ score === null ? '待定' : score }}</span>
    <small v-if="detail && original !== null">来源原始分数 {{ original }}</small>
  </div>
</template>
<script>
import { problemSource, cfRating, finiteRating, safeSourceUrl } from '@/common/learning';
export default {
  props: { problem: { type: Object, default: () => ({}) }, detail: Boolean },
  computed: {
    source() { return problemSource(this.problem); },
    score() { return cfRating(this.problem); },
    original() { return finiteRating(this.problem.sourceRating); },
    url() { return safeSourceUrl(this.problem.sourceUrl || this.problem.link); },
    ratingTitle() { return this.problem.ratingProvenance === 'estimated' ? '按 CF 标尺估算，非 Codeforces 官方评分' : '保留来源分数；未标定题目不按原有难度自动换算'; },
  },
};
</script>
<style scoped>
.problem-origin { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 12px; color: var(--muted); margin: 7px 0; line-height: 1.6; }
.rating-chip { background: var(--soft); color: var(--accent); border: 1px solid var(--line); border-radius: 5px; padding: 2px 7px; white-space: nowrap; }
</style>
