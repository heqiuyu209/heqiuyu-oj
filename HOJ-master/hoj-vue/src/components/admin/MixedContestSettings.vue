<template>
  <section class="mixed-settings">
    <el-form-item label="题目组织方式">
      <el-switch :value="!!value" active-text="混合试卷（选择 / 判断 / 填空 / 编程）" inactive-text="编程题比赛" @change="toggle" />
    </el-form-item>
    <template v-if="value">
      <el-form-item label="比赛试卷" required>
        <el-select :value="value.paperId" filterable remote :remote-method="search" :loading="loading" placeholder="搜索已有套卷" @input="set('paperId', $event)">
          <el-option v-for="paper in papers" :key="paper.id" :value="paper.id" :label="paper.title" />
        </el-select>
        <p>在“题集与试卷 → 新建试卷”中维护题目和分值，保存后即可选择，无需开放日常答题或单题练习。比赛保存时统一冻结题目版本；比赛开始后不能更换试卷。</p>
      </el-form-item>
      <div class="release-fields" v-for="field in ['answer', 'explanation']" :key="field">
        <el-form-item :label="field === 'answer' ? '客观题答案开放' : '解析开放'">
          <el-select :value="value[field + 'Policy']" @input="set(field + 'Policy', $event)">
            <el-option label="比赛结束后" value="AFTER_END" /><el-option label="指定赛后时间" value="AT_TIME" /><el-option label="不开放" value="NEVER" />
          </el-select>
          <el-date-picker v-if="value[field + 'Policy'] === 'AT_TIME'" :value="value[field + 'ReleaseAt']" type="datetime" value-format="timestamp" placeholder="不早于比赛结束时间" @input="set(field + 'ReleaseAt', $event)" />
        </el-form-item>
      </div>
      <el-alert :title="type === 0 ? '排名：客观题得分优先，再按编程题 AC 数和罚时排序。' : '排名：客观题与编程题按分值合计，总分降序。'" type="info" :closable="false" />
      <p>编程题即时判题；客观题保存后可修改，交卷时结算得分。截止后自动交卷，仅已保存的答案和已提交判题的代码参与计分。比赛记录与日常练习独立。</p>
      <p>混合赛在比赛内查看榜单；外部滚榜、打星与奖项设置待后续接入。</p>
    </template>
  </section>
</template>
<script>
import api from '@/common/api';
import { responseData, errorMessage } from '@/common/objective';
export default {
  name: 'MixedContestSettings', props: { value: Object, type: Number },
  data: () => ({ papers: [], loading: false, request: 0 }),
  created() { this.search(''); },
  beforeDestroy() { this.request++; },
  watch: { 'value.paperId'(id) { if (id && !this.papers.some(p => p.id === id)) this.search(''); } },
  methods: {
    toggle(enabled) { this.$emit('input', enabled ? { paperId: null, scoreRule: 'BEST', answerPolicy: 'AFTER_END', explanationPolicy: 'AFTER_END' } : null); },
    set(key, value) { this.$emit('input', { ...this.value, [key]: value }); },
    async search(keyword) { const token = ++this.request; this.loading = true; try { const data = responseData(await api.adminPapers({ keyword })); if (token !== this.request) return; this.papers = data.records; if (this.value && this.value.paperId && !this.papers.some(p => p.id === this.value.paperId)) { const paper = responseData(await api.adminPaper(this.value.paperId)); if (token === this.request) this.papers.push(paper); } } catch (e) { if (token === this.request) this.$message.error(errorMessage(e)); } finally { if (token === this.request) this.loading = false; } },
  },
};
</script>
<style scoped>
.mixed-settings { margin: 20px 0; padding: 22px; border: 1px solid var(--line, #dce6ed); border-radius: 12px; background: var(--panel, #fff); }.mixed-settings p { color: var(--muted, #7d919f); line-height: 1.8; font-size: 13px; }.mixed-settings .el-select { min-width: 270px; max-width: 100%; }.release-fields .el-date-editor { margin: 10px; }
</style>
