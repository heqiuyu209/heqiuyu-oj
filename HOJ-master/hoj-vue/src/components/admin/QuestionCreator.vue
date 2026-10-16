<template>
  <div class="question-creator">
    <el-dialog title="新建题目" :visible.sync="choosing" width="min(680px, 94vw)" append-to-body :close-on-click-modal="false">
      <p class="creator-help">先选择题型。题目保存到题库后，可单独开放练习，也可加入题集、试卷或比赛。</p>
      <div class="type-grid">
        <button v-for="type in types" :key="type.value" type="button" @click="select(type.value)"><i :class="type.icon" /><strong>{{ type.label }}</strong><span>{{ type.description }}</span></button>
      </div>
    </el-dialog>
    <objective-admin ref="objective" editor-only @question-saved="objectiveSaved" />
    <el-dialog title="新建编程题" :visible.sync="programDialog" width="min(1200px, 96vw)" top="3vh" custom-class="program-create-dialog" append-to-body :close-on-click-modal="false" :before-close="closeProgram">
      <p class="creator-help">默认仅保存在题库。准备比赛题时请保持私有，单题练习可在题库中独立开放。</p>
      <problem v-if="programDialog" ref="program" :key="generation" embedded @saved="programSaved" />
    </el-dialog>
  </div>
</template>
<script>
import ObjectiveAdmin from '@/views/admin/problem/ObjectiveAdmin';
import Problem from '@/views/admin/problem/Problem';
const types = [
  { value: 'PROGRAMMING', label: '编程题', icon: 'el-icon-monitor', description: '编写代码，提交评测' },
  { value: 'SINGLE_CHOICE', label: '单选题', icon: 'el-icon-circle-check', description: '一个正确选项' },
  { value: 'MULTIPLE_CHOICE', label: '多选题', icon: 'el-icon-finished', description: '多个正确选项' },
  { value: 'TRUE_FALSE', label: '判断题', icon: 'el-icon-check', description: '判断正确或错误' },
  { value: 'FILL_BLANK', label: '填空题', icon: 'el-icon-edit', description: '单空或多空，逐空计分' },
];
export default {
  name: 'QuestionCreator', components: { ObjectiveAdmin, Problem },
  props: { objectiveOnly: Boolean },
  data: () => ({ choosing: false, programDialog: false, generation: 0 }),
  computed: { uid() { return this.$store.getters.userInfo.uid; }, types() { return types.filter(type => !this.objectiveOnly || type.value !== 'PROGRAMMING'); } },
  watch: { uid() { this.choosing = false; this.programDialog = false; this.generation++; } },
  methods: {
    open() { this.choosing = true; },
    select(type) { this.choosing = false; if (type === 'PROGRAMMING') { this.generation++; this.programDialog = true; } else this.$refs.objective.newQuestion(type); },
    objectiveSaved(question) { this.$emit('saved', { kind: 'OBJECTIVE', question }); },
    programSaved(question) { this.programDialog = false; this.$emit('saved', { kind: 'PROGRAMMING', question }); },
    closeProgram(done) { if (this.$refs.program && this.$refs.program.saving) return; this.programDialog = false; if (typeof done === 'function') done(); },
  },
};
</script>
<style scoped>
.creator-help { color: var(--muted); line-height: 1.8; margin: 0 0 20px; }
.type-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.type-grid button { display: grid; grid-template-columns: 32px 1fr; gap: 8px; text-align: left; padding: 20px; border: 1px solid var(--line); border-radius: 12px; background: var(--surface, #fff); color: var(--text); cursor: pointer; font: inherit; }
.type-grid button:hover, .type-grid button:focus-visible { border-color: var(--accent); background: var(--soft); outline: 2px solid var(--accent); outline-offset: 2px; }
.type-grid i { grid-row: span 2; font-size: 24px; color: var(--accent); }.type-grid strong { font-size: 16px; }.type-grid span { color: var(--muted); font-size: 12px; }
@media(max-width:500px) { .type-grid { grid-template-columns: 1fr; } }
</style>
<style>.program-create-dialog .el-dialog__body { max-height: 78vh; overflow-y: auto; }</style>
