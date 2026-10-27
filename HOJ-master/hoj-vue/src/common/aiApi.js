import axios from 'axios';
export default {
  aiProblemContext: reference => axios.post('/api/ai/problem-context', reference),
  aiSettings: () => axios.get('/api/ai/settings'),
  saveAiSettings: settings => axios.put('/api/ai/settings', settings),
  removeAiSettings: () => axios.delete('/api/ai/settings'),
  aiChat: chat => axios.post('/api/ai/chat', chat, { timeout: 70000 }),
  aiRecommendations: params => axios.get('/api/ai/recommendations', { params }),
  aiExplainRecommendations: data => axios.post('/api/ai/recommendations/explain', data, { timeout: 70000 }),
};
