<template>
  <section class="objective-run" v-loading="loading">
    <header class="run-heading">
      <div class="run-breadcrumb"><el-button class="run-back" icon="el-icon-back" :disabled="saving || navigating" @click="$router.push(backLocation)">返回{{ backLabel }}</el-button><span>客观题练习</span></div>
      <el-button class="run-refresh" icon="el-icon-refresh" :disabled="saving || navigating" @click="refresh">刷新结果</el-button>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="run">
      <div class="run-title"><div><p>{{ run.questionId ? '单题练习' : modes[run.mode] }}</p><h1>{{ run.title }}</h1></div>
      <div class="run-summary">
        <el-tag :type="finished ? 'success' : 'info'">{{ finished ? run.mode === 'PAPER' ? '已交卷' : '已完成' : '进行中' }}</el-tag><span>已作答 {{ run.answeredCount }} / {{ run.questions.length }}</span>
        <strong v-if="run.score !== undefined">得分 {{ run.score }} / {{ run.maxScore }}</strong>
        <span v-else-if="finished">判分反馈尚未开放</span>
      </div>
      </div>
      <p v-if="neighborsError" class="navigation-error" role="alert">{{ neighborsError }} <el-button type="text" @click="loadNeighbors">重试加载相邻题目</el-button></p>
      <div v-if="!run.questionId" class="run-filters">
        <el-select :value="type" :disabled="saving || navigating" clearable placeholder="全部题型" @change="changeType">
          <el-option v-for="(label, value) in questionTypes" :key="value" :label="label" :value="value" />
        </el-select>
        <el-checkbox v-if="finished" v-model="onlyWrong" @change="selectFirst">只看已开放的错题</el-checkbox>
      </div>
      <nav v-if="!run.questionId" class="question-nav" aria-label="题目导航">
        <el-button v-for="question in visibleQuestions" :key="question.id" :type="selectedId === question.id ? 'primary' : 'default'" :plain="selectedId !== question.id" :disabled="saving || navigating" :aria-label="question.title" :title="question.title" @click="selectQuestion(question.id)">
          {{ run.questions.findIndex(q => q.id === question.id) + 1 }} <i v-if="question.submitted" class="el-icon-check" />
        </el-button>
      </nav>
      <WorkspaceLayout v-if="selectedQuestion" editor-title="选项与作答" statement-title="题目内容" :allow-learning="false" preference-key="objective-workspace-v1">
        <template #navigation><QuestionNavigation :position="run.questionId ? '题号 ' + selectedQuestion.id : '第 ' + (run.questions.findIndex(q => q.id === selectedId) + 1) + ' / ' + run.questions.length + ' 题'" :question-title="selectedQuestion.title" :previous-disabled="saving || navigating || neighborsLoading || !previousQuestion" :next-disabled="saving || navigating || neighborsLoading || !nextQuestion" :previous-title="previousQuestion ? previousQuestion.title : '已是第一题'" :next-title="nextQuestion ? nextQuestion.title : '已是最后一题'" @previous="navigateQuestion(-1)" @next="navigateQuestion(1)" /></template>
        <template #statement>
          <article class="question-content">
            <header><el-tag size="small" effect="plain">{{ questionTypes[selectedQuestion.questionType] }}</el-tag><h2>{{ selectedQuestion.title }}</h2>
              <div class="question-origin"><span><i class="el-icon-collection-tag" /> 来源：{{ selectedQuestion.source || '本校题库' }}</span><span>Rating {{ selectedQuestion.rating == null ? '未评级' : selectedQuestion.rating }}</span><span>题号 {{ selectedQuestion.id }}</span><span>版本 {{ selectedQuestion.version }}</span></div>
              <p v-if="!run.questionId" class="question-set"><i class="el-icon-folder-opened" /> 所属{{ run.mode === 'PAPER' ? '试卷' : '题集' }}：{{ run.title }}</p>
            </header>
            <h3>题目描述</h3><p class="question-stem">{{ selectedQuestion.stem }}</p>
            <div class="practice-rules"><h3>作答说明</h3>
              <p>{{ run.questionId ? '提交即完成本次练习，需要重答时可重新练习。' : run.mode === 'PAPER' ? '可以逐题保存和修改，交卷后统一判分。填空按各空分值计分，多选须全部选对。' : '逐题提交后本次答案不能修改；需要重答时可重新练习。填空按各空分值计分，多选须全部选对。' }}</p>
              <p>答案：{{ releaseLabel(run.answerPolicy, run.answerReleaseAt) }}<br>解析：{{ releaseLabel(run.explanationPolicy, run.explanationReleaseAt) }}</p>
            </div>
          </article>
        </template>
        <template #editor><ObjectiveAnswer ref="answer" :key="run.id + ':' + selectedQuestion.id" :problem="selectedQuestion" enabled :mode="run.mode" :run-id="run.id" :finished="finished" :loading="saving || navigating" @submit="saveAnswer" /></template>
      </WorkspaceLayout>
      <p v-else class="run-notice">当前筛选下没有题目。尚未开放的判分结果不会用于错题筛选。</p>
      <div v-if="finished || !run.questionId" class="run-actions">
        <el-button v-if="!finished && !run.questionId" type="primary" :loading="saving" :disabled="saving" @click="finish">{{ run.mode === 'PAPER' ? '交卷并结束作答' : '结束本次练习' }}</el-button>
        <el-button v-if="finished" type="primary" :loading="saving" @click="restart">重新{{ run.mode === 'PAPER' ? '答卷' : '练习' }}</el-button>
      </div>
    </template>
  </section>
