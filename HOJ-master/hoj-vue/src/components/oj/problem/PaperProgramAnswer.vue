<template>
  <section class="paper-code">
    <div class="code-toolbar"><el-select v-model="language" :disabled="locked" aria-label="编程语言"><el-option v-for="name in problem.languages" :key="name" :value="name" :label="name" /></el-select><span>{{ live ? '提交后可查看判题结果' : '交卷后统一判题' }}</span></div>
    <codemirror ref="editor" v-model="code" :options="editorOptions" />
    <div class="code-actions"><small>{{ dirty ? '代码尚未保存' : '代码已保存至本次答卷' }}<br>{{ live ? '计分依据为已提交的代码，保存草稿不会判题。' : '保存草稿后，交卷时统一提交评测。' }}</small><div><el-button :disabled="locked" :loading="loading" @click="$emit('save', payload)">保存草稿</el-button><el-button v-if="live && !finished" type="primary" :disabled="locked || !code.trim()" :loading="loading" @click="$emit('submit', payload)">提交判题</el-button></div></div>
    <div class="program-results" aria-live="polite">
      <p v-if="problem.grading"><i class="el-icon-loading" /> 正在判题，结果将自动更新。</p>
      <p v-if="problem.score !== undefined">本题得分 <strong>{{ problem.score }} / {{ problem.maxScore }}</strong></p>
      <p v-if="!problem.answerVisible && finished">已交卷，判分反馈尚未开放。</p>
      <article v-for="result in (problem.submissions || []).slice().reverse()" :key="result.id" class="submission"><div><span>#{{ result.id }}</span><el-tag :type="result.status === 0 ? 'success' : [5, 6, 7, 9, 15].includes(result.status) ? 'info' : 'warning'" size="small">{{ verdict(result.status) }}</el-tag><span v-if="result.score !== undefined">{{ result.score }} 分</span></div><pre v-if="result.message">{{ result.message }}</pre></article>
      <div v-if="problem.explanationVisible" class="program-explanation"><h3>答案解析</h3><div v-dompurify-html="explanation" /></div>
    </div>
  </section>
</template>
<script>
import { codemirror } from 'vue-codemirror-lite';
import 'codemirror/mode/clike/clike';
import 'codemirror/mode/python/python';
import 'codemirror/mode/javascript/javascript';
import 'codemirror/mode/go/go';
import 'codemirror/mode/rust/rust';
import 'codemirror/theme/base16-dark.css';
import { JUDGE_STATUS } from '@/common/constants';
import utils from '@/common/utils';
import { appearanceRuntime } from '@/common/appearance';
export default {
  name: 'PaperProgramAnswer', components: { codemirror },
  props: { problem: { type: Object, required: true }, live: Boolean, finished: Boolean, loading: Boolean },
  data() { return { code: this.problem.code || '', language: this.problem.language || this.problem.languages[0], contentTypes: {} }; },
  computed: {
    locked() { return this.finished || this.loading; },
    dirty() { return this.code !== (this.problem.code || '') || this.code.trim() !== '' && this.language !== this.problem.language; },
    payload() { return { language: this.language, code: this.code }; },
    editorOptions() { return { lineNumbers: true, tabSize: 4, lineWrapping: true, mode: this.contentTypes[this.language] || 'text/x-c++src', theme: appearanceRuntime.dark ? 'base16-dark' : 'default', readOnly: this.locked }; },
    explanation() { return this.$markDown.render(this.problem.explanation || '本题暂未提供解析。'); },
  },
  watch: { 'problem.code'() { this.code = this.problem.code || ''; this.language = this.problem.language || this.problem.languages[0]; } },
  mounted() { utils.getLanguages().then(items => { if (!this._isDestroyed) this.contentTypes = Object.fromEntries(items.map(item => [item.name, item.contentType])); }).catch(() => {}); },
  methods: { verdict(status) { return JUDGE_STATUS[status] ? JUDGE_STATUS[status].name : '等待判题'; } },
};
</script>
<style scoped>
.paper-code { padding: 20px; color: var(--text); }.code-toolbar { display: flex; align-items: center; gap: 14px; margin-bottom: 18px; }.code-toolbar span, small { color: var(--muted); font-size: 12px; line-height: 1.8; }.code-toolbar .el-select { width: 160px; }.paper-code /deep/ .CodeMirror { height: 410px; border: 1px solid var(--line); border-radius: 8px; font: 14px/1.7 Consolas, monospace; }.code-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 14px; margin: 18px 0; }.code-actions .el-button { border-radius: 8px; }.program-results { border-top: 1px solid var(--line); padding-top: 14px; }.submission { padding: 10px 0; border-bottom: 1px solid var(--line); }.submission > div { display: flex; align-items: center; gap: 12px; font-size: 13px; }.submission pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 200px; overflow-y: auto; background: var(--soft); padding: 12px; }.program-explanation { overflow-wrap: anywhere; }
@media(max-width:600px) { .paper-code { padding: 16px; }.code-toolbar { flex-wrap: wrap; }.paper-code /deep/ .CodeMirror { height: 320px; } }
</style>
