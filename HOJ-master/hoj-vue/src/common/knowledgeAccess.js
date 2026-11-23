import api from '@/common/api';
import { responseData, errorMessage } from '@/common/objective';

// Page data never survives a login, role or contest-access change. The server
// still authorizes every read and every retrieval/model response independently.
export default {
  data: () => ({ knowledgeEpoch: 0, policyRequest: 0, accessReady: false, contestBlocked: false, accessError: '' }),
  computed: {
    knowledgeIdentity() {
      const getters = this.$store.getters;
      return [getters.userInfo.uid || '', getters.authEpoch, (getters.userInfo.roleList || []).slice().sort().join(',')].join(':');
    },
    knowledgeAllowed() { return this.$store.getters.isAuthenticated && this.accessReady && !this.contestBlocked && !this.accessError; },
  },
  watch: { knowledgeIdentity() { this.invalidateKnowledge(); this.loadKnowledgeAccess(); } },
  mounted() {
    this.loadKnowledgeAccess();
    this.knowledgeTimer = setInterval(() => { if (!document.hidden && !this.accessError) this.loadKnowledgeAccess(); }, 15000);
    this.knowledgeAccessChanged = () => { this.invalidateKnowledge(); this.loadKnowledgeAccess(); };
    this.knowledgeVisibilityChanged = () => { if (!document.hidden) this.loadKnowledgeAccess(); };
    window.addEventListener('hoj-ai-access-changed', this.knowledgeAccessChanged);
    document.addEventListener('visibilitychange', this.knowledgeVisibilityChanged);
  },
  beforeDestroy() {
    this.invalidateKnowledge(); clearInterval(this.knowledgeTimer);
    window.removeEventListener('hoj-ai-access-changed', this.knowledgeAccessChanged);
    document.removeEventListener('visibilitychange', this.knowledgeVisibilityChanged);
  },
  methods: {
    invalidateKnowledge() {
      this.knowledgeEpoch++; this.policyRequest++; this.accessReady = false; this.accessError = '';
      if (this.clearKnowledgeData) this.clearKnowledgeData();
    },
    knowledgeCurrent(epoch) { return epoch === this.knowledgeEpoch && this.knowledgeAllowed; },
    async loadKnowledgeAccess() {
      if (!this.$store.getters.isAuthenticated) return;
      const epoch = this.knowledgeEpoch, request = ++this.policyRequest;
      try {
        const settings = responseData(await api.aiSettings());
        if (epoch !== this.knowledgeEpoch || request !== this.policyRequest) return;
        if (!settings || typeof settings.contestBlocked !== 'boolean') throw new Error('Invalid access response');
        const first = !this.accessReady, changed = settings.contestBlocked !== this.contestBlocked;
        if (changed) { this.knowledgeEpoch++; if (this.clearKnowledgeData) this.clearKnowledgeData(); }
        this.contestBlocked = settings.contestBlocked; this.accessReady = true; this.accessError = '';
        if ((first || changed) && !settings.contestBlocked && this.onKnowledgeReady) this.onKnowledgeReady();
      } catch (error) {
        if (epoch !== this.knowledgeEpoch || request !== this.policyRequest) return;
        this.knowledgeEpoch++; if (this.clearKnowledgeData) this.clearKnowledgeData();
        this.accessReady = false; this.accessError = errorMessage(error);
      }
    },
  },
};
