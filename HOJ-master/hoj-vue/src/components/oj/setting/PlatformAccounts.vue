<template>
  <div class="platform-accounts">
    <p class="muted">关联公开账号，保留各平台自己的分数与同步状态。无需提供平台密码或 Cookie。</p>
    <div v-for="platform in platforms" :key="platform.key" class="platform-account">
      <div><strong>{{ platform.name }}</strong><el-tag size="mini" :type="platform.key === 'cf' && user.cfUsername ? 'success' : 'info'">{{ platform.key === 'cf' ? (user.cfUsername ? '已登记 · 未验证归属' : '未关联') : '同步待接入' }}</el-tag></div>
      <label :for="'handle-' + platform.key">{{ platform.label }}</label>
      <el-input :id="'handle-' + platform.key" v-model="handles[platform.key]" maxlength="50" :placeholder="platform.placeholder" size="small" />
      <div class="platform-actions">
        <el-button v-if="platform.key === 'cf'" size="small" type="primary" :loading="saving" @click="saveCf">保存 CF 关联</el-button>
        <el-button v-else size="small" @click="saveDraft">保存本地草稿</el-button>
        <a v-if="profileLink(platform.key)" :href="profileLink(platform.key)" target="_blank" rel="noopener noreferrer">查看公开主页 <i class="el-icon-top-right" /></a>
      </div>
    </div>
    <p class="muted">CF 使用已有账号资料接口保存。牛客、QOJ 当前仅保存本机草稿，尚未完成服务端绑定、归属验证或记录同步。</p>
  </div>
</template>
<script>
import api from '@/common/api';
import { readPreference, savePreference } from '@/common/appearance';
export default {
  data: () => ({ handles: { cf: '', nowcoder: '', qoj: '' }, saving: false, platforms: [
    { key: 'cf', name: 'Codeforces', label: 'Handle', placeholder: '例如 tourist' },
    { key: 'nowcoder', name: '牛客', label: '用户 ID', placeholder: '公开主页中的数字用户 ID' },
    { key: 'qoj', name: 'QOJ', label: '用户名', placeholder: '公开主页中的用户名' },
  ] }),
  computed: { user() { return this.$store.getters.userInfo; }, key() { return 'platform-accounts:' + encodeURIComponent(this.user.uid || 'guest'); } },
  watch: { key: { immediate: true, handler() { const saved = readPreference(this.key, {}); this.handles = { cf: this.user.cfUsername || '', nowcoder: saved.nowcoder || '', qoj: saved.qoj || '' }; } }, 'user.cfUsername'(value) { this.handles.cf = value || ''; } },
  methods: {
    profileLink(key) {
      const value = this.handles[key].trim();
      if (!value) return '';
      if (key === 'cf' && /^[a-zA-Z0-9_.-]{3,24}$/.test(value)) return 'https://codeforces.com/profile/' + encodeURIComponent(value);
      if (key === 'nowcoder' && /^\d{1,20}$/.test(value)) return 'https://www.nowcoder.com/users/' + value;
      if (key === 'qoj' && /^[a-zA-Z0-9_.-]{1,50}$/.test(value)) return 'https://qoj.ac/user/profile/' + encodeURIComponent(value);
      return '';
    },
    saveDraft() { const ok = savePreference(this.key, { nowcoder: this.handles.nowcoder.trim(), qoj: this.handles.qoj.trim() }); this.$message[ok ? 'success' : 'error'](ok ? '已保存本地草稿，尚未绑定或同步' : '保存失败，请复制账号信息'); },
    saveCf() {
      if (this.handles.cf.trim() && !this.profileLink('cf')) { this.$message.error('请输入有效的 Codeforces Handle'); return; }
      const data = {};
      ['realname', 'username', 'gender', 'nickname', 'signature', 'number', 'blog', 'school', 'github'].forEach(key => { if (this.user[key] != null) data[key] = this.user[key]; });
      data.cfUsername = this.handles.cf.trim();
      const uid = this.user.uid;
      this.saving = true;
      api.changeUserInfo(data).then(res => {
        if (this._isDestroyed || uid !== this.user.uid) return;
        this.$store.dispatch('setUserInfo', res.data.data);
        this.$message.success('CF 账号关联已保存，未验证账号归属');
      }).catch(() => {}).finally(() => { this.saving = false; });
    },
  },
};
</script>
<style scoped>
.platform-account { padding: 20px; margin: 14px 0; border: 1px solid var(--line); border-radius: 12px; max-width: 650px; }
.platform-account > div:first-child { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.platform-account label { display: block; margin-bottom: 6px; }
.platform-actions { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-top: 12px; }
</style>
