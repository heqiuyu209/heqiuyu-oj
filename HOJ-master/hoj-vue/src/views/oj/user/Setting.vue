<template>
  <el-card class="box-card">
    <el-collapse v-model="activeName" accordion>
      <el-collapse-item name="Account">
        <template slot="title">
          <i class="fa fa-gear"> {{ $t('m.Account_Setting') }}</i>
        </template>
        <component :is="Account"></component>
      </el-collapse-item>
      <el-collapse-item name="UserInfo">
        <template slot="title">
          <i class="fa fa-gear"> {{ $t('m.UserInfo_Setting') }}</i>
        </template>
        <component :is="UserInfo"></component>
      </el-collapse-item>
      <el-collapse-item name="Platforms"><template slot="title"><i class="el-icon-connection" /> 跨平台账号关联</template><ExternalAccounts v-if="activeName === 'Platforms' && currentUid" :uid="currentUid" /></el-collapse-item>
      <el-collapse-item name="Capture">
        <template slot="title"><i class="el-icon-collection" /> 浏览器题面采集</template>
        <CaptureConnection v-if="activeName === 'Capture' && currentUid" :key="currentUid + ':' + $store.getters.authEpoch" />
      </el-collapse-item>
    </el-collapse>
  </el-card>
</template>
<script>
import ExternalAccounts from '@/components/oj/learning/ExternalAccounts';
import CaptureConnection from '@/components/oj/setting/CaptureConnection';
const Account = () => import('@/components/oj/setting/Account');
const UserInfo = () => import('@/components/oj/setting/UserInfo');
export default {
  components: {
    CaptureConnection,
    ExternalAccounts,
    Account,
    UserInfo,
  },
  computed: {
    currentUid() {
      return this.$store.getters.isAuthenticated ? this.$store.getters.userInfo.uid || '' : '';
    },
  },
  data() {
    return {
      Account: 'Account',
      UserInfo: 'UserInfo',
      activeName: 'Account',
    };
  },
};
</script>
<style scoped>
@media screen and (min-width: 1200px) {
  .box-card {
    margin-left: 10%;
    margin-right: 10%;
  }
}
/deep/ .el-collapse-item__header {
  border-radius: 4px;
  font-size: 18px;
  color: #409eff;
}
</style>
