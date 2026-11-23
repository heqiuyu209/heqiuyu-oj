import axios from 'axios';
const account = id => '/api/learning/accounts/' + encodeURIComponent(id);
export default {
  externalAccounts: () => axios.get('/api/learning/accounts'),
  externalProfile: uid => axios.get('/api/learning/users/' + encodeURIComponent(uid) + '/external'),
  externalLink: (handle, platform = 'CODEFORCES') => axios.post('/api/learning/accounts', { platform, handle }),
  externalUnlink: id => axios.delete(account(id)),
  externalProof: id => axios.post(account(id) + '/proof'),
  externalSync: id => axios.post(account(id) + '/sync'),
  externalVerify: id => axios.post(account(id) + '/verify'),
  remoteCredentials: id => axios.get(account(id) + '/submission-credentials'),
  saveRemoteCredentials: (id, cookieHeader, version, authorized) => axios.put(account(id) + '/submission-credentials', { cookieHeader, version, authorized }),
  deleteRemoteCredentials: (id, version) => axios.delete(account(id) + '/submission-credentials', { params: { version } }),
};
