<template>
  <span class="remote-credential-settings">
    <el-button size="small" icon="el-icon-key" @click="open">会话授权</el-button>
    <el-dialog :visible.sync="visible" title="授权平台登录会话" width="560px" top="6vh" append-to-body custom-class="remote-credential-dialog" :close-on-click-modal="false" @close="dismiss">
      <p class="credential-account">{{ platformName }} · {{ account.handle }}</p>
      <p class="credential-note">在源站手动登录，再授权本站使用这个账号的会话。会话加密保存，保存后不再显示。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p v-if="loading" role="status">正在读取保存状态…</p>
      <form v-else-if="settings" @submit.prevent="save">
        <p class="credential-status" role="status"><i :class="settings.credentialType === 'BROWSER_SESSION' ? 'el-icon-circle-check' : 'el-icon-key'" /> {{ settings.reauthorizationRequired ? '旧密码凭据需要重新授权' : settings.configured ? '已保存登录会话' : '尚未授权登录会话' }}</p>
        <p v-if="settings.submissionSupported === false" class="credential-note credential-pending">当前未开放此平台的代码提交，可先管理会话授权。</p>
        <p class="credential-note">本次只核对登录身份并保存会话。做题页主动点击提交后，才会使用此账号提交代码。</p>
        <ol class="credential-steps">
          <li><span>登录 {{ platformName }}</span><p class="credential-note">确认源站当前登录的是 {{ account.handle }}。</p><a :href="loginUrl" target="_blank" rel="noopener noreferrer" class="credential-login">打开源站登录 <i class="el-icon-top-right" /></a></li>
          <li><label for="remote-submit-session">粘贴登录会话（Cookie）</label><p class="credential-note">浏览器不会自动共享源站登录信息，请手动复制。</p>
            <el-input id="remote-submit-session" v-model="cookieHeader" type="password" autocomplete="off" maxlength="8192" :disabled="busy" :placeholder="settings.configured ? '粘贴新会话以替换，原值不会回填' : '粘贴当前源站请求的 Cookie 值'" />
            <details class="credential-help"><summary>如何获取会话？</summary><p>在已登录的源站页面按 F12 打开开发者工具，切到 Network（网络），刷新页面，选中该源站的页面请求，在 Request Headers（请求标头）里复制 Cookie 的值。只粘贴这个值，不要粘贴整段请求或运行任何脚本。</p><p>会话能代表你的登录身份，请只填在这里。会话过期或源站要求验证时，需要重新登录并授权。</p></details>
          </li>
        </ol>
        <el-checkbox v-model="authorized" :disabled="busy" class="credential-consent">我授权本站核对会话身份，并在我主动提交时使用此账号代提交代码。</el-checkbox>
        <p v-if="settings.sessionCheckedAt" class="credential-note">上次身份核对：{{ new Date(settings.sessionCheckedAt).toLocaleString('zh-CN') }}。提交前仍需检查会话是否有效。</p>
      </form>
      <span slot="footer"><el-button v-if="settings && settings.configured" type="text" :disabled="busy" @click="remove">撤销授权</el-button><el-button @click="visible=false">关闭</el-button><el-button type="primary" :loading="busy" :disabled="loading || !settings || !cookieHeader.length || !authorized" @click="save">核对并授权</el-button></span>
    </el-dialog>
  </span>
