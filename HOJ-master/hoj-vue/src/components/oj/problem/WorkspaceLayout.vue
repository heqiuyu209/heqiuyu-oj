<template>
  <section class="workspace" :class="{ 'with-question-navigation': !!($slots.navigation || $scopedSlots.navigation) }" @keydown.esc="expanded = ''">
    <div class="workspace-toolbar">
      <div><strong>解题工作台</strong><span class="muted">拖动卡片标题调整顺序，拖动分隔线调整宽度</span></div>
      <div class="workspace-actions">
        <el-button size="mini" :type="!learning ? 'primary' : 'default'" @click="preset(false)">简洁双栏</el-button>
        <el-button v-if="allowLearning" size="mini" :type="learning ? 'primary' : 'default'" @click="preset(true)">学习模式</el-button>
        <el-button size="mini" @click="swap">左右互换</el-button>
        <el-button size="mini" @click="reset">重置布局</el-button>
      </div>
    </div>
    <div class="workspace-grid" ref="grid">
      <template v-for="(panel, index) in visiblePanels">
        <section :key="panel" class="workspace-card" :class="{ expanded: expanded === panel }"
          :style="{ flexGrow: weights[panel] }" @dragover.prevent @drop.prevent="drop(panel)">
          <header class="workspace-card-heading" draggable="true" @dragstart="startDrag($event, panel)" @dragend="dragging = ''">
            <strong><i class="el-icon-rank" /> {{ titles[panel] }}</strong>
            <div>
              <button :disabled="index === 0" @click="move(panel, -1)" :aria-label="titles[panel] + '向前移动'" title="向前移动">‹</button>
              <button :disabled="index === visiblePanels.length - 1" @click="move(panel, 1)" :aria-label="titles[panel] + '向后移动'" title="向后移动">›</button>
              <button @click="expand(panel)" :aria-label="expanded === panel ? '恢复卡片' : '展开卡片'" :title="expanded === panel ? '恢复卡片' : '展开卡片'">⛶</button>
            </div>
          </header>
          <div class="workspace-card-body" :data-panel="panel" :style="{ height: heights[panel] ? heights[panel] + 'px' : null }"><slot :name="panel" /></div>
        </section>
        <div v-if="index < visiblePanels.length - 1" :key="panel + '-split'" class="workspace-split"
          role="separator" tabindex="0" aria-orientation="vertical" aria-label="调整卡片宽度"
          :aria-valuenow="Math.round(weights[panel])" aria-valuemin="16" aria-valuemax="80"
          @pointerdown="startResize($event, index)" @keydown.left.prevent="nudge(index, -2)" @keydown.right.prevent="nudge(index, 2)"><span /></div>
      </template>
    </div>
    <slot name="navigation" />
    <p v-if="!saved" class="muted">浏览器无法保存布局，当前调整仍可使用。</p>
  </section>
