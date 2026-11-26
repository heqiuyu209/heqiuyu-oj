import axios from 'axios';
const base = platform => '/api/admin/remote-sources/' + (['atcoder', 'nowcoder'].includes(platform) ? platform : 'codeforces');
const source = key => typeof key === 'string' && key.startsWith('nowcoder:') ? 'nowcoder' : typeof key === 'string' && key.startsWith('atcoder:task:') ? 'atcoder' : 'codeforces';
export default {
  remoteCatalogue: (params, platform = 'codeforces') => axios.get(base(platform), { params }),
  remoteNowCoderLookup: sourceId => axios.post(base('nowcoder') + '/lookup', { sourceId }),
  remoteCatalogueSync: (platform = 'codeforces') => axios.post(base(platform) + '/sync'),
  remoteImport: problemKey => axios.post(base(source(problemKey)) + '/imports', { problemKey }),
  remoteStatement: problemKey => axios.get(base(source(problemKey)) + '/statements', { params: { problemKey } }),
  remoteStatementRefresh: (problemKey, autoRefresh) => axios.post(base(source(problemKey)) + '/refresh', { problemKey, autoRefresh }),
  previewCodeforcesHtml: data => axios.post(base('codeforces') + '/statements/html-preview', data),
  remoteStatementSave: (language, data) => axios.post(base(source(data.problemKey)) + '/statements/' + language, data),
  remoteTranslate: data => axios.post('/api/admin/remote-sources/translation', data, { timeout: 70000 }),
  publicRemoteStatement: pid => axios.get('/api/problems/' + encodeURIComponent(pid) + '/statements'),
};
