<template>
  <div class="collection-page group-page">
    <header class="collection-heading">
      <div><h1>{{ $t('m.NavBar_Group') }}</h1><p>一起训练、交流与参赛，在团队里共同成长。</p></div>
      <div class="collection-actions"><el-button size="small" :disabled="!isAuthenticated" @click="handleOnlyMine(!query.onlyMine)">{{ query.onlyMine ? $t('m.All_Group') : $t('m.My_Group') }}</el-button><el-button type="primary" icon="el-icon-plus" size="small" @click="toCreateGroup">{{ $t('m.Create_Group') }}</el-button></div>
    </header>
    <el-card class="collection-filters" shadow="never">
      <div class="collection-search"><span>{{ $t('m.Search_Group') }}</span><vxe-input v-model="query.keyword" :placeholder="$t('m.Enter_keyword')" type="search" size="medium" @keyup.enter.native="filterByKeyword" @search-click="filterByKeyword" /><span class="collection-count">{{ total }} 个团队</span></div>
      <section class="collection-filter-row"><b>{{ $t('m.Group_Auth') }}</b><div class="filter-chips">
        <button class="filter-chip" :aria-pressed="!query.auth" @click="filterByAuth(null)">{{ $t('m.All') }}</button>
        <button v-for="(auth, key) in GROUP_TYPE_REVERSE" :key="key" class="filter-chip" :aria-pressed="query.auth == key" @click="filterByAuth(key)">{{ $t('m.Group_' + auth.name) }}</button>
      </div></section>
    </el-card>
    <section class="collection-results" v-loading="loading" :aria-busy="loading" aria-label="团队列表">
      <ListFeedback :error="loadError" :loading="loading" :empty="!groupList.length" @retry="getGroupList" />
      <div v-if="!loadError" class="collection-grid">
        <article v-spotlight v-for="group in groupList" :key="group.id" class="collection-card group-collection-card">
          <div class="collection-card-top"><span class="team-avatar"><el-image v-if="group.avatar" :src="group.avatar" fit="cover" lazy><span slot="error">{{ group.shortName || group.name | firstLetters }}</span></el-image><span v-else>{{ group.shortName || group.name | firstLetters }}</span></span>
            <el-tooltip :content="$t('m.' + GROUP_TYPE_REVERSE[group.auth].tips)"><el-tag size="small" :type="GROUP_TYPE_REVERSE[group.auth].color" effect="plain">{{ $t('m.Group_' + GROUP_TYPE_REVERSE[group.auth].name) }}</el-tag></el-tooltip>
          </div>
          <h2><button class="collection-title" :title="group.name" @click="toGroup(group.id)">{{ group.name }}</button></h2>
          <p class="collection-description" :title="group.brief">{{ group.brief }}</p>
          <div class="collection-meta"><span><i class="el-icon-user" /> {{ group.memberCount }} 位成员</span><span v-if="!group.visible" class="collection-tag">{{ $t('m.Group_Hidden') }}</span><time :title="group.gmtCreate | localtime">{{ group.gmtCreate | localtime('YYYY-MM-DD') }}</time></div>
          <footer class="collection-card-footer"><button class="collection-text-link collection-owner" :title="group.owner" @click="toUserHome(group.owner)">{{ group.owner }}</button><el-button size="small" @click="toGroup(group.id)">进入团队</el-button></footer>
        </article>
      </div>
    </section>
    <Pagination :total="total" :page-size="query.limit" @on-change="currentChange" :current.sync="query.currentPage" @on-page-size-change="onPageSizeChange" :layout="'prev, pager, next, sizes'" :pageSizes="[9, 15, 30, 60, 120]" />
    <el-dialog
      :title="$t('m.Create_Group')"
      :visible.sync="showEditGroupDialog"
      :fullscreen="true"
      @open="onOpenEditDialog"
    >
      <el-form label-position="top" :model="group" :rules="rules" ref="group">
        <el-row :gutter="20">
          <el-col :md="12" :xs="24">
            <el-form-item :label="$t('m.Group_Name')" required prop="name">
              <el-input
                v-model="group.name"
                :placeholder="$t('m.Group_Name')"
                class="title-input"
                minlength="5"
                maxlength="25"
                show-word-limit
              >
              </el-input>
            </el-form-item>
          </el-col>
          <el-col :md="12" :xs="24">
            <el-form-item
              :label="$t('m.Group_Short_Name')"
              required
              prop="shortName"
            >
              <el-input
                v-model="group.shortName"
                :placeholder="$t('m.Group_Short_Name')"
                class="title-input"
                minlength="5"
                maxlength="10"
                show-word-limit
              >
              </el-input>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item :label="$t('m.Group_Brief')" required prop="brief">
              <el-input
                v-model="group.brief"
                :placeholder="$t('m.Group_Brief')"
                class="title-input"
                minlength="5"
                maxlength="50"
                show-word-limit
              >
              </el-input>
            </el-form-item>
          </el-col>
          <el-col :md="8" :xs="24">
            <el-form-item :label="$t('m.Group_Auth')" required prop="auth">
              <el-select v-model="group.auth">
                <el-tooltip
                  :content="$t('m.Group_Public_Tips')"
                  placement="right"
                >
                  <el-option
                    :label="$t('m.Group_Public')"
                    :value="1"
                  ></el-option>
                </el-tooltip>
                <el-tooltip
                  :content="$t('m.Group_Protected_Tips')"
                  placement="right"
                >
                  <el-option
                    :label="$t('m.Group_Protected')"
                    :value="2"
                  ></el-option>
                </el-tooltip>
                <el-tooltip
                  :content="$t('m.Group_Private_Tips')"
                  placement="right"
                >
                  <el-option
                    :label="$t('m.Group_Private')"
                    :value="3"
                  ></el-option>
                </el-tooltip>
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :md="8" :xs="24" v-if="group.auth != 1">
            <el-form-item :label="$t('m.Group_Code')" required prop="code">
              <el-input
                v-model="group.code"
                :placeholder="$t('m.Group_Code')"
                class="title-input"
                minlength="6"
                maxlength="6"
                show-word-limit
              >
              </el-input>
            </el-form-item>
          </el-col>
          <el-col :md="8" :xs="24">
            <el-form-item :label="$t('m.Visible')" required prop="visible">
              <el-switch
                v-model="group.visible"
                :active-text="$t('m.Group_Visible')"
                :inactive-text="$t('m.Group_Not_Visible')"
              >
              </el-switch>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item
              :label="$t('m.Group_Description')"
              required
              prop="description"
            >
              <Editor :value.sync="group.description"></Editor>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <span slot="footer" class="dialog-footer">
        <el-button type="danger" @click.native="showEditGroupDialog = false">{{
          $t('m.Cancel')
        }}</el-button>
        <el-button type="primary" @click.native="submitGroup">{{
          $t('m.OK')
        }}</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { GROUP_TYPE_REVERSE } from '@/common/constants';
