import DOMPurify from 'dompurify';

function singleLine(value) {
  return String(value || '').replace(/\s+/g, ' ').trim().replace(/[\\`*_{}\[\]()<>#!|]/g, '\\$&');
}

function fenced(value, language = '') {
  const source = String(value == null ? '' : value);
  const length = (source.match(/`{3,}/g) || []).reduce((max, run) => Math.max(max, run.length + 1), 3);
  const fence = '`'.repeat(length);
  return fence + language + '\n' + source + (source.endsWith('\n') ? '' : '\n') + fence;
}

function statementSection(value, url) {
  const source = String(value || '').trim();
  if (!/^<(?:p|div|span|section|a|img|ul|ol|pre|table|blockquote|h[1-6])[\s/>]/i.test(source)) return source;

  const fragment = DOMPurify.sanitize(source, {
    RETURN_DOM_FRAGMENT: true,
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form', 'input', 'button', 'embed', 'object'],
    FORBID_ATTR: ['style', 'srcset'],
  });
  const container = document.createElement('div');
  container.appendChild(fragment);
  for (const element of container.querySelectorAll('a[href], img[src]')) {
    const attribute = element.tagName === 'A' ? 'href' : 'src';
    try {
      const address = new URL(element.getAttribute(attribute), url);
      if (address.protocol === 'https:' || address.protocol === 'http:') {
        element.setAttribute(attribute, address.href);
        continue;
      }
    } catch (_) {}
    element.removeAttribute(attribute);
  }
  // Keep imported tables and inline HTML while letting Markdown parse formulas in paragraphs.
  for (const paragraph of Array.from(container.querySelectorAll('p, div, section')).reverse()) {
    if (paragraph.closest('table, blockquote, li, pre')) continue;
    const content = document.createDocumentFragment();
    content.append('\n\n', ...paragraph.childNodes, '\n\n');
    paragraph.replaceWith(content);
  }
  return container.innerHTML.trim();
}

function statementMarkdown(problem, url) {
  const sections = [];
  const limits = [];
  if (Number(problem.timeLimit) > 0) limits.push('时间限制：' + Number(problem.timeLimit) + ' ms');
  if (Number(problem.memoryLimit) > 0) limits.push('内存限制：' + Number(problem.memoryLimit) + ' MB');
  if (limits.length) sections.push(limits.join('，'));
  for (const [field, label] of [['description', '描述'], ['input', '输入'], ['output', '输出']]) {
    const content = statementSection(problem[field], url);
    if (content) sections.push('### ' + label + '\n\n' + content);
  }
  if (Array.isArray(problem.examples)) {
    problem.examples.forEach((example, index) => {
      sections.push('### 样例 ' + (index + 1) + '\n\n输入\n\n' + fenced(example.input) + '\n\n输出\n\n' + fenced(example.output));
    });
  }
  const hint = statementSection(problem.hint, url);
  if (hint) sections.push('### 提示\n\n' + hint);
  return sections.join('\n\n');
}

function codeLanguage(language) {
  if (/C\+\+|g\+\+/i.test(language)) return 'cpp';
  if (/Python|PyPy/i.test(language)) return 'python';
  if (/JavaScript|Node\.js/i.test(language)) return 'javascript';
  return /^(?:java|c|go|rust|kotlin|pascal|ruby|typescript|csharp)$/i.test(language) ? language.toLowerCase() : '';
}

export function problemNoteMarkdown({ problem, problemId, url, sections, code, language, notes }) {
  const blocks = [
    '# ' + singleLine(problem.title),
    '- 题号：' + singleLine(problemId) + '\n- 链接：[打开题目](<' + url + '>)',
  ];
  if (sections.includes('statement')) {
    blocks.push('## 题面\n\n' + statementMarkdown(problem, url));
  }
  if (sections.includes('code')) {
    blocks.push('## 代码\n\n' + fenced(code, codeLanguage(language || '')));
  }
  if (sections.includes('notes')) blocks.push('## 笔记\n\n' + notes);
  return blocks.join('\n\n') + '\n';
}

export function problemNoteFilename(problemId) {
  let name = String(problemId || '题目笔记').replace(/[<>:"/\\|?*\x00-\x1f]/g, '-').slice(0, 96).replace(/[ .]+$/g, '');
  if (/^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(name)) name = 'HOJ-' + name;
  return (name || '题目笔记') + '.md';
}
