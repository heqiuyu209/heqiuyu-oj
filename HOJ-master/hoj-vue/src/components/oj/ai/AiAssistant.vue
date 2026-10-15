<template>
  <div class="ai-assistant">
    <el-button v-show="!open" class="ai-launcher" type="primary" round @click="show" aria-label="打开 AI 助教"><i class="el-icon-chat-dot-round" /> AI 助教</el-button>
    <section v-if="open" class="ai-popup" role="dialog" aria-label="AI 助教" @keydown.esc="close" :style="position">
      <header @pointerdown="startDrag"><span><i class="el-icon-chat-dot-round" /> AI 助教</span><button @click="close" aria-label="关闭 AI 助教">×</button></header>
      <div class="ai-popup-body">
        <p class="ai-context">{{ $route.params.problemID ? '当前题目 · ' + $route.params.problemID : '编程学习助教' }}</p>
        <h3>从你卡住的地方开始</h3>
        <p class="muted">理解题意、推导思路、分析复杂度，保留自己完成解题的过程。</p>
        <el-alert :closable="false" :title="contest ? '比赛页面暂不提供 AI 助教' : 'AI 服务尚未开放'" type="info" show-icon />
        <div class="ai-prompts"><el-button v-for="text in prompts" :key="text" size="small" :disabled="contest" @click="question = text">{{ text }}</el-button></div>
        <el-input ref="input" v-model="question" type="textarea" :rows="4" :disabled="contest" placeholder="先写下你的疑问；服务开放后再发送。" />
        <div class="ai-send"><small class="muted">当前不会发送题目或代码</small><el-button size="small" type="primary" disabled>发送</el-button></div>
      </div>
    </section>
  </div>
</template>
<script>
export default {
  data: () => ({ open: false, question: '', x: null, y: null, prompts: ['帮我理解题意', '提示下一步思路', '分析复杂度'] }),
  computed: { contest() { return !!this.$route.params.contestID; }, position() { return this.x === null ? {} : { left: this.x + 'px', top: this.y + 'px', right: 'auto', bottom: 'auto' }; } },
  watch: { '$route.fullPath'() { this.question = ''; }, '$store.getters.token'() { this.question = ''; this.open = false; } },
  mounted() { window.addEventListener('resize', this.clamp); },
  beforeDestroy() { this.stopDrag(); window.removeEventListener('resize', this.clamp); },
  methods: {
    show() { this.open = true; this.$nextTick(() => { this.clamp(); if (this.$refs.input && !this.contest) this.$refs.input.focus(); }); },
    close() { this.open = false; this.stopDrag(); this.$nextTick(() => { const button = this.$el.querySelector('.ai-launcher'); if (button) button.focus(); }); },
    startDrag(event) { if (event.button !== 0 || event.target.closest('button')) return; const rect = event.currentTarget.parentElement.getBoundingClientRect(); this.drag = { x: event.clientX - rect.left, y: event.clientY - rect.top }; window.addEventListener('pointermove', this.move); window.addEventListener('pointerup', this.stopDrag); window.addEventListener('pointercancel', this.stopDrag); },
    move(event) { if (!this.drag) return; this.x = event.clientX - this.drag.x; this.y = event.clientY - this.drag.y; this.clamp(); },
    clamp() { if (this.x === null) return; const popup = this.$el.querySelector('.ai-popup'); if (!popup) return; this.x = Math.max(8, Math.min(this.x, window.innerWidth - popup.offsetWidth - 8)); this.y = Math.max(8, Math.min(this.y, window.innerHeight - popup.offsetHeight - 8)); },
    stopDrag() { this.drag = null; window.removeEventListener('pointermove', this.move); window.removeEventListener('pointerup', this.stopDrag); window.removeEventListener('pointercancel', this.stopDrag); },
  },
};
</script>
<style scoped>
.ai-launcher { position: fixed; right: 24px; bottom: 80px; z-index: 2001; box-shadow: 0 6px 24px #17465a24; }
.ai-popup { position: fixed; right: 24px; bottom: 80px; width: 360px; max-width: calc(100vw - 48px); max-height: calc(100dvh - 104px); overflow: auto; z-index: 2002; border: 1px solid var(--line); border-radius: 16px; background: var(--surface); box-shadow: 0 16px 64px #102a4433; }
.ai-popup header { display: flex; justify-content: space-between; align-items: center; padding: 15px 20px; border-bottom: 1px solid var(--line); font-weight: 600; cursor: move; touch-action: none; }
.ai-popup header button { border: 0; background: transparent; font-size: 23px; color: var(--muted); cursor: pointer; }
.ai-popup-body { padding: 18px; }
.ai-popup-body p { line-height: 1.8; }
.ai-context { font-size: 12px; color: var(--accent); }
.ai-prompts { display: flex; flex-wrap: wrap; gap: 7px; margin: 16px 0; }
.ai-prompts .el-button { margin: 0; }
.ai-send { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; }
</style>
