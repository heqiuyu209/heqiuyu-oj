<template>
  <div class="learning-note-export">
    <el-button size="mini" icon="el-icon-download" :disabled="!ready" @click="open">导出 Markdown</el-button>
    <el-dialog title="导出题目笔记" :visible.sync="visible" width="min(420px, calc(100vw - 24px))"
      custom-class="problem-note-export-dialog" append-to-body>
      <p class="note-export-filename">{{ filename }}</p>
      <p class="note-export-details">标题、题号和链接会自动写入。</p>
      <el-checkbox-group v-model="sections" aria-label="导出内容">
        <el-checkbox label="statement">题面</el-checkbox>
        <el-checkbox label="code" :disabled="!readCode">代码</el-checkbox>
        <el-checkbox label="notes">笔记</el-checkbox>
      </el-checkbox-group>
      <p v-if="error" class="note-export-error" role="status">{{ error }}</p>
      <span slot="footer">
        <el-button size="small" :disabled="busy" @click="visible = false">取消</el-button>
        <el-button v-if="error && canChooseFile" size="small" :disabled="busy" @click="exportFile(false)">下载 Markdown</el-button>
        <el-button size="small" type="primary" :loading="busy" :disabled="!ready" @click="exportFile(canChooseFile)">
          {{ canChooseFile ? '选择位置并保存' : '下载 Markdown' }}
        </el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { problemNoteFilename, problemNoteMarkdown } from '@/common/problemNoteMarkdown';

export default {
  name: 'LearningNoteExport',
  props: {
    problem: Object,
    readCode: Function,
    notes: { type: String, default: '' },
  },
  data: () => ({ visible: false, busy: false, sections: ['notes'], error: '' }),
  computed: {
    problemId() { return this.$route.params.problemID; },
    filename() { return problemNoteFilename(this.problemId); },
    ready() { return !!(this.problem && this.problem.title && this.problemId); },
    canChooseFile() { return window.isSecureContext && typeof window.showSaveFilePicker === 'function'; },
  },
  methods: {
    open() {
      this.error = '';
      this.visible = true;
    },
    async exportFile(chooseFile) {
      if (!this.ready || this.busy) return;
      this.error = '';
      this.busy = true;
      try {
        const filename = this.filename;
        const sections = this.sections.filter(section => section !== 'code' || this.readCode);
        const editor = sections.includes('code') ? this.readCode() : {};
        const href = this.$router.resolve({ path: this.$route.path }).href;
        const url = new URL(href, window.location.href).href;
        const input = { ...editor, problem: this.problem, problemId: this.problemId, url, sections, notes: this.notes };
        if (chooseFile) {
          const file = await window.showSaveFilePicker({
            id: 'hoj-problem-note',
            suggestedName: filename,
            types: [{ description: 'Markdown', accept: { 'text/markdown': ['.md'] } }],
            excludeAcceptAllOption: true,
          });
          if (!/\.md$/i.test(file.name)) {
            this.error = '请选择以 .md 结尾的文件名。';
            return;
          }
          const markdown = problemNoteMarkdown(input);
          const writer = await file.createWritable();
          try {
            await writer.write(markdown);
            await writer.close();
          } catch (error) {
            await writer.abort().catch(() => {});
            throw error;
          }
          this.$message.success('已保存 ' + file.name);
        } else {
          const content = new Blob([problemNoteMarkdown(input)], { type: 'text/markdown;charset=utf-8' });
          const link = document.createElement('a');
          const objectUrl = URL.createObjectURL(content);
          link.href = objectUrl;
          link.download = filename;
          document.body.appendChild(link);
          link.click();
          link.remove();
          window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
          this.$message.success('Markdown 已开始下载');
        }
        this.visible = false;
      } catch (error) {
        if (error.name !== 'AbortError') this.error = '无法保存文件，请重新选择位置或下载 Markdown。';
      } finally {
        this.busy = false;
      }
    },
  },
};
</script>

<style scoped>
.note-export-filename { margin: 0 0 4px; color: var(--text); font-weight: 600; overflow-wrap: anywhere; }
.note-export-details { margin: 0 0 14px; color: var(--muted); }
.note-export-error { margin: 10px 0 0; color: #e15b5b; }
</style>
