<template>
  <section class="objective-run" v-loading="loading">
    <header class="run-heading">
      <div><router-link to="/objective">返回客观题练习</router-link><h1>{{ run ? run.title : '客观题作答' }}</h1></div>
      <el-button :disabled="saving" @click="refresh">刷新结果</el-button>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="run">
      <div class="run-summary">
        <el-tag>{{ modes[run.mode] }}</el-tag><span>{{ finished ? '已交卷' : '进行中' }} · 已作答 {{ run.answeredCount }} / {{ run.questions.length }}</span>
        <strong v-if="run.score !== undefined">得分 {{ run.score }} / {{ run.maxScore }}</strong>
        <span v-else-if="finished">判分反馈尚未开放</span>
      </div>
      <p class="run-notice">{{ run.mode === 'PAPER' ? '可以逐题保存和修改，交卷后统一判分。每题 1 分，多选须全部选对。' : '逐题提交后本次答案不能修改；需要重答时可重新练习。每题 1 分，多选须全部选对。' }}</p>
      <p class="run-notice">答案：{{ releaseLabel(run.answerPolicy, run.answerReleaseAt) }}；解析：{{ releaseLabel(run.explanationPolicy, run.explanationReleaseAt) }}。</p>
      <div class="run-filters">
        <el-select :value="type" clearable placeholder="全部题型" @change="changeType">
          <el-option v-for="(label, value) in questionTypes" :key="value" :label="label" :value="value" />
        </el-select>
        <el-checkbox v-if="finished" v-model="onlyWrong" @change="selectFirst">只看已开放的错题</el-checkbox>
      </div>
      <div class="run-workspace">
        <nav class="question-nav" aria-label="题目导航">
          <el-button v-for="(question, index) in visibleQuestions" :key="question.id" :type="selectedId === question.id ? 'primary' : 'default'" :plain="selectedId !== question.id" :disabled="saving" @click="selectQuestion(question.id)">
            {{ index + 1 }}. {{ question.title }} <span v-if="question.submitted">✓</span>
          </el-button>
          <p v-if="!visibleQuestions.length" class="run-notice">当前筛选下没有题目。尚未开放的判分结果不会用于错题筛选。</p>
        </nav>
        <article v-if="selectedQuestion" class="question-content">
          <header><h2>{{ selectedQuestion.title }}</h2><small class="run-notice">题目版本 {{ selectedQuestion.version }}</small></header>
          <p class="question-stem">{{ selectedQuestion.stem }}</p>
          <ObjectiveAnswer ref="answer" :key="selectedQuestion.id" :problem="selectedQuestion" enabled :mode="run.mode" :run-id="run.id" :finished="finished" :loading="saving" @submit="saveAnswer" />
        </article>
      </div>
      <footer class="run-actions">
        <el-button v-if="!finished" type="primary" :loading="saving" :disabled="saving" @click="finish">{{ run.mode === 'PAPER' ? '交卷并结束作答' : '结束本次练习' }}</el-button>
        <el-button v-if="finished" type="primary" :loading="saving" @click="restart">重新{{ run.mode === 'PAPER' ? '答卷' : '练习' }}</el-button>
      </footer>
    </template>
  </section>
