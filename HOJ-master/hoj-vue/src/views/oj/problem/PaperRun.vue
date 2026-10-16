<template>
  <section class="paper-run" v-loading="loading">
    <header class="run-toolbar"><el-button icon="el-icon-back" :disabled="saving" @click="goBack">{{ run && run.contestId ? '返回比赛' : '返回套卷' }}</el-button><el-button icon="el-icon-refresh" :disabled="saving" @click="refresh">刷新结果</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="run">
      <el-alert v-if="run.gradingFailed" title="部分代码因判题服务异常未完成评测，成绩尚未结算。请重试判题。" type="warning" :closable="false"><el-button type="text" :disabled="saving" @click="retry">重试失败的评测</el-button></el-alert>
      <el-alert v-if="run.contestId" :title="deadlineText" type="info" :closable="false" />
      <div class="run-title"><div><p>{{ run.contestId ? '混合比赛' : '整套试卷' }} · {{ run.programMode === 'LIVE' ? '编程即时判题' : '交卷后统一判题' }}</p><h1>{{ run.title }}</h1></div><div class="run-score"><el-tag :type="finished ? 'success' : 'info'">{{ finished ? '已交卷' : '作答中' }}</el-tag><span>已作答 {{ run.answeredCount }} / {{ run.items.length }}</span><strong v-if="run.score !== undefined">{{ run.score }} / {{ run.maxScore }} 分</strong><span v-else-if="finished">{{ run.gradingComplete ? '成绩尚未开放' : '正在判题' }}</span></div></div>
      <nav class="question-numbers" aria-label="试卷题目"><el-button v-for="(item, index) in run.items" :key="item.key" :type="item.key === selectedKey ? 'primary' : 'default'" :disabled="saving" :title="item.title" @click="select(item.key)">{{ index + 1 }} <i v-if="answered(item)" class="el-icon-check" /></el-button></nav>
      <WorkspaceLayout v-if="question" :key="run.id" statement-title="题目内容" :editor-title="question.kind === 'PROGRAMMING' ? '代码与判题' : '选项与作答'" :allow-learning="false" preference-key="paper-workspace-v1">
        <template #navigation><QuestionNavigation :position="'第 ' + (index + 1) + ' / ' + run.items.length + ' 题'" :question-title="question.title" :previous-disabled="saving || index <= 0" :next-disabled="saving || index >= run.items.length - 1" :previous-title="index > 0 ? run.items[index - 1].title : '已是第一题'" :next-title="index < run.items.length - 1 ? run.items[index + 1].title : '已是最后一题'" @previous="select(run.items[index - 1].key)" @next="select(run.items[index + 1].key)" /></template>
        <template #statement><article class="paper-statement"><el-tag effect="plain" size="small">{{ question.kind === 'PROGRAMMING' ? '编程题' : questionTypes[question.questionType] }}</el-tag><h2>{{ question.title }}</h2><div class="origin"><span><i class="el-icon-collection-tag" /> 来源：{{ question.source || '本校题库' }}</span><span>{{ question.maxScore }} 分</span><span>Rating {{ question.rating == null ? '未评级' : question.rating }}</span><span>题号 {{ question.problemId || question.referenceId }}</span></div>
          <template v-if="question.kind === 'PROGRAMMING'"><p class="limits">时间 {{ question.timeLimit }} ms · 内存 {{ question.memoryLimit }} MB</p><h3>题目描述</h3><div class="statement-html" v-dompurify-html="safe(question.stem)" /><h3>输入</h3><div v-dompurify-html="safe(question.input)" /><h3>输出</h3><div v-dompurify-html="safe(question.output)" /><div v-for="(example, exampleIndex) in examples" :key="exampleIndex" class="examples"><h3>样例 {{ exampleIndex + 1 }}</h3><p>输入</p><pre>{{ example.input }}</pre><p>输出</p><pre>{{ example.output }}</pre></div></template>
          <template v-else><h3>题目描述</h3><p class="stem">{{ question.stem }}</p></template>
          <div class="rules"><h3>作答规则</h3><p>{{ run.programMode === 'LIVE' ? '编程题可多次提交判题；仅保存草稿不参与计分。' : '编程题先保存代码，交卷后统一评测。' }}{{ run.scoreRule === 'BEST' ? ' 编程题取最高分。' : ' 编程题取最后一次提交的分数。' }} 客观题交卷后统一判分。</p><p>{{ run.contestId ? '标准答案' : '答案与成绩' }}：{{ releaseLabel(run.answerPolicy, run.answerReleaseAt) }}<br>解析：{{ releaseLabel(run.explanationPolicy, run.explanationReleaseAt) }}</p><p>本次作答使用开卷时的题目版本；{{ run.contestId ? '比赛与练习记录独立。' : '套卷与单题记录独立。' }}</p></div>
        </article></template>
        <template #editor><PaperProgramAnswer v-if="question.kind === 'PROGRAMMING'" ref="answer" :key="run.id + ':' + question.key" :problem="question" :live="run.programMode === 'LIVE'" :finished="finished" :loading="saving" @save="saveProgram" @submit="submitProgram" /><ObjectiveAnswer v-else ref="answer" :key="run.id + ':' + question.key" :problem="question" enabled mode="PAPER" :run-id="'paper:' + run.id" :finished="finished" :loading="saving" @submit="saveObjective" /></template>
      </WorkspaceLayout>
      <div class="finish-actions"><p>{{ finished ? '本次答卷已结束，可从入口再次查看。' : '可以逐题保存和修改；交卷后不能修改答案。' }}</p><el-button v-if="!finished" type="primary" :loading="saving" @click="finish">交卷并结束作答 <i class="el-icon-check" /></el-button></div>
    </template>
  </section>
