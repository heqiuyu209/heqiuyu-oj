<template>
  <section class="codeforces-html-import">
    <el-button size="small" icon="el-icon-document-add" :disabled="unavailable" :aria-expanded="String(expanded)" @click="expanded = !expanded">
      {{ expanded ? '收起网页导入' : '导入网页 HTML' }}
    </el-button>
    <div v-if="expanded" class="codeforces-html-import__body">
      <p>在浏览器打开源题，将题目页另存为「网页，仅 HTML」，或粘贴页面源代码。解析后可在原文编辑器预览和校对。</p>
      <div class="codeforces-html-import__actions">
        <input ref="htmlFile" type="file" accept=".html,.htm,text/html" hidden @change="readFile" />
        <el-button size="small" :loading="reading" :disabled="unavailable" @click="$refs.htmlFile.click()">选择 HTML 文件</el-button>
        <span v-if="fileName">{{ fileName }}</span>
      </div>
      <el-input
        v-model="html"
        type="textarea"
        :rows="4"
        :disabled="unavailable"
        aria-label="CF 网页 HTML"
        placeholder="粘贴题目页 HTML"
        @input="fileName = ''; error = ''"
      />
      <el-button type="primary" size="small" :loading="parsing" :disabled="unavailable || !html" @click="parse">解析并填入</el-button>
      <p v-if="error" class="codeforces-html-import__error" role="alert">{{ error }}</p>
    </div>
  </section>
</template>

<script>
import api from '@/common/api';
import { errorMessage, responseData } from '@/common/objective';

const MAX_HTML_BYTES = 4 * 1024 * 1024;
const sizeMessage = '网页 HTML 最大为 4 MiB';

export default {
  name: 'CodeforcesHtmlImport',
  props: {
    problemKey: { type: String, required: true },
    disabled: Boolean,
  },
  data: () => ({ expanded: false, html: '', fileName: '', error: '', reading: false, parsing: false, disposed: false }),
  computed: {
    unavailable() { return this.disabled || this.reading || this.parsing; },
  },
  beforeDestroy() { this.disposed = true; },
  methods: {
    async readFile(event) {
      const input = event.target;
      const file = input.files[0];
      input.value = '';
      if (!file || this.unavailable) return;
      this.error = '';
      if (file.size > MAX_HTML_BYTES) {
        this.error = sizeMessage;
        return;
      }
      this.reading = true;
      try {
        const html = await file.text();
        if (!this.disposed) {
          this.html = html;
          this.fileName = file.name;
        }
      } catch (_) {
        if (!this.disposed) this.error = '无法读取 HTML 文件，请重新选择。';
      } finally {
        if (!this.disposed) this.reading = false;
      }
    },
    async parse() {
      if (this.unavailable || !this.html) return;
      this.error = '';
      if (new Blob([this.html]).size > MAX_HTML_BYTES) {
        this.error = sizeMessage;
        return;
      }
      const fragment = document.createElement('template');
      fragment.innerHTML = this.html;
      const statements = fragment.content.querySelectorAll('.problem-statement');
      if (statements.length !== 1) {
        this.error = '请导入一题完整的 CF 题面。请先在浏览器确认能看到题目内容。';
        return;
      }
      const statement = statements[0];
      statement.querySelectorAll('script, iframe, object, embed').forEach(node => {
        if (node.tagName === 'SCRIPT' && /^math\/tex(?:;|$)/i.test(node.type)) return;
        node.remove();
      });
      const link = fragment.content.querySelector('link[rel~="canonical"][href]');
      const meta = fragment.content.querySelector('meta[property="og:url"][content]');
      const pageUrl = link ? link.getAttribute('href') : meta ? meta.getAttribute('content') : null;

      this.parsing = true;
      try {
        const preview = responseData(await api.previewCodeforcesHtml({
          problemKey: this.problemKey,
          html: statement.outerHTML,
          pageUrl,
        }));
        if (!this.disposed && !this.disabled) this.$emit('parsed', { ...preview, problemKey: this.problemKey });
      } catch (error) {
        if (!this.disposed) this.error = errorMessage(error);
      } finally {
        if (!this.disposed) this.parsing = false;
      }
    },
  },
};
</script>

<style scoped>
.codeforces-html-import { margin: 16px 0; }
.codeforces-html-import__body { display: grid; justify-items: start; gap: 10px; margin-top: 10px; padding: 12px; border: 1px solid var(--line); border-radius: 8px; }
.codeforces-html-import__body > p, .codeforces-html-import__actions { margin: 0; font-size: 12px; line-height: 1.7; color: var(--muted); }
.codeforces-html-import__actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; overflow-wrap: anywhere; }
.codeforces-html-import__body > .codeforces-html-import__error { color: var(--danger, #dd625c); }
</style>
