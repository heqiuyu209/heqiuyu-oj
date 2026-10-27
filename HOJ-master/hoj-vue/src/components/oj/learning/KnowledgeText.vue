<template>
  <div v-if="format === 'markdown'" class="knowledge-prose" v-html="rendered" />
  <div v-else-if="format === 'pdf'" class="knowledge-pdf-pages">
    <section v-for="(page, index) in pages" :key="index" :data-pdf-page="index + 1" class="knowledge-pdf-page" tabindex="-1" :aria-label="'第 ' + (index + 1) + ' 页'">
      <p class="knowledge-page-number">第 {{ index + 1 }} 页</p>
      <pre v-if="page.trim()" class="knowledge-prose knowledge-plain">{{ page }}</pre>
      <p v-else class="knowledge-muted">此页没有可提取文字。</p>
    </section>
  </div>
  <pre v-else class="knowledge-prose knowledge-plain">{{ content }}</pre>
</template>
<script>
import { renderKnowledgeMarkdown } from '@/common/knowledgeMarkdown';
export default {
  name: 'KnowledgeText',
  props: { content: { type: String, default: '' }, format: { type: String, default: 'text' }, allowLinks: { type: Boolean, default: false } },
  computed: {
    pages() { return this.content.split('\n\f\n'); },
    rendered() {
      return renderKnowledgeMarkdown(this.content, this.allowLinks);
    },
  },
};
</script>
<style scoped>
.knowledge-prose { color: var(--text); font: inherit; line-height: 1.95; overflow-wrap: anywhere; }
.knowledge-plain { white-space: pre-wrap; margin: 0; }
.knowledge-pdf-page { padding: 22px 0 30px; border-bottom: 1px solid var(--line); scroll-margin-top: 120px; }
.knowledge-pdf-page:last-child { border-bottom: 0; }
.knowledge-pdf-page:focus { outline: 2px solid var(--accent); outline-offset: 8px; }
.knowledge-page-number { color: var(--accent); font-size: 13px; margin: 0 0 18px; }
.knowledge-prose /deep/ h1, .knowledge-prose /deep/ h2, .knowledge-prose /deep/ h3 { color: var(--text); line-height: 1.5; margin: 1.4em 0 .7em; }
.knowledge-prose /deep/ h1 { font-size: 25px; }
.knowledge-prose /deep/ h2 { font-size: 21px; }
.knowledge-prose /deep/ h3 { font-size: 18px; }
.knowledge-prose /deep/ pre { padding: 16px; background: var(--soft); border: 1px solid var(--line); border-radius: 8px; overflow: auto; line-height: 1.7; }
.knowledge-prose /deep/ code { font-family: Consolas, monospace; font-size: .93em; }
.knowledge-prose /deep/ blockquote { margin: 16px 0; padding: 5px 18px; border-left: 3px solid var(--accent); color: var(--muted); }
.knowledge-prose /deep/ table { border-collapse: collapse; display: block; max-width: 100%; overflow: auto; }
.knowledge-prose /deep/ th, .knowledge-prose /deep/ td { border: 1px solid var(--line); padding: 8px 14px; }
.knowledge-prose /deep/ .knowledge-math-inline { display: inline-block; max-width: 100%; overflow-x: auto; overflow-y: hidden; vertical-align: middle; padding: 2px 3px; }
.knowledge-prose /deep/ .knowledge-math-display { display: block; max-width: 100%; overflow-x: auto; padding: 10px 2px; margin: .7em 0; }
.knowledge-prose /deep/ .knowledge-math-display .katex-display { margin: 0; }
.knowledge-prose /deep/ .knowledge-math-display:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.knowledge-prose /deep/ .knowledge-math-fallback { white-space: pre-wrap; overflow-wrap: anywhere; color: var(--muted); }
</style>
