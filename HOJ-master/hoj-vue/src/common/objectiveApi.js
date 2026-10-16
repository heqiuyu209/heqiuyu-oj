import axios from 'axios';

const base = '/api/objective';
const admin = '/api/admin/objective';
export default {
  objectiveQuestions: params => axios.get(base + '/questions', { params }),
  objectiveNeighbors: (id, params) => axios.get(base + '/questions/' + id + '/neighbors', { params }),
  objectiveSets: params => axios.get(base + '/sets', { params }),
  objectiveSet: id => axios.get(base + '/sets/' + id),
  objectiveStart: data => axios.post(base + '/runs', data),
  objectiveRuns: params => axios.get(base + '/runs', { params }),
  objectiveRun: id => axios.get(base + '/runs/' + id),
  objectiveAnswer: (id, data) => axios.put(base + '/runs/' + id + '/answers', data),
  objectiveFinish: id => axios.post(base + '/runs/' + id + '/finish'),
  adminObjectiveQuestions: params => axios.get(admin + '/questions', { params }),
  adminObjectiveQuestion: id => axios.get(admin + '/questions/' + id),
  adminSaveObjectiveQuestion: data => axios({ url: admin + '/questions', method: data.id ? 'put' : 'post', data }),
  adminObjectiveSets: params => axios.get(admin + '/sets', { params }),
  adminObjectiveSet: id => axios.get(admin + '/sets/' + id),
  adminSaveObjectiveSet: data => axios({ url: admin + '/sets', method: data.id ? 'put' : 'post', data }),
};
