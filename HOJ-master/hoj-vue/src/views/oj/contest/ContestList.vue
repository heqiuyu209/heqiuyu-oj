<template>
  <div class="collection-page contest-page">
    <header class="collection-heading"><div><h1>{{ $t('m.NavBar_Contest') }}</h1><p>关注赛程，走进赛场，赛后继续补题与复盘。</p></div><span class="collection-count">{{ total }} 场比赛</span></header>
    <el-card class="collection-filters" shadow="never">
      <div class="collection-search"><span>{{ $t('m.Contest_Rule') }}</span>
        <div class="filter-chips">
          <button class="filter-chip" :aria-pressed="query.type === ''" @click="onRuleChange('')">{{ $t('m.All') }}</button>
          <button class="filter-chip" :aria-pressed="query.type === 0" @click="onRuleChange(0)">ACM</button>
          <button class="filter-chip" :aria-pressed="query.type === 1" @click="onRuleChange(1)">OI</button>
        </div>
        <vxe-input v-model="query.keyword" :placeholder="$t('m.Enter_keyword')" type="search" size="medium" @keyup.enter.native="onKeywordChange" @search-click="onKeywordChange" />
      </div>
      <section class="collection-filter-row"><b>{{ $t('m.Status') }}</b><div class="filter-chips">
        <button class="filter-chip" :aria-pressed="query.status === ''" @click="onStatusChange('')">{{ $t('m.All') }}</button>
        <button v-for="(status, key) in CONTEST_STATUS_REVERSE" :key="key" class="filter-chip" :aria-pressed="String(query.status) === key" @click="onStatusChange(key)">{{ $t('m.' + status.name) }}</button>
      </div></section>
    </el-card>
    <section class="collection-results" v-loading="loading" :aria-busy="loading" aria-label="比赛列表">
      <ListFeedback :error="loadError" :loading="loading" :empty="!contests.length" @retry="getContestList" />
      <div v-if="!loadError" class="collection-grid">
        <article v-for="contest in contests" :key="contest.id" class="collection-card contest-collection-card" :class="{ 'is-running': contest.status === 0 }">
          <div class="collection-card-top">
            <span class="contest-state" :style="{ '--contest-state': CONTEST_STATUS_REVERSE[contest.status].color }"><i aria-hidden="true" />{{ $t('m.' + CONTEST_STATUS_REVERSE[contest.status].name) }}</span>
            <el-tooltip :disabled="contest.type == 0" :content="contest.oiRankScoreType === 'Recent' ? $t('m.Based_on_The_Recent_Score_Submitted_Of_Each_Problem') : $t('m.Based_on_The_Highest_Score_Submitted_For_Each_Problem')">
              <button class="collection-tag" @click="onRuleChange(contest.type)">{{ parseContestType(contest.type) }}</button>
            </el-tooltip>
          </div>
          <h2><button class="collection-title" :title="contest.title" @click="toContest(contest)">{{ contest.title }}</button></h2>
          <div class="contest-schedule"><p><i class="el-icon-date" aria-hidden="true" />{{ contest.startTime | localtime }}</p><p><i class="el-icon-time" aria-hidden="true" />{{ getDuration(contest.startTime, contest.endTime) }}<span v-if="contest.count != null"><i class="el-icon-user" aria-hidden="true" />{{ contest.count }} 人参与</span></p></div>
          <div class="collection-meta">
            <el-tooltip :content="$t('m.' + CONTEST_TYPE_REVERSE[contest.auth].tips)"><el-tag size="small" :type="CONTEST_TYPE_REVERSE[contest.auth].color" effect="plain"><i v-if="contest.auth" class="el-icon-lock" />{{ $t('m.' + CONTEST_TYPE_REVERSE[contest.auth].name) }}</el-tag></el-tooltip>
            <el-tooltip v-if="contest.openRank" :content="$t('m.Contest_Outside_ScoreBoard')"><el-button class="contest-scoreboard" size="mini" icon="el-icon-data-analysis" :disabled="contest.status == CONTEST_STATUS.SCHEDULED" @click="toContestOutsideScoreBoard(contest.id, contest.type)">{{ $t('m.Contest_Rank') }}</el-button></el-tooltip>
          </div>
          <footer class="collection-card-footer"><span class="collection-hint">{{ parseContestType(contest.type) }} 赛制</span><el-button size="small" :type="contest.status === 0 ? 'primary' : 'default'" @click="toContest(contest)">{{ contest.status === 0 ? '进入比赛' : contest.status === 1 ? '查看与补题' : '查看比赛' }}</el-button></footer>
        </article>
      </div>
    </section>
    <Pagination :total="total" :pageSize="limit" @on-change="onCurrentPageChange" :current.sync="currentPage" />
  </div>
