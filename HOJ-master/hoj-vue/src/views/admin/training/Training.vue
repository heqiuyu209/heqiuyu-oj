<template>
  <div class="view">
    <el-card v-loading="loading">
      <div slot="header">
        <span class="panel-title home-title">
          {{ title }}
        </span>
      </div>
      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false">
        <el-button type="text" @click="getTraining">重新加载</el-button>
      </el-alert>
      <el-form label-position="top" :disabled="loading || saving">
        <el-row :gutter="20">
          <el-col :span="24">
            <el-form-item :label="$t('m.Training_rank')" required>
              <el-input-number
                v-model="training.rank"
                @change="handleChange"
                :min="0"
                :max="2147483647"
                :label="$t('m.Training_rank')"
              ></el-input-number>
            </el-form-item>
          </el-col>

          <el-col :span="24">
            <el-form-item :label="$t('m.Training_Title')" required>
              <el-input
                v-model="training.title"
                :placeholder="$t('m.Training_Title')"
              ></el-input>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item :label="$t('m.Training_Description')" required>
              <Editor :value.sync="training.description"></Editor>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item :label="$t('m.Category')" required>
              <el-select v-model="trainingCategoryId">
                <el-option
                  :label="category.name"
                  :value="category.id"
                  v-for="(category, index) in trainingCategoryList"
                  :key="index"
                ></el-option>
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :md="8" :xs="24">
            <el-form-item :label="$t('m.Training_Auth')" required>
              <el-select v-model="training.auth">
                <el-option
                  :label="$t('m.Public_Training')"
                  value="Public"
                ></el-option>
                <el-option
                  :label="$t('m.Private_Training')"
                  value="Private"
                ></el-option>
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :md="8" :xs="24">
            <el-form-item
              :label="$t('m.Training_Password')"
              v-show="training.auth != 'Public'"
              :required="training.auth != 'Public'"
            >
              <el-input
                v-model="training.privatePwd"
                :placeholder="$t('m.Training_Password')"
              ></el-input>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <el-button type="primary" :loading="saving" :disabled="!ready || loading" @click.native="saveTraining">{{
        $t('m.Save')
      }}</el-button>
    </el-card>
  </div>
</template>

<script>
import api from '@/common/api';
import { mapGetters } from 'vuex';
import myMessage from '@/common/message';
const Editor = () => import('@/components/admin/Editor.vue');
const emptyTraining = () => ({
  rank: 1000,
  title: '',
  description: '',
  privatePwd: '',
  auth: 'Public',
});
export default {
  name: 'CreateTraining',
  components: {
    Editor,
  },
  data() {
    return {
      title: 'Create Training',
      training: emptyTraining(),
      trainingCategoryId: null,
      trainingCategoryList: [],
      loading: false,
      saving: false,
      ready: false,
      loadError: '',
      trainingRequest: 0,
      formActive: true,
    };
  },
  mounted() {
    this.init();
  },
  watch: {
    $route() {
      this.resetTraining();
      if (this.$route.name === 'admin-edit-training') {
        this.title = this.$i18n.t('m.Edit_Training');
        this.getTraining();
      } else {
        this.title = this.$i18n.t('m.Create_Training');
        this.ready = true;
      }
    },
  },
  beforeDestroy() {
    this.formActive = false;
    this.trainingRequest++;
  },
  computed: {
    ...mapGetters(['userInfo']),
  },
  methods: {
    resetTraining() {
      this.trainingRequest++;
      this.training = emptyTraining();
      this.trainingCategoryId = null;
      this.loading = false;
      this.saving = false;
      this.ready = false;
      this.loadError = '';
    },
    init() {
      const request = this.trainingRequest;
      api.getTrainingCategoryList().then((res) => {
        if (!this.formActive) return;
        let data = res.data.data;
        if (!data || !data.length) {
          this.$alert(
            this.$i18n.t('m.Redirect_To_Category'),
            this.$i18n.t('m.Redirect'),
            {
              confirmButtonText: this.$i18n.t('m.OK'),
              showClose: false,
              callback: (action) => {
                this.$router.push({
                  path: '/admin/training/category',
                });
              },
            }
          );
        } else {
          this.trainingCategoryList = data;
          if (request !== this.trainingRequest) return;
          if (this.$route.name === 'admin-edit-training') {
            this.title = this.$i18n.t('m.Edit_Training');
            this.getTraining();
          } else {
            this.title = this.$i18n.t('m.Create_Training');
            this.ready = true;
          }
        }
      });
    },

    getTraining() {
      if (this.$route.name !== 'admin-edit-training') return;
      this.resetTraining();
      const request = this.trainingRequest;
      const trainingId = String(this.$route.params.trainingId);
      this.loading = true;
      api
        .admin_getTraining(trainingId)
        .then((res) => {
          if (request !== this.trainingRequest || this.$route.name !== 'admin-edit-training' || String(this.$route.params.trainingId) !== trainingId) return;
          let data = res.data.data;
          if (!data.training || String(data.training.id) !== trainingId) throw new Error('Unexpected training');
          this.training = data.training;
          this.trainingCategoryId = data.trainingCategory ? data.trainingCategory.id : null;
          this.ready = true;
        })
        .catch(() => {
          if (request === this.trainingRequest) this.loadError = '训练信息加载失败，请重新加载后再保存。';
        })
        .finally(() => {
          if (request === this.trainingRequest) this.loading = false;
        });
    },

    saveTraining() {
      if (!this.ready || this.loading || this.saving) return;
      if (!this.training.rank && this.training.rank != 0) {
        myMessage.error(
          this.$i18n.t('m.Training_rank') + ' ' + this.$i18n.t('m.is_required')
        );
        return;
      }

      if (!this.training.title) {
        myMessage.error(
          this.$i18n.t('m.Training_Title') + ' ' + this.$i18n.t('m.is_required')
        );
        return;
      }
      if (!this.training.description) {
        myMessage.error(
          this.$i18n.t('m.Training_Description') +
            ' ' +
            this.$i18n.t('m.is_required')
        );
        return;
      }

      if (!this.trainingCategoryId) {
        myMessage.error(
          this.$i18n.t('m.Training_Category') +
            ' ' +
            this.$i18n.t('m.is_required')
        );
        return;
      }

      if (this.training.auth != 'Public' && !this.training.privatePwd) {
        myMessage.error(
          this.$i18n.t('m.Training_Password') +
            ' ' +
            this.$i18n.t('m.is_required')
        );
        return;
      }

      let funcName =
        this.$route.name === 'admin-edit-training'
          ? 'admin_editTraining'
          : 'admin_createTraining';

      let data = Object.assign({}, this.training);
      if (funcName === 'admin_createTraining') {
        data['author'] = this.userInfo.username;
      }
      let trainingDto = {
        training: data,
        trainingCategory: {
          id: this.trainingCategoryId,
        },
      };

      const request = this.trainingRequest;
      this.saving = true;
      api[funcName](trainingDto)
        .then((res) => {
          if (request !== this.trainingRequest) return;
          myMessage.success('success');
          this.$router.push({
            name: 'admin-training-list',
            query: { refresh: 'true' },
          });
        })
        .catch(() => {})
        .finally(() => {
          if (request === this.trainingRequest) this.saving = false;
        });
    },
  },
};
</script>
<style scoped>
.userPreview {
  padding-left: 10px;
  padding-top: 20px;
  padding-bottom: 20px;
  color: red;
  font-size: 16px;
  margin-bottom: 10px;
}
</style>
