const fs = require('fs'), path = require('path'), assert = require('assert').strict;
(async () => {
  const source = fs.readFileSync(path.join(__dirname, '../src/common/knowledgeVideo.js'), 'utf8');
  const { videoTranscript, videoClock, videoAccessPath, videoMediaPath, serializeVideoAccess } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
  const id = 'a'.repeat(32), body = { schema: 1, durationMs: 80000, segments: [{ startMs: 1000, endMs: 2000, text: '前缀和 "引号"\n<img src=x onerror=alert(1)>' }] };
  assert.equal(videoTranscript(JSON.stringify(body)).segments[0].text, body.segments[0].text);
  assert.equal(videoClock(64720), '00:01:04.720');
  assert.equal(videoMediaPath({ id, version: 2 }), '/api/knowledge/documents/' + id + '/video?version=2');
  assert.equal(videoMediaPath({ id, draft: true }), '/api/admin/knowledge/video-imports/' + id + '/video');
  assert.equal(videoAccessPath({ id, maintenance: true, version: 3 }), '/api/admin/knowledge/documents/' + id + '/versions/3/video/access');
  for (const resource of [{ id: '../../x', version: 1 }, { id, version: 0 }, { id, version: '2' }, { id, version: 2147483648 }]) assert.throws(() => videoAccessPath(resource));
  for (const change of [value => value.segments[0].endMs = 1000, value => value.segments[0].startMs = -1, value => value.durationMs = 7200001, value => value.segments[0].text = '', value => value.segments[0].text = 'a'.repeat(4001)]) {
    const invalid = JSON.parse(JSON.stringify(body)); change(invalid); assert.throws(() => videoTranscript(JSON.stringify(invalid)));
  }
  const editing = { ...body, segments: [{ ...body.segments[0], text: '' }] };
  assert.equal(videoTranscript(JSON.stringify(editing), true).segments[0].text, '');
  const order = []; let release;
  const oldGrant = serializeVideoAccess('resource', () => new Promise(resolve => { order.push('old-start'); release = () => { order.push('old-end'); resolve(); }; }));
  const cleanup = serializeVideoAccess('resource', () => order.push('old-clear'));
  const newGrant = serializeVideoAccess('resource', () => order.push('new-grant'));
  await Promise.resolve(); await Promise.resolve(); assert.deepEqual(order, ['old-start']); release();
  await Promise.all([oldGrant, cleanup, newGrant]); assert.deepEqual(order, ['old-start','old-end','old-clear','new-grant']);
  await assert.rejects(serializeVideoAccess('resource', () => Promise.reject(new Error('synthetic failure'))));
  assert.equal(await serializeVideoAccess('resource', () => 42), 42);
  console.log('Video contracts passed: transcript bounds, editable drafts, scoped URLs, and serialized grant/cleanup across sessions.');
})().catch(error => { console.error(error); process.exitCode = 1; });
