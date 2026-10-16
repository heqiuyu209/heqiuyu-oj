<template>
  <section class="question-bank">
    <header><div><h1>题库</h1><p>先录入题目，再选择开放单题练习，或用于题集、试卷和比赛。</p></div><el-button type="primary" icon="el-icon-plus" @click="$refs.creator.open()">新建题目</el-button></header>
    <el-tabs v-model="tab" @tab-click="changeTab"><el-tab-pane label="编程题" name="programming" /><el-tab-pane label="客观题 · 填空题" name="objective" /></el-tabs>
    <problem-list v-if="tab === 'programming'" :key="revision" bank-mode />
    <objective-admin v-else :key="revision" questions-only compact />
    <question-creator ref="creator" @saved="saved" />
  </section>
</template>
<script>
import ProblemList from './ProblemList';
import ObjectiveAdmin from './ObjectiveAdmin';
import QuestionCreator from '@/components/admin/QuestionCreator';
export default {
  name: 'QuestionBank', components: { ProblemList, ObjectiveAdmin, QuestionCreator },
  data() { return { tab: this.$route.query.type === 'objective' ? 'objective' : 'programming', revision: 0 }; },
  watch: { '$route.query.type'(value) { this.tab = value === 'objective' ? 'objective' : 'programming'; } },
  methods: {
    changeTab() { this.$router.replace({ path: '/admin/problems', query: { type: this.tab } }).catch(() => {}); },
    saved({ kind }) { this.tab = kind === 'PROGRAMMING' ? 'programming' : 'objective'; this.revision++; this.changeTab(); },
  },
};
</script>
<style scoped>
.question-bank { padding: 24px; color: var(--text); }.question-bank > header { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 24px; }.question-bank h1 { font-size: 26px; margin: 0 0 10px; }.question-bank p { color: var(--muted); margin: 0; line-height: 1.8; }
@media(max-width:600px) { .question-bank { padding: 12px; }.question-bank > header { align-items: flex-start; flex-direction: column; } }
</style>
