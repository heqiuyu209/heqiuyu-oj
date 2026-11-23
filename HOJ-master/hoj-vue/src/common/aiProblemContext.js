// Page-local descriptors only. No statement, answer, code, key or persistence here.
let active = null;
const listeners = new Set();
export function publishAiProblem(owner, descriptor) {
  if (!descriptor && (!active || active.owner !== owner)) return;
  const next = descriptor ? { owner, ...descriptor } : null;
  if (JSON.stringify(next) === JSON.stringify(active)) return;
  active = next;
  listeners.forEach(listener => listener(active));
}
export function currentAiProblem() { return active; }
export function subscribeAiProblem(listener) {
  listeners.add(listener); listener(active);
  return () => listeners.delete(listener);
}
