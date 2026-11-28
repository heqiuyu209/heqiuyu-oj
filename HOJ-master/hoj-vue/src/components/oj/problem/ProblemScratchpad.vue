<template>
  <section class="problem-scratchpad" aria-label="本题草稿板">
    <div class="scratchpad-toolbar" role="group" aria-label="绘图工具">
      <button v-for="item in tools" :key="item.id" type="button" class="scratchpad-tool"
        :aria-pressed="tool === item.id"
        :aria-expanded="item.id === 'pen' ? String(brushSettingsOpen) : null" @click="selectTool(item.id)">
        <i :class="item.icon" aria-hidden="true"></i>
        {{ item.label }}
      </button>
      <div class="scratchpad-history">
        <button type="button" class="scratchpad-tool" :disabled="!canUndo" @click="undo">
          <i class="el-icon-refresh-left" aria-hidden="true"></i> 撤销
        </button>
        <button type="button" class="scratchpad-tool" :disabled="!items.length" @click="clear">
          <i class="el-icon-delete" aria-hidden="true"></i> 清空
        </button>
      </div>
    </div>

    <div v-if="brushSettingsOpen" class="scratchpad-brush">
      <div class="scratchpad-colors" role="group" aria-label="画笔颜色">
        <button v-for="color in colors" :key="color.label" type="button" class="scratchpad-swatch"
          :style="{ '--swatch': color.value || 'var(--text)' }"
          :aria-label="color.label" :title="color.label" :aria-pressed="penColor === color.value"
          @click="penColor = color.value"></button>
      </div>
      <label class="scratchpad-width">
        粗细
        <input v-model.number="penWidth" type="range" min="1" max="12" aria-label="画笔粗细" />
        <span>{{ penWidth }}</span>
      </label>
    </div>

    <div v-if="tool === 'text' || tool === 'array'" class="scratchpad-options">
      <input v-if="tool === 'text'" ref="textInput" v-model="textValue" type="text" maxlength="60"
        aria-label="标注文字" placeholder="输入文字，再点画布" />
      <template v-else>
        <input ref="arrayInput" v-model="arrayValue" type="text" maxlength="96"
          aria-label="数组元素" placeholder="数组值用空格分隔，再点画布" />
        <small v-if="arrayValues.length > maxArrayLength">最多 {{ maxArrayLength }} 格</small>
      </template>
    </div>

    <div class="scratchpad-paper">
      <canvas ref="canvas" width="480" height="600" tabindex="0" aria-label="绘图画布"
        @pointerdown="startDrawing" @pointermove="moveDrawing" @pointerup="finishDrawing"
        @pointercancel="cancelDrawing" @lostpointercapture="cancelDrawing"
        @keydown.ctrl.z.prevent="undo" @keydown.meta.z.prevent="undo">
        当前浏览器无法显示画布。
      </canvas>
    </div>
    <p v-if="!saved" class="scratchpad-error" role="status">当前浏览器无法保存草稿。</p>
  </section>
</template>

<script>
import { appearanceRuntime, readPreference, savePreference } from '@/common/appearance';
import { draftKey } from '@/common/learning';

const WIDTH = 480;
const HEIGHT = 600;
const MAX_ARRAY_LENGTH = 12;
const TOOLS = [
  { id: 'pen', label: '画笔', icon: 'el-icon-edit' },
  { id: 'eraser', label: '橡皮', icon: 'scratchpad-eraser-icon' },
  { id: 'box', label: '方框', icon: 'el-icon-crop' },
  { id: 'circle', label: '圆圈', icon: 'scratchpad-circle-icon' },
  { id: 'arrow', label: '箭头', icon: 'el-icon-right' },
  { id: 'text', label: '文字', icon: 'el-icon-tickets' },
  { id: 'array', label: '数组', icon: 'el-icon-s-grid' },
];
const COLORS = [
  { label: '默认笔色', value: null },
  { label: '蓝色', value: '#2577bc' },
  { label: '红色', value: '#cf414e' },
  { label: '绿色', value: '#248452' },
  { label: '橙色', value: '#c66c24' },
  { label: '紫色', value: '#8056ba' },
];

function isFreehand(kind) {
  return kind === 'pen' || kind === 'eraser';
}

function cellWidth(count) {
  return Math.min(40, (WIDTH - 16) / count);
}

