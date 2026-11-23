<template>
  <div class="ai-assistant">
    <el-button v-show="!open" class="ai-launcher" type="primary" round @click="show" aria-label="打开 AI 助教"><i class="el-icon-chat-dot-round" /> AI 助教</el-button>
    <section v-if="open" class="ai-popup" role="dialog" aria-label="AI 助教" @keydown.esc="close" :style="position">
      <header @pointerdown="startDrag"><span><i class="el-icon-chat-dot-round" /> AI 助教</span><button @click="close" aria-label="关闭 AI 助教">×</button></header>
      <div class="ai-popup-body">
        <template v-if="!authenticated"><h3>从你卡住的地方开始</h3><p class="muted">登录后配置自己的模型服务，逐步理解题意、推导思路和分析代码。</p><el-button type="primary" @click="login">登录后使用</el-button></template>
        <template v-else>
          <nav class="ai-tabs" aria-label="助教操作"><button type="button" :aria-current="tab === 'chat' ? 'page' : null" @click="tab='chat'">学习对话</button><button type="button" :aria-current="tab === 'settings' ? 'page' : null" @click="tab='settings'">模型设置</button><button type="button" :disabled="blocked" @click="$router.push('/knowledge');close()">知识库</button></nav>
          <el-alert v-if="settings.contestBlocked" title="你正在参赛，比赛结束后恢复 AI 助教" type="info" :closable="false" show-icon />
          <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button v-if="!ready" type="text" @click="loadSettings()">重新加载</el-button></el-alert>
          <div v-if="!ready && !error" class="ai-loading" role="status"><i class="el-icon-loading" /> 正在读取模型设置</div>
          <form v-if="tab === 'settings' && ready" class="ai-settings" @submit.prevent="saveSettings">
            <p class="muted">使用你自己的 OpenAI 兼容服务。保存配置不会调用模型或产生费用。</p>
            <label for="ai-base-url">API 基础地址</label><el-input id="ai-base-url" v-model="form.baseUrl" :disabled="saving || pending" placeholder="https://服务域名/v1" autocomplete="off" :maxlength="512" />
            <small>填写 HTTPS 基础地址，端口为 443 或 8443，不含 chat/completions。</small>
            <label for="ai-model">模型名称</label><el-input id="ai-model" v-model="form.model" :disabled="saving || pending" placeholder="填写服务商提供的完整模型名" autocomplete="off" :maxlength="120" />
            <label for="ai-key">API Key</label><el-input id="ai-key" v-model="form.apiKey" type="password" :disabled="saving || pending" :placeholder="settings.hasKey ? '已保存，留空保留；更换地址需重新输入' : '填写你的 API Key'" autocomplete="new-password" :maxlength="4096" />
            <small>密钥由后端加密保存，浏览器不保存，之后也无法查看原文。更换地址时请填写对应的密钥。</small>
            <div class="ai-settings-actions"><el-button native-type="submit" type="primary" size="small" :loading="saving" :disabled="pending">保存设置</el-button><el-button v-if="settings.configured" size="small" :disabled="saving || pending" @click="removeSettings">删除配置</el-button></div>
          </form>
          <template v-if="tab === 'chat' && ready">
            <div v-if="!settings.configured" class="ai-empty"><h3>先连接你的模型服务</h3><p class="muted">填写 API 地址、模型名和 API Key 后即可提问。</p><el-button size="small" type="primary" @click="tab='settings'">配置模型</el-button></div>
            <template v-else>
              <p class="ai-context">{{ settings.model }}<el-button type="text" :disabled="pending || !messages.length" @click="clearChat">清空对话</el-button></p>
              <div ref="history" class="ai-history" role="log" aria-live="polite" aria-label="学习对话">
                <div v-if="!messages.length" class="ai-empty"><h3>从你卡住的地方开始</h3><p>写下题意、你的思路或代码，先获取下一步提示。</p></div>
                <article v-for="(message, index) in messages" :key="index" :class="'ai-message ai-message-' + message.role"><strong>{{ message.role === 'user' ? '你' : '助教' }}</strong><p>{{ message.content }}</p></article>
                <div v-if="pending" role="status" class="muted"><i class="el-icon-loading" /> 正在思考，请稍候</div>
              </div>
              <div class="ai-prompts"><el-button v-for="text in prompts" :key="text" size="mini" :disabled="blocked || pending" @click="question=text;focusQuestion()">{{ text }}</el-button></div>
              <form @submit.prevent="send">
                <el-input ref="input" v-model="question" type="textarea" :rows="3" :disabled="blocked || pending" :maxlength="8000" placeholder="写下你的疑问，可粘贴题面或自己的代码" aria-label="向 AI 助教提问" @keydown.ctrl.enter.native.prevent="send" />
                <div v-if="availableProblem" class="ai-problem-context">
                  <el-checkbox :value="includeProblem" :disabled="blocked || pending" @change="setIncludeProblem">附带当前题面</el-checkbox>
                  <el-button v-if="includeProblem" class="ai-context-refresh" type="text" size="mini" :disabled="blocked || pending || contextLoading" @click="setIncludeProblem(true)">刷新题面</el-button>
                  <p>{{ availableProblem.title }}<br><small>{{ availableProblem.reference.kind === 'PROGRAMMING' ? '使用题库原始题面。' : '使用本次答卷的冻结题面。' }}发送时附带题干、选项或输入输出样例，可在下方预览。</small></p>
                  <p v-if="contextLoading" role="status"><i class="el-icon-loading" /> 正在核验题面与访问权限</p>
                  <details v-if="contextPreview" @toggle="$nextTick(clamp)"><summary>查看将发送的题面</summary><strong>{{ contextPreview.material.title }}</strong><p>{{ contextPreview.material.source }}</p><div v-for="field in ['stem', 'input', 'output', 'examples']" v-show="contextPreview.material[field]" :key="field"><small>{{ { stem: '题干', input: '输入', output: '输出', examples: '样例' }[field] }}</small><pre>{{ contextPreview.material[field] }}</pre></div><p v-for="option in contextPreview.material.options || []" :key="'option-'+option.id">{{ option.id }}. {{ option.text }}</p><p v-for="blank in contextPreview.material.blanks || []" :key="'blank-'+blank.id">填空 {{ blank.id }}：{{ blank.label }}</p></details>
                </div>
                <el-checkbox v-model="includeProfile" :disabled="blocked || pending" class="ai-profile-toggle">附带我的站内能力画像</el-checkbox>
                <el-checkbox v-if="includeProfile" v-model="includeExternalProfile" :disabled="blocked || pending" class="ai-profile-toggle">同时附带已验证的外部画像</el-checkbox>
                <div class="ai-send"><small>发送至你配置的模型服务</small><el-button native-type="submit" size="small" type="primary" :loading="pending" :disabled="blocked || pending || !question.trim() || includeProblem && (contextLoading || !contextPreview)">发送</el-button></div>
              </form>
              <p class="ai-privacy">发送本次对话及勾选的题面、画像。最近 7 轮保留在当前页面；刷新、换题或更换附带题面选项时清除历史。AI 回复需要自行验证。</p>
            </template>
          </template>
        </template>
      </div>
    </section>
  </div>