</template>
<script>
import api from '@/common/api';
import ObjectiveAnswer from '@/components/oj/problem/ObjectiveAnswer';
import { modes, questionTypes, releaseLabel, requestId, responseData, errorMessage } from '@/common/objective';
export default {
  name: 'ObjectiveRun',
  components: { ObjectiveAnswer },
  data: () => ({ run: null, selectedId: null, type: '', onlyWrong: false, loading: false, saving: false, error: '', request: 0, restartId: '', modes, questionTypes }),
  computed: {
    uid() { return this.$store.getters.userInfo.uid; },
    finished() { return this.run && this.run.state === 'FINISHED'; },
    visibleQuestions() { return this.run ? this.run.questions.filter(q => (!this.type || q.questionType === this.type) && (!this.onlyWrong || q.answerVisible && q.correct === false)) : []; },
    selectedQuestion() { return this.visibleQuestions.find(q => q.id === this.selectedId) || null; },
  },
  watch: {
    '$route.params.runID'() { this.run = null; this.selectedId = null; this.type = ''; this.onlyWrong = false; this.load(); },
    uid() { this.request++; this.run = null; this.selectedId = null; this.restartId = ''; this.saving = false; this.load(); },
  },
  created() { this.load(); },
  mounted() { window.addEventListener('beforeunload', this.beforeUnload); },
  beforeDestroy() { this.request++; window.removeEventListener('beforeunload', this.beforeUnload); },
  beforeRouteLeave(to, from, next) {
    this.confirmLeave(next);
  },
  beforeRouteUpdate(to, from, next) { this.confirmLeave(next); },
  methods: {
    releaseLabel,
    hasUnsaved() { return !!(this.$refs.answer && this.$refs.answer.dirty && !this.finished && !this.$refs.answer.locked); },
    confirmLeave(next) {
      if (this.saving) { this.$message.warning('正在保存，请稍候'); return next(false); }
      if (!this.hasUnsaved()) return next();
      this.$confirm('当前答案尚未保存，离开将丢失这次修改。', '离开作答', { type: 'warning' }).then(() => next()).catch(() => next(false));
    },
    beforeUnload(event) { if (this.hasUnsaved() || this.saving) { event.preventDefault(); event.returnValue = ''; } },
    accept(run) {
      this.run = run;
      if (!this.visibleQuestions.some(q => q.id === this.selectedId)) this.selectFirst();
    },
    async load() {
      const request = ++this.request;
      this.loading = true; this.error = '';
      try {
        const run = responseData(await api.objectiveRun(this.$route.params.runID));
        if (request === this.request) this.accept(run);
      } catch (error) { if (request === this.request) this.error = errorMessage(error); }
      finally { if (request === this.request) this.loading = false; }
    },
    async refresh() {
      if (this.saving) return;
      if (this.hasUnsaved()) {
        try { await this.$confirm('刷新将丢失当前未保存的修改。', '刷新结果', { type: 'warning' }); } catch (_) { return; }
      }
      this.load();
    },
    selectFirst() { this.selectedId = this.visibleQuestions.length ? this.visibleQuestions[0].id : null; },
    async changeType(type) {
      if (this.hasUnsaved()) {
        try { await this.$confirm('筛选将丢失当前未保存的修改。', '筛选题目', { type: 'warning' }); } catch (_) { return; }
      }
      this.type = type; this.selectFirst();
    },
    async selectQuestion(id) {
      if (this.hasUnsaved() && id !== this.selectedId) {
        try { await this.$confirm('当前答案尚未保存，切换将丢失修改。', '切换题目', { type: 'warning' }); } catch (_) { return; }
      }
      this.selectedId = id;
    },
    async saveAnswer(selected) {
      if (this.saving || !this.selectedQuestion) return false;
      this.saving = true; this.error = '';
      const request = this.request;
      try {
        const run = responseData(await api.objectiveAnswer(this.run.id, { questionId: this.selectedQuestion.id, selectedAnswers: selected }));
        if (request !== this.request) return false;
        this.accept(run); this.$message.success(this.run.mode === 'PAPER' ? '答案已保存' : '答案已提交');
        return true;
      } catch (error) { if (request === this.request) this.error = errorMessage(error); return false; }
      finally { if (request === this.request) this.saving = false; }
    },
    async finish() {
      if (this.saving || !this.run || this.finished) return;
      const runId = this.run.id;
      const request = this.request;
      const uid = this.uid;
      const isCurrent = () => request === this.request && uid === this.uid
        && this.run && this.run.id === runId && String(this.$route.params.runID) === String(runId);
      if (this.hasUnsaved()) {
        if (this.run.mode === 'PAPER') { if (!await this.saveAnswer(this.$refs.answer.selected)) return; }
        else { this.$message.warning('请先提交当前答案，再结束练习'); return; }
      }
      if (!isCurrent()) return;
      const missing = this.run.questions.length - this.run.answeredCount;
      try { await this.$confirm(missing ? '还有 ' + missing + ' 题未作答，结束后这些题按未作答处理。' : '结束后不能再修改本次作答。', this.run.mode === 'PAPER' ? '确认交卷' : '结束练习', { type: 'warning' }); } catch (_) { return; }
      if (!isCurrent()) return;
      this.saving = true; this.error = '';
      try {
        const run = responseData(await api.objectiveFinish(runId));
        if (isCurrent()) this.accept(run);
      } catch (error) { if (isCurrent()) this.error = errorMessage(error); }
      finally { if (isCurrent()) this.saving = false; }
    },
    async restart() {
      if (this.saving) return;
      if (!this.restartId) this.restartId = requestId();
      this.saving = true; this.error = '';
      const request = this.request;
      try {
        const run = responseData(await api.objectiveStart({ setId: this.run.setId, requestId: this.restartId }));
        if (request !== this.request) return;
        this.restartId = ''; this.saving = false;
        this.$router.push('/objective/run/' + run.id);
      } catch (error) { if (request === this.request) this.error = errorMessage(error); }
      finally { if (request === this.request) this.saving = false; }
    },
  },
};
</script>
<style scoped>
.objective-run { max-width: 1320px; margin: 24px auto; padding: 24px; color: var(--text); background: var(--surface, #fff); border: 1px solid var(--line); border-radius: 8px; }
.run-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.run-heading h1 { margin: 16px 0; font-size: 26px; }
.run-summary, .run-filters { display: flex; align-items: center; flex-wrap: wrap; gap: 16px; margin: 16px 0; }
.run-notice { color: var(--muted); line-height: 1.7; }
.run-workspace { display: grid; grid-template-columns: minmax(200px, 260px) minmax(0, 1fr); gap: 24px; margin-top: 24px; }
.question-nav { display: flex; flex-direction: column; gap: 10px; }
.question-nav .el-button { margin: 0; white-space: normal; text-align: left; line-height: 1.6; }
.question-content { border: 1px solid var(--line); border-radius: 6px; min-width: 0; }
.question-content > header { padding: 20px 24px 0; }
.question-content h2 { font-size: 22px; line-height: 1.6; margin: 0 0 8px; }
.question-stem { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.9; padding: 0 24px; }
.run-actions { display: flex; justify-content: flex-end; padding-top: 24px; }
@media (max-width: 700px) { .objective-run { margin: 12px 0; padding: 16px; } .run-workspace { grid-template-columns: 1fr; } .question-nav { flex-direction: row; overflow-x: auto; padding-bottom: 8px; } .question-nav .el-button { min-width: 140px; } }
</style>
