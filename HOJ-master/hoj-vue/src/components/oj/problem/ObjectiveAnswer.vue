<template>
  <section class="objective-answer">
    <el-tag size="small">{{ labels[problem.questionType] || '客观题' }}</el-tag>
    <h3>{{ problem.questionType === 'FILL_BLANK' ? '填写你的答案' : '选择你的答案' }}</h3>
    <p class="muted">{{ instructions[problem.questionType] }}</p>
    <el-radio-group v-if="isSingle" v-model="single" :disabled="locked" @change="save">
      <el-radio v-for="option in options" :key="option.id" :label="option.id" border>{{ option.id }}. {{ option.text }}</el-radio>
    </el-radio-group>
    <el-checkbox-group v-else-if="problem.questionType === 'MULTIPLE_CHOICE'" v-model="multiple" :disabled="locked" @change="save">
      <el-checkbox v-for="option in options" :key="option.id" :label="option.id" border>{{ option.id }}. {{ option.text }}</el-checkbox>
    </el-checkbox-group>
    <div v-else>
      <label v-for="blank in blanks" :key="blank.id" class="blank-answer">
        <span>{{ blank.label || '第 ' + (blanks.indexOf(blank) + 1) + ' 空' }} · {{ blank.score }} 分</span>
        <el-input :value="answers[blank.id] || ''" :disabled="locked" maxlength="2000" placeholder="输入答案" @input="setAnswer(blank.id, $event)" />
      </label>
    </div>
    <p v-if="!options.length && problem.questionType !== 'FILL_BLANK'" class="muted">暂未提供选项。</p>
    <el-alert v-if="!enabled" title="此页面尚未接入客观题判分，请从客观题练习入口作答" type="info" :closable="false" show-icon />
    <div v-if="enabled && problem.submitted" class="objective-feedback" aria-live="polite">
      <p v-if="!problem.answerVisible && problem.score !== undefined" class="correct-answer">得分 {{ problem.score }} / {{ problem.maxScore }}</p>
      <template v-if="problem.answerVisible">
        <el-alert :title="problem.correct ? '回答正确' : hasAnswer ? problem.score > 0 ? '部分正确' : '回答错误' : '本题未作答'" :type="problem.correct ? 'success' : 'warning'" :closable="false" show-icon />
        <p v-if="problem.maxScore != null" class="correct-answer">得分 {{ problem.score }} / {{ problem.maxScore }}</p>
        <p class="correct-answer">正确答案：{{ (problem.correctAnswers || []).join('、') }}</p>
      </template>
      <p v-else class="muted">{{ problem.score !== undefined ? '本题已交卷，标准答案尚未开放。' : '已提交，答案与判分反馈尚未开放。' }}</p>
      <div v-if="problem.explanationVisible"><h4>答案解析</h4><p class="objective-explanation">{{ problem.explanation || '本题暂未提供解析。' }}</p></div>
      <p v-else class="muted">解析尚未开放。</p>
    </div>
    <div class="answer-footer">
      <small class="muted">{{ enabled ? dirty ? '当前答案尚未保存。' : mode === 'PAPER' && !finished ? '保存后仍可修改，交卷后不能修改。' : problem.submitted ? '本题已提交。' : '选择后提交，由系统判分。' : saved ? '作答草稿保存在当前浏览器。' : '浏览器无法保存，请复制备份。' }}</small>
      <el-button type="primary" :disabled="!canSubmit" :loading="loading" @click="$emit('submit', selected)">{{ enabled && mode === 'PAPER' ? '保存答案' : '提交答案' }}</el-button>
    </div>
  </section>
