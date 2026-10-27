import axios from 'axios';
export default {
  virtualContestRun: cid => axios.get('/api/contests/' + encodeURIComponent(cid) + '/virtual-run'),
  startVirtualContest: (cid, data) => axios.post('/api/contests/' + encodeURIComponent(cid) + '/virtual-run', data),
};