</template>
<script>
import api from '@/common/api';
import { responseData, errorMessage } from '@/common/objective';
import { currentAiProblem, subscribeAiProblem } from '@/common/aiProblemContext';
const emptySettings = () => ({ configured: false, hasKey: false, baseUrl: '', model: '', contestBlocked: false });
export default {
  name: 'AiAssistant',
  data: () => ({ open: false, question: '', messages: [], includeProfile: false, includeExternalProfile: false, problemDescriptor: null, contextPreview: null, contextLoading: false, includeProblem: false, contextRequest: 0, x: null, y: null, tab: 'chat', settings: emptySettings(), form: { baseUrl: '', model: '', apiKey: '' }, ready: false, error: '', saving: false, pending: false, epoch: 0, settingsRequest: 0, chatRequest: 0, prompts: ['帮我理解题意', '提示下一步思路', '分析复杂度'] }),
  computed: {
    authenticated() { return this.$store.getters.isAuthenticated; },
    identity() { return [(this.$store.getters.userInfo.uid || ''), this.authenticated, this.$store.getters.authEpoch, (this.$store.getters.userInfo.roleList || []).slice().sort().join(',')].join(':'); },
    blocked() { return !this.ready || this.settings.contestBlocked; },
    availableProblem() { const value=this.problemDescriptor; return value && value.route===this.$route.fullPath && value.uid===(this.$store.getters.userInfo.uid || '') && value.authEpoch===this.$store.getters.authEpoch ? value : null; },
    position() { return this.x === null ? {} : { left: this.x + 'px', top: this.y + 'px', right: 'auto', bottom: 'auto' }; },
  },
  watch: {
    '$route.fullPath'() { this.epoch++; this.settingsRequest++; this.clearChat(); this.acceptProblem(currentAiProblem()); this.saving=false; this.form.apiKey=''; if (this.open && this.authenticated) this.loadSettings(); },
    identity() { this.reset(); this.open=false; },
    'settings.contestBlocked'(value) { if (value) this.clearChat(); },
    includeProfile(value) { if (!value) this.includeExternalProfile=false; },
  },
  mounted() { this.unsubscribeProblem=subscribeAiProblem(this.acceptProblem); window.addEventListener('hoj-ai-open-problem', this.openProblem); window.addEventListener('resize', this.clamp); window.addEventListener('hoj-ai-access-changed', this.refreshPolicy); window.addEventListener('hoj-ai-open-settings', this.openSettings); this.timer=window.setInterval(() => { if (this.open && this.authenticated && this.ready && !this.saving) this.loadSettings(true); }, 20000); },
  beforeDestroy() { this.reset(); this.stopDrag(); if(this.unsubscribeProblem)this.unsubscribeProblem();window.removeEventListener('hoj-ai-open-problem',this.openProblem);window.removeEventListener('resize', this.clamp); window.removeEventListener('hoj-ai-access-changed', this.refreshPolicy); window.removeEventListener('hoj-ai-open-settings', this.openSettings); window.clearInterval(this.timer); },
  methods: {
    acceptProblem(descriptor) { if(JSON.stringify(descriptor)===JSON.stringify(this.problemDescriptor))return;this.clearChat();this.problemDescriptor=descriptor; },
    openProblem() { this.tab='chat';this.show(); },
    setIncludeProblem(value) { const draft=this.question;this.clearChat();this.question=draft;this.includeProblem=value===true;if(this.includeProblem)this.loadProblemContext(); },
    async loadProblemContext() {
      if(!this.availableProblem||!this.includeProblem||this.blocked)return;
      const epoch=this.epoch, request=++this.contextRequest, descriptor=this.availableProblem;this.contextLoading=true;this.error='';
      try { const context=responseData(await api.aiProblemContext(descriptor.reference));if(epoch!==this.epoch||request!==this.contextRequest||descriptor!==this.availableProblem||!this.includeProblem)return;this.contextPreview=context;this.$nextTick(this.clamp); }
      catch(error) { if(epoch===this.epoch&&request===this.contextRequest){this.includeProblem=false;this.contextPreview=null;this.error=errorMessage(error);} }
      finally { if(epoch===this.epoch&&request===this.contextRequest)this.contextLoading=false; }
    },
    async openSettings() { this.open=true; this.tab='settings'; const epoch=this.epoch; if(this.authenticated) await this.loadSettings(); if(epoch===this.epoch) this.tab='settings'; this.$nextTick(this.clamp); },
    refreshPolicy() { this.epoch++; this.settingsRequest++; this.clearChat(); this.form.apiKey=''; this.saving=false; this.ready=false; if (this.open && this.authenticated) this.loadSettings(true); },
    reset() { this.epoch++; this.settingsRequest++; this.clearChat(); this.ready=false; this.settings=emptySettings(); this.form={baseUrl:'',model:'',apiKey:''}; this.error=''; this.saving=false; this.tab='chat'; },
    clearChat() { this.chatRequest++; this.messages=[]; this.question=''; this.pending=false; this.includeProfile=false; this.includeExternalProfile=false;this.contextRequest++;this.includeProblem=false;this.contextPreview=null;this.contextLoading=false; },
    login() { this.$store.commit('changeModalStatus', { mode:'Login',visible:true }); },
    show() { this.open=true; if (this.authenticated) this.loadSettings(); this.$nextTick(() => { this.clamp(); this.focusQuestion(); }); },
    close() { this.open=false; this.form.apiKey=''; this.stopDrag(); this.$nextTick(() => { const button=this.$el.querySelector('.ai-launcher'); if (button) button.focus(); }); },
    focusQuestion() { this.$nextTick(() => { if (this.$refs.input && !this.blocked) this.$refs.input.focus(); }); },
    async loadSettings(quiet=false) {
      const epoch=this.epoch, request=++this.settingsRequest, previous=this.settings, hadSettings=this.ready || !!this.settings.fingerprint;
      if (!quiet) { this.ready=false; this.error=''; }
      try {
        const settings=responseData(await api.aiSettings());
        if (epoch!==this.epoch || request!==this.settingsRequest) return;
        const modelChanged=hadSettings && (previous.configured !== settings.configured || previous.fingerprint !== settings.fingerprint);
        if (modelChanged) { this.clearChat(); this.error='模型配置已更新，旧对话已清空，请核对设置后重新提问'; }
        this.settings=settings; this.ready=true;
        if (!quiet || modelChanged) { this.form={baseUrl:settings.baseUrl,model:settings.model,apiKey:''}; if (!settings.configured) this.tab='settings'; }
      } catch (error) { if (epoch===this.epoch && request===this.settingsRequest) { this.ready=false; this.clearChat(); this.error=errorMessage(error); } }
    },
    async saveSettings() {
      if (this.saving || this.pending) return;
      const epoch=this.epoch; this.saving=true; this.error=''; this.settingsRequest++;
      try {
        const settings=responseData(await api.saveAiSettings({...this.form}));
        if (epoch!==this.epoch) return;
        this.settings=settings; this.form={baseUrl:settings.baseUrl,model:settings.model,apiKey:''}; this.clearChat(); this.tab='chat'; this.$emit('settings-changed'); this.$message.success('模型设置已保存');
      } catch (error) { if (epoch===this.epoch) this.error=errorMessage(error); }
      finally { if (epoch===this.epoch) this.saving=false; }
    },
    async removeSettings() {
      const epoch=this.epoch;
      try { await this.$confirm('删除后需重新填写 API Key 才能使用助教。', '删除模型配置', {type:'warning'}); } catch (_) { return; }
      if (epoch!==this.epoch || this.saving || this.pending) return;
      this.saving=true; this.error=''; this.settingsRequest++;
      try {
        const settings=responseData(await api.removeAiSettings());
        if (epoch!==this.epoch) return;
        this.settings=settings; this.form={baseUrl:'',model:'',apiKey:''}; this.clearChat(); this.$emit('settings-changed'); this.$message.success('模型配置已删除');
      } catch (error) { if (epoch===this.epoch) this.error=errorMessage(error); }
      finally { if (epoch===this.epoch) this.saving=false; }
    },
    async send() {
      const content=this.question.trim(); if (!content || this.pending || this.blocked || !this.settings.configured) return;
      if(this.includeProblem&&(this.contextLoading||!this.contextPreview||!this.availableProblem))return;
      const context=this.includeProblem?this.contextPreview:null;
      const epoch=this.epoch, request=++this.chatRequest, history=this.messages.slice(-14).map(message => ({...message}));
      while (history.length && history.reduce((sum,message)=>sum+message.content.length,content.length)>32000) history.splice(0,2);
      const sent=[...history,{role:'user',content}]; this.messages=sent; this.pending=true; this.error='';
      try {
        const reply=responseData(await api.aiChat({messages:sent,modelFingerprint:this.settings.fingerprint,problemContext:context?{reference:context.reference,fingerprint:context.fingerprint}:undefined,includeProfile:this.includeProfile,includeExternalProfile:this.includeProfile && this.includeExternalProfile}));
        // A foreground settings read pauses new sends; it need not discard an already authorized reply.
        if (epoch!==this.epoch || request!==this.chatRequest || this.settings.contestBlocked) return;
        if (reply.modelFingerprint !== this.settings.fingerprint) { this.clearChat(); this.error='模型配置已变化，请核对设置后重新提问'; this.loadSettings(true); return; }
        if(context&&(reply.problemContextIncluded!==true||reply.problemFingerprint!==context.fingerprint)) { this.clearChat();this.error='题面已变化，请重新核验后提问';return; }
        this.messages=[...sent,{role:'assistant',content:reply.content}].slice(-14); this.question='';
      } catch (error) {
        if (epoch!==this.epoch || request!==this.chatRequest) return;
        const status=error && (error.data && error.data.status || error.response && error.response.data && error.response.data.status);
        if(context && [403,404,409].includes(status)) { this.clearChat();this.question=content; }
        else this.messages=history;
        this.error=errorMessage(error); this.loadSettings(true);
      } finally { if (epoch===this.epoch && request===this.chatRequest) { this.pending=false; this.$nextTick(() => { if (this.$refs.history) this.$refs.history.scrollTop=this.$refs.history.scrollHeight; }); } }
    },
    startDrag(event) { if (event.button !== 0 || event.target.closest('button')) return; const rect = event.currentTarget.parentElement.getBoundingClientRect(); this.drag = { x: event.clientX - rect.left, y: event.clientY - rect.top }; window.addEventListener('pointermove', this.move); window.addEventListener('pointerup', this.stopDrag); window.addEventListener('pointercancel', this.stopDrag); },
    move(event) { if (!this.drag) return; this.x = event.clientX - this.drag.x; this.y = event.clientY - this.drag.y; this.clamp(); },
    clamp() { if (this.x === null) return; const popup = this.$el.querySelector('.ai-popup'); if (!popup) return; const top=window.innerWidth<=600?88:8;this.x = Math.max(8, Math.min(this.x, window.innerWidth - popup.offsetWidth - 8)); this.y = Math.max(top, Math.min(this.y, window.innerHeight - popup.offsetHeight - 8)); },
    stopDrag() { this.drag = null; window.removeEventListener('pointermove', this.move); window.removeEventListener('pointerup', this.stopDrag); window.removeEventListener('pointercancel', this.stopDrag); },
  },
};
</script>
<style scoped>
.ai-context-refresh { float: right; padding: 2px 0; }
.ai-problem-context { padding: 12px 0; margin-top: 10px; border-bottom: 1px solid var(--line); font-size: 12px; }.ai-problem-context>p { margin: 6px 0; overflow-wrap: anywhere; }.ai-problem-context small { color: var(--muted); line-height: 1.8; }.ai-problem-context .el-checkbox { white-space: normal; }.ai-problem-context details { max-height: 190px; overflow: auto; padding: 8px; border: 1px solid var(--line); border-radius: 6px; }.ai-problem-context summary { cursor: pointer; color: var(--accent); padding-bottom: 6px; }.ai-problem-context pre { font: inherit; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; margin: 8px 0; }
.ai-launcher { position: fixed; right: 24px; bottom: 80px; z-index: 2001; box-shadow: 0 6px 24px #17465a24; }
.ai-popup { position: fixed; right: 24px; bottom: 80px; width: 430px; max-width: calc(100vw - 32px); max-height: calc(100dvh - 104px); overflow: auto; z-index: 2002; border: 1px solid var(--line); border-radius: 16px; background: var(--surface); color: var(--text); box-shadow: 0 16px 64px #102a4433; }
.ai-popup header { display: flex; justify-content: space-between; align-items: center; padding: 15px 20px; border-bottom: 1px solid var(--line); font-weight: 600; cursor: move; touch-action: none; }
.ai-popup header button { border: 0; background: transparent; font-size: 23px; color: var(--muted); cursor: pointer; }
.ai-popup-body { padding: 18px; }
.ai-popup-body p { line-height: 1.8; }
.ai-context { font-size: 12px; color: var(--accent); }
.ai-prompts { display: flex; flex-wrap: wrap; gap: 7px; margin: 16px 0; }
.ai-prompts .el-button { margin: 0; }
.ai-send { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; }
.ai-tabs { display:flex; gap:20px; margin-bottom:16px; border-bottom:1px solid var(--line); }.ai-tabs button { padding:0 0 10px; border:0; border-bottom:2px solid transparent; background:transparent; color:var(--muted); font:inherit; cursor:pointer; }.ai-tabs button[aria-current] { color:var(--accent); border-color:var(--accent); }.ai-tabs button:focus-visible,.ai-popup header button:focus-visible { outline:2px solid var(--accent); outline-offset:2px; }
.ai-loading { padding:20px 0; }.ai-settings label { display:block; margin:18px 0 8px; font-weight:600; font-size:13px; }.ai-settings small,.ai-send small,.ai-privacy { color:var(--muted); font-size:11px; line-height:1.8; }.ai-settings small { display:block; margin-top:6px; }.ai-settings-actions { display:flex; gap:12px; margin-top:20px; }.ai-settings-actions .el-button { margin:0; }.ai-context { display:flex; gap:12px; justify-content:space-between; align-items:center; margin:8px 0; overflow-wrap:anywhere; }.ai-context .el-button { flex-shrink:0; }.ai-history { max-height:35dvh; min-height:140px; overflow-y:auto; padding:4px 2px 12px; }.ai-empty { padding:16px 0; }.ai-empty p { color:var(--muted); font-size:13px; }.ai-message { padding:10px 12px; margin:0 0 12px; border-radius:8px; background:var(--soft); }.ai-message-assistant { background:transparent; border-left:2px solid var(--accent); border-radius:0; }.ai-message strong { font-size:12px; color:var(--muted); }.ai-message p { font-size:13px; white-space:pre-wrap; overflow-wrap:anywhere; margin:6px 0 0; }.ai-profile-toggle { margin-top:12px; }.ai-privacy { margin:12px 0 0; }.ai-popup .el-alert + .el-alert { margin-top:10px; }
@media(max-width:600px) { .ai-launcher { right:16px; }.ai-popup { right:16px; max-height:calc(100dvh - 184px); }.ai-popup-body { padding:14px; } }
</style>
