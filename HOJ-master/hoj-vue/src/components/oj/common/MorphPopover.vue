<template>
  <div ref="anchor" class="morph-anchor">
    <slot name="trigger" :open="open && !closing" :toggle="toggle" />
    <div v-show="open" ref="panel" class="morph-popover" :style="{ width: width + 'px' }" role="dialog" :aria-label="label" @keydown.esc.stop.prevent="close(true)">
      <slot :close="close" />
    </div>
  </div>
</template>
<script>
import { appearanceRuntime } from '@/common/appearance';
// Trigger-to-panel transform inspired by Motion Primitives' Morphing Popover.
// Native transforms keep this Vue 2 implementation independent of React/Motion.
export default {
  name: 'MorphPopover',
  props: { width: { type: Number, default: 344 }, label: { type: String, default: '个人菜单' } },
  data: () => ({ open: false, closing: false, runtime: appearanceRuntime }),
  mounted() { document.body.appendChild(this.$refs.panel); },
  beforeDestroy() {
    this.sequence = (this.sequence || 0) + 1;
    this.stopListening();
    if (this.animation) this.animation.cancel();
    this.$refs.panel.remove();
    this.$emit('visibility-change', false);
  },
  watch: {
    'runtime.morph'(enabled) { if (!enabled && this.animation) this.animation.finish(); },
  },
  methods: {
    toggle() { this.open && !this.closing ? this.close(true) : this.show(); },
    show() {
      const sequence = this.sequence = (this.sequence || 0) + 1;
      if (this.animation) this.animation.cancel();
      this.closing = false;
      this.open = true;
      this.$emit('visibility-change', true);
      this.listen();
      this.$nextTick(() => {
        if (sequence !== this.sequence) return;
        this.position();
        this.play(false);
        const first = this.$refs.panel.querySelector('[data-popover-focus]');
        if (first) first.focus({ preventScroll: true });
      });
    },
    close(restoreFocus = false) {
      if (!this.open || this.closing) return;
      const sequence = this.sequence = (this.sequence || 0) + 1;
      this.closing = true;
      this.$emit('visibility-change', false);
      this.stopListening();
      if (this.animation) this.animation.cancel();
      const animation = this.play(true);
      const finish = () => {
        if (sequence !== this.sequence) return;
        this.open = false;
        this.closing = false;
        if (restoreFocus) this.$refs.anchor.querySelector('button').focus({ preventScroll: true });
      };
      if (animation) animation.finished.then(finish, finish);
      else finish();
    },
    position() {
      const panel = this.$refs.panel, anchor = this.$refs.anchor.getBoundingClientRect();
      const width = Math.min(this.width, window.innerWidth - 24);
      panel.style.width = width + 'px';
      panel.style.maxHeight = Math.max(120, window.innerHeight - 24) + 'px';
      const height = panel.offsetHeight;
      const top = Math.max(12, Math.min(anchor.bottom + 10, window.innerHeight - height - 12));
      panel.style.top = top + 'px';
      panel.style.left = Math.max(12, Math.min(anchor.right - width, window.innerWidth - width - 12)) + 'px';
    },
    play(reverse) {
      const panel = this.$refs.panel;
      if (!this.runtime.morph || !panel.animate) return null;
      const anchor = this.$refs.anchor.getBoundingClientRect(), rect = panel.getBoundingClientRect();
      const collapsed = { transform: `translate(${anchor.left - rect.left}px, ${anchor.top - rect.top}px) scale(${anchor.width / rect.width}, ${anchor.height / rect.height})`, opacity: .3, borderRadius: '28px' };
      const expanded = { transform: 'translate(0, 0) scale(1, 1)', opacity: 1, borderRadius: '20px' };
      this.animation = panel.animate(reverse ? [expanded, collapsed] : [collapsed, expanded], {
        duration: reverse ? 220 : 340, easing: 'cubic-bezier(.22, 1, .36, 1)',
      });
      return this.animation;
    },
    queuePosition() {
      if (!this.open || this.closing || this.positionFrame) return;
      this.positionFrame = requestAnimationFrame(() => { this.positionFrame = null; this.position(); });
    },
    onOutside(event) {
      if (!this.$refs.panel.contains(event.target) && !this.$refs.anchor.contains(event.target)) this.close();
    },
    onEscape(event) { if (event.key === 'Escape') { event.preventDefault(); this.close(true); } },
    listen() {
      this.stopListening();
      document.addEventListener('pointerdown', this.onOutside, true);
      document.addEventListener('focusin', this.onOutside);
      document.addEventListener('keydown', this.onEscape);
      window.addEventListener('resize', this.queuePosition);
      window.addEventListener('scroll', this.queuePosition, { passive: true, capture: true });
      if (window.ResizeObserver) {
        this.observer = new ResizeObserver(this.queuePosition);
        this.observer.observe(this.$refs.panel);
        this.observer.observe(this.$refs.anchor.closest('#header') || this.$refs.anchor);
      }
    },
    stopListening() {
      document.removeEventListener('pointerdown', this.onOutside, true);
      document.removeEventListener('focusin', this.onOutside);
      document.removeEventListener('keydown', this.onEscape);
      window.removeEventListener('resize', this.queuePosition);
      window.removeEventListener('scroll', this.queuePosition, true);
      if (this.observer) this.observer.disconnect();
      cancelAnimationFrame(this.positionFrame);
      this.positionFrame = null;
    },
  },
};
</script>
<style>
.morph-anchor { display: inline-flex; align-items: center; flex-shrink: 0; }
.morph-popover { position: fixed; z-index: 3100; overflow: auto; overscroll-behavior: contain; box-sizing: border-box; padding: 16px; border: 1px solid var(--line); border-radius: 20px; background: var(--surface); color: var(--text); box-shadow: 0 16px 50px rgba(5, 24, 38, .16); transform-origin: top left; scrollbar-width: thin; }
.morph-popover .el-radio-button__inner { padding: 9px 12px; }
</style>
