import axios from 'axios';
const account = id => '/api/learning/accounts/' + encodeURIComponent(id);
const browserSubmit = '/api/codeforces-browser-submit';
const codeforcesVerdicts = {
  OK: '通过（AC）', WRONG_ANSWER: '答案错误（WA）', COMPILATION_ERROR: '编译错误（CE）',
  TIME_LIMIT_EXCEEDED: '超时（TLE）', MEMORY_LIMIT_EXCEEDED: '超内存（MLE）', RUNTIME_ERROR: '运行错误（RE）',
  PENDING: '等待判题', TESTING: '判题中',
};

export function codeforcesVerdictLabel(verdict) {
  return codeforcesVerdicts[verdict] || verdict;
}

export function codeforcesProblemUrl(problem) {
  const match = /^CF-([1-9][0-9]{0,8})([A-Za-z][A-Za-z0-9]{0,15})$/.exec(problem || '');
  return match ? 'https://codeforces.com/problemset/problem/' + match[1] + '/' + match[2].toUpperCase() : '';
}
export default {
  externalAccounts: config => axios.get('/api/learning/accounts', config),
  externalProfile: uid => axios.get('/api/learning/users/' + encodeURIComponent(uid) + '/external'),
  externalLink: (handle, platform = 'CODEFORCES') => axios.post('/api/learning/accounts', { platform, handle }),
  externalUnlink: id => axios.delete(account(id)),
  externalProof: id => axios.post(account(id) + '/proof'),
  externalSync: (id, config) => axios.post(account(id) + '/sync', undefined, config),
  codeforcesSyncState: config => axios.get('/api/learning/codeforces-sync', config),
  requestCodeforcesSync: (id, scope, config) => axios.post(account(id) + '/codeforces-sync', scope, config),
  createCodeforcesBrowserSubmission: input => axios.post(browserSubmit, input, { hojInlineError: true }),
  latestCodeforcesBrowserSubmission: problem => axios.get(browserSubmit + '/latest', { params: { problem }, hojInlineError: true }),
  codeforcesBrowserSubmission: id => axios.get(browserSubmit + '/' + encodeURIComponent(id), { hojInlineError: true }),
  externalVerify: id => axios.post(account(id) + '/verify'),
  remoteCredentials: id => axios.get(account(id) + '/submission-credentials'),
  saveRemoteCredentials: (id, cookieHeader, version, authorized) => axios.put(account(id) + '/submission-credentials', { cookieHeader, version, authorized }, { hojInlineError: true }),
  deleteRemoteCredentials: (id, version) => axios.delete(account(id) + '/submission-credentials', { params: { version } }),
};