import api from '@/common/api';
import mMessage from '@/common/message';
import { mapGetters } from 'vuex';
import Pagination from '@/components/oj/common/Pagination';
import Editor from '@/components/admin/Editor';
import ListFeedback from '@/components/oj/common/ListFeedback';
export default {
  name: 'GroupList',
  components: {
    Pagination,
    Editor,
    ListFeedback,
  },
  filters: { firstLetters(value) { return String(value || '队').slice(0, 2); } },
  data() {
    return {
      showEditGroupDialog: false,
      query: {
        keyword: '',
        auth: 0,
        currentPage:1,
        limit:15,
        onlyMine: false,
      },
      total: 0,
      group: {
        avatar: '',
        name: '',
        shortName: '',
        brief: '',
        description: '',
        owner: '',
        auth: 1,
        visible: true,
      },
      rules: {
        name: [
          {
            required: true,
            message: this.$i18n.t('m.Group_Name_Check_Required'),
            trigger: 'blur',
          },
          {
            min: 5,
            max: 25,
            message: this.$i18n.t('m.Group_Name_Check_Min_Max'),
            trigger: 'blur',
          },
        ],
        shortName: [
          {
            required: true,
            message: this.$i18n.t('m.Group_Short_Name_Check_Required'),
            trigger: 'blur',
          },
          {
            min: 5,
            max: 10,
            message: this.$i18n.t('m.Group_Short_Name_Check_Min_Max'),
            trigger: 'blur',
          },
        ],
        brief: [
          {
            required: true,
            message: this.$i18n.t('m.Group_Brief_Check_Required'),
            trigger: 'blur',
          },
          {
            min: 5,
            max: 50,
            message: this.$i18n.t('m.Group_Brief_Check_Min_Max'),
            trigger: 'blur',
          },
        ],
        code: [
          {
            required: true,
            message: this.$i18n.t('m.Group_Code_Check_Required'),
            trigger: 'blur',
          },
          {
            min: 6,
            max: 6,
            message: this.$i18n.t('m.Group_Code_Check_Min_Max'),
            trigger: 'blur',
          },
        ],
        auth: [
          {
            required: true,
            message: this.$i18n.t('m.Group_Auth_Check_Required'),
            trigger: 'blur',
          },
        ],
        description: [
          {
            required: true,
            message: this.$i18n.t('m.Group_Description_Check_Required'),
            trigger: 'blur',
          },
          {
            min: 5,
            max: 1000,
            message: this.$i18n.t('m.Group_Description_Check_Min_Max'),
            trigger: 'blur',
          },
        ],
      },
      backupGroup: null,
      groupList: [],
      loading: false,
      loadError: false,
    };
  },
  mounted() {
    this.init();
  },
  created() {
    this.GROUP_TYPE_REVERSE = Object.assign({}, GROUP_TYPE_REVERSE);
  },
  methods: {
    init() {
      let route = this.$route.query;
      this.query.auth = route.auth;
      this.query.keyword = route.keyword || '';
      this.query.onlyMine = route.onlyMine + '' == 'true' ? true : false;
      this.query.currentPage = Math.max(1, parseInt(route.currentPage, 10) || 1);
      const limit = Number(route.limit);
      this.query.limit = [9, 15, 30, 60, 120].includes(limit) ? limit : 15;
      this.getGroupList();
    },
    onPageSizeChange(pageSize) {
      this.query.currentPage = 1;
      this.query.limit = pageSize;
      this.handleRouter();
    },
    currentChange(page) {
      this.query.currentPage = page;
      this.handleRouter();
    },
    filterByKeyword() {
      this.query.currentPage = 1;
      this.handleRouter();
    },
    filterByAuth(auth) {
      this.query.currentPage = 1;
      this.query.auth = auth;
      this.handleRouter();
    },
    handleOnlyMine(onlyMine) {
      this.query.currentPage = 1;
      this.query.onlyMine = onlyMine;
      this.handleRouter();
    },
    handleAuth(auth) {
      this.query.auth = auth;
      this.handleRouter();
    },
    handleRouter() {
      this.$router.push({
        path: this.$route.path,
        query: this.query,
      });
    },
    getGroupList() {
      this.loading = true;
      this.loadError = false;
      api.getGroupList(this.query.currentPage, this.query.limit, this.query).then(
        (res) => {
          this.groupList = res.data.data.records;
          this.total = res.data.data.total;
          this.loading = false;
        },
        (err) => {
          this.loadError = true;
          this.loading = false;
        }
      );
    },
    toGroup(groupID) {
      if (!this.isAuthenticated) {
        mMessage.warning(this.$i18n.t('m.Please_login_first'));
        this.$store.dispatch('changeModalStatus', { visible: true });
      } else {
        this.$router.push({
          name: 'GroupDetails',
          params: { groupID: groupID },
        });
      }
    },
    toUserHome(username) {
      this.$router.push({
        name: 'UserHome',
        query: { username: username },
      });
    },
    toCreateGroup() {
      if (!this.isAuthenticated) {
        mMessage.warning(this.$i18n.t('m.Please_login_first'));
        this.$store.dispatch('changeModalStatus', { visible: true });
      } else {
        if (this.backupGroup) {
          this.group = this.backupGroup;
        } else {
          this.group = {
            id: null,
            avatar: '',
            name: '',
            shortName: '',
            brief: '',
            description: '',
            owner: '',
            auth: 1,
            code: '',
            status: null,
            visible: true,
          };
        }
        this.showEditGroupDialog = true;
      }
    },
    onOpenEditDialog() {
      setTimeout(() => {
        if (document.createEvent) {
          let event = document.createEvent('HTMLEvents');
          event.initEvent('resize', true, true);
          window.dispatchEvent(event);
        } else if (document.createEventObject) {
          window.fireEvent('onresize');
        }
      }, 0);
    },
    submitGroup() {
      this.$refs['group'].validate((valid) => {
        if (valid) {
          let group = Object.assign({}, this.group);
          api.addGroup(group).then((res) => {
            mMessage.success(this.$i18n.t('m.Create_Successfully'));
            this.showEditGroupDialog = false;
            this.init();
          });
        }
      });
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
    group(newVal, oldVal) {
      if (newVal != oldVal) {
        this.backupGroup = this.group;
      }
    },
  },
};
</script>
