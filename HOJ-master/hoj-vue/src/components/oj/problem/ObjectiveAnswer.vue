<template>
  <section class="objective-answer">
    <el-tag size="small">{{ labels[problem.questionType] }}</el-tag>
    <h3>写下你的答案</h3>
    <p class="muted">{{ instructions[problem.questionType] }}</p>
    <el-radio-group v-if="problem.questionType === 'SINGLE_CHOICE'" v-model="single" @change="save">
      <el-radio v-for="option in options" :key="option.id" :label="option.id" border>{{ option.text }}</el-radio>
    </el-radio-group>
    <el-checkbox-group v-else-if="problem.questionType === 'MULTIPLE_CHOICE'" v-model="multiple" @change="save">
      <el-checkbox v-for="option in options" :key="option.id" :label="option.id" border>{{ option.text }}</el-checkbox>
    </el-checkbox-group>
    <div v-else>
      <label v-for="blank in blanks" :key="blank.id" class="blank-answer">
        <span>{{ blank.label || '第 ' + (blanks.indexOf(blank) + 1) + ' 空' }}</span>
        <el-input :value="answers[blank.id] || ''" maxlength="2000" placeholder="输入答案" @input="setAnswer(blank.id, $event)" />
      </label>
    </div>
    <p v-if="!options.length && problem.questionType !== 'FILL_BLANK'" class="muted">暂未提供选项。</p>
    <el-alert title="客观题判分服务尚未开放" type="info" :closable="false" show-icon />
    <div class="answer-footer"><small class="muted">{{ saved ? '作答草稿保存在当前浏览器。' : '浏览器无法保存，请复制备份。' }}</small><el-button type="primary" disabled>提交答案</el-button></div>
  </section>
</template>
<script>
import { draftKey } from '@/common/learning';
import { readPreference, savePreference } from '@/common/appearance';
export default {
  props: { problem: { type: Object, required: true } },
  data: () => ({ single: '', multiple: [], answers: {}, saved: true,
    labels: { SINGLE_CHOICE: '单选题', MULTIPLE_CHOICE: '多选题', FILL_BLANK: '填空题' },
    instructions: { SINGLE_CHOICE: '请选择一个选项。', MULTIPLE_CHOICE: '请选择所有符合题意的选项。', FILL_BLANK: '按顺序填写；开放判分后将按预设答案评判。' } }),
  computed: {
    options() { return Array.isArray(this.problem.options) ? this.problem.options : []; },
    blanks() { return Array.isArray(this.problem.blanks) ? this.problem.blanks : []; },
    key() { return 'objective:' + draftKey(this.$store.getters.userInfo.uid, this.$route.params) + ':' + this.problem.questionType + ':' + (this.problem.questionVersion || '1'); },
  },
  watch: { key: { immediate: true, handler() {
    const saved = readPreference(this.key, {});
    const ids = this.options.map(option => option.id);
    this.single = ids.includes(saved.single) ? saved.single : '';
    this.multiple = Array.isArray(saved.multiple) ? saved.multiple.filter(id => ids.includes(id)) : [];
    this.answers = {};
    this.blanks.forEach(blank => { const value = saved.answers && saved.answers[blank.id]; if (typeof value === 'string') this.$set(this.answers, blank.id, value); });
  } } },
  methods: {
    setAnswer(id, value) { this.$set(this.answers, id, value); this.save(); },
    save() { this.saved = savePreference(this.key, { single: this.single, multiple: this.multiple, answers: this.answers }); },
  },
};
</script>
<style scoped>
.objective-answer { padding: 24px; }
.objective-answer h3 { color: var(--text); font-size: 22px; margin: 18px 0 10px; }
.el-radio-group, .el-checkbox-group { display: grid; gap: 12px; margin: 24px 0; width: 100%; }
.el-radio.is-bordered, .el-checkbox.is-bordered { display: flex; align-items: center; margin: 0; height: auto; min-height: 48px; padding: 14px; background: var(--soft); border-color: var(--line); }
.objective-answer /deep/ .el-radio__label, .objective-answer /deep/ .el-checkbox__label { white-space: normal; line-height: 1.7; }
.blank-answer { display: block; margin: 18px 0; }
.blank-answer > span { display: block; color: var(--muted); margin-bottom: 8px; }
.answer-footer { display: flex; gap: 16px; align-items: center; justify-content: space-between; margin-top: 20px; }
</style>
