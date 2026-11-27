<template>
  <div>
    <el-button icon="el-icon-collection" @click="open">浏览器采集队列</el-button>
    <el-dialog title="浏览器采集队列" :visible.sync="visible" append-to-body width="min(92vw, 860px)">
      <div v-loading="loading">
        <div class="capture-queue-actions">
          <el-switch v-if="isRoot" :value="enabled" :disabled="busy || loading"
            active-text="自动导入开启" inactive-text="自动导入暂停" @change="setEnabled" />
          <span v-else>{{ enabled ? '自动导入开启' : '自动导入暂停' }}</span>
          <span>待导入 {{ total }} 题</span>
          <el-button size="small" :disabled="busy || loading" @click="load">刷新</el-button>
        </div>
        <p v-if="!enabled && !loading">暂停期间保留待导入题面。</p>
        <p v-if="error" role="alert" class="capture-queue-error">{{ error }}</p>
        <p v-if="notice" role="status">{{ notice }}</p>
        <el-table :data="items" row-key="problemKey" empty-text="暂无待导入题面" @selection-change="selected = $event">
          <el-table-column type="selection" width="44" />
          <el-table-column prop="displayId" label="题号" min-width="116" />
          <el-table-column prop="title" label="题目" min-width="160" />
          <el-table-column label="操作" width="175">
            <template slot-scope="{ row }">
              <el-button type="text" :disabled="busy" @click="inspect(row)">预览</el-button>
              <a :href="row.sourceUrl" target="_blank" rel="noopener noreferrer">源站</a>
              <el-button type="text" :disabled="busy" @click="discard(row)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <section v-if="preview" class="capture-queue-preview">
          <h3>{{ preview.title }}</h3>
          <p>时间限制 {{ preview.timeLimit }} ms · 内存限制 {{ preview.memoryLimit }} MiB</p>
          <pre v-text="plainText(preview.description)" />
          <h4>输入</h4><pre v-text="plainText(preview.input)" />
          <h4>输出</h4><pre v-text="plainText(preview.output)" />
          <h4>样例</h4><pre v-text="preview.examples" />
          <h4 v-if="preview.hint">说明</h4><pre v-if="preview.hint" v-text="plainText(preview.hint)" />
        </section>
      </div>
      <span slot="footer">
        <el-button @click="visible = false">关闭</el-button>
        <el-button type="primary" :loading="importing" :disabled="!enabled || loading || busy || !items.length" @click="approve">
          导入{{ selected.length ? '所选' : '本批' }} {{ batch.length }} 题
        </el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import axios from 'axios';
import { errorMessage, responseData } from '@/common/objective';

const endpoint = '/api/admin/remote-captures';
const request = { hojInlineError: true };

export default {
  name: 'RemoteCaptureQueue',
  data: () => ({
    visible: false, enabled: false, loading: false, saving: false, importing: false,
    total: 0, items: [], selected: [], preview: null, error: '', notice: '', previewRequest: 0,
  }),
  computed: {
    isRoot() { return (this.$store.getters.userInfo.roleList || []).includes('root'); },
    batch() { return this.selected.length ? this.selected : this.items; },
    busy() { return this.saving || this.importing; },
  },
  methods: {
    open() {
      this.visible = true;
      this.notice = '';
      this.preview = null;
      this.load();
    },
    async load() {
      this.loading = true;
      this.error = '';
      try {
        const result = responseData(await axios.get(endpoint, request));
        this.enabled = result.enabled;
        this.total = result.total;
        this.items = result.items;
        this.selected = [];
      } catch (error) {
        this.items = [];
        this.error = errorMessage(error);
      } finally {
        this.loading = false;
      }
    },
    async setEnabled(enabled) {
      this.saving = true;
      this.error = '';
      try {
        const result = responseData(await axios.put(endpoint + '/policy', null, { ...request, params: { enabled } }));
        this.enabled = result.enabled;
      } catch (error) {
        this.error = errorMessage(error);
      } finally {
        this.saving = false;
      }
    },
    async inspect(row) {
      const token = ++this.previewRequest;
      this.preview = null;
      this.error = '';
      try {
        const result = responseData(await axios.get(endpoint + '/statement', { ...request, params: { problemKey: row.problemKey } }));
        if (token === this.previewRequest) this.preview = result;
      } catch (error) {
        if (token === this.previewRequest) this.error = errorMessage(error);
      }
    },
    async discard(row) {
      const confirmed = await this.$confirm('移除 ' + row.displayId + ' 的待导入题面？', '移除题面').catch(() => false);
      if (!confirmed) return;
      this.saving = true;
      try {
        await axios.delete(endpoint + '/statement', { ...request, params: { problemKey: row.problemKey } });
        this.previewRequest++;
        this.preview = null;
        await this.load();
      } catch (error) {
        this.error = errorMessage(error);
      } finally {
        this.saving = false;
      }
    },
    async approve() {
      this.importing = true;
      this.notice = this.error = '';
      try {
        const result = responseData(await axios.post(endpoint + '/import', this.batch.map(row => row.problemKey), request));
        this.notice = `已导入 ${result.imported} 题，已跳过 ${result.skipped} 题`;
        this.previewRequest++;
        this.preview = null;
        if (result.imported) this.$emit('imported');
        await this.load();
        const failures = Object.entries(result.failures).map(([key, message]) => key + ' · ' + message);
        this.error = [this.error, ...failures].filter(Boolean).join('\n');
      } catch (error) {
        this.error = errorMessage(error);
      } finally {
        this.importing = false;
      }
    },
    plainText(html) {
      return new DOMParser().parseFromString(html || '', 'text/html').body.textContent;
    },
  },
};
</script>

<style scoped>
.capture-queue-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 14px; }
.capture-queue-error { color: var(--danger, #c93737); white-space: pre-line; }
.capture-queue-preview { max-height: 40vh; overflow: auto; margin-top: 12px; padding: 12px; border: 1px solid var(--line, #ddd); }
.capture-queue-preview pre { white-space: pre-wrap; overflow-wrap: anywhere; }
a { color: var(--accent, #409eff); margin: 0 8px; }
</style>
