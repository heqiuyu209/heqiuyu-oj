<template>
  <div class="ai-sidebar" :class="{ collapsed: !visible }">
    <div class="ai-sidebar-toggle" @click="toggle">
      <span v-if="!visible">AI</span>
      <span v-else>✕</span>
    </div>

    <div v-if="visible" class="ai-sidebar-content">
      <div class="ai-header">
        <h4>AI 训练助手</h4>
        <span class="ai-status" :class="{ online: aiAvailable }">
          {{ aiAvailable ? '在线' : '不可用' }}
        </span>
      </div>

      <div v-if="inContest" class="ai-banner contest">
        <p>比赛期间 AI 助手已暂停</p>
      </div>

      <div v-else>
        <!-- Stuck Detection -->
        <div v-if="stuckState && stuckState.is_stuck" class="ai-stuck-alert">
          <p class="stuck-type">检测到: {{ stuckTypeLabel(stuckState.stuck_type) }}</p>
          <p class="stuck-desc">{{ stuckState.description }}</p>
          <el-button size="small" type="warning" @click="requestHint(1)" :loading="hintLoading">
            获取提示
          </el-button>
        </div>

        <!-- Hint Display -->
        <div v-if="currentHint" class="ai-hint-box">
          <div class="ai-hint-level">第 {{ hintLevel }} 层提示</div>
          <p class="ai-hint-text">{{ currentHint }}</p>
          <div class="ai-hint-actions" v-if="hintLevel < 3">
            <el-button size="mini" @click="requestHint(hintLevel + 1)" :loading="hintLoading">
              需要更多提示
            </el-button>
          </div>
          <div class="ai-feedback">
            <span>这个提示有帮助吗？</span>
            <el-button size="mini" icon="el-icon-check" @click="feedback(true)"></el-button>
            <el-button size="mini" icon="el-icon-close" @click="feedback(false)"></el-button>
          </div>
        </div>

        <!-- No issue area -->
        <div v-if="!stuckState || !stuckState.is_stuck" class="ai-idle">
          <p>{{ idleMessage }}</p>
        </div>

        <!-- Quick Chat -->
        <div class="ai-chat">
          <el-input
            v-model="chatInput"
            placeholder="向AI教练提问..."
            size="small"
            @keyup.enter.native="sendChat"
          >
            <el-button slot="append" icon="el-icon-s-promotion" @click="sendChat"
                       :loading="chatLoading"></el-button>
          </el-input>
          <div v-if="chatReply" class="ai-chat-reply">
            <p>{{ chatReply }}</p>
          </div>
        </div>
      </div>

      <div class="ai-footer">
        <span>今日: {{ dailyUsage }}/{{ dailyLimit }} 次</span>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'AISidebar',
  data() {
    return {
      visible: false,
      aiAvailable: true,
      inContest: false,
      stuckState: null,
      currentHint: null,
      hintLevel: 1,
      hintLoading: false,
      chatInput: '',
      chatReply: null,
      chatLoading: false,
      idleMessage: '正在监控你的训练状态...',
      dailyUsage: 0,
      dailyLimit: 50,
      pollTimer: null,
    };
  },
  computed: {
    currentProblemId() {
      const m = this.$route.path.match(/\/problem\/(\d+)/);
      return m ? parseInt(m[1]) : null;
    },
    currentContestId() {
      const m = this.$route.path.match(/\/contest\/(\d+)/);
      return m ? parseInt(m[1]) : null;
    },
    uid() {
      return this.$store.state.user ? this.$store.state.user.uid : null;
    },
  },
  mounted() {
    if (this.uid) {
      this.fetchStatus();
      this.startPolling();
    }
    this.inContest = !!this.currentContestId;
  },
  beforeDestroy() {
    if (this.pollTimer) clearInterval(this.pollTimer);
  },
  watch: {
    '$route'() {
      this.inContest = !!this.currentContestId;
      this.currentHint = null;
      this.hintLevel = 1;
      this.chatReply = null;
    },
  },
  methods: {
    toggle() { this.visible = !this.visible; },
    stuckTypeLabel(type) {
      const map = {
        logic: '思路问题', optimization: '优化问题',
        implementation: '实现问题', abandoned: '可能放弃',
        repeated_failure: '反复失败',
      };
      return map[type] || type;
    },
    async fetchStatus() {
      try {
        const resp = await this.$http.get(`/agent/api/agent/status/${this.uid}`);
        this.dailyUsage = resp.data.daily_llm_calls;
        this.dailyLimit = resp.data.daily_limit;
        this.inContest = resp.data.in_contest;
      } catch(e) { this.aiAvailable = false; }
    },
    startPolling() {
      this.pollTimer = setInterval(() => {
        if (this.visible && this.currentProblemId && !this.inContest) {
          this.detectStuck();
        }
      }, 30000); // Every 30s
    },
    async detectStuck() {
      try {
        const resp = await this.$http.post('/agent/api/agent/detect', {
          uid: this.uid, pid: this.currentProblemId,
        });
        this.stuckState = resp.data;
      } catch(e) { /* silent */ }
    },
    async requestHint(level) {
      this.hintLoading = true;
      try {
        const resp = await this.$http.post('/agent/api/agent/hint', {
          uid: this.uid, pid: this.currentProblemId, level,
        });
        this.currentHint = resp.data.hint;
        this.hintLevel = resp.data.level;
        this.stuckState = resp.data.stuck_state;
      } catch(e) {
        this.$message.error('AI服务暂不可用');
      } finally {
        this.hintLoading = false;
      }
    },
    async sendChat() {
      if (!this.chatInput.trim()) return;
      this.chatLoading = true;
      try {
        const resp = await this.$http.post('/agent/api/agent/chat', {
          uid: this.uid, pid: this.currentProblemId, message: this.chatInput,
        });
        this.chatReply = resp.data.reply;
      } catch(e) {
        this.$message.error('AI服务暂不可用');
      } finally {
        this.chatLoading = false;
        this.chatInput = '';
      }
    },
    async feedback(helpful) {
      this.$message.success(helpful ? '感谢反馈!' : '我们会持续改进');
    },
  },
};
</script>

