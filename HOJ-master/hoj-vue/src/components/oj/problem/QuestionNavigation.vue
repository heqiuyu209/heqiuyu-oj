<template>
  <nav class="question-switcher question-dock" aria-label="题目切换">
    <el-button class="previous-question" icon="el-icon-arrow-left" :disabled="previousDisabled" :title="previousTitle" @click="$emit('previous')">上一题</el-button>
    <div class="question-current" aria-live="polite">
      <strong>{{ position }}</strong>
      <span v-if="questionTitle" :title="questionTitle">{{ questionTitle }}</span>
    </div>
    <el-button class="next-question" type="primary" :disabled="nextDisabled" :title="nextTitle" @click="$emit('next')">下一题 <i class="el-icon-arrow-right" /></el-button>
  </nav>
</template>
<script>
export default {
  name: 'QuestionNavigation',
  props: {
    position: { type: String, required: true },
    questionTitle: { type: String, default: '' },
    previousDisabled: { type: Boolean, default: false },
    nextDisabled: { type: Boolean, default: false },
    previousTitle: { type: String, default: '上一题' },
    nextTitle: { type: String, default: '下一题' },
  },
};
</script>
<style scoped>
.question-dock { position: fixed; left: 50%; bottom: calc(16px + env(safe-area-inset-bottom, 0px)); transform: translateX(-50%); z-index: 1999; display: flex; align-items: center; gap: 16px; width: 520px; max-width: calc(100vw - 32px); box-sizing: border-box; padding: 8px; border: 1px solid var(--line); border-radius: 14px; background: var(--surface); box-shadow: 0 8px 32px #173e501f; color: var(--text); }
.question-dock .el-button { flex-shrink: 0; margin: 0; min-height: 44px; padding: 12px 16px; border-radius: 9px; }
.question-dock .el-button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.question-current { flex: 1; min-width: 0; text-align: center; line-height: 1.5; }
.question-current strong { display: block; font-size: 14px; font-variant-numeric: tabular-nums; }
.question-current span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: var(--muted); }
@media (max-width: 650px) { .question-dock { gap: 8px; }.question-dock .el-button { padding: 12px; }.question-current strong { font-size: 13px; }.question-current span { font-size: 11px; } }
</style>
