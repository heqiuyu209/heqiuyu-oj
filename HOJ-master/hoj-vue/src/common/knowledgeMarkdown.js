import MarkdownIt from 'markdown-it';
import katex from 'katex';

const delimiters = [
  { left: '$$', right: '$$', display: true },
  { left: '$', right: '$', display: false },
  { left: '\\[', right: '\\]', display: true },
  { left: '\\(', right: '\\)', display: false },
];

function escaped(source, at) {
  let count = 0;
  while (at > 0 && source[--at] === '\\') count++;
  return count % 2 === 1;
}

function closing(source, from, delimiter, max) {
  let at = source.indexOf(delimiter.right, from);
  while (at !== -1 && at < max) {
    const invalidDollar = delimiter.left === '$' && (/\s/.test(source[at - 1] || '') || /[0-9]/.test(source[at + 1] || ''));
    if (!escaped(source, at) && !invalidDollar && at + delimiter.right.length <= max) return at;
    at = source.indexOf(delimiter.right, at + 1);
  }
  return -1;
}

function inlineMath(state, silent) {
  const from = state.pos;
  const delimiter = delimiters.find(item => state.src.startsWith(item.left, from));
  if (!delimiter || (delimiter.left === '$' && /\s/.test(state.src[from + 1] || ''))) return false;
  const end = closing(state.src, from + delimiter.left.length, delimiter, state.posMax);
  if (end === -1) {
    // Markdown's ordinary escape rule otherwise removes an unmatched \( or \[.
    if (delimiter.left[0] !== '\\') return false;
    if (!silent) state.pending += delimiter.left;
    state.pos += delimiter.left.length;
    return true;
  }
  if (end === from + delimiter.left.length) return false;
  if (!silent) {
    const token = state.push('knowledge_math', '', 0);
    token.content = state.src.slice(from + delimiter.left.length, end);
    token.meta = { display: delimiter.display, raw: state.src.slice(from, end + delimiter.right.length) };
  }
  state.pos = end + delimiter.right.length;
  return true;
}

function blockMath(state, start, end, silent) {
  if (state.sCount[start] - state.blkIndent >= 4) return false;
  const from = state.bMarks[start] + state.tShift[start];
  const delimiter = delimiters.find(item => item.display && state.src.startsWith(item.left, from));
  if (!delimiter) return false;
  for (let line = start; line < end; line++) {
    const contentStart = line === start ? from + delimiter.left.length : state.bMarks[line] + state.tShift[line];
    const close = closing(state.src, contentStart, delimiter, state.eMarks[line]);
    if (close === -1 || state.src.slice(close + delimiter.right.length, state.eMarks[line]).trim()) continue;
    if (silent) return true;
    const first = state.src.slice(from + delimiter.left.length, state.eMarks[start]);
    const content = line === start ? state.src.slice(from + delimiter.left.length, close)
      : first + '\n' + state.getLines(start + 1, line, state.blkIndent, true) + state.src.slice(contentStart, close);
    if (!content.trim()) return false;
    const token = state.push('knowledge_math', '', 0);
    token.block = true;
    token.content = content;
    token.meta = { display: true, raw: delimiter.left + content + delimiter.right };
    token.map = [start, line + 1];
    state.line = line + 1;
    return true;
  }
  return false;
}

export function renderKnowledgeMarkdown(content, allowLinks = false, trackSourceLines = false) {
  const parser = new MarkdownIt({ html: false, breaks: true, linkify: false, maxNesting: 64 });
  if (trackSourceLines) {
    parser.core.ruler.after('inline', 'knowledge_source_lines', state => {
      for (const token of state.tokens) {
        if (token.map && !token.hidden && token.type !== 'inline') {
          token.attrSet('data-knowledge-lines', token.map.join(':'));
        }
      }
    });
  }
  const macros = Object.create(null);
  let count = 0, characters = 0;
  parser.inline.ruler.before('escape', 'knowledge_math', inlineMath);
  parser.block.ruler.before('fence', 'knowledge_math', blockMath, { alt: ['paragraph', 'reference', 'blockquote', 'list'] });
  parser.renderer.rules.knowledge_math = (tokens, index) => {
    const token = tokens[index], body = token.content;
    const attrs = parser.renderer.renderAttrs(token);
    const fallback = () => '<code' + attrs + ' class="knowledge-math-fallback" title="公式未能排版，已保留原文">' + parser.utils.escapeHtml(token.meta.raw) + '</code>';
    if (body.length > 4096 || count >= 256 || characters + body.length > 32768) return fallback();
    count++; characters += body.length;
    let depth = 0;
    for (const character of body) { if (character === '{') depth++; else if (character === '}') depth--; if (depth > 64) return fallback(); }
    try {
      const math = katex.renderToString(body, { displayMode: token.meta.display, output: 'htmlAndMathml', throwOnError: true, trust: false, strict: 'ignore', maxExpand: 500, maxSize: 20, macros });
      const tag = token.block ? 'div' : 'span', mode = token.meta.display ? 'display' : 'inline';
      return '<' + tag + attrs + ' class="knowledge-math-' + mode + '"' + (token.meta.display ? ' tabindex="0" role="group" aria-label="数学公式，可横向滚动"' : '') + '>' + math + '</' + tag + '>';
    } catch (_) { return fallback(); }
  };
  const heading = parser.renderer.rules.heading_open;
  parser.renderer.rules.heading_open = (tokens, index, options, env, renderer) => {
    const inline = tokens[index + 1];
    if (inline && inline.type === 'inline') tokens[index].attrSet('data-knowledge-heading', inline.content);
    return heading ? heading(tokens, index, options, env, renderer) : renderer.renderToken(tokens, index, options);
  };
  parser.renderer.rules.image = (tokens, index) => parser.utils.escapeHtml(tokens[index].content || '');
  if (!allowLinks) {
    parser.renderer.rules.link_open = () => '<span>';
    parser.renderer.rules.link_close = () => '</span>';
  } else {
    parser.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
      tokens[index].attrSet('target', '_blank'); tokens[index].attrSet('rel', 'noopener noreferrer');
      return renderer.renderToken(tokens, index, options);
    };
  }
  return parser.render(content);
}
