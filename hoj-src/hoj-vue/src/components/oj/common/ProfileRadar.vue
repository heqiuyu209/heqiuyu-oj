<template>
  <div class="profile-radar-container">
    <div v-if="loading" class="profile-loading">
      <p>加载用户画像中...</p>
    </div>

    <div v-else-if="profile" class="profile-content">
      <!-- Overall Rating -->
      <div class="profile-overall">
        <div class="rating-badge">
          <span class="rating-number">{{ profile.overall.overall_rating }}</span>
          <span class="rating-label">综合评分</span>
        </div>
        <div class="stats-row">
          <div class="stat-item">
            <span class="stat-num">{{ profile.overall.total_solved }}</span>
            <span class="stat-label">已解决</span>
          </div>
          <div class="stat-item">
            <span class="stat-num">{{ (profile.overall.ac_rate * 100).toFixed(1) }}%</span>
            <span class="stat-label">AC率</span>
          </div>
          <div class="stat-item">
            <span class="stat-num">{{ profile.overall.active_days }}</span>
            <span class="stat-label">活跃天数</span>
          </div>
        </div>
      </div>

      <!-- Algorithm Radar Chart -->
      <div class="profile-section">
        <h5>算法能力</h5>
        <div ref="radarChart" class="radar-chart"></div>
      </div>

      <!-- Behavior Scores -->
      <div class="profile-section">
        <h5>行为特征</h5>
        <div class="behavior-bars">
          <div class="bar-item" v-for="(val, key) in profile.behavior" :key="key">
            <span class="bar-label">{{ behaviorLabel(key) }}</span>
            <div class="bar-track">
              <div class="bar-fill" :style="{ width: val + '%', backgroundColor: barColor(val) }"></div>
            </div>
            <span class="bar-value">{{ val.toFixed(0) }}</span>
          </div>
        </div>
      </div>

      <!-- Weak Points -->
      <div class="profile-section" v-if="weakTags.length > 0">
        <h5>建议强化方向</h5>
        <div class="weak-tags">
          <el-tag v-for="tag in weakTags" :key="tag.name" size="small" type="warning">
            {{ tag.name }} ({{ tag.score.toFixed(0) }})
          </el-tag>
        </div>
      </div>
    </div>

    <div v-else class="profile-empty">
      <p>暂无画像数据，开始做题积累数据吧!</p>
    </div>
  </div>
</template>

<script>
// echarts loaded via CDN as global

export default {
  name: 'ProfileRadar',
  props: { uid: { type: Number, required: true } },
  data() {
    return {
      profile: null,
      loading: true,
      chart: null,
    };
  },
  computed: {
    weakTags() {
      if (!this.profile) return [];
      const algo = this.profile.algorithm || {};
      return Object.entries(algo)
        .filter(([, s]) => s < 40)
        .map(([name, score]) => ({ name, score }))
        .sort((a, b) => a.score - b.score)
        .slice(0, 5);
    },
  },
  async mounted() {
    await this.fetchProfile();
    if (this.profile) this.$nextTick(() => this.renderRadar());
  },
  methods: {
    async fetchProfile() {
      this.loading = true;
      try {
        const resp = await this.$http.get(`/profile/api/profile/${this.uid}`);
        this.profile = resp.data;
      } catch(e) {
        this.profile = null;
      } finally {
        this.loading = false;
      }
    },
    behaviorLabel(key) {
      const map = {
        persistence_score: '坚持度',
        independent_thinking: '独立性',
        debug_ability: '调试力',
      };
      return map[key] || key;
    },
    barColor(val) {
      if (val >= 70) return '#67c23a';
      if (val >= 40) return '#e6a23c';
      return '#f56c6c';
    },
    renderRadar() {
      if (!this.$refs.radarChart) return;
      this.chart = echarts.init(this.$refs.radarChart);
      const algo = this.profile.algorithm || {};
      const indicators = Object.entries(algo).map(([name]) => ({
        name, max: 100,
      }));
      this.chart.setOption({
        radar: {
          center: ['50%', '50%'],
          radius: '65%',
          indicator: indicators,
        },
        series: [{
          type: 'radar',
          data: [{ value: Object.values(algo), name: '能力分布' }],
          areaStyle: { color: 'rgba(64,158,255,0.2)' },
          lineStyle: { color: '#409EFF' },
          itemStyle: { color: '#409EFF' },
        }],
      });
    },
  },
};

</script>

<style scoped>
.profile-radar-container { padding: 16px; }
.profile-loading, .profile-empty { text-align: center; padding: 40px; color: #909399; }
.profile-overall { text-align: center; margin-bottom: 20px; }
.rating-badge { margin-bottom: 12px; }
.rating-number { font-size: 36px; font-weight: bold; color: #409EFF; display: block; }
.rating-label { font-size: 12px; color: #909399; }
.stats-row { display: flex; justify-content: space-around; }
.stat-item { text-align: center; }
.stat-num { font-size: 18px; font-weight: bold; display: block; }
.stat-label { font-size: 11px; color: #909399; }
.profile-section { margin-bottom: 16px; }
.profile-section h5 { margin: 0 0 8px 0; font-size: 14px; color: #303133; }
.radar-chart { width: 100%; height: 280px; }
.behavior-bars .bar-item { display: flex; align-items: center; margin-bottom: 8px; }
.bar-label { width: 60px; font-size: 12px; color: #606266; }
.bar-track { flex: 1; height: 8px; background: #f0f0f0; border-radius: 4px; margin: 0 8px; overflow: hidden; }
.bar-fill { height: 100%; border-radius: 4px; transition: width .3s; }
.bar-value { width: 30px; font-size: 12px; text-align: right; color: #909399; }
.weak-tags .el-tag { margin: 4px; }
</style>
