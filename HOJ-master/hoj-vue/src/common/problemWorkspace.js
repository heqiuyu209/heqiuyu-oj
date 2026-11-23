import { draftKey } from '@/common/learning';
import { readPreference, savePreference } from '@/common/appearance';

export default {
  data: () => ({ legacyDraft: null }),
  computed: {
    draftIdentity() { return draftKey(this.$store.getters.userInfo.uid, this.$route.params); },
  },
  mounted() { window.addEventListener('beforeunload', this.saveCurrentDraft); window.addEventListener('resize', this.resizeWatchHeight); },
  beforeDestroy() { clearTimeout(this.draftTimer); window.removeEventListener('beforeunload', this.saveCurrentDraft); window.removeEventListener('resize', this.resizeWatchHeight); clearInterval(this.refreshStatus); this.problemRequest = (this.problemRequest || 0) + 1; },
  methods: {
    restoreWorkspaceDraft() {
      const selected = readPreference(this.draftIdentity + ':selected', null);
      if (typeof selected === 'string') this.language = selected;
      const saved = readPreference(this.draftIdentity + ':' + encodeURIComponent(this.language), null);
      if (saved && typeof saved.code === 'string') { this.code = saved.code; this.legacyDraft = null; }
    },
    saveCurrentDraft() {
      clearTimeout(this.draftTimer);
      if (!this.language || this.loading || this.submitDisabled) return;
      savePreference(this.draftIdentity + ':selected', this.language);
      savePreference(this.draftIdentity + ':' + encodeURIComponent(this.language), { code: this.code });
    },
    queueDraftSave() { clearTimeout(this.draftTimer); this.draftTimer = setTimeout(this.saveCurrentDraft, 350); },
    importLegacyDraft() {
      this.$confirm('导入会替换当前编辑器内容。旧版草稿没有账号归属，请确认这是你的代码。', '导入旧版草稿', { type: 'warning' }).then(() => {
        if (!this.legacyDraft || !this.problemData.languages.includes(this.legacyDraft.language)) { this.$message.warning('当前题目不支持旧草稿的语言'); return; }
        this.saveCurrentDraft();
        this.language = this.legacyDraft.language;
        this.code = this.legacyDraft.code;
        this.saveCurrentDraft();
        this.legacyDraft = null;
      }).catch(() => {});
    },
    refreshWorkspaceEditor() { this.$nextTick(() => { if (this.$refs.codeEditor) this.$refs.codeEditor.editor.refresh(); }); },
  },
  watch: {
    code() { this.queueDraftSave(); },
    '$store.getters.userInfo.uid'() {
      clearTimeout(this.draftTimer);
      this.initProblemCodeAndSetting();
      this.init();
    },
  },
};
