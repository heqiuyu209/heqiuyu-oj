<template>
  <div class="objective-page">
    <ProblemNavigation />
    <section class="objective-list">
    <header class="objective-heading">
      <div><h1>客观题练习</h1><p>选择一道题直接练习，也可以通过题集和试卷进行系统自测。</p></div>
    </header>
    <el-tabs v-model="tab" @tab-click="reset">
      <el-tab-pane label="单题练习" name="questions" />
      <el-tab-pane label="题集与试卷" name="sets" />
      <el-tab-pane label="我的作答 / 错题复习" name="history" />
    </el-tabs>
    <form v-if="tab !== 'history'" class="objective-filters" @submit.prevent="reset">
      <el-input v-model="keyword" :placeholder="tab === 'questions' ? '搜索题目标题' : '搜索题集或试卷'" clearable maxlength="100" />
      <el-select v-if="tab === 'questions'" v-model="type" placeholder="全部题型" clearable @change="reset">
        <el-option v-for="(label, value) in questionTypes" :key="value" :value="value" :label="label" />
      </el-select>
      <el-select v-else v-model="mode" placeholder="全部模式" clearable @change="reset">
        <el-option v-for="(label, value) in modes" :key="value" :value="value" :label="label" />
      </el-select>
      <el-button native-type="submit" type="primary">搜索</el-button>
    </form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-if="tab === 'questions'" v-loading="loading" class="single-question-list">
      <article v-for="row in rows" :key="row.id" class="single-question-row">
        <div><p>{{ questionTypes[row.questionType] }} <span>题号 {{ row.id }}</span></p><h2>{{ row.title }}</h2></div>
        <el-button type="primary" size="small" :loading="starting === row.id" :disabled="starting !== null" @click="start(row)">开始练习</el-button>
      </article>
      <p v-if="!rows.length && !loading" class="single-empty">{{ emptyText }}</p>
    </div>
    <el-table v-loading="loading" :class="{ 'single-table': tab === 'questions' }" :data="rows" :empty-text="emptyText">
      <el-table-column v-if="tab === 'questions'" prop="id" label="ID" width="80" />
      <el-table-column prop="title" :label="tab === 'questions' ? '题目' : '名称'" min-width="220" />
      <el-table-column v-if="tab === 'questions'" label="题型" width="120"><template slot-scope="{ row }">{{ questionTypes[row.questionType] }}</template></el-table-column>
      <el-table-column v-else label="模式" width="120"><template slot-scope="{ row }">{{ row.questionId ? '单题练习' : modes[row.mode] }}</template></el-table-column>
      <el-table-column v-if="tab === 'sets'" prop="questionCount" label="题数" width="80" />
      <el-table-column v-if="tab === 'sets'" label="答案开放" min-width="170"><template slot-scope="{ row }">{{ releaseLabel(row.answerPolicy, row.answerReleaseAt) }}</template></el-table-column>
      <el-table-column v-if="tab === 'history'" label="状态" width="110"><template slot-scope="{ row }">{{ row.state === 'FINISHED' ? row.mode === 'PAPER' ? '已交卷' : '已完成' : '进行中' }}</template></el-table-column>
      <el-table-column v-if="tab === 'history'" label="开始时间" min-width="180"><template slot-scope="{ row }">{{ new Date(row.createdAt).toLocaleString() }}</template></el-table-column>
      <el-table-column label="操作" min-width="160"><template slot-scope="{ row }">
        <el-button v-if="tab !== 'history'" type="primary" size="small" :loading="starting === row.id" :disabled="starting !== null" @click="start(row)">{{ row.mode === 'PAPER' ? '开始答卷' : '开始练习' }}</el-button>
        <el-button v-else type="text" @click="$router.push({ path: '/objective/run/' + row.id, query: loadedContext })">{{ row.state === 'FINISHED' ? '查看结果 / 复习错题' : '继续作答' }}</el-button>
      </template></el-table-column>
    </el-table>
    <div class="objective-pagination"><el-button v-if="error" size="small" @click="load">重试</el-button><el-pagination layout="prev, pager, next, total" :current-page="page" :page-size="20" :total="total" @current-change="changePage" /></div>
  </section>
  </div>