function drawArray(ctx, item) {
  const { x, y, values } = item;
  const width = cellWidth(values.length);
  ctx.save();
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.font = '18px Consolas, monospace';
  values.forEach((value, index) => {
    const left = x + index * width;
    ctx.strokeRect(left, y, width, 35);
    ctx.fillText(value, left + width / 2, y + 18, width - 5);
  });
  ctx.globalAlpha = 0.65;
  ctx.font = '15px Consolas, monospace';
  values.forEach((_, index) => ctx.fillText(String(index), x + (index + 0.5) * width, y + 47));
  ctx.restore();
}

function drawItem(ctx, item) {
  if (item.kind === 'array') {
    drawArray(ctx, item);
    return;
  }
  if (item.kind === 'text') {
    ctx.font = '22px sans-serif';
    ctx.textBaseline = 'top';
    ctx.fillText(item.text, item.x, item.y, WIDTH - item.x - 4);
    return;
  }

  const { points } = item;
  const first = points[0];
  if (isFreehand(item.kind)) {
    ctx.save();
    if (item.kind === 'eraser') {
      ctx.globalCompositeOperation = 'destination-out';
      ctx.strokeStyle = ctx.fillStyle = '#000';
      ctx.lineWidth = 24;
    } else {
      if (item.color) ctx.strokeStyle = ctx.fillStyle = item.color;
      if (item.width) ctx.lineWidth = item.width;
    }
    ctx.beginPath();
    if (points.length === 1) {
      ctx.arc(first.x, first.y, ctx.lineWidth / 2, 0, Math.PI * 2);
      ctx.fill();
    } else {
      ctx.moveTo(first.x, first.y);
      for (const point of points) ctx.lineTo(point.x, point.y);
      ctx.stroke();
    }
    ctx.restore();
    return;
  }

  const last = points[points.length - 1];
  const dx = last.x - first.x;
  const dy = last.y - first.y;
  if (item.kind === 'box') {
    ctx.strokeRect(first.x, first.y, dx, dy);
    return;
  }
  if (item.kind === 'circle') {
    ctx.beginPath();
    ctx.arc(first.x, first.y, Math.hypot(dx, dy), 0, Math.PI * 2);
    ctx.stroke();
    return;
  }

  const angle = Math.atan2(dy, dx);
  const head = Math.min(15, Math.hypot(dx, dy) * 0.5);
  ctx.beginPath();
  ctx.moveTo(first.x, first.y);
  ctx.lineTo(last.x, last.y);
  ctx.moveTo(last.x - head * Math.cos(angle - Math.PI / 6), last.y - head * Math.sin(angle - Math.PI / 6));
  ctx.lineTo(last.x, last.y);
  ctx.lineTo(last.x - head * Math.cos(angle + Math.PI / 6), last.y - head * Math.sin(angle + Math.PI / 6));
  ctx.stroke();
}