</template>
<script>
import api from '@/common/api';
import ListFeedback from '@/components/oj/common/ListFeedback';
import { mapGetters } from 'vuex';
import utils from '@/common/utils';
import time from '@/common/time';
import {
  CONTEST_STATUS_REVERSE,
  CONTEST_TYPE_REVERSE,
  CONTEST_STATUS,
} from '@/common/constants';
import myMessage from '@/common/message';
const Pagination = () => import('@/components/oj/common/Pagination');
// const ContestListAttention = () => import('@/components/oj/contest/ContestListAttention');
const limit = 10;

export default {
  name: 'contest-list',
  components: {
    Pagination,
    ListFeedback,
    // ContestListAttention
  },
  data() {
    return {
      currentPage: 1,
      query: {
        status: '',
        keyword: '',
        type: '',
      },
      limit: limit,
      total: 0,
      rows: '',
      contests: [],
      CONTEST_STATUS_REVERSE: {},
      CONTEST_STATUS: {},
      CONTEST_TYPE_REVERSE: {},
      loading: true,
      loadError: false,
    };
  },
  created() {
    let route = this.$route.query;
    this.currentPage = parseInt(route.currentPage) || 1;
  },
  mounted() {
    this.CONTEST_STATUS_REVERSE = Object.assign({}, CONTEST_STATUS_REVERSE);
    this.CONTEST_TYPE_REVERSE = Object.assign({}, CONTEST_TYPE_REVERSE);
    this.CONTEST_STATUS = Object.assign({}, CONTEST_STATUS);
    this.init();
  },
  methods: {
    init() {
      let route = this.$route.query;
      this.query.status = route.status || '';
      if(route.type === 0 || route.type === '0'){
        this.query.type = 0;
      }else if(route.type === 1 || route.type === '1'){
        this.query.type = 1;
      }else{
        this.query.type = '';
      }
      this.query.keyword = route.keyword || '';
      this.currentPage = parseInt(route.currentPage) || 1;
      this.getContestList();
    },
    getContestList() {
      this.loading = true;
      this.loadError = false;
      api.getContestList(this.currentPage, this.limit, this.query).then(
        (res) => {
          this.contests = res.data.data.records;
          this.total = res.data.data.total;
          this.loading = false;
        },
        (err) => {
          this.loadError = true;
          this.loading = false;
        }
      );
    },

    filterByChange() {
      let query = Object.assign({}, this.query);
      query.currentPage = this.currentPage;
      this.$router.push({
        name: 'ContestList',
        query: utils.filterEmptyValue(query),
      });
    },

    parseContestType(type) {
      if (type == 0) {
        return 'ACM';
      } else if (type == 1) {
        return 'OI';
      }
    },

    onCurrentPageChange(page) {
      this.currentPage = page;
      this.filterByChange();
    },
    onRuleChange(rule) {
      this.query.type = rule;
      this.currentPage = 1;
      this.filterByChange();
    },
    onStatusChange(status) {
      this.query.status = status;
      this.currentPage = 1;
      this.filterByChange();
    },
    onKeywordChange() {
      this.currentPage = 1;
      this.filterByChange();
    },
    toContest(contest) {
      if (!this.isAuthenticated) {
        myMessage.warning(this.$i18n.t('m.Please_login_first'));
        this.$store.dispatch('changeModalStatus', { visible: true });
      } else {
        this.$router.push({
          name: 'ContestDetails',
          params: { contestID: contest.id },
        });
      }
    },
    toContestOutsideScoreBoard(cid, type) {
      if (type == 0) {
        this.$router.push({
          name: 'ACMScoreBoard',
          params: { contestID: cid },
        });
      } else if (type == 1) {
        this.$router.push({
          name: 'OIScoreBoard',
          params: { contestID: cid },
        });
      }
    },
    getDuration(startTime, endTime) {
      return time.formatSpecificDuration(startTime, endTime);
    },

  },
  computed: {
    ...mapGetters(['isAuthenticated', 'userInfo']),
  },
  watch: {
    $route(newVal, oldVal) {
      if (newVal !== oldVal) {
        this.init();
      }
    },
  },
};
</script>
