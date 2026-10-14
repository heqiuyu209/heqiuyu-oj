import Vue from 'vue';

export function readPreference(key, fallback) {
  try {
    const value = JSON.parse(localStorage.getItem('cdutetc:' + key));
    return value == null ? fallback : value;
  } catch (_) { return fallback; }
}
export function savePreference(key, value) {
  try {
    localStorage.setItem('cdutetc:' + key, JSON.stringify(value));
    return true;
  } catch (_) { return false; }
}
const saved = readPreference('appearance', {});
export const appearance = Vue.observable({
  theme: ['light', 'dark', 'system'].includes(saved.theme) ? saved.theme : 'light',
  navigation: ['normal', 'glass', 'dynamic'].includes(saved.navigation) ? saved.navigation : 'dynamic',
  compact: saved.compact === true,
});
export function applyAppearance() {
  const dark = appearance.theme === 'dark' || (appearance.theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  document.documentElement.dataset.density = appearance.compact ? 'compact' : 'comfortable';
}
export function updateAppearance(key, value) {
  appearance[key] = value;
  savePreference('appearance', appearance);
  applyAppearance();
}