</template>
<script>
import api from '@/common/api';
import utils from '@/common/utils';
import ObjectiveAnswer from '@/components/oj/problem/ObjectiveAnswer';
import PaperProgramAnswer from '@/components/oj/problem/PaperProgramAnswer';
import WorkspaceLayout from '@/components/oj/problem/WorkspaceLayout';
import QuestionNavigation from '@/components/oj/problem/QuestionNavigation';
import { questionTypes, releaseLabel, requestId, responseData, errorMessage } from '@/common/objective';
export default {
  name: 'PaperRun', components: { ObjectiveAnswer, PaperProgramAnswer, WorkspaceLayout, QuestionNavigation },
  data: () => ({ run: null, selectedKey: '', loading: false, saving: false, error: '', request: 0, revision: 0, timer: null, clockTimer: null, clock: Date.now(), offset: 0, nonces: {}, questionTypes }),
  computed: { examples() { return this.question ? utils.stringToExamples(this.question.examples || '') : []; }, uid() { return this.$store.getters.userInfo.uid; }, finished() { return this.run && (this.run.state === 'FINISHED' || this.run.contestId && this.clock + this.offset >= this.run.endTime); }, deadlineText() { if (!this.run || !this.run.contestId) return ''; const seconds = Math.max(0, Math.ceil((this.run.endTime - this.clock - this.offset) / 1000)); return seconds ? '距比赛截止 ' + Math.floor(seconds / 3600) + ' 小时 ' + Math.floor(seconds % 3600 / 60) + ' 分 ' + seconds % 60 + ' 秒 · 请及时保存答案、提交代码判题；截止后自动交卷。' : '比赛已截止，已保存的答案已锁定，未提交判题的代码草稿不计分。'; }, question() { return this.run && this.run.items.find(item => item.key === this.selectedKey); }, index() { return this.run ? this.run.items.findIndex(item => item.key === this.selectedKey) : -1; } },
  watch: { '$route.params.runID'() { this.reset(); }, uid() { this.reset(); } },
  created() { this.load(); }, mounted() { this.clockTimer = setInterval(() => { this.clock = Date.now(); }, 1000); window.addEventListener('beforeunload', this.beforeUnload); },
  beforeDestroy() { clearInterval(this.clockTimer); this.request++; clearTimeout(this.timer); window.removeEventListener('beforeunload', this.beforeUnload); },
  beforeRouteLeave(to, from, next) { this.leave(next); }, beforeRouteUpdate(to, from, next) { this.leave(next); },
  methods: {
    releaseLabel, goBack() { this.$router.push(this.run && this.run.contestId ? '/contest/' + this.run.contestId + '/problems' : { path: '/papers', query: this.$route.query }); }, safe(value) { return this.$markDown.render(value || ''); },
    answered(item) { return (item.selectedAnswers || []).some(value => value.trim()) || !!(item.code && item.code.trim()) || item.submissionCount > 0; },
    dirty() { return !this.finished && this.$refs.answer && this.$refs.answer.dirty; },
    context() { const request = this.request, uid = this.uid, id = this.run && this.run.id, key = this.selectedKey; return () => request === this.request && uid === this.uid && id === (this.run && this.run.id) && key === this.selectedKey; },
    reset() { this.request++; clearTimeout(this.timer); this.run = null; this.selectedKey = ''; this.saving = false; this.nonces = {}; this.load(); },
    beforeUnload(event) { if (this.dirty() || this.saving) { event.preventDefault(); event.returnValue = ''; } },
    async leave(next) { if (this.saving) return next(false); const current = this.context(); if (!this.dirty()) return next(); try { await this.$confirm('当前修改尚未保存，离开将丢失这次修改。', '离开答卷', { type: 'warning' }); next(current() ? undefined : false); } catch (_) { next(false); } },
    accept(run, polling = false) {
      // Polling only updates feedback, preserving the saved-answer references watched by the editors.
      if (polling && this.run && run.state === this.run.state) run.items.forEach(item => { const old = this.run.items.find(q => q.key === item.key); if (old) { item.selectedAnswers = old.selectedAnswers; item.code = old.code; item.language = old.language; } });
      if (run.serverNow) this.offset = run.serverNow - Date.now(); this.clock = Date.now(); this.run = run; if (!run.items.some(item => item.key === this.selectedKey)) this.selectedKey = run.items[0].key;
      this.schedule();
    },
    schedule() { clearTimeout(this.timer); const run = this.run; if (run && (run.contestId && (run.state !== 'FINISHED' || ['answer', 'explanation'].some(field => run[field + 'Policy'] !== 'NEVER' && this.clock + this.offset < (run[field + 'Policy'] === 'AT_TIME' ? run[field + 'ReleaseAt'] : run.endTime))) || run.items.some(item => item.grading))) this.timer = setTimeout(this.poll, 2000); },
    async poll() { if (this.saving || this.loading) return this.schedule(); const token = this.request, revision = this.revision, id = this.run.id; try { const run = responseData(await api.paperRun(id)); if (token === this.request && revision === this.revision && id === this.run.id) this.accept(run, true); } catch (_) { if (token === this.request) { this.error = '判题结果暂未刷新，可点击刷新结果重试。'; this.timer = setTimeout(this.poll, 5000); } } },
    async load() { const token = ++this.request; this.loading = true; this.error = ''; try { const run = responseData(await api.paperRun(this.$route.params.runID)); if (token === this.request) { if (run.contestId && !this.$route.params.contestID) { this.$router.replace('/contest/' + run.contestId + '/mixed/run/' + run.id); return; } if (this.$route.params.contestID && String(run.contestId) !== this.$route.params.contestID) throw new Error('答卷与比赛不匹配'); this.accept(run); } } catch (e) { if (token === this.request) this.error = errorMessage(e); } finally { if (token === this.request) this.loading = false; } },
    async refresh() { if (this.saving) return; const current = this.context(); if (this.dirty()) { try { await this.$confirm('刷新将丢失当前未保存的修改。', '刷新结果', { type: 'warning' }); } catch (_) { return; } } if (current()) this.load(); },
    async select(key) { if (this.saving || key === this.selectedKey) return; const current = this.context(); if (this.dirty()) { try { await this.$confirm('当前修改尚未保存，切换将丢失这次修改。', '切换题目', { type: 'warning' }); } catch (_) { return; } } if (current()) this.selectedKey = key; },
    saveObjective(selectedAnswers) { return this.save({ selectedAnswers }); }, saveProgram(payload) { return this.save(payload); },
    async save(payload) {
      if (this.saving || this.finished) return false; const current = this.context(), id = this.run.id, key = this.selectedKey;
      this.revision++; this.saving = true; this.error = '';
      try { const run = responseData(await api.paperAnswer(id, { key, ...payload })); if (current()) { this.accept(run); this.$message.success('已保存至本次答卷'); return true; } } catch (e) { if (current()) this.error = errorMessage(e); } finally { if (current()) this.saving = false; } return false;
    },
    async submitProgram(payload) {
      if (this.saving || this.finished) return;
      const current = this.context(), id = this.run.id, key = this.selectedKey, fingerprint = JSON.stringify([key, payload.language, payload.code]);
      if (!this.nonces[fingerprint]) this.$set(this.nonces, fingerprint, requestId());
      this.revision++; this.saving = true; this.error = '';
      try { const run = responseData(await api.paperSubmit(id, { key, ...payload, requestId: this.nonces[fingerprint] })); if (current()) { this.$delete(this.nonces, fingerprint); this.accept(run); } } catch (e) { if (current()) this.error = errorMessage(e); } finally { if (current()) this.saving = false; }
    },
    async finish() {
      if (this.saving || this.finished) return; const current = this.context(), id = this.run.id;
      const notice = this.run.programMode === 'LIVE' ? '编程题按已经提交判题的代码计分，未提交的草稿不计分。' : '已保存的编程代码将在交卷后统一评测。';
      try { await this.$confirm('确认交卷？当前修改会先保存，交卷后不能修改。' + notice, '交卷', { type: 'warning', confirmButtonText: '保存并交卷' }); } catch (_) { return; }
      if (!current()) return;
      if (this.dirty()) { const editor = this.$refs.answer; if (!await this.save(this.question.kind === 'PROGRAMMING' ? editor.payload : { selectedAnswers: editor.selected })) return; }
      if (!current()) return; this.revision++; this.saving = true; this.error = '';
      try { const run = responseData(await api.paperFinish(id)); if (current()) this.accept(run); } catch (e) { if (current()) this.error = errorMessage(e); } finally { if (current()) this.saving = false; }
    },
    async retry() { if (this.saving) return; const current = this.context(); this.revision++; this.saving = true; try { const run = responseData(await api.paperRetry(this.run.id)); if (current()) this.accept(run, true); } catch (e) { if (current()) this.error = errorMessage(e); } finally { if (current()) this.saving = false; } },
  },
};
</script>
<style scoped>
.paper-run { padding: 24px 24px calc(104px + env(safe-area-inset-bottom, 0px)); color: var(--text); }.run-toolbar, .run-title, .run-score { display: flex; align-items: center; gap: 14px; justify-content: space-between; }.run-toolbar .el-button, .finish-actions .el-button { border-radius: 9px; min-height: 44px; }.run-title { margin: 26px 0 20px; flex-wrap: wrap; }.run-title p, .run-score span { color: var(--muted); }.run-title h1 { font-size: 28px; margin: 8px 0; overflow-wrap: anywhere; }.run-score { justify-content: flex-start; flex-wrap: wrap; }.run-score strong { color: var(--accent); }.question-numbers { display: flex; flex-wrap: wrap; gap: 8px; margin: 18px 0; }.question-numbers .el-button { margin: 0; min-width: 44px; }.paper-statement { padding: 28px; overflow-wrap: anywhere; }.paper-statement h2 { font-size: 26px; line-height: 1.5; margin: 16px 0; }.paper-statement h3 { margin: 28px 0 14px; font-size: 17px; }.origin { display: flex; flex-wrap: wrap; gap: 10px 20px; font-size: 13px; color: var(--muted); }.stem { white-space: pre-wrap; line-height: 1.9; }.limits, .rules { color: var(--muted); font-size: 13px; line-height: 1.9; }.rules { margin-top: 28px; border-top: 1px solid var(--line); }.finish-actions { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin: 28px 0; }.finish-actions p { color: var(--muted); font-size: 13px; }.statement-html { line-height: 1.8; }.paper-statement /deep/ pre { white-space: pre-wrap; overflow-wrap: anywhere; }.paper-statement /deep/ img { max-width: 100%; }
@media(max-width: 650px) { .paper-run { padding: 16px 12px calc(104px + env(safe-area-inset-bottom, 0px)); }.paper-statement { padding: 20px 16px; }.run-title h1 { font-size: 23px; }.finish-actions { align-items: flex-start; flex-direction: column; } }
</style>
