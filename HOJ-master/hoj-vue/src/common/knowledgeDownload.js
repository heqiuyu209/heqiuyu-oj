// Validate error blobs before creating a download; the shared Axios handler
// deliberately delegates binary response errors to their caller.
const invalid = message => Object.assign(new Error(message), { data: { msg: message } });
export async function knowledgePdfBlob(response) {
  const blob = response && response.data;
  if (!(blob instanceof Blob)) throw invalid('PDF 原件响应无效，请重试。');
  const type = String((response.headers || {})['content-type'] || blob.type).split(';')[0].trim().toLowerCase();
  if (response.status !== 200 || type !== 'application/pdf') {
    let message = 'PDF 原件不可用，请重新核对资料权限。';
    if (blob.size <= 16384 && type === 'application/json') {
      try { const value = JSON.parse(await blob.text()); if (typeof value.msg === 'string') message = value.msg; } catch (_) { /* Use the fixed error. */ }
    }
    throw invalid(message);
  }
  if (blob.size < 5 || blob.size > 33554432 || await blob.slice(0, 5).text() !== '%PDF-') throw invalid('PDF 原件响应无效，请重试。');
  return blob;
}

export function saveKnowledgePdf(blob, name) {
  const url = URL.createObjectURL(blob), anchor = document.createElement('a');
  anchor.href = url; anchor.download = typeof name === 'string' ? name.replace(/[\x00-\x1f\x7f/\\]/g, '_') : 'document.pdf';
  document.body.appendChild(anchor); anchor.click(); anchor.remove();
  // Allow the browser to start reading before releasing the in-memory URL.
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