</template>
<script>
import { draftKey } from '@/common/learning';
import { readPreference, savePreference } from '@/common/appearance';
import { questionTypes } from '@/common/objective';
export default {
  name: 'ObjectiveAnswer',
  props: {
    problem: { type: Object, required: true }, enabled: Boolean, finished: Boolean, loading: Boolean,
    mode: { type: String, default: 'PRACTICE' }, runId: { type: [String, Number], default: '' },
  },
  data: () => ({ single: '', multiple: [], answers: {}, saved: true,
    labels: { ...questionTypes, FILL_BLANK: '填空题' },
    instructions: { SINGLE_CHOICE: '请选择一个选项。', MULTIPLE_CHOICE: '请选择所有正确选项；全部选对才得分。', TRUE_FALSE: '请选择正确或错误。', FILL_BLANK: '按题目顺序填写结果，每个空独立计分。' } }),
  computed: {
    options() { return Array.isArray(this.problem.options) ? this.problem.options : []; },
    blanks() { return Array.isArray(this.problem.blanks) ? this.problem.blanks : []; },
    isSingle() { return ['SINGLE_CHOICE', 'TRUE_FALSE'].includes(this.problem.questionType); },
    selected() { return this.problem.questionType === 'FILL_BLANK' ? this.blanks.map(blank => this.answers[blank.id] || '') : this.isSingle ? this.single ? [this.single] : [] : this.multiple.slice().sort(); },
    hasAnswer() { return this.selected.some(value => value.trim().length > 0); },
    dirty() { const saved = this.problem.selectedAnswers || []; const normalized = this.problem.questionType === 'FILL_BLANK' ? this.blanks.map((_, index) => saved[index] || '') : saved.slice().sort(); return JSON.stringify(this.selected) !== JSON.stringify(normalized); },
    locked() { return this.loading || this.finished || this.enabled && this.mode === 'PRACTICE' && this.problem.submitted; },
    canSubmit() { return this.enabled && !this.locked && !!this.$store.getters.userInfo.uid && (this.mode === 'PAPER' || this.hasAnswer); },
    key() { return 'objective:' + draftKey(this.$store.getters.userInfo.uid, this.$route.params) + ':' + this.runId + ':' + (this.problem.key || this.problem.id) + ':' + this.problem.questionType + ':' + (this.problem.version || this.problem.questionVersion || '1'); },
  },
  watch: {
    key: { immediate: true, handler() { this.restore(); } },
    'problem.selectedAnswers'(selected, previous) {
      // Vue 2 also invokes array watchers when only the parent problem object changes.
      // Result polling retains this array; explicit save/refresh replaces it.
      if (this.enabled && selected !== previous) this.restore();
    },
  },
  methods: {
    restore() {
      const saved = readPreference(this.key, {});
      const ids = this.options.map(option => option.id);
      const candidate = this.enabled ? this.problem.selectedAnswers || [] : saved.selected || (saved.single ? [saved.single] : saved.multiple || []);
      const selected = Array.isArray(candidate) ? candidate : [];
      this.single = selected.length && ids.includes(selected[0]) ? selected[0] : '';
      this.multiple = selected.filter(id => ids.includes(id));
      this.answers = {};
      this.blanks.forEach((blank, index) => { const value = this.enabled ? selected[index] : saved.answers && saved.answers[blank.id]; if (typeof value === 'string') this.$set(this.answers, blank.id, value); });
    },
    setAnswer(id, value) { this.$set(this.answers, id, value); this.save(); },
    save() {
      // Server-backed runs keep the authoritative saved answers on the server.
      if (!this.enabled) this.saved = savePreference(this.key, { selected: this.selected, answers: this.answers });
    },
  },
};
</script>
<style scoped>
.objective-answer { padding: 24px; }
.objective-answer h3 { color: var(--text); font-size: 22px; margin: 18px 0 10px; }
.muted { color: var(--muted); line-height: 1.7; }
.el-radio-group, .el-checkbox-group { display: grid; gap: 12px; margin: 24px 0; width: 100%; }
.el-radio.is-bordered, .el-checkbox.is-bordered { display: flex; align-items: center; margin: 0; height: auto; min-height: 48px; padding: 14px; background: var(--soft); border-color: var(--line); }
.el-radio.is-bordered:not(.is-disabled):hover, .el-checkbox.is-bordered:not(.is-disabled):hover { border-color: var(--accent); }
.el-radio.is-bordered.is-checked, .el-checkbox.is-bordered.is-checked { border-color: var(--accent); box-shadow: inset 3px 0 var(--accent); }
.objective-answer /deep/ .is-disabled .el-radio__label, .objective-answer /deep/ .is-disabled .el-checkbox__label { color: var(--text); }
.objective-answer /deep/ .el-radio__input, .objective-answer /deep/ .el-checkbox__input { flex-shrink: 0; }
.objective-answer /deep/ .el-radio__label, .objective-answer /deep/ .el-checkbox__label { min-width: 0; }
.objective-answer /deep/ .el-radio__label, .objective-answer /deep/ .el-checkbox__label { white-space: pre-wrap; line-height: 1.7; overflow-wrap: anywhere; }
.blank-answer { display: block; margin: 18px 0; }
.blank-answer > span { display: block; color: var(--muted); margin-bottom: 8px; }
.answer-footer { display: flex; gap: 16px; align-items: center; justify-content: space-between; margin-top: 20px; }
.answer-footer .el-button { border-radius: 8px; min-height: 44px; }
.objective-feedback { border-top: 1px solid var(--line); padding-top: 18px; }
.correct-answer, .objective-explanation { color: var(--text); white-space: pre-wrap; line-height: 1.8; overflow-wrap: anywhere; }
@media (max-width: 600px) { .objective-answer { padding: 16px; } .answer-footer { align-items: flex-start; flex-direction: column; } }
</style>