<style scoped>
.ai-sidebar {
  position: fixed;
  right: 0;
  top: 120px;
  z-index: 999;
  font-size: 13px;
}
.ai-sidebar-toggle {
  width: 36px; height: 36px;
  background: #409EFF; color: #fff;
  display: flex; align-items: center; justify-content: center;
  border-radius: 8px 0 0 8px;
  cursor: pointer;
  margin-bottom: 4px;
}
.ai-sidebar-content {
  width: 300px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px 0 0 8px;
  box-shadow: -2px 2px 12px rgba(0,0,0,.1);
  overflow: hidden;
}
.ai-header {
  padding: 12px 16px;
  background: #409EFF;
  color: #fff;
  display: flex; justify-content: space-between; align-items: center;
}
.ai-header h4 { margin: 0; font-size: 15px; }
.ai-status { font-size: 11px; padding: 2px 8px; border-radius: 10px; background: rgba(255,255,255,.2); }
.ai-status.online { background: rgba(103,194,58,.8); }
.ai-banner { padding: 12px 16px; text-align: center; }
.ai-banner.contest { background: #fdf6ec; color: #e6a23c; }
.ai-banner.contest p { margin: 0; }
.ai-stuck-alert { padding: 12px 16px; background: #fef0f0; border-bottom: 1px solid #fde2e2; }
.stuck-type { font-weight: bold; color: #f56c6c; margin: 0 0 4px 0; }
.stuck-desc { margin: 0 0 8px 0; color: #909399; font-size: 12px; }
.ai-hint-box { padding: 12px 16px; background: #f0f9eb; border-bottom: 1px solid #e1f3d8; }
.ai-hint-level { font-size: 11px; color: #67c23a; font-weight: bold; margin-bottom: 8px; }
.ai-hint-text { margin: 0 0 8px 0; line-height: 1.6; }
.ai-hint-actions { margin-bottom: 8px; }
.ai-feedback { font-size: 12px; color: #909399; }
.ai-feedback span { margin-right: 8px; }
.ai-idle { padding: 16px; text-align: center; color: #909399; }
.ai-chat { padding: 12px 16px; }
.ai-chat-reply { margin-top: 8px; padding: 8px 12px; background: #f4f4f5; border-radius: 6px; line-height: 1.6; }
.ai-chat-reply p { margin: 0; }
.ai-footer { padding: 8px 16px; font-size: 11px; color: #c0c4cc; text-align: center; border-top: 1px solid #ebeef5; }
</style>