export default {
  name: 'ProblemScratchpad',
  data: () => ({
    tools: TOOLS,
    colors: COLORS,
    maxArrayLength: MAX_ARRAY_LENGTH,
    tool: 'pen',
    penClicked: false,
    brushSettingsOpen: false,
    penColor: null,
    penWidth: 3,
    items: [],
    cleared: null,
    textValue: '',
    arrayValue: '',
    saved: true,
  }),
  computed: {
    storageKey() {
      const { isAuthenticated, userInfo } = this.$store.getters;
      return 'sketch:' + draftKey(isAuthenticated && userInfo ? userInfo.uid : null, this.$route.params);
    },
    dark() { return appearanceRuntime.dark; },
    canUndo() { return this.items.length > 0 || this.cleared !== null; },
    arrayValues() { return this.arrayValue.split(/[\s,，]+/).filter(Boolean); },
  },
  watch: {
    storageKey: { immediate: true, handler(key) { this.load(key); } },
    dark() { this.requestPaint(); },
  },
  mounted() {
    if (window.ResizeObserver) {
      this.resizeObserver = new ResizeObserver(this.requestPaint);
      this.resizeObserver.observe(this.$refs.canvas);
    }
    this.requestPaint();
  },
  beforeDestroy() {
    if (this.resizeObserver) this.resizeObserver.disconnect();
    cancelAnimationFrame(this.paintFrame);
    this.stopStroke();
  },
  methods: {
    load(key) {
      this.stopStroke();
      const stored = readPreference(key, []);
      this.items = Array.isArray(stored) ? stored : [];
      this.loadedKey = key;
      this.cleared = null;
      this.textValue = '';
      this.arrayValue = '';
      this.saved = true;
      this.requestPaint();
    },
    save() {
      this.saved = savePreference(this.loadedKey, this.items);
      this.requestPaint();
    },
    selectTool(id) {
      this.stopStroke();
      const isPen = id === 'pen';
      this.brushSettingsOpen = isPen && this.penClicked && !this.brushSettingsOpen;
      this.penClicked = isPen;
      this.tool = id;
      this.requestPaint();
      if (id !== 'text' && id !== 'array') return;
      this.$nextTick(() => {
        const input = id === 'text' ? this.$refs.textInput : this.$refs.arrayInput;
        if (input) input.focus();
      });
    },
    point(event) {
      const bounds = this.$refs.canvas.getBoundingClientRect();
      return {
        x: Math.max(0, Math.min(WIDTH, (event.clientX - bounds.left) * WIDTH / bounds.width)),
        y: Math.max(0, Math.min(HEIGHT, (event.clientY - bounds.top) * HEIGHT / bounds.height)),
      };
    },
    startDrawing(event) {
      if (event.button !== 0 || !event.isPrimary || this.stroke) return;
      event.preventDefault();
      const canvas = this.$refs.canvas;
      const point = this.point(event);
      canvas.focus({ preventScroll: true });
      if (this.tool === 'text') {
        const text = this.textValue.trim();
        if (!text) {
          this.$refs.textInput.focus();
          return;
        }
        this.items.push({ kind: 'text', text, x: Math.min(point.x, WIDTH - 52), y: Math.min(point.y, HEIGHT - 26) });
        this.save();
        return;
      }
      if (this.tool === 'array') {
        const values = this.arrayValues.length ? this.arrayValues : Array(6).fill('');
        if (values.length > MAX_ARRAY_LENGTH) {
          this.$refs.arrayInput.focus();
          return;
        }
        this.items.push({
          kind: 'array', values,
          x: Math.max(4, Math.min(point.x, WIDTH - cellWidth(values.length) * values.length - 4)),
          y: Math.min(point.y, HEIGHT - 57),
        });
        this.save();
        return;
      }

      const points = isFreehand(this.tool) ? [point] : [point, point];
      const item = { kind: this.tool, points };
      if (this.tool === 'pen') {
        item.color = this.penColor;
        item.width = this.penWidth;
      }
      this.stroke = { id: event.pointerId, key: this.loadedKey, item };
      canvas.setPointerCapture(event.pointerId);
      this.requestPaint();
    },
    moveDrawing(event) {
      if (!this.stroke || this.stroke.id !== event.pointerId) return;
      const { kind, points } = this.stroke.item;
      const next = this.point(event);
      if (isFreehand(kind)) {
        const last = points[points.length - 1];
        if (Math.hypot(next.x - last.x, next.y - last.y) < 1.5) return;
        points.push(next);
      } else {
        points[1] = next;
      }
      this.requestPaint();
    },
    finishDrawing(event) {
      if (!this.stroke || this.stroke.id !== event.pointerId) return;
      this.moveDrawing(event);
      const { key, item } = this.stroke;
      this.stopStroke();
      const [start, end] = item.points;
      if (key === this.storageKey && (isFreehand(item.kind) || Math.hypot(end.x - start.x, end.y - start.y) >= 3)) {
        this.items.push(item);
        this.save();
      } else {
        this.requestPaint();
      }
    },
    stopStroke() {
      const stroke = this.stroke;
      this.stroke = null;
      const canvas = this.$refs.canvas;
      if (stroke && canvas && canvas.hasPointerCapture(stroke.id)) canvas.releasePointerCapture(stroke.id);
    },
    cancelDrawing(event) {
      if (!this.stroke || this.stroke.id !== event.pointerId) return;
      this.stopStroke();
      this.requestPaint();
    },
    undo() {
      if (!this.canUndo) return;
      this.stopStroke();
      if (this.items.length) {
        this.items.pop();
      } else {
        this.items = this.cleared;
        this.cleared = null;
      }
      this.save();
    },
    clear() {
      if (!this.items.length) return;
      this.stopStroke();
      this.cleared = this.items;
      this.items = [];
      this.save();
    },
    requestPaint() {
      if (!this.$refs.canvas || this.paintFrame) return;
      this.paintFrame = requestAnimationFrame(() => {
        this.paintFrame = 0;
        this.paint();
      });
    },
    paint() {
      const canvas = this.$refs.canvas;
      const ratio = window.devicePixelRatio || 1;
      const width = Math.round(canvas.clientWidth * ratio);
      const height = Math.round(canvas.clientHeight * ratio);
      if (!width || !height) return;
      if (canvas.width !== width) canvas.width = width;
      if (canvas.height !== height) canvas.height = height;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;
      ctx.setTransform(width / WIDTH, 0, 0, height / HEIGHT, 0, 0);
      ctx.clearRect(0, 0, WIDTH, HEIGHT);
      ctx.lineWidth = 2.6;
      ctx.lineJoin = 'round';
      ctx.lineCap = 'round';
      ctx.fillStyle = ctx.strokeStyle = getComputedStyle(canvas).color;
      this.items.forEach(item => drawItem(ctx, item));
      if (this.stroke) drawItem(ctx, this.stroke.item);
    },
  },
};
</script>

