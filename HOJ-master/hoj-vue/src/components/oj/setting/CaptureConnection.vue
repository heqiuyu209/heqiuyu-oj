<template>
  <div v-loading="busy">
    <p>生成采集密钥后，将完整密钥粘贴到 HOJCom 扩展的「设置 → 连接 HOJ」，扩展会识别本站地址。</p>
    <p v-if="expiresAt">密钥有效期至 {{ new Date(expiresAt).toLocaleString() }}</p>
    <p v-if="error" role="alert" class="capture-connection-error">{{ error }}</p>
    <el-input v-if="key" :value="key" readonly aria-label="HOJ 题面采集密钥">
      <el-button slot="append" @click="copy">复制</el-button>
    </el-input>
    <div class="capture-connection-actions">
      <el-button type="primary" size="small" :disabled="busy" @click="issue">{{ expiresAt ? '更换采集密钥' : '生成采集密钥' }}</el-button>
      <el-button v-if="expiresAt" size="small" :disabled="busy" @click="revoke">撤销采集密钥</el-button>
    </div>
  </div>
</template>

<script>
import axios from 'axios';
import { errorMessage, responseData } from '@/common/objective';

const endpoint = '/api/remote-captures/credential';
const options = { hojInlineError: true };

function connectionKey(secret) {
  const origin = btoa(window.location.origin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `HOJ1.${origin}.${secret}`;
}

export default {
  name: 'CaptureConnection',
  data: () => ({ busy: false, key: '', expiresAt: null, error: '' }),
  mounted() { this.run(() => axios.get(endpoint, options)); },
  methods: {
    async run(action) {
      this.busy = true;
      this.error = '';
      try {
        const data = responseData(await action());
        this.key = data.key ? connectionKey(data.key) : '';
        this.expiresAt = data.expiresAt;
      } catch (error) {
        this.error = errorMessage(error);
      } finally {
        this.busy = false;
      }
    },
    issue() { this.run(() => axios.post(endpoint, null, options)); },
    revoke() { this.run(() => axios.delete(endpoint, options)); },
    async copy() {
      try {
        await navigator.clipboard.writeText(this.key);
        this.$message.success('采集密钥已复制');
      } catch (_) {
        this.error = '请选中密钥后手动复制';
      }
    },
  },
};
</script>

<style scoped>
.capture-connection-actions { display: flex; gap: 8px; margin-top: 12px; }
.capture-connection-error { color: var(--danger, #c93737); }
</style>
