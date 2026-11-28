<template>
  <section class="learning-pomodoro" aria-label="番茄钟">
    <div class="pomodoro-modes" role="group" aria-label="计时模式">
      <el-button v-for="(mode, id) in modes" :key="id" size="mini" plain
        :type="id === timer.mode ? 'primary' : 'default'" :aria-pressed="String(id === timer.mode)"
        @click="selectMode(id)">{{ mode.label }} {{ mode.minutes }} 分钟</el-button>
    </div>

    <div class="pomodoro-dial" :style="{ '--progress': progress + '%' }">
      <output class="pomodoro-time" role="timer" aria-label="剩余时间" aria-live="off">{{ displayTime }}</output>
    </div>

    <div class="pomodoro-actions">
      <el-button type="primary" size="small" class="pomodoro-primary" @click="toggle">{{ actionLabel }}</el-button>
      <el-button size="small" icon="el-icon-refresh-left" :disabled="!started" @click="reset">重置</el-button>
    </div>
    <p v-if="finished" class="pomodoro-status" role="status">{{ completedLabel }}</p>
    <p v-if="!saved" class="pomodoro-status" role="status">当前浏览器无法保存计时，刷新后会重置。</p>
  </section>
</template>

<script>
import { readPreference, savePreference } from '@/common/appearance';

const MODES = {
  focus: { label: '专注', minutes: 25 },
  rest: { label: '休息', minutes: 5 },
};

function duration(mode) {
  return MODES[mode].minutes * 60 * 1000;
}

function freshTimer(mode) {
  return { mode, remaining: duration(mode), deadline: null };
}

export default {
  name: 'LearningPomodoro',
  props: { storageKey: { type: String, required: true } },
  data: () => ({ modes: MODES, timer: freshTimer('focus'), now: Date.now(), saved: true }),
  computed: {
    duration() { return duration(this.timer.mode); },
    remaining() {
      const remaining = this.timer.deadline === null ? this.timer.remaining : this.timer.deadline - this.now;
      return Math.max(0, Math.min(this.duration, remaining));
    },
    displayTime() {
      const seconds = Math.ceil(this.remaining / 1000);
      return String(Math.floor(seconds / 60)).padStart(2, '0') + ':' + String(seconds % 60).padStart(2, '0');
    },
    progress() { return (1 - this.remaining / this.duration) * 100; },
    started() { return this.timer.deadline !== null || this.remaining < this.duration; },
    finished() { return this.remaining === 0; },
    running() { return this.timer.deadline !== null && !this.finished; },
    nextMode() { return this.timer.mode === 'focus' ? 'rest' : 'focus'; },
    actionLabel() {
      if (this.running) return '暂停';
      if (this.finished) return '开始' + MODES[this.nextMode].label;
      return (this.started ? '继续' : '开始') + MODES[this.timer.mode].label;
    },
    completedLabel() { return MODES[this.timer.mode].label + '时间到了'; },
  },
  watch: { storageKey: { immediate: true, handler: 'load' } },
  mounted() {
    this.clock = window.setInterval(this.tick, 250);
    window.addEventListener('storage', this.syncStorage);
  },
  beforeDestroy() {
    window.clearInterval(this.clock);
    window.removeEventListener('storage', this.syncStorage);
  },
  methods: {
    load() {
      const saved = readPreference(this.storageKey, null);
      const valid = saved && Object.prototype.hasOwnProperty.call(MODES, saved.mode) &&
        Number.isFinite(saved.remaining) && (saved.deadline === null || Number.isFinite(saved.deadline));
      this.timer = valid ? saved : freshTimer('focus');
      this.now = Date.now();
      this.saved = true;
    },
    syncStorage(event) {
      if (event.key === 'cdutetc:' + this.storageKey || event.key === null) this.load();
    },
    persist(timer, now = Date.now()) {
      this.timer = timer;
      this.now = now;
      this.saved = savePreference(this.storageKey, timer);
    },
    selectMode(mode) {
      if (mode !== this.timer.mode) this.persist(freshTimer(mode));
    },
    toggle() {
      const now = Date.now();
      if (this.running) {
        const remaining = Math.max(0, Math.min(this.duration, this.timer.deadline - now));
        this.persist({ ...this.timer, remaining, deadline: null }, now);
        return;
      }
      const mode = this.finished ? this.nextMode : this.timer.mode;
      const remaining = this.finished ? duration(mode) : this.remaining;
      this.persist({ mode, remaining, deadline: now + remaining }, now);
    },
    reset() { this.persist(freshTimer(this.timer.mode)); },
    tick() {
      if (!this.running) return;
      this.now = Date.now();
      if (this.finished) this.$message.success(this.completedLabel);
    },
  },
};
</script>

<style scoped>
.learning-pomodoro { display: grid; justify-items: center; gap: 16px; padding-block: 4px; }
.pomodoro-modes, .pomodoro-actions { display: flex; flex-wrap: wrap; justify-content: center; gap: 8px; }
.pomodoro-modes .el-button, .pomodoro-actions .el-button { margin: 0; }
.pomodoro-dial {
  display: grid;
  width: min(100%, 148px);
  aspect-ratio: 1;
  box-sizing: border-box;
  padding: 5px;
  border-radius: 50%;
  background: conic-gradient(var(--accent) var(--progress), var(--line) 0);
}
.pomodoro-time {
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--surface);
  color: var(--text);
  font-size: 32px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.pomodoro-status { margin: 0; color: var(--muted); font-size: 13px; text-align: center; }
</style>