</template>
<script>
import api from '@/common/api';
import { responseData, errorMessage } from '@/common/objective';
export default {
  name: 'RemoteCredentialSettings',
  props: { account: { type: Object, required: true } },
  data: () => ({ visible: false, settings: null, cookieHeader: '', authorized: false, error: '', loading: false, busy: false, epoch: 0, disposed: false }),
  computed: {
    actor() { return this.$store.getters.isAuthenticated ? this.$store.getters.userInfo.uid || '' : ''; },
    ownershipVerified() { return this.account.ownershipVerified === undefined ? !!this.account.verified : !!this.account.ownershipVerified; },
    context() { return [this.actor, this.account.id, this.account.platform, this.account.handle, this.ownershipVerified].join(':'); },
    platformName() { return ({ CODEFORCES: 'Codeforces', ATCODER: 'AtCoder', NOWCODER: '牛客' })[this.account.platform] || this.account.platform; },
    loginUrl() { return this.account.platform === 'NOWCODER' ? 'https://ac.nowcoder.com/login' : this.account.platform === 'ATCODER' ? 'https://atcoder.jp/login?lang=en' : 'https://codeforces.com/enter?locale=en'; },
  },
  watch: { context() { this.visible = false; this.dismiss(); } },
  beforeDestroy() { this.disposed = true; this.dismiss(); },
  methods: {
    dismiss() { this.epoch++; this.cookieHeader = ''; this.authorized = false; this.settings = null; this.error = ''; this.loading = false; this.busy = false; },
    current(epoch, context) { return !this.disposed && this.visible && epoch === this.epoch && context === this.context; },
    async open() {
      if (this.disposed || !this.actor || !this.ownershipVerified) return;
      this.dismiss(); this.visible = true; this.loading = true;
      const epoch = ++this.epoch, context = this.context;
      try { const result = responseData(await api.remoteCredentials(this.account.id)); if (this.current(epoch, context)) this.settings = result; }
      catch (error) { if (this.current(epoch, context)) this.error = errorMessage(error); }
      finally { if (this.current(epoch, context)) this.loading = false; }
    },
    async save() {
      if (this.disposed || !this.visible || this.busy || !this.settings || !this.cookieHeader.length || !this.authorized) return;
      const epoch = ++this.epoch, context = this.context, cookieHeader = this.cookieHeader, version = this.settings.version;
      this.cookieHeader = ''; this.authorized = false; this.busy = true; this.error = '';
      try { const result = responseData(await api.saveRemoteCredentials(this.account.id, cookieHeader, version, true)); if (this.current(epoch, context)) { this.settings = result; this.$emit('changed'); } }
      catch (error) { if (this.current(epoch, context)) this.error = errorMessage(error); }
      finally { if (this.current(epoch, context)) this.busy = false; }
    },
    async remove() {
      if (this.disposed || !this.visible || this.busy || !this.settings) return;
      const epoch = this.epoch, context = this.context, id = this.account.id, version = this.settings.version;
      try { await this.$confirm('撤销 ' + this.platformName + ' · ' + this.account.handle + ' 的会话授权？之后需要重新授权才能代提交。', '撤销授权', { type: 'warning' }); } catch (_) { return; }
      if (!this.current(epoch, context) || this.busy) return;
      this.busy = true; this.error = ''; this.cookieHeader = ''; this.authorized = false;
      try { const result = responseData(await api.deleteRemoteCredentials(id, version)); if (this.current(epoch, context)) { this.settings = result; this.$emit('changed'); } }
      catch (error) { if (this.current(epoch, context)) this.error = errorMessage(error); }
      finally { if (this.current(epoch, context)) this.busy = false; }
    },
  },
};
</script>
<style>
.remote-credential-dialog { max-width: calc(100vw - 24px); max-height: 88vh; display: flex; flex-direction: column; text-align: left; color: var(--text); }
.remote-credential-dialog .el-dialog__body { overflow-y: auto; padding-top: 12px; }
.remote-credential-dialog .credential-account { color: var(--accent); font-weight: 600; margin-top: 0; }
.remote-credential-dialog .credential-note { color: var(--muted); font-size: 12px; line-height: 1.8; margin: 8px 0; }
.remote-credential-dialog .credential-status { color: var(--text); font-size: 14px; }
.remote-credential-dialog .credential-steps { padding-left: 24px; margin: 24px 0; }
.remote-credential-dialog .credential-steps li { padding-left: 4px; margin-bottom: 22px; font-size: 14px; }
.remote-credential-dialog .credential-steps label { display: block; font-size: 14px; }
.remote-credential-dialog .credential-login { display: inline-block; color: var(--accent); padding: 8px 0; }
.remote-credential-dialog .credential-help { font-size: 12px; line-height: 1.8; color: var(--muted); margin-top: 12px; }
.remote-credential-dialog .credential-help summary { cursor: pointer; color: var(--accent); }
.remote-credential-dialog .credential-consent { display: flex; align-items: flex-start; }
.remote-credential-dialog .credential-consent .el-checkbox__label { white-space: normal; line-height: 1.7; font-size: 12px; }
.remote-credential-dialog .credential-consent .el-checkbox__input { margin-top: 3px; }
.remote-credential-dialog .el-dialog__footer>span { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
.remote-credential-dialog .el-dialog__footer .el-button { margin: 0; }
</style>
