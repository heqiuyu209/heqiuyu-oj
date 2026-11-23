import axios from 'axios';
const root = '/api/personal-remote';
export default {
  personalRemoteAccounts: scope => axios.post(root + '/accounts', scope),
  personalRemoteOptions: input => axios.post(root + '/options', input),
  personalRemoteSubmit: input => axios.post(root + '/submissions', input),
  personalRemoteStatus: id => axios.get(root + '/submissions/' + encodeURIComponent(id)),
  personalRemoteRequest: id => axios.get(root + '/requests/' + encodeURIComponent(id)),
  personalRemoteReconcile: id => axios.post(root + '/submissions/' + encodeURIComponent(id) + '/reconcile'),
  personalRemoteCancel: id => axios.post(root + '/submissions/' + encodeURIComponent(id) + '/cancel'),
};
