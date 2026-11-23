<template>
  <div class="rating-summary">
    <div><small>比赛 Rating</small><strong>{{ rating === null ? '未定级' : rating }}</strong><span>正式计分赛成绩更新</span></div>
  </div>
</template>
<script>
export default {
  props: { profile: { type: Object, default: () => ({}) } },
  computed: { rating() { return Number.isFinite(this.profile.contestRating) ? this.profile.contestRating : null; } },
};
</script>
<style scoped>
.rating-summary { margin: 16px 0; }
.rating-summary > div { padding: 16px; border: 1px solid var(--line); border-radius: 10px; background: var(--soft); }
small, strong, span { display: block; }
small, span { color: var(--muted); font-size: 12px; }
strong { font-size: 25px; margin: 8px 0; color: var(--text); }
</style>