</template>
<script>
import { readPreference, savePreference } from '@/common/appearance';
const defaults = () => ({ order: ['statement', 'editor', 'learning'], learning: false, weights: { statement: 50, editor: 50, learning: 28 }, heights: {} });
export default {
  props: {
    editorTitle: { type: String, default: '编程与自测' },
    statementTitle: { type: String, default: '题目与提交记录' },
    allowLearning: { type: Boolean, default: true },
    preferenceKey: { type: String, default: 'workspace-v1' },
  },
  data() {
    const state = defaults();
    const stored = readPreference(this.preferenceKey, {});
    if (Array.isArray(stored.order) && stored.order.length === 3 && new Set(stored.order).size === 3 && stored.order.every(id => state.order.includes(id))) state.order = stored.order;
    state.learning = this.allowLearning && stored.learning === true;
    Object.keys(state.weights).forEach(id => {
      const width = stored.weights && stored.weights[id];
      if (typeof width === 'number' && width >= 16 && width <= 80) state.weights[id] = width;
      const height = stored.heights && stored.heights[id];
      if (typeof height === 'number' && height >= 250 && height <= 2000) state.heights[id] = height;
    });
    return { ...state, dragging: '', expanded: '', saved: true };
  },
  computed: {
    titles() { return { statement: this.statementTitle, editor: this.editorTitle, learning: '学习记录' }; },
    visiblePanels() { return this.order.filter(id => id !== 'learning' || this.allowLearning && this.learning); },
  },
  mounted() { this.observe(); window.addEventListener('keydown', this.escape); },
  beforeDestroy() { if (this.observer) this.observer.disconnect(); this.stopResize(); window.removeEventListener('keydown', this.escape); clearTimeout(this.saveTimer); },
  methods: {
    persist() { this.saved = savePreference(this.preferenceKey, { order: this.order, learning: this.learning, weights: this.weights, heights: this.heights }); this.$nextTick(() => this.$emit('resize')); },
    preset(learning) { this.learning = learning; this.expanded = ''; this.order = defaults().order; this.weights = learning ? { statement: 36, editor: 40, learning: 24 } : defaults().weights; this.persist(); this.$nextTick(this.observe); },
    reset() { Object.assign(this, defaults()); this.expanded = ''; this.persist(); this.$nextTick(this.observe); },
    swap() { const a = this.order.indexOf('statement'); const b = this.order.indexOf('editor'); const next = [...this.order]; next[a] = 'editor'; next[b] = 'statement'; this.order = next; this.persist(); },
    startDrag(event, panel) { this.dragging = panel; event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', panel); },
    drop(target) { if (!this.dragging || target === this.dragging) return; const next = [...this.order]; const a = next.indexOf(this.dragging); const b = next.indexOf(target); [next[a], next[b]] = [next[b], next[a]]; this.order = next; this.dragging = ''; this.persist(); },
    move(panel, direction) { const index = this.visiblePanels.indexOf(panel); const target = this.visiblePanels[index + direction]; if (target) { this.dragging = panel; this.drop(target); } },
    expand(panel) { this.expanded = this.expanded === panel ? '' : panel; this.$nextTick(() => this.$emit('resize')); },
    escape(event) { if (event.key === 'Escape') { this.expanded = ''; this.$emit('resize'); } },
    nudge(index, amount) { const a = this.visiblePanels[index]; const b = this.visiblePanels[index + 1]; if (this.weights[a] + amount < 16 || this.weights[b] - amount < 16 || this.weights[a] + amount > 80 || this.weights[b] - amount > 80) return; this.weights[a] += amount; this.weights[b] -= amount; this.persist(); },
    startResize(event, index) { if (event.button !== 0) return; event.preventDefault(); this.resizeState = { index, x: event.clientX }; window.addEventListener('pointermove', this.resize); window.addEventListener('pointerup', this.stopResize); window.addEventListener('pointercancel', this.stopResize); },
    resize(event) { if (!this.resizeState) return; const amount = (event.clientX - this.resizeState.x) / this.$refs.grid.clientWidth * this.visiblePanels.reduce((n, id) => n + this.weights[id], 0); this.nudge(this.resizeState.index, amount); this.resizeState.x = event.clientX; },
    stopResize() { this.resizeState = null; window.removeEventListener('pointermove', this.resize); window.removeEventListener('pointerup', this.stopResize); window.removeEventListener('pointercancel', this.stopResize); },
    observe() {
      if (!window.ResizeObserver || !this.$el.querySelectorAll) return;
      if (this.observer) this.observer.disconnect();
      this.observer = new ResizeObserver(entries => { if (this.expanded) return; entries.forEach(entry => { const node = entry.target; if (node.style.height && node.offsetHeight >= 250) this.heights[node.dataset.panel] = Math.round(node.offsetHeight); }); clearTimeout(this.saveTimer); this.saveTimer = setTimeout(() => this.persist(), 150); });
      this.$el.querySelectorAll('.workspace-card-body').forEach(node => this.observer.observe(node));
    },
  },
};
</script>
<style scoped>
.workspace-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 8px 0 20px; }
.workspace-toolbar strong { display: block; font-size: 21px; margin-bottom: 6px; }
.workspace-actions { display: flex; gap: 6px; flex-wrap: wrap; }
.workspace-actions .el-button { margin: 0; }
.workspace-grid { display: flex; align-items: flex-start; }
.workspace-card { flex-basis: 0; min-width: 0; background: var(--surface); border: 1px solid var(--line); border-radius: 14px; overflow: hidden; }
.workspace-card-heading { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; border-bottom: 1px solid var(--line); cursor: grab; color: var(--text); background: var(--soft); }
.workspace-card-heading button { border: 0; background: transparent; color: var(--muted); cursor: pointer; font-size: 19px; min-width: 26px; padding: 0 4px; }
.workspace-card-heading button:disabled { opacity: .3; cursor: default; }
.workspace-card-body { min-height: 250px; overflow: auto; resize: vertical; max-height: 2000px; }
.workspace-split { width: 16px; align-self: stretch; cursor: col-resize; display: flex; align-items: center; justify-content: center; flex-shrink: 0; touch-action: none; }
.workspace-split span { width: 3px; height: 48px; border-radius: 8px; background: var(--line); }
.workspace-split:hover span, .workspace-split:focus span { background: var(--accent); }
.workspace-card.expanded { position: fixed; inset: 88px 18px 48px; z-index: 1998; }
.with-question-navigation .workspace-card.expanded { bottom: calc(96px + env(safe-area-inset-bottom, 0px)); }
.expanded .workspace-card-body { height: calc(100% - 50px) !important; resize: none; }
@media(max-width: 1100px) { .workspace-toolbar { align-items: flex-start; flex-direction: column; } }
@media(max-width: 767px) { .workspace-grid { display: block; } .workspace-card { margin-bottom: 16px; } .workspace-split { display: none; } .workspace-card.expanded { inset: 68px 6px 12px; } .workspace-toolbar .muted { font-size: 12px; } }
</style>
