<template>
  <div class="rating-summary">
    <div v-for="item in scores" :key="item.key"><small>{{ item.label }}</small><strong>{{ item.value === null ? '待定' : item.value }}</strong><span>{{ item.hint }}</span></div>
  </div>
</template>
<script>
import { finiteRating } from '@/common/learning';
export default {
  props: { profile: { type: Object, default: () => ({}) } },
  computed: { scores() { return [
    { key: 'practice', label: '练习 Rating', value: finiteRating(this.profile.practiceRating), hint: '日常训练 · 计分规则待定' },
    { key: 'contest', label: '比赛 Rating', value: finiteRating(this.profile.contestRating), hint: '计分比赛 · 计分规则待定' },
  ]; } },
};
</script>
<style scoped>
.rating-summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin: 16px 0; }
.rating-summary > div { padding: 16px; border: 1px solid var(--line); border-radius: 10px; background: var(--soft); }
small, strong, span { display: block; }
small, span { color: var(--muted); font-size: 12px; }
strong { font-size: 25px; margin: 8px 0; color: var(--text); }
@media(max-width: 520px) { .rating-summary { grid-template-columns: 1fr; } }
</style>
