<template>
  <nav class="problem-navigation" aria-label="题目类型">
    <router-link to="/problem" :class="{ selected: !objective }" :aria-current="!objective ? 'page' : null">
      <i class="el-icon-edit-outline" aria-hidden="true" />
      <strong>编程题</strong><span>代码与算法实践</span>
    </router-link>
    <router-link to="/objective" :class="{ selected: objective }" :aria-current="objective ? 'page' : null">
      <i class="el-icon-circle-check" aria-hidden="true" />
      <strong>客观题</strong><span>单题练习与整卷自测</span>
    </router-link>
  </nav>
</template>
<script>
export default {
  name: 'ProblemNavigation',
  computed: { objective() { return this.$route.path.split('/')[1] === 'objective'; } },
};
</script>
<style scoped>
.problem-navigation { display: flex; gap: 8px; border-bottom: 1px solid var(--line); margin-bottom: 22px; }
.problem-navigation a { display: flex; align-items: center; gap: 10px; min-height: 58px; padding: 8px 22px; border-bottom: 3px solid transparent; color: var(--muted); text-decoration: none; }
.problem-navigation i { font-size: 22px; }
.problem-navigation strong { font-size: 18px; white-space: nowrap; }
.problem-navigation span { font-size: 13px; }
.problem-navigation a:hover { color: var(--accent); background: var(--soft); }
.problem-navigation a.selected { border-bottom-color: var(--accent); color: var(--accent); background: var(--primary-soft); }
.problem-navigation a:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
@media (max-width: 700px) {
  .problem-navigation { gap: 0; margin-bottom: 16px; }
  .problem-navigation a { flex: 1; justify-content: center; gap: 8px; padding: 8px 10px; min-height: 52px; }
  .problem-navigation span { display: none; }
  .problem-navigation strong { font-size: 16px; }
}
</style>
