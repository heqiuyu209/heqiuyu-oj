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
      <el-collapse-item name="CFBind">
        <template slot="title">
          <i class="fa fa-codeforces"> Codeforces 账号绑定</i>
        </template>
        <component :is="CFBind"></component>
      </el-collapse-item>
    </el-collapse>
  </el-card>
</template>
<script>
const Account = () => import('@/components/oj/setting/Account');
const UserInfo = () => import('@/components/oj/setting/UserInfo');
const CFBind = () => import('@/components/oj/common/CFBind');
export default {
  components: {
    Account,
    UserInfo,
    CFBind,
  },
  data() {
    return {
      Account: 'Account',
      UserInfo: 'UserInfo',
      CFBind: 'CFBind',
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
