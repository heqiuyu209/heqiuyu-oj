<template>
  <section class="objective-list">
    <header class="objective-heading">
      <div><h1>客观题练习</h1><p>从知识点练习到整卷自测，按自己的节奏检查理解。</p></div>
      <el-button @click="$router.push('/problem')">编程题库</el-button>
    </header>
    <el-tabs v-model="tab" @tab-click="reset">
      <el-tab-pane label="题集与试卷" name="sets" />
      <el-tab-pane label="我的作答 / 错题复习" name="history" />
    </el-tabs>
    <form v-if="tab === 'sets'" class="objective-filters" @submit.prevent="reset">
      <el-input v-model="keyword" placeholder="搜索题集或试卷" clearable maxlength="100" />
      <el-select v-model="mode" placeholder="全部模式" clearable @change="reset">
        <el-option v-for="(label, value) in modes" :key="value" :value="value" :label="label" />
      </el-select>
      <el-button native-type="submit" type="primary">搜索</el-button>
    </form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-table v-loading="loading" :data="rows" :empty-text="error ? '暂时无法加载，请重试' : tab === 'sets' ? '暂时没有已发布的题集' : '开始一份题集后，作答记录会出现在这里'">
      <el-table-column prop="title" label="名称" min-width="220" />
      <el-table-column label="模式" width="120"><template slot-scope="{ row }">{{ modes[row.mode] }}</template></el-table-column>
      <el-table-column v-if="tab === 'sets'" prop="questionCount" label="题数" width="80" />
      <el-table-column v-if="tab === 'sets'" label="答案开放" min-width="170"><template slot-scope="{ row }">{{ releaseLabel(row.answerPolicy, row.answerReleaseAt) }}</template></el-table-column>
      <el-table-column v-if="tab === 'history'" label="状态" width="110"><template slot-scope="{ row }">{{ row.state === 'FINISHED' ? '已交卷' : '进行中' }}</template></el-table-column>
      <el-table-column v-if="tab === 'history'" label="开始时间" min-width="180"><template slot-scope="{ row }">{{ new Date(row.createdAt).toLocaleString() }}</template></el-table-column>
      <el-table-column label="操作" min-width="160"><template slot-scope="{ row }">
        <el-button v-if="tab === 'sets'" type="primary" size="small" :loading="starting === row.id" :disabled="starting !== null" @click="start(row)">{{ row.mode === 'PAPER' ? '开始答卷' : '开始练习' }}</el-button>
        <el-button v-else type="text" @click="$router.push('/objective/run/' + row.id)">{{ row.state === 'FINISHED' ? '查看结果 / 复习错题' : '继续作答' }}</el-button>
      </template></el-table-column>
    </el-table>
    <div class="objective-pagination"><el-button v-if="error" size="small" @click="load">重试</el-button><el-pagination layout="prev, pager, next, total" :current-page="page" :page-size="20" :total="total" @current-change="changePage" /></div>
  </section>
</template>
<script>
import api from '@/common/api';
import { modes, releaseLabel, requestId, responseData, errorMessage } from '@/common/objective';
export default {
  name: 'ObjectiveList',
  data: () => ({ tab: 'sets', keyword: '', mode: '', page: 1, total: 0, rows: [], loading: false, error: '', starting: null, startIds: {}, request: 0, modes }),
  computed: { uid() { return this.$store.getters.userInfo.uid; } },
  watch: { uid() { this.request++; this.rows = []; this.startIds = {}; this.starting = null; this.reset(); } },
  created() { this.load(); },
  beforeDestroy() { this.request++; },
  methods: {
    releaseLabel,
    reset() { this.page = 1; this.load(); },
    changePage(page) { this.page = page; this.load(); },
    async load() {
      const request = ++this.request;
      this.loading = true; this.error = ''; this.rows = [];
      try {
        const params = { currentPage: this.page, limit: 20, keyword: this.keyword, mode: this.mode };
        const data = responseData(await (this.tab === 'sets' ? api.objectiveSets(params) : api.objectiveRuns(params)));
        if (request !== this.request) return;
        this.rows = data.records; this.total = data.total;
      } catch (error) { if (request === this.request) this.error = errorMessage(error); }
      finally { if (request === this.request) this.loading = false; }
    },
    async start(row) {
      if (this.starting !== null) return;
      this.starting = row.id;
      const uid = this.uid;
      if (!this.startIds[row.id]) this.$set(this.startIds, row.id, requestId());
      try {
        const run = responseData(await api.objectiveStart({ setId: row.id, requestId: this.startIds[row.id] }));
        if (uid !== this.uid) return;
        this.$delete(this.startIds, row.id);
        this.$router.push('/objective/run/' + run.id);
      } catch (error) { if (uid === this.uid) this.error = errorMessage(error); }
      finally { if (uid === this.uid) this.starting = null; }
    },
  },
};
</script>
<style scoped>
.objective-list { max-width: 1200px; margin: 24px auto; padding: 24px; background: var(--surface, #fff); border: 1px solid var(--line, #e6e9ed); border-radius: 8px; }
.objective-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.objective-heading h1 { margin: 0; font-size: 26px; color: var(--text); }
.objective-heading p { color: var(--muted); line-height: 1.7; }
.objective-filters { display: flex; gap: 12px; margin: 12px 0 24px; }
.objective-filters .el-input { max-width: 340px; }
.objective-pagination { display: flex; justify-content: flex-end; margin-top: 24px; overflow-x: auto; }
@media (max-width: 600px) { .objective-list { padding: 16px; margin: 12px 0; } .objective-heading { align-items: flex-start; } .objective-filters { flex-wrap: wrap; } .objective-filters .el-input { max-width: none; } }
</style>
