<template>
  <button class="theme-toggle" type="button" :class="{ 'is-dark': runtime.dark }" :aria-label="runtime.dark ? '切换为浅色主题' : '切换为深色主题'" :title="runtime.dark ? '切换为浅色主题' : '切换为深色主题'" @click="toggle">
    <svg class="theme-sun" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><circle cx="12" cy="12" r="4" /><path stroke-linecap="round" d="M12 2v2m0 16v2M2 12h2m16 0h2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></svg>
    <svg class="theme-moon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path stroke-linejoin="round" d="M20.7 14A8.8 8.8 0 0 1 10 3.3 9 9 0 1 0 20.7 14Z" /></svg>
  </button>
</template>
<script>
import { appearanceRuntime, updateAppearance } from '@/common/appearance';
export default {
  name: 'ThemeToggle',
  data: () => ({ runtime: appearanceRuntime }),
  methods: {
    toggle(event) {
      const rect = event.currentTarget.getBoundingClientRect();
      return updateAppearance('theme', this.runtime.dark ? 'light' : 'dark', { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 });
    },
  },
};
</script>
<style scoped>
.theme-toggle { position: relative; display: inline-grid; place-items: center; width: 38px; height: 38px; padding: 0; flex-shrink: 0; border-radius: 50%; border: 1px solid transparent; background: transparent; color: var(--nav-ink); cursor: pointer; }
.theme-toggle:hover { background: var(--nav-hover); }
.theme-toggle svg { position: absolute; width: 22px; height: 22px; transition: transform 320ms ease, opacity 220ms ease; }
.theme-moon { opacity: 0; transform: rotate(-60deg) scale(.5); }
.is-dark .theme-moon { opacity: 1; transform: rotate(0) scale(1); }
.is-dark .theme-sun { opacity: 0; transform: rotate(70deg) scale(.5); }
html[data-motion='reduced'] .theme-toggle svg { transition: none; }
</style>
