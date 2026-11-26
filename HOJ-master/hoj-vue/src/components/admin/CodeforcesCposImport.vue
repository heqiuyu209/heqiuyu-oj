<template>
  <div>
    <el-button icon="el-icon-folder-opened" @click="visible = true">从 CPOS 批量导入</el-button>
    <el-dialog title="从 CPOS 批量导入 Codeforces 题面" :visible.sync="visible"
      width="min(560px, 92vw)" append-to-body :show-close="!importing"
      :close-on-click-modal="!importing" :close-on-press-escape="!importing">
      <p>用 CPOS 采集可正常打开的英文题面。选取 VS Code 扩展的 <code>.cpos-vscode</code> 目录，HOJ 会按同名文件配对题面和样例。</p>
      <input ref="folder" type="file" webkitdirectory multiple hidden @change="selectFolder">
      <el-button :loading="reading" :disabled="importing" @click="$refs.folder.click()">选择采集目录</el-button>
      <p v-if="notice" role="status">{{ notice }}</p>
      <p v-if="processed" role="status">
        已处理 {{ processed }} / {{ queue.length }}，导入 {{ imported }}，已有原文 {{ skipped }}，失败 {{ failed }}
      </p>
      <ul v-if="errors.length" class="cpos-errors" aria-label="未导入的记录">
        <li v-for="(error, index) in errors" :key="index">{{ error }}</li>
      </ul>
      <p>已保存的原文会自动跳过。新题导入后可在题库中校对、翻译并开放。</p>
      <span slot="footer">
        <el-button :disabled="importing" @click="visible = false">关闭</el-button>
        <el-button type="primary" :loading="importing" :disabled="reading || !queue.length"
          @click="importStatements">导入 {{ queue.length }} 条采集记录</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import axios from 'axios';
import { errorMessage, responseData } from '@/common/objective';

const capturePath = /^[^/]+\/(problems|samples)\/([^/]+\.json)$/i;
const problemId = /^[1-9][A-Z0-9]{1,34}$/i;
const maxCaptureFileBytes = 6 * (4 * 1024 * 1024 + 500000);

export default {
  name: 'CodeforcesCposImport',
  data: () => ({
    visible: false, reading: false, importing: false, disposed: false,
    queue: [], readErrors: [], errors: [], notice: '',
    processed: 0, imported: 0, skipped: 0, failed: 0,
  }),
  beforeDestroy() { this.disposed = true; },
  methods: {
    async selectFolder(event) {
      const files = Array.from(event.target.files || []);
      event.target.value = '';
      if (!files.length) return;
      const authEpoch = this.$store.getters.authEpoch;
      this.reading = true;
      this.queue = [];
      this.notice = '';
      this.readErrors = [];
      this.resetResults();
      const metadataFiles = [];
      const sampleFiles = new Map();
      for (const file of files) {
        const path = capturePath.exec(file.webkitRelativePath);
        if (!path) continue;
        if (path[1].toLowerCase() === 'problems') metadataFiles.push(file);
        else sampleFiles.set(path[2], file);
      }
      metadataFiles.sort((a, b) => b.lastModified - a.lastModified);
      let index = 0;
      for (const metadata of metadataFiles) {
        let label = '采集记录 ' + (++index);
        const samples = sampleFiles.get(metadata.name);
        try {
          if (metadata.size > maxCaptureFileBytes) throw new Error('题面文件过大');
          const record = JSON.parse(await metadata.text());
          if (!this.active(authEpoch)) return;
          if (!record || typeof record.platform !== 'string') throw new Error('缺少题目来源');
          if (record.platform.toLowerCase() !== 'codeforces') continue;
          if (typeof record.id !== 'string' || !problemId.test(record.id)) throw new Error('题号无效');
          label = 'CF-' + record.id.toUpperCase();
          if (typeof record.statementHtml !== 'string' || !record.statementHtml.trim()) {
            throw new Error('缺少题面，请在浏览器重新采集');
          }
          if (!samples) throw new Error('缺少配对的样例文件，请重新采集');
          if (samples.size > maxCaptureFileBytes) throw new Error('样例文件过大');
          this.queue.push({ metadata, samples, label });
        } catch (error) {
          if (!this.active(authEpoch)) return;
          const reason = error instanceof SyntaxError ? '题面 JSON 格式无效' : error.message;
          this.readErrors.push(label + ' · ' + reason);
        }
      }
      this.errors = this.readErrors.slice();
      this.notice = this.queue.length
        ? '可导入 ' + this.queue.length + ' 条 Codeforces 采集记录'
        : '没有可导入的 Codeforces 题面，请检查所选目录';
      this.reading = false;
    },
    active(authEpoch) {
      return !this.disposed && this.$store.getters.authEpoch === authEpoch;
    },
    resetResults() {
      this.processed = this.imported = this.skipped = this.failed = 0;
      this.errors = this.readErrors.slice();
    },
    async importStatements() {
      if (this.reading || this.importing || !this.queue.length) return;
      const authEpoch = this.$store.getters.authEpoch;
      this.importing = true;
      this.resetResults();
      try {
        for (const item of this.queue) {
          try {
            const [metadata, samples] = await Promise.all([item.metadata.text(), item.samples.text()]);
            if (!this.active(authEpoch)) return;
            const { id, url, statementHtml } = JSON.parse(metadata);
            const tests = JSON.parse(samples);
            if (!Array.isArray(tests) || tests.some(test => !test ||
              typeof test.input !== 'string' || typeof test.expected_output !== 'string')) {
              throw new Error('样例 JSON 缺少有效的输入或输出');
            }
            const result = responseData(await axios.post('/api/admin/remote-sources/codeforces/cpos/import', {
              platform: 'codeforces', id, url, statementHtml,
              tests: tests.map(test => ({ input: test.input, expectedOutput: test.expected_output })),
            }, { hojInlineError: true }));
            if (!this.active(authEpoch)) return;
            if (result === true) this.imported++;
            else if (result === false) this.skipped++;
            else throw new Error('题面导入接口返回异常');
          } catch (error) {
            if (!this.active(authEpoch)) return;
            this.failed++;
            const reason = error instanceof SyntaxError ? '采集 JSON 格式无效'
              : error.isAxiosError || error.data ? errorMessage(error) : error.message;
            this.errors.push(item.label + ' · ' + (reason || '无法导入，请重试'));
          }
          this.processed++;
        }
      } finally {
        if (this.active(authEpoch)) {
          this.importing = false;
          if (this.imported) this.$emit('imported');
        }
      }
    },
  },
};
</script>

<style scoped>
.cpos-errors { max-height: 12rem; overflow: auto; padding-inline-start: 1.5rem; line-height: 1.7; }
</style>
