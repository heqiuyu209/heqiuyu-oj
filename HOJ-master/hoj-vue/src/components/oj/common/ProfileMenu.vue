<template>
  <MorphPopover ref="popover" class="drop-menu profile-menu" label="个人菜单与界面设置" @visibility-change="onVisibility">
    <template slot="trigger" slot-scope="{ open, toggle }">
      <button class="el-dropdown-link nav-account" type="button" aria-label="打开个人菜单" aria-haspopup="dialog" :aria-expanded="String(open)" @click="toggle">
        <avatar :username="user.username" :inline="true" :size="30" color="#FFF" :src="avatar" class="drop-avatar" />
        <span class="nav-username mobile-username">{{ user.username }}</span><i class="el-icon-arrow-down" :class="{ 'profile-arrow-open': open }" />
      </button>
    </template>
    <header class="profile-heading"><avatar :username="user.username" :size="38" color="#FFF" :src="avatar" /><div><strong>{{ user.username }}</strong><small>CDUTETC · 成理工程</small></div><button class="profile-close" type="button" aria-label="关闭个人菜单" @click="$refs.popover.close(true)"><i class="el-icon-close" /></button></header>
    <div v-show="view === 'menu'" class="profile-links">
      <button data-popover-focus class="el-dropdown-menu__item" type="button" @click="command('/user-home')"><i class="el-icon-user" />{{ $t('m.NavBar_UserHome') }}</button>
      <button class="el-dropdown-menu__item" type="button" @click="command('/setting')"><i class="el-icon-setting" />{{ $t('m.NavBar_Setting') }}</button>
      <button class="el-dropdown-menu__item" type="button" @click="command('/status?onlyMine=true')"><i class="el-icon-document" />{{ $t('m.NavBar_Submissions') }}</button>
      <button v-if="isAdmin" class="el-dropdown-menu__item" type="button" @click="command('/admin')"><i class="el-icon-s-tools" />{{ $t('m.NavBar_Management') }}</button>
      <button class="el-dropdown-menu__item profile-appearance-link" type="button" @click="showSettings"><i class="el-icon-magic-stick" />主题与布局<i class="el-icon-arrow-right" /></button>
      <button class="el-dropdown-menu__item profile-logout" type="button" @click="command('/logout')"><i class="el-icon-switch-button" />{{ $t('m.NavBar_Logout') }}</button>
    </div>
    <div v-show="view === 'appearance'" class="profile-appearance">
      <button ref="back" class="profile-back" type="button" @click="view = 'menu'"><i class="el-icon-arrow-left" />返回个人菜单</button>
      <AppearancePanel />
    </div>
  </MorphPopover>
</template>
<script>
import Avatar from 'vue-avatar';
import MorphPopover from './MorphPopover';
import AppearancePanel from './AppearancePanel';
export default {
  name: 'ProfileMenu',
  components: { Avatar, MorphPopover, AppearancePanel },
  props: { user: { type: Object, required: true }, avatar: String, isAdmin: Boolean },
  data: () => ({ view: 'menu' }),
  watch: { '$route'() { this.$refs.popover.close(); } },
  methods: {
    onVisibility(open) { if (open) this.view = 'menu'; this.$emit('visibility-change', open); },
    command(route) { this.$refs.popover.close(); this.$emit('command', route); },
    showSettings() { this.view = 'appearance'; this.$nextTick(() => this.$refs.back.focus()); },
  },
};
</script>
<style scoped>
.profile-heading { display: flex; align-items: center; gap: 12px; padding: 2px 0 16px; margin-bottom: 8px; border-bottom: 1px solid var(--line); }
.profile-heading > div { min-width: 0; }
.profile-heading strong { display: block; overflow: hidden; text-overflow: ellipsis; max-width: 210px; }
.profile-heading small { display: block; margin-top: 5px; color: var(--muted); font-size: 11px; }
.profile-close { margin-left: auto; padding: 6px; border: 0; border-radius: 50%; color: var(--muted); background: transparent; cursor: pointer; }
.profile-links > button { display: flex; align-items: center; gap: 10px; width: 100%; padding: 8px 12px; border: 0; border-radius: 10px; color: var(--text); background: transparent; text-align: left; cursor: pointer; font-size: 14px; line-height: 28px; }
.profile-links > button:hover, .profile-close:hover { background: var(--soft); color: var(--accent); }
.profile-appearance-link .el-icon-arrow-right { margin-left: auto; }
.profile-links .profile-appearance-link { margin-top: 6px; background: var(--soft); }
.profile-links .profile-logout { margin-top: 8px; border-top: 1px solid var(--line); border-radius: 0 0 10px 10px; }
.profile-back { display: flex; gap: 8px; align-items: center; padding: 8px 0 16px; color: var(--muted); border: 0; background: none; cursor: pointer; }
.nav-account > .el-icon-arrow-down { transition: transform 220ms ease; }
.profile-arrow-open { transform: rotate(180deg); }
</style>
