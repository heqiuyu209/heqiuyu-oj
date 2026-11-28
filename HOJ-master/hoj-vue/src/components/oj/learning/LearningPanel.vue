<template>
  <div class="learning-panel">
    <el-tabs v-model="tab">
      <el-tab-pane label="草稿板" name="scratchpad" lazy>
        <ProblemScratchpad />
      </el-tab-pane>
      <el-tab-pane label="笔记" name="notes" lazy>
        <el-input v-model="notes" type="textarea" :rows="6" aria-label="本题笔记"
          placeholder="记录本题思路、卡点和复杂度" @input="save" />
        <small class="muted">{{ saveNotice }}</small>
      </el-tab-pane>
      <el-tab-pane label="计划" name="plan" lazy>
        <el-input v-model="plan" type="textarea" :rows="4" aria-label="下一步训练计划"
          placeholder="例如，补两道二分边界题，再复盘本题" @input="save" />
        <small class="muted">{{ saveNotice }}</small>
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
import LearningRecommendations from './LearningRecommendations';

export default {
  components: { ProblemScratchpad, LearningRecommendations },
  props: { showRecommendations: Boolean },
  data: () => ({ tab: 'scratchpad', notes: '', plan: '', saved: true }),
  computed: {
    viewerUid() {
      const { userInfo, isAuthenticated } = this.$store.getters;
      return isAuthenticated ? userInfo.uid : null;
    },
    saveNotice() {
      return this.saved ? '保存在当前浏览器。' : '当前浏览器无法保存，请复制备份。';
    },
    key() {
      return 'notes:' + draftKey(this.$store.getters.userInfo.uid, this.$route.params);
    },
  },
  watch: {
    key: {
      immediate: true,
      handler() {
        const data = readPreference(this.key, {});
        this.notes = data.notes || '';
        this.plan = data.plan || '';
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
      this.saved = savePreference(this.key, { notes: this.notes, plan: this.plan });
    },
  },
};
</script>

<style scoped>
.learning-panel { min-width: 0; }
.learning-panel ::v-deep .el-tabs__header { margin: 0; padding-inline: 10px; }
.learning-panel ::v-deep .el-tabs__item { padding-inline: 9px; font-size: 13px; }
.learning-panel small { display: block; margin-top: 10px; }
</style>