<style scoped>
.problem-scratchpad { display: grid; gap: 10px; min-width: 0; }
.scratchpad-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.scratchpad-history { display: flex; gap: 6px; margin-inline-start: auto; }
.scratchpad-tool {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 30px;
  padding: 4px 7px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.scratchpad-tool:hover, .scratchpad-tool[aria-pressed="true"] { color: var(--accent); background: var(--soft); }
.scratchpad-tool[aria-pressed="true"] { border-color: var(--line); }
.scratchpad-tool:disabled { opacity: .45; cursor: default; }
.scratchpad-tool:focus-visible, .scratchpad-swatch:focus-visible, canvas:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
.scratchpad-circle-icon, .scratchpad-eraser-icon {
  display: inline-block;
  box-sizing: border-box;
  width: 11px;
  height: 11px;
  border: 1px solid currentColor;
}
.scratchpad-circle-icon { border-radius: 50%; }
.scratchpad-eraser-icon { height: 8px; border-radius: 2px; transform: rotate(-35deg); }
.scratchpad-brush, .scratchpad-colors, .scratchpad-width {
  display: flex;
  align-items: center;
}
.scratchpad-brush { flex-wrap: wrap; gap: 8px 12px; }
.scratchpad-colors { gap: 4px; }
.scratchpad-swatch {
  width: 23px;
  height: 23px;
  padding: 3px;
  border: 1px solid transparent;
  border-radius: 50%;
  background: transparent;
  cursor: pointer;
}
.scratchpad-swatch::before { content: ''; display: block; height: 100%; border-radius: 50%; background: var(--swatch); }
.scratchpad-swatch:hover, .scratchpad-swatch[aria-pressed="true"] { border-color: var(--accent); }
.scratchpad-width { flex: 1 0 94px; gap: 5px; color: var(--muted); font-size: 12px; white-space: nowrap; }
.scratchpad-width input { flex: 1; min-width: 36px; width: 80px; margin: 0; accent-color: var(--accent); }
.scratchpad-width span { min-width: 2ch; text-align: right; font-variant-numeric: tabular-nums; }
.scratchpad-options { display: grid; gap: 4px; font-size: 12px; color: var(--muted); }
.scratchpad-options input {
  box-sizing: border-box;
  width: 100%;
  padding: 7px 9px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--surface);
  color: var(--text);
  font: inherit;
}
.scratchpad-paper {
  box-sizing: border-box;
  width: 100%;
  max-width: min(620px, max(280px, calc((100vh - 200px) * .8)));
  margin-inline: auto;
  border: 1px solid var(--line);
  border-radius: 8px;
  background-color: var(--surface);
  background-image: radial-gradient(var(--line) 1px, transparent 1px);
  background-size: 15px 15px;
}
canvas {
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: 4 / 5;
  border-radius: inherit;
  color: var(--text);
  cursor: crosshair;
  touch-action: none;
}
.scratchpad-error { margin: 0; color: #f56c6c; font-size: 12px; }
</style>
