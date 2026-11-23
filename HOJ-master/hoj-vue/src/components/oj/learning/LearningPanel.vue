<template>
  <div class="learning-panel">
    <el-tabs v-model="tab">
      <el-tab-pane label="能力画像" name="profile" lazy>
        <AbilityProfile v-if="viewerUid" :key="viewerSession" :uid="viewerUid" compact />
        <el-button v-else type="text" @click="login">登录查看能力画像</el-button>
      </el-tab-pane>
      <el-tab-pane label="笔记与计划" name="notes">
        <label>本题复盘</label>
        <el-input v-model="notes" type="textarea" :rows="6" placeholder="思路、卡点、复杂度，以及下次怎样更早发现问题。" @input="save" />
        <label>下一步训练计划</label>
        <el-input v-model="plan" type="textarea" :rows="3" placeholder="例如：补两道二分边界题，再复盘本题。" @input="save" />
        <small class="muted">{{ saved ? '已保存在当前浏览器，按账号区分。' : '当前浏览器无法保存，请复制备份。' }}</small>
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
import AbilityProfile from './AbilityProfile';
import LearningRecommendations from './LearningRecommendations';

export default {
  components: { AbilityProfile, LearningRecommendations },
  props: { showRecommendations: Boolean },
  data: () => ({ tab: 'profile', notes: '', plan: '', saved: true }),
  computed: {
    viewerUid() {
      const { userInfo, isAuthenticated } = this.$store.getters;
      return isAuthenticated ? userInfo.uid : null;
    },
    viewerSession() {
      return this.viewerUid + ':' + this.$store.getters.authEpoch;
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
      },
    },
    showRecommendations(visible) {
      if (!visible && this.tab === 'recommend') this.tab = 'notes';
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
.learning-panel { padding: 8px 16px 20px; }
.learning-panel label { display: block; margin: 16px 0 8px; }
.learning-panel small { display: block; margin-top: 10px; }
</style>
