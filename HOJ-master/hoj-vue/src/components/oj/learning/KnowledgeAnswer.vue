<template>
  <div class="knowledge-answer">
    <p v-if="!paragraphs" class="knowledge-error" role="alert">回答依据暂时无法展示，请重新检索。</p>
    <template v-else>
      <p class="knowledge-muted knowledge-answer-hint">展开依据可对照原文。</p>
      <article v-for="(paragraph, index) in paragraphs" :key="index" class="knowledge-answer-claim">
        <KnowledgeText :content="paragraph.content" format="markdown" />
        <details class="knowledge-answer-evidence">
          <summary>查看依据 <span>{{ paragraph.evidence.length }}</span></summary>
          <blockquote v-for="(item, position) in paragraph.evidence" :key="position">
            <p>{{ item.quote }}</p>
            <div class="knowledge-answer-source"><router-link :to="citationRoute(item.source)">{{ item.source.citationId }} · {{ item.source.title }}</router-link><small>{{ location(item.source) }}</small></div>
          </blockquote>
        </details>
      </article>
    </template>
  </div>
</template>
<script>
import KnowledgeText from './KnowledgeText';
export default {
  name: 'KnowledgeAnswer',
  components: { KnowledgeText },
  props: {
    claims: { type: Array, required: true },
    citations: { type: Array, default: () => [] },
    citationRoute: { type: Function, required: true },
  },
  computed: {
    paragraphs() {
      if (!Array.isArray(this.citations) || this.citations.some(item => !item || typeof item.citationId !== 'string')) return null;
      const sources = new Map(this.citations.map(item => [item.citationId, item]));
      const paragraphs = [];
      for (const claim of this.claims) {
        if (!claim || typeof claim.content !== 'string' || !claim.content.trim() || !Array.isArray(claim.evidence) || !claim.evidence.length) return null;
        const evidence = [];
        for (const item of claim.evidence) {
          const source = item && sources.get(item.citationId);
          if (!source || typeof source.excerpt !== 'string' || typeof item.quote !== 'string' || !item.quote.trim() || !source.excerpt.includes(item.quote)) return null;
          evidence.push({ quote: item.quote, source });
        }
        paragraphs.push({ content: claim.content, evidence });
      }
      return paragraphs;
    },
  },
  methods: {
    location(item) { return [Number.isInteger(item.page) ? '第 ' + item.page + ' 页' : item.headingPath || '', '版本 ' + item.version].filter(Boolean).join(' · '); },
  },
};
</script>
<style scoped>
.knowledge-answer-hint { margin: 0 0 16px; }
.knowledge-answer-claim { padding: 0 0 20px; margin-bottom: 22px; border-bottom: 1px solid var(--line); }
.knowledge-answer-claim:last-child { border-bottom: 0; margin-bottom: 0; padding-bottom: 0; }
.knowledge-answer-evidence { margin-top: 12px; }
.knowledge-answer-evidence summary { display: list-item; width: fit-content; color: var(--accent); font-size: 13px; line-height: 1.9; cursor: pointer; padding: 3px 0; }
.knowledge-answer-evidence summary span { display: inline-block; color: var(--muted); padding-left: 5px; }
.knowledge-answer-evidence summary:focus-visible { outline: 2px solid var(--accent); outline-offset: 4px; }
.knowledge-answer-evidence blockquote { margin: 14px 0 0; padding: 12px 18px; border-left: 3px solid var(--line); background: var(--soft); }
.knowledge-answer-evidence blockquote p { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.9; color: var(--text); margin: 0 0 10px; }
.knowledge-answer-source { display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px 14px; font-size: 13px; line-height: 1.8; }
.knowledge-answer-source a { color: var(--accent); overflow-wrap: anywhere; }
.knowledge-answer-source small { color: var(--muted); overflow-wrap: anywhere; }
@media (max-width: 760px) { .knowledge-answer-evidence blockquote { padding: 10px 12px; } }
</style>
