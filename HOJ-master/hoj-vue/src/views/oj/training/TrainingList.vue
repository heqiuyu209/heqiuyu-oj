<template>
  <div class="collection-page training-page">
    <header class="collection-heading">
      <div><h1>{{ $t('m.NavBar_Training') }}</h1><p>按知识点积累，在每一份题集中稳步推进。</p></div>
      <span class="collection-count">{{ total }} 份训练</span>
    </header>
    <el-card class="collection-filters" shadow="never">
      <div class="collection-search">
        <span>{{ $t('m.Search_Training') }}</span>
        <vxe-input v-model="query.keyword" :placeholder="$t('m.Enter_keyword')" type="search" size="medium"
          @keyup.enter.native="filterByKeyword" @search-click="filterByKeyword" />
      </div>
      <section class="collection-filter-row">
        <b>{{ $t('m.Training_Auth') }}</b>
        <div class="filter-chips">
          <button class="filter-chip" :aria-pressed="!query.auth" @click="filterByAuthType(null)">{{ $t('m.All') }}</button>
          <button v-for="item in TRAINING_TYPE" :key="item.name" class="filter-chip" :aria-pressed="query.auth === item.name"
            @click="filterByAuthType(item.name)">{{ $t('m.Training_' + item.name) }}</button>
        </div>
      </section>
      <section class="collection-filter-row">
        <b>{{ $t('m.Training_Category') }}</b>
        <div class="filter-chips">
          <button class="filter-chip" :aria-pressed="!query.categoryId" @click="filterByCategory(null)">{{ $t('m.All') }}</button>
          <button v-for="category in categoryList" :key="category.id" class="filter-chip" :aria-pressed="query.categoryId == category.id"
            @click="filterByCategory(category.id)">{{ category.name }}</button>
        </div>
      </section>
    </el-card>
    <section class="collection-results" v-loading="loading" :aria-busy="loading" aria-label="训练列表">
      <ListFeedback :error="loadError" :loading="loading" :empty="!trainingList.length" @retry="getTrainingList" />
      <div v-if="!loadError" class="collection-grid">
        <article v-spotlight v-for="training in trainingList" :key="training.id" class="collection-card training-card">
          <div class="collection-card-top">
            <span class="collection-symbol"><i class="el-icon-reading" aria-hidden="true" /></span>
            <el-tag size="small" :type="(TRAINING_TYPE[training.auth] || {}).color" effect="plain">{{ $t('m.Training_' + training.auth) }}</el-tag>
          </div>
          <h2><button class="collection-title" :title="training.title" @click="toTraining(training.id)">{{ training.title }}</button></h2>
          <div class="collection-meta"><span v-if="training.categoryName" class="collection-tag">{{ training.categoryName }}</span><span>{{ training.problemCount }} 道题</span><span v-if="training.rank != null">{{ $t('m.Number') }} {{ training.rank }}</span></div>
          <div v-if="isAuthenticated" class="training-progress">
            <div><span>{{ $t('m.Progress') }}</span><span>{{ training.acCount || 0 }} / {{ training.problemCount }}</span></div>
            <el-progress :percentage="getPassingRate(training.acCount, training.problemCount)" :show-text="false" :stroke-width="5" />
          </div>
          <p v-else class="collection-hint">登录后记录你的训练进度</p>
          <footer class="collection-card-footer">
            <div class="collection-author"><button class="collection-text-link" @click="goUserHome(training.author)">{{ training.author }}</button><small v-if="training.gmtModified" :title="training.gmtModified | localtime">{{ training.gmtModified | fromNow }}</small></div>
            <el-button size="small" @click="toTraining(training.id)">进入训练</el-button>
          </footer>
        </article>
      </div>
    </section>
    <Pagination :total="total" :pageSize="limit" @on-change="filterByPage" :current.sync="currentPage" />
  </div>
</template>
<script>
import api from '@/common/api';
import ListFeedback from '@/components/oj/common/ListFeedback';
import utils from '@/common/utils';
import { TRAINING_TYPE } from '@/common/constants';
import myMessage from '@/common/message';
import { mapGetters } from 'vuex';
const Pagination = () => import('@/components/oj/common/Pagination');
export default {
  name: 'TrainingList',
  components: {
    Pagination,
    ListFeedback,
  },
  data() {
    return {
      query: {
        keyword: '',
        categoryId: null,
        auth: null,
      },
      total: 0,
      currentPage: 1,
      limit: 15,
      categoryList: [],
      trainingList: [],
      TRAINING_TYPE: {},
      loading: false,
      loadError: false,
    };
  },
  created() {
    let route = this.$route.query;
    this.currentPage = parseInt(route.currentPage) || 1;
    this.TRAINING_TYPE = Object.assign({}, TRAINING_TYPE);
    this.getTrainingCategoryList();
  },
  mounted() {
    this.init();
  },
  methods: {
    init() {
      let route = this.$route.query;
      this.query.keyword = route.keyword || '';
      this.query.categoryId = route.categoryId || null;
      this.query.auth = route.auth || null;
      this.currentPage = parseInt(route.currentPage) || 1;
      this.getTrainingList();
    },

    filterByPage(page) {
      this.currentPage = page;
      this.filterByChange();
    },

    filterByCategory(categoryId) {
      this.query.categoryId = categoryId;
      this.currentPage = 1;
      this.filterByChange();
    },

    filterByAuthType(auth) {
      this.query.auth = auth;
      this.currentPage = 1;
      this.filterByChange();
    },

    filterByKeyword() {
      this.currentPage = 1;
      this.filterByChange();
    },

    filterByChange() {
      let query = Object.assign({}, this.query);
      query.currentPage = this.currentPage;
      this.$router.push({
        path: '/training',
        query: utils.filterEmptyValue(query),
      });
    },
    getTrainingList() {
      this.loading = true;
      this.loadError = false;
      let query = Object.assign({}, this.query);
      api.getTrainingList(this.currentPage, this.limit, query).then(
        (res) => {
          this.trainingList = res.data.data.records;
          this.total = res.data.data.total;
          this.loading = false;
        },
        (err) => {
          this.loadError = true;
          this.loading = false;
        }
      );
    },
    getTrainingCategoryList() {
      api.getTrainingCategoryList().then((res) => {
        this.categoryList = res.data.data;
      });
    },

    toTraining(trainingID) {
      if (!this.isAuthenticated) {
        myMessage.warning(this.$i18n.t('m.Please_login_first'));
        this.$store.dispatch('changeModalStatus', { visible: true });
      } else {
        this.$router.push({
          name: 'TrainingDetails',
          params: { trainingID: trainingID },
        });
      }
    },
    goUserHome(username) {
      this.$router.push({
        path: '/user-home',
        query: { username },
      });
    },

    getPassingRate(ac, total) {
      if (!total) {
        return 0;
      }
      return Math.min(100, Math.max(0, Math.round((Number(ac) || 0) / total * 100)));
    },
  },
  computed: {
    ...mapGetters(['isAuthenticated']),
  },
  watch: {
    $route(newVal, oldVal) {
      if (newVal !== oldVal) {
        this.init();
      }
    },
    isAuthenticated(newVal, oldVal){
      this.init();
    }
  },
};
</script>
