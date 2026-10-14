<template>
  <div class="learning-panel">
    <el-tabs v-model="tab">
      <el-tab-pane label="能力画像" name="profile">
        <p class="muted">按知识点整理训练记录。统计不足时保留空缺，不以做题数量代替掌握度。</p>
        <div v-for="area in areas" :key="area.key" class="knowledge-row">
          <div><strong>{{ area.name }}</strong><span>{{ count(area.key) }}</span></div>
          <p>{{ area.topics }}</p>
        </div>
        <p class="muted">{{ profile.knowledgeStats ? '已通过题数来自学习统计。' : '能力统计尚未接入，完成题目后可先记录复盘。' }}</p>
      </el-tab-pane>
      <el-tab-pane label="笔记与计划" name="notes">
        <label>本题复盘</label>
        <el-input v-model="notes" type="textarea" :rows="6" placeholder="思路、卡点、复杂度，以及下次怎样更早发现问题。" @input="save" />
        <label>下一步训练计划</label>
        <el-input v-model="plan" type="textarea" :rows="3" placeholder="例如：补两道二分边界题，再复盘本题。" @input="save" />
        <small class="muted">{{ saved ? '已保存在当前浏览器，按账号区分。' : '当前浏览器无法保存，请复制备份。' }}</small>
      </el-tab-pane>
      <el-tab-pane label="推荐" name="recommend">
        <p>从当前知识点继续练习</p>
        <el-tag v-for="tag in tags" :key="tag.id || tag.name" class="learning-tag">{{ tag.name }}</el-tag>
        <p class="muted">个性化推荐尚未接入。可以先在题库中按知识点筛选。</p>
        <el-button size="small" @click="$router.push('/problem')">打开题库</el-button>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
<script>
import { KNOWLEDGE_AREAS, draftKey, finiteRating } from '@/common/learning';
import { readPreference, savePreference } from '@/common/appearance';
export default {
  props: { tags: { type: Array, default: () => [] }, profile: { type: Object, default: () => ({}) } },
  data: () => ({ areas: KNOWLEDGE_AREAS, tab: 'profile', notes: '', plan: '', saved: true }),
  computed: { key() { return 'notes:' + draftKey(this.$store.getters.userInfo.uid, this.$route.params); } },
  watch: { key: { immediate: true, handler() { const data = readPreference(this.key, {}); this.notes = data.notes || ''; this.plan = data.plan || ''; } } },
  methods: {
    save() { this.saved = savePreference(this.key, { notes: this.notes, plan: this.plan }); },
    count(key) { const row = (this.profile.knowledgeStats || {})[key]; const count = row && finiteRating(row.solved); return count == null ? '暂无统计' : count + ' 题已通过'; },
  },
};
</script>
<style scoped>
.learning-panel { padding: 8px 16px 20px; }
.knowledge-row { padding: 13px 0; border-bottom: 1px solid var(--line); }
.knowledge-row > div { display: flex; justify-content: space-between; gap: 8px; }
.knowledge-row p, .knowledge-row span { color: var(--muted); font-size: 12px; line-height: 1.7; }
.learning-panel label { display: block; margin: 16px 0 8px; }
.learning-panel small { display: block; margin-top: 10px; }
.learning-tag { margin: 4px; }
</style>
