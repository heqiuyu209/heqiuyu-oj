import { appearanceRuntime } from './appearance';

// One pending frame for the hovered surface, with no work while the pointer rests.
// A Vue 2 directive also makes cleanup follow each card's actual lifetime.
export default {
  bind(el, binding, vnode) {
    let frame = 0, point, listening = false;
    const leave = () => {
      cancelAnimationFrame(frame); frame = 0;
      el.classList.remove('spotlight-active');
    };
    const move = event => {
      if (!window.matchMedia('(hover: hover) and (pointer: fine)').matches) return;
      point = { x: event.clientX, y: event.clientY };
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        const rect = el.getBoundingClientRect();
        el.style.setProperty('--spot-x', point.x - rect.left + 'px');
        el.style.setProperty('--spot-y', point.y - rect.top + 'px');
        el.classList.add('spotlight-active');
      });
    };
    const enable = enabled => {
      if (enabled === listening) return;
      listening = enabled;
      if (enabled) {
        el.addEventListener('pointermove', move, { passive: true });
        el.addEventListener('pointerleave', leave);
      } else {
        el.removeEventListener('pointermove', move);
        el.removeEventListener('pointerleave', leave);
        leave();
      }
    };
    el.classList.add('spotlight-surface');
    const stop = vnode.context.$watch(() => appearanceRuntime.spotlight, enable, { immediate: true });
    el._disposeSpotlight = () => { stop(); enable(false); leave(); };
  },
  unbind(el) { if (el._disposeSpotlight) el._disposeSpotlight(); delete el._disposeSpotlight; },
};
