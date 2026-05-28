<template>
  <div class="cf-bind-container">
    <el-card shadow="never">
      <div slot="header">
        <span><i class="fa fa-codeforces"></i> Codeforces 账号绑定</span>
        <el-tag v-if="cfBound" type="success" size="small" style="float:right">
          已绑定: {{ cfUsername }}
        </el-tag>
        <el-tag v-else type="info" size="small" style="float:right">未绑定</el-tag>
      </div>

      <el-form :model="form" label-width="80px" size="small">
        <el-form-item label="CF用户名">
          <el-input v-model="form.cf_username" placeholder="Codeforces Handle" :disabled="cfBound" />
        </el-form-item>
        <el-form-item label="CF密码">
          <el-input v-model="form.cf_password" type="password" placeholder="Codeforces Password" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="bindAccount" :loading="loading">
            {{ cfBound ? '更新绑定' : '绑定账号' }}
          </el-button>
          <span v-if="bindResult" :class="bindResult.ok ? 'success-msg' : 'error-msg'">
            {{ bindResult.msg }}
          </span>
        </el-form-item>
      </el-form>

      <el-divider />
      <p class="notice">
        绑定后可在此平台直接提交代码到 Codeforces 并获取评测结果。
        密码使用 AES 加密存储，不会明文保存。
      </p>
    </el-card>
  </div>
</template>

<script>
export default {
  name: 'CFBind',
  data() {
    return {
      form: { cf_username: '', cf_password: '' },
      loading: false,
      bindResult: null,
      cfBound: false,
      cfUsername: '',
    };
  },
  computed: {
    uid() {
      const info = this.$store.getters.userInfo || {};
      return info.uid || null;
    },
  },
  mounted() {
    if (this.uid) this.checkStatus();
  },
  methods: {
    async checkStatus() {
      try {
        const resp = await this.$http.get(`/vjudge/api/vjudge/cf-status/${this.uid}`);
        this.cfBound = resp.data.bound;
        this.cfUsername = resp.data.cf_username || '';
        if (this.cfBound) this.form.cf_username = this.cfUsername;
      } catch(e) {}
    },
    async bindAccount() {
      if (!this.form.cf_username || !this.form.cf_password) {
        this.$message.warning('请填写CF用户名和密码');
        return;
      }
      this.loading = true;
      this.bindResult = null;
      try {
        const resp = await this.$http.post('/vjudge/api/vjudge/bind-cf', {
          uid: this.uid,
          cf_username: this.form.cf_username,
          cf_password: this.form.cf_password,
        });
        this.cfBound = true;
        this.cfUsername = resp.data.cf_username;
        this.bindResult = { ok: true, msg: '绑定成功！' };
        this.form.cf_password = '';
        this.$emit('bound', resp.data.cf_username);
      } catch(e) {
        const msg = e.response?.data?.detail || '绑定失败';
        this.bindResult = { ok: false, msg };
        this.$message.error(msg);
      } finally {
        this.loading = false;
      }
    },
  },
};
</script>

<style scoped>
.cf-bind-container { max-width: 500px; margin: 16px auto; }
.success-msg { color: #67c23a; margin-left: 12px; }
.error-msg { color: #f56c6c; margin-left: 12px; }
.notice { font-size: 12px; color: #909399; }
</style>
