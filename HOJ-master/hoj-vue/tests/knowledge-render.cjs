// Pure rendering regression: no API, database, model, or external resource access.
const assert = require('assert').strict;
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');
const { createRequire } = require('module');
const { spawnSync } = require('child_process');

const countMath = html => (html.match(/class="katex"/g) || []).length;
const countFallback = html => (html.match(/class="knowledge-math-fallback"/g) || []).length;

(async () => {
  const { renderKnowledgeMarkdown: render } = await import(pathToFileURL(path.resolve(__dirname, '../src/common/knowledgeMarkdown.js')));
  if (process.argv.includes('--expansion-probe')) {
    const recursive = render(String.raw`$\def\loop{\loop}\loop$`);
    const doubling = render(String.raw`$\def\a{a}\edef\b{\a\a}\edef\c{\b\b}\edef\d{\c\c}\edef\e{\d\d}\edef\f{\e\e}\edef\g{\f\f}\edef\h{\g\g}\edef\i{\h\h}\edef\j{\i\i}\edef\k{\j\j}\edef\l{\k\k}\edef\m{\l\l}\edef\n{\m\m}\edef\o{\n\n}\o$`);
    assert.equal(countFallback(recursive), 1);
    assert.equal(countFallback(doubling), 1);
    process.stdout.write('bounded-expansion-ok');
    return;
  }

  const checks = [];
  function check(name, run) { run(); checks.push(name); }
  check('Every direct and legacy consumer resolves the patched KaTeX version', () => {
    const lock = require('../package-lock.json');
    const versions = Object.entries(lock.packages).filter(([key]) => key.endsWith('/katex')).map(([, pkg]) => pkg.version);
    assert(versions.length > 0);
    assert(versions.every(version => version === '0.18.2'), JSON.stringify(versions));
    for (const consumer of ['@iktakahiro/markdown-it-katex', 'vue-katex-auto-render']) {
      const consumerRequire = createRequire(require.resolve(consumer));
      assert.equal(consumerRequire('katex').version, '0.18.2');
    }
    assert.equal(require('katex').version, '0.18.2');
    assert.equal(require('markdown-it/package.json').version, '15.0.3');
  });
  check('All four delimiter pairs render inline or display math', () => {
    const html = render(String.raw`行内 $S_r-S_{l-1}$ 和 \(O(n\log n)\)。

$$
\sum_{i=1}^{n}i=\frac{n(n+1)}{2}
$$

\[
\begin{pmatrix}1&2\\3&4\end{pmatrix}
\]`);
    assert.equal(countMath(html), 4);
    assert.equal(countFallback(html), 0);
    assert.equal((html.match(/class="knowledge-math-display"/g) || []).length, 2);
    assert.equal((html.match(/aria-label="数学公式，可横向滚动"/g) || []).length, 2);
    assert(html.includes('<math'));
  });
  check('Display math supports blank lines, quoted blocks, lists and table cells', () => {
    const html = render(String.raw`> $$
> a+b
>
> =c
> $$

- \[
  \frac{1}{2}
  \]

| 公式 | 值 |
| --- | --- |
| $x$ | 1 |`);
    assert.equal(countMath(html), 3, html);
    assert.equal(countFallback(html), 0, html);
    assert(html.includes('<blockquote>') && html.includes('<li>') && html.includes('<td>'));
  });
  check('Inline, fenced and indented code remain literal', () => {
    const html = render('`$literal$` and `\\(literal\\)`\n\n```cpp\nconst char *x = "$not_math$";\n\\[literal\\]\n```\n\n    $$not_math$$\n\n$x$');
    assert.equal(countMath(html), 1);
    assert(html.includes('<code>$literal$</code>'));
    assert(html.includes('const char *x = &quot;$not_math$&quot;;'));
    assert(html.includes('<pre><code>$$not_math$$'));
    assert(!/<code[^>]*>[\s\S]*?class="katex"[\s\S]*?<\/code>/.test(html));
  });
  check('Currency, escaped dollars and unmatched delimiters retain ordinary text', () => {
    const html = render(String.raw`价格 $2.00 和 $3.00，转义 \$5，未闭合 \(x+1，另一个 \[y+2。`);
    assert.equal(countMath(html), 0);
    assert(html.includes('$2.00') && html.includes('$3.00') && html.includes('$5'));
    assert(html.includes(String.raw`\(x+1`) && html.includes(String.raw`\[y+2`));
    assert.equal(countMath(render('before $x$ after $2.00')), 1);
  });
  check('Untrusted HTML, image URLs and JavaScript links stay inert', () => {
    const html = render('<img src=x onerror="window.bad=1">\n\n![追踪](https://private-leak.invalid/image)\n\n[恶意](javascript:alert(1))\n\n[普通](https://example.invalid/path)');
    assert(!/<(?:img|script|a)\b/i.test(html));
    assert(html.includes('&lt;img'));
    assert(!html.includes('src="https://private-leak.invalid/image"'));
    const links = render('[普通](https://example.invalid/path) [恶意](javascript:alert(1))', true);
    assert.equal((links.match(/<a\b/g) || []).length, 1);
    assert(links.includes('target="_blank"') && links.includes('rel="noopener noreferrer"'));
  });
  check('Math cannot create links, images or HTML even when ordinary links are allowed', () => {
    const html = render(String.raw`$\href{https://private-leak.invalid/math}{x}$

$\includegraphics{https://private-leak.invalid/image}$

$\htmlClass{bad}{x}$`, true);
    assert(!/<(?:a|img|script)\b/i.test(html));
    assert(!/class="bad"/.test(html));
    assert(!html.includes('src="https://private-leak.invalid'));
  });
  check('Bad TeX is escaped without breaking later formulas', () => {
    const raw = String.raw`$\unknown{<img src=x onerror="window.bad=1">}$`;
    const html = render(raw + '\n\n$a+b$');
    assert.equal(countFallback(html), 1);
    assert.equal(countMath(html), 1);
    assert(!/<img\b/i.test(html));
    assert(html.includes('&lt;img'));
    assert(html.includes('公式未能排版，已保留原文'));
  });
  check('User macros are shared within one render and isolated between documents', () => {
    const first = render(String.raw`$\gdef\shared{9}$ 然后 $\shared+1$`);
    assert.equal(countFallback(first), 0);
    assert.equal(countMath(first), 2);
    assert.equal(countFallback(render(String.raw`$\shared+1$`)), 1);
    assert.equal(countFallback(render(String.raw`$\def\x{1}\x$`)), 0);
  });
  check('Math headings retain their original, escaped citation identity', () => {
    const html = render('## 前缀和 $S_i$ "<&\n\nbody');
    assert.equal(countMath(html), 1);
    assert(html.includes('data-knowledge-heading="前缀和 $S_i$ &quot;&lt;&amp;"'));
  });
  check('Source line ranges are emitted only for a reader following a citation', () => {
    const source = [
      '# 引用', '', '带公式 $x$ 的段落。', '', '```txt', '原样显示', '```', '', '$$', 'x^2', '$$',
    ].join('\n');
    const html = render(source, true, true);
    assert.match(html, /<h1 [^>]*data-knowledge-lines="0:1"/);
    assert.match(html, /<p [^>]*data-knowledge-lines="2:3"/);
    assert.match(html, /<(?:pre|code) [^>]*data-knowledge-lines="4:7"/);
    assert.match(html, /<div [^>]*data-knowledge-lines="8:11"[^>]*knowledge-math-display/);
    assert(!render(source, true).includes('data-knowledge-lines'));
  });
  check('Oversized or deeply nested expressions retain their full source', () => {
    const long = '$' + 'x'.repeat(4097) + '$';
    assert.equal(countFallback(render(long)), 1);
    assert(render(long).includes(long));
    const deep = '$' + '{'.repeat(65) + 'x' + '}'.repeat(65) + '$';
    assert.equal(countFallback(render(deep)), 1);
    assert(render(deep).includes(deep));
  });
  check('Document formula counts are bounded without dropping following content', () => {
    const html = render(Array(257).fill('$x$').join(' ') + '\n\n后续文字');
    assert.equal(countMath(html), 256);
    assert.equal(countFallback(html), 1);
    assert(html.includes('后续文字'));
  });
  check('Document total formula input has a separate bounded budget', () => {
    const html = render(Array(12).fill('$\\text{' + 'a'.repeat(3000) + '}$').join('\n\n'));
    assert.equal(countMath(html), 10);
    assert.equal(countFallback(html), 2);
  });
  check('TeX dimensions cannot grow beyond the configured size cap', () => {
    const html = render(String.raw`$\rule{999999em}{999999em}$`);
    assert.equal(countMath(html), 1);
    assert(!/style="[^"]*999999/.test(html));
    assert(html.includes('width:20em') && html.includes('height:20em'));
  });
  check('Recursive and doubling macros terminate within the expansion budget', () => {
    const child = spawnSync(process.execPath, [__filename, '--expansion-probe'], { encoding: 'utf8', windowsHide: true, timeout: 6000 });
    assert.ifError(child.error);
    assert.equal(child.status, 0, child.stderr);
    assert.equal(child.stdout, 'bounded-expansion-ok');
  });

  const report = { validated: true, checks, node: process.version, katex: require('katex').version, markdownIt: require('markdown-it/package.json').version };
  if (process.env.UI_REVIEW_OUTPUT) {
    fs.mkdirSync(process.env.UI_REVIEW_OUTPUT, { recursive: true });
    fs.writeFileSync(path.join(process.env.UI_REVIEW_OUTPUT, 'knowledge-render.json'), JSON.stringify(report, null, 2));
  }
  console.log(JSON.stringify(report, null, 2));
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
