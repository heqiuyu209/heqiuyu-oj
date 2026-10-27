import axios from 'axios';
import { videoAccessPath, serializeVideoAccess } from './knowledgeVideo';
const admin = '/api/admin/knowledge';
export default {
  knowledgeDirectory: params => axios.get('/api/knowledge/documents', { params }),
  knowledgeDocument: (id, version) => axios.get('/api/knowledge/documents/' + encodeURIComponent(id), { params: { version } }),
  knowledgeSearch: data => axios.post('/api/knowledge/search', data, { timeout: 35000 }),
  knowledgeAnswer: data => axios.post('/api/knowledge/answer', data, { timeout: 100000 }),
  knowledgeAdminList: params => axios.get(admin, { params }),
  knowledgeAdminDetail: id => axios.get(admin + '/documents/' + encodeURIComponent(id)),
  knowledgeAdminVersion: (id, version) => axios.get(admin + '/documents/' + encodeURIComponent(id) + '/versions/' + version),
  knowledgeUpload: (id, data) => axios.post(admin + '/documents' + (id ? '/' + encodeURIComponent(id) + '/versions' : ''), data),
  knowledgeUploadPdf: (id, file, metadata) => axios.post(admin + '/documents/' + (id ? encodeURIComponent(id) + '/pdf-versions' : 'pdf'), file, {
    params: metadata, headers: { 'Content-Type': 'application/pdf' }, timeout: metadata.ocr ? 120000 : 70000,
  }),
  knowledgeVideoImports: () => axios.get(admin + '/video-imports'),
  knowledgeVideoImport: id => axios.get(admin + '/video-imports/' + encodeURIComponent(id)),
  knowledgeUploadVideo: (file, metadata, options = {}) => axios.post(admin + '/video-imports', file, {
    ...options, params: metadata, headers: { 'Content-Type': /\.webm$/i.test(file.name) ? 'video/webm' : 'video/mp4' }, timeout: 600000,
  }),
  knowledgeVideoCommand: (id, action, data) => axios.post(admin + '/video-imports/' + encodeURIComponent(id) + '/' + action, data),
  knowledgeVideoAccess: (resource, current = () => true) => {
    const path = videoAccessPath(resource);
    return serializeVideoAccess(path, () => current() ? axios.post(path, null, { params: !resource.maintenance && !resource.draft ? { version: resource.version } : {}, timeout: 15000 }) : null);
  },
  knowledgeVideoClear: resource => {
    const path = videoAccessPath(resource);
    return serializeVideoAccess(path, () => axios.delete(path, { timeout: 10000 }));
  },
  knowledgeOriginal: (id, version, maintenance = false) => axios.get(maintenance
    ? admin + '/documents/' + encodeURIComponent(id) + '/versions/' + version + '/original'
    : '/api/knowledge/documents/' + encodeURIComponent(id) + '/original', { params: maintenance ? {} : { version }, responseType: 'blob', timeout: 30000 }),
  knowledgeChange: (id, action, data) => action === 'visibility'
    ? axios.put(admin + '/documents/' + encodeURIComponent(id) + '/visibility', data)
    : axios.post(admin + '/documents/' + encodeURIComponent(id) + '/' + action, data),
};
