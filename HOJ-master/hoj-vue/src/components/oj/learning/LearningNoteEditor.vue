<template>
  <div class="learning-note-editor" :class="{ 'live-preview': live }">
    <el-radio-group v-model="live" size="mini" aria-label="笔记编辑模式">
      <el-radio-button :label="true">实时预览</el-radio-button>
      <el-radio-button :label="false">源码</el-radio-button>
    </el-radio-group>
    <textarea ref="source" aria-label="本题笔记" placeholder="记录本题思路、卡点和复杂度"></textarea>
  </div>
</template>

<script>
import { CodeMirror } from 'vue-codemirror-lite';
import 'codemirror/mode/markdown/markdown';
import 'codemirror/addon/selection/active-line';
import 'codemirror/addon/display/placeholder';
import 'codemirror/addon/edit/continuelist';

export default {
  name: 'LearningNoteEditor',
  props: { value: { type: String, default: '' }, active: Boolean },
  data: () => ({ live: true }),
  watch: {
    value(value) {
      if (this.editor && this.editor.getValue() !== value) this.editor.setValue(value);
    },
    active(visible) {
      if (visible) this.$nextTick(this.refresh);
    },
    live() {
      this.$nextTick(this.refresh);
    },
  },
  mounted() {
    this.editor = CodeMirror.fromTextArea(this.$refs.source, {
      mode: {
        name: 'markdown',
        highlightFormatting: true,
        strikethrough: true,
        tokenTypeOverrides: { list1: 'note-list', list2: 'note-list', list3: 'note-list' },
      },
      lineWrapping: true,
      styleActiveLine: true,
      screenReaderLabel: '本题笔记',
      extraKeys: { Enter: 'newlineAndIndentContinueMarkdownList' },
    });
    this.editor.setValue(this.value);
    this.editor.clearHistory();
    this.editor.on('change', editor => {
      const value = editor.getValue();
      if (value !== this.value) this.$emit('input', value);
    });
  },
  beforeDestroy() { this.editor.toTextArea(); },
  methods: {
    refresh() { if (this.editor) this.editor.refresh(); },
  },
};
</script>

<style scoped>
.learning-note-editor { display: grid; gap: 8px; min-width: 0; }
.learning-note-editor ::v-deep .CodeMirror {
  height: min(320px, 45vh);
  min-height: 170px;
  background: var(--surface);
  color: var(--text);
  font: inherit;
  line-height: 1.6;
}
.learning-note-editor ::v-deep .CodeMirror-lines { padding: 9px 6px; }
.learning-note-editor ::v-deep .CodeMirror-placeholder { color: var(--muted); }
.learning-note-editor ::v-deep .CodeMirror-activeline-background { background: transparent; }
.live-preview ::v-deep .cm-header { color: var(--text); font-weight: 700; }
.live-preview ::v-deep .cm-header-1 { font-size: 22px; }
.live-preview ::v-deep .cm-header-2 { font-size: 19px; }
.live-preview ::v-deep .cm-header-3 { font-size: 17px; }
.live-preview ::v-deep .cm-quote { color: var(--muted); }
.live-preview ::v-deep .cm-link { color: var(--accent); }
.live-preview ::v-deep .cm-comment { font-family: Consolas, monospace; color: var(--text); }
.live-preview ::v-deep .cm-formatting-header,
.live-preview ::v-deep .cm-formatting-strong,
.live-preview ::v-deep .cm-formatting-em,
.live-preview ::v-deep .cm-formatting-code,
.live-preview ::v-deep .cm-formatting-strikethrough { display: none; }
.live-preview ::v-deep .CodeMirror-focused .CodeMirror-activeline .cm-formatting { display: inline; color: var(--muted); }
.live-preview ::v-deep .cm-formatting-list-ul { font-size: 0; }
.live-preview ::v-deep .cm-formatting-list-ul::before { content: '•'; margin-inline-end: 0.45em; font-size: 1rem; color: var(--muted); }
.live-preview ::v-deep .CodeMirror-focused .CodeMirror-activeline .cm-formatting-list-ul { font-size: inherit; }
.live-preview ::v-deep .CodeMirror-focused .CodeMirror-activeline .cm-formatting-list-ul::before { content: none; }
</style>
