<template>
  <section class="site-rank">
    <header class="rank-heading"><div><h1>站内排名</h1><p>训练积累与赛场表现，分别记录。</p></div><router-link :to="type === 'practice' ? '/problem' : '/contest'">{{ type === 'practice' ? '前往题库' : '查看比赛' }} <i class="el-icon-arrow-right" /></router-link></header>
    <nav class="rank-tabs" aria-label="排名类型"><router-link v-for="tab in tabs" :key="tab.type" :to="'/rank/' + tab.type" :aria-current="type === tab.type ? 'page' : null">{{ tab.label }}</router-link></nav>
    <div class="rank-explanation"><h2>{{ type === 'practice' ? '平时 Rating' : '比赛 Rating' }}</h2><p>{{ type === 'practice' ? '公开、已评级题目的首次独立通过计分；重复通过不加分，简单题的收益随水平提高递减。套卷与比赛作答分别记录。' : '正式计分赛结束并完成判题后，根据对手实力和实际名次更新。表现超出预期则上升，低于预期则下降。' }}</p><small>站内独立评分，与 Codeforces 账号分数无关。{{ type === 'practice' ? '初始基准 800，每道题最多增加 32 分。' : '初始基准 1400，至少两名有效参赛者才计分；并列使用平均名次。' }} 约每分钟更新。</small></div>
    <form class="rank-search" @submit.prevent="search"><el-input v-model="keyword" clearable maxlength="100" placeholder="搜索用户名或昵称" aria-label="搜索用户名或昵称" /><el-button native-type="submit" type="primary" :loading="loading">搜索</el-button></form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-table v-loading="loading" :data="rows" class="rating-table" empty-text="暂无已定级用户；完成评级题或参加计分赛后会在此显示。">
      <el-table-column prop="rank" label="名次" width="75" />
      <el-table-column label="用户" min-width="180"><template slot-scope="{ row }"><router-link :to="{name:'UserHome',query:{uid:row.uid}}">{{ row.username }}</router-link><small v-if="row.nickname">{{ row.nickname }}</small></template></el-table-column>
      <el-table-column label="Rating" min-width="100"><template slot-scope="{ row }"><strong class="rating-number">{{ row.rating }}</strong></template></el-table-column>
      <el-table-column label="最近变化" min-width="100"><template slot-scope="{ row }"><span :class="row.delta > 0 ? 'gain' : row.delta < 0 ? 'loss' : ''">{{ signed(row.delta) }}</span></template></el-table-column>
      <el-table-column prop="peak" label="最高 Rating" min-width="110" />
      <el-table-column prop="ratedCount" :label="type === 'practice' ? '计分题数' : '计分场次'" min-width="95" />
      <el-table-column label="记录" width="85"><template slot-scope="{ row }"><el-button type="text" @click="history(row)">查看</el-button></template></el-table-column>
    </el-table>
    <footer class="rank-footer"><small>{{ updatedAt ? '最近更新：' + date(updatedAt) : '等待首次结算' }}</small><el-pagination layout="prev, pager, next" :total="total" :current-page="page" :page-size="30" @current-change="changePage" /></footer>
    <el-dialog :title="historyTitle + ' · Rating 记录'" :visible.sync="dialog" width="min(850px, 94vw)" append-to-body>
      <el-alert v-if="historyError" :title="historyError" type="error" :closable="false" />
      <el-table v-loading="historyLoading" :data="historyRows" empty-text="暂无计分记录，最多显示最近100条记录">
        <el-table-column label="时间" min-width="160"><template slot-scope="{ row }">{{ date(row.occurredAt) }}</template></el-table-column>
        <el-table-column prop="title" label="题目 / 比赛" min-width="180" />
        <el-table-column prop="oldRating" label="原 Rating" width="95" /><el-table-column prop="rating" label="新 Rating" width="95" />
        <el-table-column label="变化" width="80"><template slot-scope="{ row }">{{ signed(row.delta) }}</template></el-table-column>
      </el-table>
    </el-dialog>
  </section>
</template>
<script>
import api from '@/common/api';
import { responseData, errorMessage } from '@/common/objective';
export default {
  name: 'SiteRank',
  data: () => ({ rows: [], total: 0, page: 1, keyword: '', loading: false, error: '', updatedAt: null, request: 0, historyRequest: 0, dialog: false, historyTitle: '', historyRows: [], historyError: '', historyLoading: false, tabs: [{ type: 'practice', label: '平时排名' }, { type: 'contest', label: '比赛排名' }] }),
  computed: { type() { return this.$route.params.type === 'contest' ? 'contest' : 'practice'; } },
  watch: { type() { this.page = 1; this.keyword = ''; this.dialog = false; this.historyRequest++; this.load(); } },
  created() { this.load(); },
  beforeDestroy() { this.request++; this.historyRequest++; },
  methods: {
    signed(value) { return value > 0 ? '+' + value : String(value); },
    date(value) { return new Date(value).toLocaleString(); },
    search() { this.page = 1; this.load(); }, changePage(page) { this.page = page; this.load(); },
    async load() { const request = ++this.request; this.loading = true; this.error = ''; this.rows = []; try { const data = responseData(await api.getSiteRatingRank({ type: this.type, currentPage: this.page, searchUser: this.keyword, limit: 30 })); if (request === this.request) { this.rows = data.records; this.total = data.total; this.updatedAt = data.updatedAt; } } catch (e) { if (request === this.request) this.error = errorMessage(e); } finally { if (request === this.request) this.loading = false; } },
    async history(row) { const request = ++this.historyRequest; this.dialog = true; this.historyTitle = row.username; this.historyRows = []; this.historyError = ''; this.historyLoading = true; try { const data = responseData(await api.getSiteRatingProfile(row.uid, this.type)); if (request === this.historyRequest) this.historyRows = data.history; } catch (e) { if (request === this.historyRequest) this.historyError = errorMessage(e); } finally { if (request === this.historyRequest) this.historyLoading = false; } },
  },
};
</script>
<style scoped>
.site-rank { padding: 28px; color: var(--text); }.rank-heading, .rank-search, .rank-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; }.rank-heading h1 { font-size: 30px; margin: 0 0 10px; }.rank-heading p, small { color: var(--muted); }.rank-tabs { display: flex; gap: 28px; border-bottom: 1px solid var(--line); margin: 28px 0 22px; }.rank-tabs a { padding: 12px 2px; color: var(--muted); border-bottom: 3px solid transparent; }.rank-tabs a[aria-current] { color: var(--accent); border-color: var(--accent); font-weight: 700; }.rank-explanation { max-width: 80ch; line-height: 1.8; margin-bottom: 24px; }.rank-explanation h2 { font-size: 20px; margin: 0 0 6px; }.rank-explanation p { margin: 0 0 8px; }.rank-search { justify-content: flex-start; margin-bottom: 22px; }.rank-search .el-input { max-width: 380px; }.rating-number { color: var(--accent); font-size: 19px; font-variant-numeric: tabular-nums; }.rating-table small { display: block; margin-top: 5px; }.gain { color: #128354; }.loss { color: #c84b48; }.rank-footer { margin-top: 20px; flex-wrap: wrap; }a:focus-visible { outline: 2px solid var(--accent); outline-offset: 4px; }
@media(max-width:600px) { .site-rank { padding: 18px 12px; }.rank-heading { align-items: flex-start; }.rank-heading h1 { font-size: 25px; }.rank-heading > a { white-space: nowrap; font-size: 13px; }.rank-footer { justify-content: center; } }
</style>
