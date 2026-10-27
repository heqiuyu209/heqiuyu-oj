const accessRequests = new Map();

// Serialize Set-Cookie responses for a resource, including cleanup across login epochs.
export function serializeVideoAccess(path, action) {
  const earlier = accessRequests.get(path) || Promise.resolve();
  const current = earlier.catch(() => {}).then(action);
  accessRequests.set(path, current);
  const release = () => { if (accessRequests.get(path) === current) accessRequests.delete(path); };
  current.then(release, release);
  return current;
}

export function videoAccessPath(resource) {
  if (!resource || !/^[a-f0-9]{32}$/.test(resource.id)) throw new Error('视频资料标识无效');
  const admin = '/api/admin/knowledge';
  if (resource.draft) return admin + '/video-imports/' + resource.id + '/video/access';
  if (!Number.isInteger(resource.version) || resource.version < 1 || resource.version > 2147483647) throw new Error('视频资料版本无效');
  return resource.maintenance ? admin + '/documents/' + resource.id + '/versions/' + resource.version + '/video/access'
    : '/api/knowledge/documents/' + resource.id + '/video/access';
}

export function videoMediaPath(resource) {
  return videoAccessPath(resource).replace(/\/access$/, '') + (!resource.maintenance && !resource.draft ? '?version=' + resource.version : '');
}

export function videoClock(milliseconds) {
  const value = Math.max(0, Math.floor(milliseconds));
  return [Math.floor(value / 3600000), Math.floor(value / 60000) % 60, Math.floor(value / 1000) % 60]
    .map(part => String(part).padStart(2, '0')).join(':') + '.' + String(value % 1000).padStart(3, '0');
}

export function videoTranscript(content, editing = false) {
  if (typeof content !== 'string' || content.length > 800000 || Array.from(content).length > (editing ? 400000 : 200000)) throw new Error('视频字幕最多 20 万字');
  let body;
  try { body = JSON.parse(content); } catch (_) { throw new Error('视频字幕格式无效，请重新载入'); }
  if (!body || body.schema !== 1 || Object.keys(body).length !== 3 || !Number.isSafeInteger(body.durationMs) || body.durationMs < 1 || body.durationMs > 7200000
    || !Array.isArray(body.segments) || !body.segments.length || body.segments.length > 5000) throw new Error('视频时间轴无效，请重新载入');
  let previous = 0;
  for (const segment of body.segments) {
    if (!segment || Object.keys(segment).length !== 3 || !Number.isSafeInteger(segment.startMs) || !Number.isSafeInteger(segment.endMs)
      || segment.startMs < previous || segment.endMs <= segment.startMs || segment.endMs > body.durationMs || typeof segment.text !== 'string'
      || !editing && (!segment.text.trim() || Array.from(segment.text).length > 4000 || /[\u0000-\u0008\u000b-\u001f]/.test(segment.text))) throw new Error('每段字幕须保留文字，最多 4,000 字，且不能包含控制字符');
    previous = segment.endMs;
  }
  return body;
}
