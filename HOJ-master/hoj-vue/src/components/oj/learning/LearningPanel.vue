<template>
  <div class="learning-panel">
    <el-tabs v-model="tab">
      <el-tab-pane label="草稿板" name="scratchpad" lazy>
        <ProblemScratchpad />
      </el-tab-pane>
      <el-tab-pane label="笔记" name="notes" class="learning-action-pane" lazy>
        <LearningNoteEditor :key="notesKey" v-model="notes" :active="tab === 'notes'" @input="save" />
        <div class="learning-note-actions">
          <small class="muted">{{ saveNotice }}</small>
          <LearningNoteExport :key="notesKey" :problem="exportProblem" :read-code="readCode" :notes="notes" />
        </div>
      </el-tab-pane>
      <el-tab-pane label="待办" name="todo" class="learning-action-pane" lazy>
        <LearningTodoList :storage-key="todoKey" :legacy-plan-key="notesKey" />
      </el-tab-pane>
      <el-tab-pane label="番茄钟" name="timer" class="learning-action-pane" lazy>
        <LearningPomodoro :storage-key="timerKey" />
      </el-tab-pane>
      <el-tab-pane v-if="showRecommendations" label="推荐" name="recommend" lazy>
        <LearningRecommendations v-if="viewerUid" :uid="viewerUid" compact />
        <el-button v-else type="text" @click="login">登录查看推荐</el-button>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import { draftKey } from '@/common/learning';
import { readPreference, savePreference } from '@/common/appearance';
import ProblemScratchpad from '@/components/oj/problem/ProblemScratchpad';
import LearningNoteEditor from './LearningNoteEditor';
import LearningNoteExport from './LearningNoteExport';
import LearningTodoList from './LearningTodoList';
import LearningPomodoro from './LearningPomodoro';
import LearningRecommendations from './LearningRecommendations';

export default {
  components: { ProblemScratchpad, LearningNoteEditor, LearningNoteExport, LearningTodoList, LearningPomodoro, LearningRecommendations },
  props: { showRecommendations: Boolean, exportProblem: Object, readCode: Function },
  data: () => ({ tab: 'scratchpad', notes: '', saved: true }),
  computed: {
    viewerUid() {
      const { userInfo, isAuthenticated } = this.$store.getters;
      return isAuthenticated ? userInfo.uid : null;
    },
    saveNotice() {
      return this.saved ? '保存在当前浏览器。' : '当前浏览器无法保存，请复制备份。';
    },
    notesKey() {
      return 'notes:' + draftKey(this.viewerUid, this.$route.params);
    },
    todoKey() {
      const { contestID, groupID } = this.$route.params;
      return 'todos:' + draftKey(this.viewerUid, contestID ? { contestID, groupID } : {});
    },
    timerKey() {
      return 'pomodoro:' + draftKey(this.viewerUid);
    },
  },
  watch: {
    notesKey: {
      immediate: true,
      handler(key) {
        const data = readPreference(key, {});
        this.notes = typeof data.notes === 'string' ? data.notes : '';
        this.saved = true;
      },
    },
    showRecommendations(visible) {
      if (!visible && this.tab === 'recommend') this.tab = 'scratchpad';
    },
  },
  methods: {
    login() {
      this.$store.dispatch('changeModalStatus', { mode: 'login', visible: true });
    },
    save() {
      const previous = readPreference(this.notesKey, {});
      this.saved = savePreference(this.notesKey, { ...previous, notes: this.notes });
    },
  },
};
</script>

<style scoped>
.learning-panel { min-width: 0; }
.learning-panel ::v-deep .el-tabs__header { margin: 0; padding-inline: 10px; }
.learning-panel ::v-deep .el-tabs__item { padding-inline: 7px; font-size: 13px; }
.learning-note-actions { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
@media (max-width: 767px) {
  /* Let controls scroll above the floating AI assistant at the bottom of the page. */
  .learning-action-pane { padding-bottom: 88px; }
}
</style>
