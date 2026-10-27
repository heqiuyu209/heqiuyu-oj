<template>
  <div>
    <el-card>
      <div slot="header">
        <span class="panel-title home-title">{{ $t('m.Admin_Category') }}</span>
        <div class="filter">
          <span>
            <el-button
              type="primary"
              size="small"
              @click="openCategoryDialog('add', null)"
              icon="el-icon-plus"
              >{{ $t('m.Add_Category') }}
            </el-button>
          </span>
        </div>
      </div>

      <p class="catalog-help">训练分类与题目共用 XCPC 常用知识结构。预置分类固定保留，自定义分类可继续新增和编辑。</p>
      <el-alert v-if="catalogError" :title="catalogError" type="error" :closable="false"><el-button type="text" @click="loadCatalog">重试加载目录</el-button></el-alert>
      <section v-for="group in presetGroups" :key="group.code" class="preset-group">
        <h3>{{ group.name }} <small>预置</small></h3>
        <el-tag v-for="topic in group.topics" :key="topic.code" effect="plain" class="preset-category">{{ topic.name }}</el-tag>
      </section>
      <h3 class="custom-heading">{{ catalogReady ? '自定义分类' : '已有分类（目录待加载）' }}</h3>
      <el-tag
        :key="index"
        v-for="(category, index) in customCategories"
        :closable="catalogReady"
        :color="category.color ? category.color : '#409eff'"
        effect="dark"
        :disable-transitions="false"
        @close="deleteCategory(category)"
        @click="openCategoryDialog('update', category)"
        class="category"
      >
        {{ category.name }}
      </el-tag>

      <el-button
        class="button-new-category"
        size="small"
        @click="openCategoryDialog('add', null)"
        >+ New Category</el-button
      >
    </el-card>

    <el-dialog
      :title="$t('m.' + upsertTitle)"
      width="350px"
      :visible.sync="addCategoryDialogVisible"
      :close-on-click-modal="false"
    >
      <el-form>
        <el-form-item :label="$t('m.Category_Name')" required>
          <el-input v-model="category.name" size="small"></el-input>
        </el-form-item>
        <el-form-item :label="$t('m.Category_Color')" required>
          <el-color-picker v-model="category.color"></el-color-picker>
        </el-form-item>
        <el-form-item style="text-align:center">
          <el-button
            type="primary"
            @click="upsertCategory"
            :loading="upsertCategoryLoading"
            >{{ $t('m.' + upsertCategoryBtn) }}
          </el-button>
        </el-form-item>
      </el-form>
    </el-dialog>
  </div>
</template>
<script>
import myMessage from '@/common/message';
import api from '@/common/api';
export default {
  name: 'TrainingCategory',
  computed: {
    customCategories() { return this.categoryList.filter(category => !this.presetTopics.some(topic => topic.categoryId === category.id)); },
    presetGroups() {
      const groups = new Map();
      for (const topic of this.presetTopics) {
        if (!groups.has(topic.groupCode)) groups.set(topic.groupCode, { code: topic.groupCode, name: topic.groupName, topics: [] });
        groups.get(topic.groupCode).topics.push(topic);
      }
      return Array.from(groups.values());
    },
  },
  data() {
    return {
      getCategoryListLoading: false,
      categoryList: [],
      presetTopics: [], catalogReady: false, catalogError: '',
      addCategoryDialogVisible: false,
      upsertTitle: 'Add_Category',
      upsertCategoryBtn: 'To_Add',
      upsertCategoryLoading: false,
      category: {
        id: null,
        name: null,
        color: null,
      },
    };
  },
  mounted() {
    this.getTrainingCategoryList();
    this.loadCatalog();
  },
  methods: {
    async loadCatalog() {
      this.catalogReady = false; this.catalogError = '';
      try { this.presetTopics = (await api.learningTopics()).data.data.topics; this.catalogReady = true; }
      catch (_) { this.catalogError = '知识点目录加载失败，暂时不能修改已有分类。'; }
    },
    getTrainingCategoryList() {
      this.getCategoryListLoading = true;
      api.getTrainingCategoryList().then(
        (res) => {
          this.categoryList = res.data.data;
          this.getCategoryListLoading = false;
        },
        (err) => {
          this.getCategoryListLoading = false;
        }
      );
    },

    deleteCategory(category) {
      if (!this.catalogReady || this.presetTopics.some(topic => topic.categoryId === category.id)) return;
      this.$confirm(this.$i18n.t('m.Delete_Category_Tips'), 'Tips', {
        type: 'warning',
      }).then(
        () => {
          api
            .admin_deleteCategory(category.id)
            .then((res) => {
              myMessage.success(this.$i18n.t('m.Delete_successfully'));
              this.categoryList.splice(this.categoryList.indexOf(category), 1);
            })
            .catch(() => {});
        },
        () => {}
      );
    },
    openCategoryDialog(action, category) {
      if (action !== 'add' && (!this.catalogReady || this.presetTopics.some(topic => topic.categoryId === category.id))) return;
      if (action == 'add') {
        this.upsertTitle = 'Add_Category';
        this.upsertCategoryBtn = 'To_Add';
        this.category = {
          id: null,
          name: null,
          color: '#409eff',
        };
      } else {
        this.upsertTitle = 'Update_Category';
        this.upsertCategoryBtn = 'To_Update';
        this.category = Object.assign({}, category);
      }
      this.addCategoryDialogVisible = true;
    },

    upsertCategory() {
      if (this.category.id) {
        this.upsertCategoryLoading = true;
        api.admin_updateCategory(this.category).then(
          (res) => {
            this.upsertCategoryLoading = false;
            myMessage.success(this.$i18n.t('m.Update_Successfully'));
            this.categoryList.push(res.data.data);
            this.addCategoryDialogVisible = false;
            this.getTrainingCategoryList();
          },
          (err) => {
            this.upsertCategoryLoading = false;
          }
        );
      } else {
        this.upsertCategoryLoading = true;
        api.admin_addCategory(this.category).then(
          (res) => {
            this.upsertCategoryLoading = false;
            myMessage.success(this.$i18n.t('m.Add_Successfully'));
            this.categoryList.push(res.data.data);
            this.addCategoryDialogVisible = false;
          },
          (err) => {
            this.upsertCategoryLoading = false;
          }
        );
      }
    },
  },
};
</script>
<style scoped>
.catalog-help { color: var(--muted); line-height: 1.8; }.preset-group { margin-top: 24px; }.preset-group h3, .custom-heading { font-size: 16px; margin-bottom: 8px; color: var(--text); }.preset-group small { margin-left: 8px; color: var(--muted); font-size: 12px; font-weight: normal; }.preset-category { cursor: default; }.custom-heading { border-top: 1px solid var(--line); padding-top: 24px; margin-top: 32px; }
.filter {
  margin-top: 10px;
}
.filter span {
  margin-right: 10px;
}
.el-tag {
  margin-left: 10px;
  margin-top: 10px;
}
.category {
  cursor: pointer;
}
.button-new-category {
  margin-left: 10px;
  height: 32px;
  line-height: 30px;
  padding-top: 0;
  padding-bottom: 0;
  margin-top: 10px;
}
</style>
