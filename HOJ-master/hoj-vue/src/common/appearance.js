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
  motion: ['system', 'full', 'reduced'].includes(saved.motion) ? saved.motion : 'system',
  experience: saved.experience === 'minimal' ? 'minimal' : 'standard',
  spotlight: saved.spotlight !== false,
  homeGlass: saved.homeGlass !== false,
  morph: saved.morph !== false,
  autoContest: saved.autoContest !== false,
  compact: saved.compact === true,
});
// Effective values are transient: entering a contest must not overwrite preferences.
export const appearanceRuntime = Vue.observable({
  contest: false, minimal: false, reduced: false, dark: false,
  spotlight: false, homeGlass: false, morph: false, navigation: appearance.navigation,
});
function isDark(theme) {
  return theme === 'dark' || (theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
}
function isMotionReduced() {
  return appearanceRuntime.minimal || appearance.motion === 'reduced' || (appearance.motion === 'system' && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
}
export function syncAppearanceRoute(route) {
  appearanceRuntime.contest = !!(route.params && route.params.contestID) && !route.path.startsWith('/admin');
  applyAppearance();
}
export function applyAppearance() {
  const dark = isDark(appearance.theme);
  appearanceRuntime.dark = dark;
  appearanceRuntime.minimal = appearance.experience === 'minimal' || (appearance.autoContest && appearanceRuntime.contest);
  appearanceRuntime.navigation = appearanceRuntime.minimal ? 'normal' : appearance.navigation;
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  document.documentElement.dataset.density = appearance.compact ? 'compact' : 'comfortable';
  const reduced = isMotionReduced();
  appearanceRuntime.reduced = reduced;
  appearanceRuntime.spotlight = appearance.spotlight && !reduced;
  appearanceRuntime.homeGlass = appearance.homeGlass && !appearanceRuntime.minimal;
  appearanceRuntime.morph = appearance.morph && !reduced;
  document.documentElement.dataset.motion = reduced ? 'reduced' : 'full';
  document.documentElement.dataset.experience = appearanceRuntime.minimal ? 'minimal' : 'standard';
  document.documentElement.dataset.homeGlass = appearanceRuntime.homeGlass ? 'on' : 'off';
  if (reduced && themeTransition) themeTransition.skipTransition();
}
let themeTransition;
let themeRequest = 0;

// Native theme snapshots, inspired by Vuetify's View Transition theme switch.
// https://github.com/vuetifyjs/vuetify/blob/master/packages/vuetify/src/composables/theme.ts
function parabolicReveal(x, y, width, height) {
  // Aim from the selected button towards the centre; the curved front sweeps
  // across the page while the region behind the button fills in first.
  const dx = width / 2 - x;
  const dy = height / 2 - y;
  const length = Math.hypot(dx, dy);
  const nx = length ? dx / length : 0;
  const ny = length ? dy / length : 1;
  const curvature = .65 / Math.hypot(width, height);
  const corners = [[0, 0], [width, 0], [0, height], [width, height]].map(([cx, cy]) => ({
    u: (cx - x) * nx + (cy - y) * ny,
    v: -(cx - x) * ny + (cy - y) * nx,
  }));
  const frontEnd = Math.max(...corners.map(({ u, v }) => u + curvature * v * v)) + 32;
  const backEnd = Math.min(...corners.map(({ u }) => u)) - 32;
  const point = (u, v) => (x + nx * u - ny * v).toFixed(2) + ' ' + (y + ny * u + nx * v).toFixed(2);
  // Matching path commands allow native interpolation. No per-frame JS work.
  return Array.from({ length: 33 }, (_, index) => {
    const progress = index / 32;
    const front = frontEnd * progress;
    const back = backEnd * Math.min(progress / .22, 1);
    const span = Math.sqrt((front - back) / curvature);
    return { clipPath: 'path("M ' + point(back, -span) + ' Q ' + point(2 * front - back, 0) + ' ' + point(back, span) + ' Z")', offset: progress };
  });
}

export async function updateAppearance(key, value, origin) {
  const commit = () => {
    appearance[key] = value;
    savePreference('appearance', appearance);
    applyAppearance();
  };
  if (key !== 'theme') { commit(); return; }

  const request = ++themeRequest;
  if (themeTransition) themeTransition.skipTransition();
  themeTransition = null;
  const root = document.documentElement;
  root.classList.remove('theme-revealing');
  const reducedMotion = isMotionReduced();
  if (isDark(value) === (root.dataset.theme === 'dark') || reducedMotion ||
      !document.startViewTransition || !root.animate) {
    commit();
    return;
  }

  const x = Math.min(window.innerWidth, Math.max(0, origin ? origin.x : window.innerWidth / 2));
  const y = Math.min(window.innerHeight, Math.max(0, origin ? origin.y : 0));
  const frames = parabolicReveal(x, y, window.innerWidth, window.innerHeight);
  let transition;
  try {
    root.classList.add('theme-revealing');
    transition = document.startViewTransition(async () => {
      // A second click can arrive before the previous snapshot was captured.
      if (request !== themeRequest) return;
      commit();
      await Vue.nextTick();
    });
    themeTransition = transition;
    await transition.ready;
    if (request === themeRequest) {
      root.animate(frames, {
        duration: 1400,
        easing: 'cubic-bezier(.4, 0, .6, 1)',
        pseudoElement: '::view-transition-new(root)',
      });
    }
    await transition.finished;
  } catch (_) {
    // Unsupported snapshots (or an interrupted transition) must not block settings.
    if (transition) transition.skipTransition();
    if (request === themeRequest) commit();
  } finally {
    if (request === themeRequest) {
      root.classList.remove('theme-revealing');
      themeTransition = null;
    }
  }
}