</template>
<script>
import api from '@/common/api';
import ObjectiveAnswer from '@/components/oj/problem/ObjectiveAnswer';
import WorkspaceLayout from '@/components/oj/problem/WorkspaceLayout';
import QuestionNavigation from '@/components/oj/problem/QuestionNavigation';
import { modes, questionTypes, releaseLabel, requestId, responseData, errorMessage } from '@/common/objective';
export default {
  name: 'ObjectiveRun',
  components: { ObjectiveAnswer, WorkspaceLayout, QuestionNavigation },
  data: () => ({ run: null, selectedId: null, type: '', onlyWrong: false, loading: false, saving: false, error: '', request: 0, restartId: '',
    neighbors: { previous: null, next: null }, neighborsLoading: false, neighborsError: '', navigating: false, approvedRunId: '', navigationIds: {}, visitedRuns: {}, modes, questionTypes }),
  computed: {
    uid() { return this.$store.getters.userInfo.uid; },
    finished() { return this.run && this.run.state === 'FINISHED'; },
    visibleQuestions() { return this.run ? this.run.questions.filter(q => (!this.type || q.questionType === this.type) && (!this.onlyWrong || q.answerVisible && q.correct === false)) : []; },
    selectedQuestion() { return this.visibleQuestions.find(q => q.id === this.selectedId) || null; },
    previousQuestion() { return this.run && this.run.questionId ? this.neighbors.previous : this.visibleQuestions[this.visibleQuestions.findIndex(q => q.id === this.selectedId) - 1]; },
    nextQuestion() { const index = this.visibleQuestions.findIndex(q => q.id === this.selectedId); return this.run && this.run.questionId ? this.neighbors.next : index < 0 ? null : this.visibleQuestions[index + 1]; },
    backLabel() { return this.$route.query.listTab === 'history' ? '作答记录' : this.$route.query.listTab === 'sets' || this.run && !this.run.questionId ? '题集与试卷' : '客观题库'; },
    backLocation() { return { path: '/objective', query: { ...this.$route.query, listTab: this.$route.query.listTab || (this.run && !this.run.questionId ? 'sets' : 'questions') } }; },
  },
  watch: {
    '$route.params.runID'() { this.run = null; this.selectedId = null; this.type = ''; this.onlyWrong = false; this.restartId = ''; this.navigating = false; this.approvedRunId = ''; this.load(); },
    uid() { this.request++; this.run = null; this.selectedId = null; this.restartId = ''; this.saving = false; this.navigating = false; this.approvedRunId = ''; this.visitedRuns = {}; this.navigationIds = {}; this.load(); },
  },
  created() { this.load(); },
  mounted() { window.addEventListener('beforeunload', this.beforeUnload); },
  beforeDestroy() { this.request++; window.removeEventListener('beforeunload', this.beforeUnload); },
  beforeRouteLeave(to, from, next) { this.confirmLeave(next, to); },
  beforeRouteUpdate(to, from, next) { this.confirmLeave(next, to); },
  methods: {
    releaseLabel,
    hasUnsaved() { return !!(this.run && this.selectedQuestion && this.$refs.answer && this.$refs.answer.dirty && !this.finished && !(this.run.mode === 'PRACTICE' && this.selectedQuestion.submitted)); },
    context() { const request = this.request, uid = this.uid, id = this.run && this.run.id, selectedId = this.selectedId; return () => request === this.request && uid === this.uid && id === (this.run && this.run.id) && selectedId === this.selectedId; },
    confirmLeave(next, to) {
      if (to.params.runID && String(to.params.runID) === this.approvedRunId) return next();
      if (this.saving || this.navigating) { this.$message.warning('正在处理，请稍候'); return next(false); }
      if (!this.hasUnsaved()) return next();
      const current = this.context();
      this.$confirm('当前答案尚未保存，离开将丢失这次修改。', '离开作答', { type: 'warning' }).then(() => next(current() ? undefined : false)).catch(() => next(false));
    },
    beforeUnload(event) { if (this.hasUnsaved() || this.saving || this.navigating) { event.preventDefault(); event.returnValue = ''; } },
    accept(run) {
      this.run = run;
      if (!this.visibleQuestions.some(q => q.id === this.selectedId)) this.selectFirst();
    },
    async load() {
      const request = ++this.request;
      this.loading = true; this.error = '';
      this.neighbors = { previous: null, next: null }; this.neighborsError = ''; this.neighborsLoading = false;
      try {
        const run = responseData(await api.objectiveRun(this.$route.params.runID));
        if (request === this.request) { this.accept(run); if (run.questionId) { this.$set(this.visitedRuns, run.questionId, run.id); this.loadNeighbors(); } }
      } catch (error) { if (request === this.request) this.error = errorMessage(error); }
      finally { if (request === this.request) this.loading = false; }
    },
    async refresh() {
      if (this.saving || this.navigating) return;
      const current = this.context();
      if (this.hasUnsaved()) {
        try { await this.$confirm('刷新将丢失当前未保存的修改。', '刷新结果', { type: 'warning' }); } catch (_) { return; }
      }
      if (current()) this.load();
    },
    selectFirst() { this.selectedId = this.visibleQuestions.length ? this.visibleQuestions[0].id : null; },
    async changeType(type) {
      if (this.saving || this.navigating) return;
      const current = this.context();
      if (this.hasUnsaved()) {
        try { await this.$confirm('筛选将丢失当前未保存的修改。', '筛选题目', { type: 'warning' }); } catch (_) { return; }
      }
      if (current()) { this.type = type; this.selectFirst(); }
    },
    async selectQuestion(id) {
      if (this.saving || this.navigating || !this.visibleQuestions.some(q => q.id === id)) return;
      const current = this.context();
      if (this.hasUnsaved() && id !== this.selectedId) {
        try { await this.$confirm('当前答案尚未保存，切换将丢失修改。', '切换题目', { type: 'warning' }); } catch (_) { return; }
      }
      if (current()) this.selectedId = id;
    },
    async loadNeighbors() {
      if (!this.run || !this.run.questionId) return;
      const current = this.context();
      const query = this.$route.query;
      this.neighborsLoading = true; this.neighborsError = '';
      try {
        const params = query.listTab === 'questions' ? { keyword: query.keyword || '', questionType: query.questionType || '' } : {};
        const neighbors = responseData(await api.objectiveNeighbors(this.run.questionId, params));
        if (current()) this.neighbors = neighbors;
      } catch (error) { if (current()) this.neighborsError = errorMessage(error); }
      finally { if (current()) this.neighborsLoading = false; }
    },
    async navigateQuestion(direction) {
      if (this.saving || this.navigating || this.neighborsLoading || !this.run) return;
      const target = direction < 0 ? this.previousQuestion : this.nextQuestion;
      if (!target) return;
      if (!this.run.questionId) return this.selectQuestion(target.id);
      const current = this.context();
      this.navigating = true;
      try {
        if (this.hasUnsaved()) {
          try { await this.$confirm('当前答案尚未提交，切换将丢失这次选择。', '切换题目', { type: 'warning', confirmButtonText: '切换题目', cancelButtonText: '继续作答' }); } catch (_) { return; }
        }
        if (!current()) return;
        let id = this.visitedRuns[target.id];
        if (!id) {
          if (!this.navigationIds[target.id]) this.$set(this.navigationIds, target.id, requestId());
          const run = responseData(await api.objectiveStart({ questionId: target.id, requestId: this.navigationIds[target.id] }));
          if (!current()) return;
          id = run.id; this.$set(this.visitedRuns, target.id, id); this.$delete(this.navigationIds, target.id);
        }
        this.approvedRunId = String(id);
        await this.$router.push({ path: '/objective/run/' + id, query: this.$route.query });
      } catch (error) { if (current()) this.error = errorMessage(error); }
      finally { if (current()) { this.navigating = false; this.approvedRunId = ''; } }
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
        const source = this.run.questionId ? { questionId: this.run.questionId } : { setId: this.run.setId };
        const run = responseData(await api.objectiveStart({ ...source, requestId: this.restartId }));
        if (request !== this.request) return;
        this.restartId = ''; this.saving = false;
        this.$router.push({ path: '/objective/run/' + run.id, query: this.$route.query });
      } catch (error) { if (request === this.request) this.error = errorMessage(error); }
      finally { if (request === this.request) this.saving = false; }
    },
  },
};
</script>
<style scoped>
.objective-run { max-width: 1440px; margin: 24px auto; padding-bottom: calc(104px + env(safe-area-inset-bottom, 0px)); color: var(--text); }
.run-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.run-breadcrumb { display: flex; align-items: center; gap: 20px; color: var(--muted); font-size: 14px; }
.run-back, .run-refresh { border-radius: 9px; min-height: 42px; }
.run-back { color: var(--accent); border-color: var(--line); }
.run-title { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin: 28px 0 24px; }
.run-title h1 { margin: 6px 0 0; font-size: 28px; line-height: 1.4; overflow-wrap: anywhere; }
.run-title p { margin: 0; font-size: 13px; color: var(--muted); }
.run-summary, .run-filters { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.run-summary { color: var(--muted); font-size: 13px; }
.run-summary strong { color: var(--accent); }
.run-filters { margin: 16px 0; }
.navigation-error { color: var(--muted); font-size: 13px; }
.run-notice { color: var(--muted); line-height: 1.7; }
.question-nav { display: flex; flex-wrap: wrap; gap: 8px; padding-bottom: 18px; }
.question-nav .el-button { margin: 0; min-width: 44px; border-radius: 8px; }
.question-content { padding: 24px; min-width: 0; }
.question-content h2 { font-size: 23px; line-height: 1.6; margin: 14px 0 8px; overflow-wrap: anywhere; }
.question-origin { display: flex; flex-wrap: wrap; gap: 8px 16px; color: var(--muted); font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
.question-set { color: var(--accent); font-size: 12px; line-height: 1.7; }
.question-content h3 { font-size: 15px; margin: 28px 0 12px; }
.question-stem { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.9; margin: 0; }
.practice-rules { margin-top: 32px; padding-top: 4px; border-top: 1px solid var(--line); }
.practice-rules p { font-size: 13px; color: var(--muted); line-height: 1.8; }
.run-actions { display: flex; justify-content: flex-end; padding-top: 24px; }
@media (max-width: 767px) {
  .objective-run { margin: 12px 0; }
  .run-breadcrumb > span { display: none; }
  .run-title { align-items: flex-start; flex-direction: column; margin: 22px 0; gap: 14px; }
  .run-title h1 { font-size: 24px; }
  .question-content { padding: 20px 16px; }
}
</style>
