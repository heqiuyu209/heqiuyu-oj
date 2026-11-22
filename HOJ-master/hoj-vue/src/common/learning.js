// Missing scores stay missing. Never map OI score or coarse HOJ difficulty to CF rating.
export function finiteRating(value) {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
}
const sourceNames = { CF: 'Codeforces', GYM: 'Codeforces Gym', NC: '牛客', NOWCODER: '牛客', QOJ: 'QOJ', AC: 'AtCoder', ATCODER: 'AtCoder', HDU: 'HDU', POJ: 'POJ', SPOJ: 'SPOJ', LIBRE: 'LibreOJ', LG: '洛谷' };
export function problemSource(problem = {}) {
  // HOJ's list VO omits isRemote; existing remote IDs carry the platform prefix.
  const prefix = String(problem.problemId || '').split('-')[0].toUpperCase();
  const legacySource = problem.isRemote !== false && sourceNames[prefix] ? prefix : '';
  const source = problem.sourcePlatform || legacySource;
  return sourceNames[String(source).toUpperCase()] || source || (problem.isRemote ? '远程题库' : '本校题库');
}
export function cfRating(problem = {}) {
  const normalized = finiteRating(problem.cfRating);
  if (normalized !== null) return normalized;
  return problemSource(problem) === 'Codeforces' ? finiteRating(problem.sourceRating) : null;
}
export function taskRating(problem = {}) {
  const rating = finiteRating(problem.rating);
  return rating === null ? cfRating(problem) : rating;
}
export function safeSourceUrl(value) {
  try { const url = new URL(value); return ['https:', 'http:'].includes(url.protocol) ? url.href : ''; } catch (_) { return ''; }
}
export function draftKey(user, params = {}, language = '') {
  return 'draft:' + [user || 'guest', params.groupID || '-', params.contestID || '-', params.trainingID || '-', params.problemID || '-', language].map(encodeURIComponent).join(':');
}
