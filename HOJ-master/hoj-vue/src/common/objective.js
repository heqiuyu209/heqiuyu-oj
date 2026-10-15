export const questionTypes = { SINGLE_CHOICE: '单选题', MULTIPLE_CHOICE: '多选题', TRUE_FALSE: '判断题' };
export const modes = { PRACTICE: '逐题练习', PAPER: '整卷答题' };
export const releasePolicies = { AFTER_ANSWER: '提交本题后', AFTER_FINISH: '交完整卷后', AT_TIME: '指定时间', NEVER: '暂不开放' };
export function requestId() {
  const values = new Uint32Array(4);
  if (window.crypto && window.crypto.getRandomValues) window.crypto.getRandomValues(values);
  else values.forEach((_, i) => { values[i] = Math.floor(Math.random() * 0xffffffff); });
  return 'obj_' + Array.from(values).map(value => value.toString(16).padStart(8, '0')).join('');
}
export function responseData(response) { return response.data.data; }
export function errorMessage(error) {
  return (error && error.data && error.data.msg) || (error && error.response && error.response.data && error.response.data.msg) || '加载失败，请重试';
}
export function releaseLabel(policy, at) {
  return policy === 'AT_TIME' && at ? new Date(at).toLocaleString() + ' 开放' : releasePolicies[policy] || '暂不开放';
}
