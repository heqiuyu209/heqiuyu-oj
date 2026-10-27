import { Message, MessageBox } from 'element-ui';

// Editors may be nested (paper -> new question). Protect the whole subtree
// when its parent closes; register only mounted editors and never persist forms.
const editors = new Set();
function belongsTo(editor, owner) {
  if (!owner) return true;
  for (let node = editor; node; node = node.$parent) if (node === owner) return true;
  return false;
}

export async function confirmAuthoringDiscard(owner) {
  const relevant = Array.from(editors).filter(editor => belongsTo(editor, owner) && editor.authoringState
    && editor.authoringIdentity === editor.$store.getters.userInfo.uid);
  if (relevant.some(editor => editor.saving)) {
    Message.info('正在保存，请稍候再离开。');
    return false;
  }
  if (!relevant.some(editor => editor.hasUnsavedAuthoring())) return true;
  try {
    const custom = relevant.find(editor => editor.hasUnsavedAuthoring() && editor.authoringDiscardMessage);
    await MessageBox.confirm(custom ? custom.authoringDiscardMessage : '尚未保存的内容将丢失，已经保存到题库的题目会保留。是否放弃本次编辑？', '有未保存的修改', {
      type: 'warning', confirmButtonText: '放弃修改', cancelButtonText: '继续编辑',
      closeOnClickModal: false, distinguishCancelAndClose: true,
    });
    return !relevant.some(editor => editor.saving);
  } catch (_) { return false; }
}

export default {
  data: () => ({ savedAuthoringState: null, authoringIdentity: null }),
  mounted() {
    editors.add(this);
    this.authoringIdentity = this.$store.getters.userInfo.uid;
    window.addEventListener('beforeunload', this.protectAuthoringUnload);
  },
  beforeDestroy() {
    editors.delete(this);
    window.removeEventListener('beforeunload', this.protectAuthoringUnload);
  },
  methods: {
    markAuthoringClean() {
      this.authoringIdentity = this.$store.getters.userInfo.uid;
      this.savedAuthoringState = JSON.stringify(this.authoringState);
    },
    hasUnsavedAuthoring() {
      return !!this.authoringState && this.savedAuthoringState !== JSON.stringify(this.authoringState);
    },
    protectAuthoringUnload(event) {
      if (this.authoringIdentity === this.$store.getters.userInfo.uid && this.authoringState && (this.saving || this.hasUnsavedAuthoring())) {
        event.preventDefault(); event.returnValue = '';
      }
    },
  },
};
