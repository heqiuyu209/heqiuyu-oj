<template><el-button class="ai-problem-help" size="mini" icon="el-icon-chat-dot-round" @click="open">问助教</el-button></template>
<script>
import { publishAiProblem } from '@/common/aiProblemContext';
export default {
  name: 'AiProblemHelp',
  props: { reference: { type: Object, required: true }, title: { type: String, default: '' } },
  computed: {
    descriptor() { return { reference: this.reference, title: this.title, route: this.$route.fullPath, uid: this.$store.getters.userInfo.uid || '', authEpoch: this.$store.getters.authEpoch }; },
  },
  watch: { descriptor: { immediate: true, deep: true, handler(value) { publishAiProblem(this._uid, value); } } },
  activated() { publishAiProblem(this._uid, this.descriptor); },
  deactivated() { publishAiProblem(this._uid, null); },
  beforeDestroy() { publishAiProblem(this._uid, null); },
  methods: { open() { publishAiProblem(this._uid, this.descriptor); window.dispatchEvent(new CustomEvent('hoj-ai-open-problem')); } },
};
</script>
<style scoped>.ai-problem-help { margin: 10px 0; color: var(--accent); }</style>
