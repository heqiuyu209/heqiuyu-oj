import axios from 'axios';
export default {
  learningTopics: () => axios.get('/api/learning/topics'),
  learningProblemHistory: params => axios.get('/api/learning/problem-history', { params }),
  learningProfile: uid => axios.get('/api/learning/users/' + encodeURIComponent(uid) + '/profile'),
  learningEvidenceProfile: uid => axios.get('/api/learning/users/' + encodeURIComponent(uid) + '/evidence'),
  trainingPlans: params => axios.get('/api/learning/training-plans', { params }),
  saveTrainingPlan: data => axios.post('/api/learning/training-plans', data),
  trainingPlan: id => axios.get('/api/learning/training-plans/' + encodeURIComponent(id)),
  updateTrainingPlan: (id, data) => axios.put('/api/learning/training-plans/' + encodeURIComponent(id), data),
};
