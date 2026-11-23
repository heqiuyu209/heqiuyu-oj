<template><section class="question-create"><h1>新建题目</h1><p>按题型录入，保存到统一题库，再安排练习、组卷或比赛。</p><el-button type="primary" @click="$refs.creator.open()">选择题型</el-button><el-button @click="$router.push('/admin/problems')">返回题库</el-button><question-creator ref="creator" @saved="saved" /></section></template>
<script>
import QuestionCreator from '@/components/admin/QuestionCreator';
export default { name: 'QuestionCreate', components: { QuestionCreator }, mounted() { this.$refs.creator.open(); }, methods: { saved({ kind }) { this.$router.push({ path: '/admin/problems', query: { type: kind === 'PROGRAMMING' ? 'programming' : 'objective' } }); } } };
</script>
<style scoped>.question-create { padding: 24px; color: var(--text); }.question-create h1 { font-size: 26px; }.question-create p { color: var(--muted); margin: 16px 0 24px; }</style>