</template>
<script>
import api from '@/common/api';
import ProblemNavigation from '@/components/oj/problem/ProblemNavigation';
import { modes, questionTypes, releaseLabel, requestId, responseData, errorMessage } from '@/common/objective';
export default {
  name: 'ObjectiveList',
  components: { ProblemNavigation },
  data() {
    const q = this.$route.query;
    return { tab: ['questions', 'sets', 'history'].includes(q.listTab) ? q.listTab : 'questions',
      keyword: typeof q.keyword === 'string' ? q.keyword.slice(0, 100) : '',
      mode: modes[q.mode] ? q.mode : '', type: questionTypes[q.questionType] ? q.questionType : '',
      page: Math.max(1, Math.min(100000, Math.floor(Number(q.listPage)) || 1)), loadedContext: {},
      total: 0, rows: [], loading: false, error: '', starting: null, startIds: {}, request: 0, modes, questionTypes };
  },
  computed: {
    uid() { return this.$store.getters.userInfo.uid; },
    emptyText() {
      if (this.error) return '暂时无法加载，请重试';
      if (this.tab === 'history') return '完成一次练习后，可以在这里查看记录与复习错题';
      if (this.keyword || this.type && this.tab === 'questions' || this.mode && this.tab === 'sets') return '没有匹配的记录，请调整筛选条件';
      return this.tab === 'questions' ? '暂时没有开放单题练习的客观题' : '暂时没有已发布的题集或试卷';
    },
  },
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
        const params = { currentPage: this.page, limit: 20, keyword: this.keyword, mode: this.mode, questionType: this.type };
        const context = { listTab: this.tab, listPage: String(this.page), keyword: this.keyword, mode: this.mode, questionType: this.type };
        const fetchList = this.tab === 'questions' ? api.objectiveQuestions : this.tab === 'sets' ? api.objectiveSets : api.objectiveRuns;
        const data = responseData(await fetchList(params));
        if (request !== this.request) return;
        this.rows = data.records; this.total = data.total;
        this.loadedContext = context;
      } catch (error) { if (request === this.request) this.error = errorMessage(error); }
      finally { if (request === this.request) this.loading = false; }
    },
    async start(row) {
      if (this.starting !== null) return;
      this.starting = row.id;
      const uid = this.uid;
      const query = { ...this.loadedContext };
      const source = this.tab === 'questions' ? 'questionId' : 'setId';
      const key = source + ':' + row.id;
      if (!this.startIds[key]) this.$set(this.startIds, key, requestId());
      try {
        const run = responseData(await api.objectiveStart({ [source]: row.id, requestId: this.startIds[key] }));
        if (uid !== this.uid) return;
        this.$delete(this.startIds, key);
        this.$router.push({ path: '/objective/run/' + run.id, query });
      } catch (error) { if (uid === this.uid) this.error = errorMessage(error); }
      finally { if (uid === this.uid) this.starting = null; }
    },
  },
};
</script>
<style scoped>
.objective-page { max-width: 1200px; margin: 24px auto; }
.objective-list { padding: 24px; background: var(--surface, #fff); border: 1px solid var(--line, #e6e9ed); border-radius: 8px; }
.objective-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.objective-heading h1 { margin: 0; font-size: 26px; color: var(--text); }
.objective-heading p { color: var(--muted); line-height: 1.7; }
.objective-filters { display: flex; gap: 12px; margin: 12px 0 24px; }
.objective-filters .el-input { max-width: 340px; }
.objective-pagination { display: flex; justify-content: flex-end; margin-top: 24px; overflow-x: auto; }
.single-question-list { display: none; }
.single-question-row { display: flex; align-items: center; gap: 16px; padding: 20px 0; border-bottom: 1px solid var(--line); }
.single-question-row > div { flex: 1; min-width: 0; }
.single-question-row p { margin: 0 0 8px; color: var(--muted); font-size: 12px; }
.single-question-row p span { margin-left: 12px; }
.single-question-row h2 { margin: 0; color: var(--text); font-size: 16px; font-weight: 500; line-height: 1.6; overflow-wrap: anywhere; }
.single-empty { color: var(--muted); text-align: center; padding: 24px 0; }
@media (max-width: 600px) { .objective-list { padding: 16px; margin: 12px 0; } .objective-heading { align-items: flex-start; } .objective-filters { flex-wrap: wrap; } .objective-filters .el-input { max-width: none; } .single-table { display: none; } .single-question-list { display: block; } }
</style>
