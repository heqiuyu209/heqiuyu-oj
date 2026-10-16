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
export const KNOWLEDGE_AREAS = [
  { key: 'syntax', name: '语法', topics: '输入输出、控制结构、函数、语言特性' },
  { key: 'algorithms', name: '算法', topics: '枚举、二分、贪心、搜索、分治、数学' },
  { key: 'structures', name: '数据结构', topics: '栈、队列、并查集、树状数组、线段树' },
  { key: 'graphs', name: '图论', topics: '遍历、最短路、最小生成树、网络流' },
  { key: 'dp', name: 'DP', topics: '线性 DP、背包、区间 DP、树形 DP、状压 DP' },
];
export function draftKey(user, params = {}, language = '') {
  return 'draft:' + [user || 'guest', params.groupID || '-', params.contestID || '-', params.trainingID || '-', params.problemID || '-', language].map(encodeURIComponent).join(':');
}
