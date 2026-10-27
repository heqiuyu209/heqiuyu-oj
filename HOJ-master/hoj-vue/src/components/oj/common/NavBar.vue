<template>
  <div>
    <template v-if="!mobileNar">
      <div id="header" aria-label="CDUTETC 导航栏" :class="['nav-' + runtime.navigation, { 'nav-scrolled': scrolled, 'nav-condensed': scrolled && !navEngaged }]" @mouseenter="onNavEnter" @mouseleave="onNavLeave" @focusin="navFocused = true" @focusout="onNavFocusOut">
        <div class="nav-shell">
          <router-link class="logo" to="/home" aria-label="CDUTETC · 成理工程在线测评平台首页"><BrandLogo compact /></router-link>
          <el-menu
          class="nav-links"
          aria-label="主导航"
          :default-active="activeMenuName"
          mode="horizontal"
          router
          active-text-color="var(--nav-menu-accent)"
          text-color="var(--text)"
          @open="onMenuOpen"
          @close="onMenuClose"
        >
          <template>
            <el-menu-item index="/home"
              ><i class="el-icon-s-home"></i>{{ $t('m.NavBar_Home') }}</el-menu-item
            >
            <el-menu-item index="/problem"
              ><i class="el-icon-s-grid"></i
              >{{ $t('m.NavBar_Problem') }}</el-menu-item
            >
            <el-menu-item index="/training"
              ><i class="el-icon-s-claim"></i
              >{{ $t('m.NavBar_Training') }}</el-menu-item
            >
            <el-menu-item index="/knowledge"><i class="el-icon-reading"></i>{{ $t('m.Knowledge') }}</el-menu-item>
            <el-menu-item index="/contest"
              ><i class="el-icon-trophy"></i
              >{{ $t('m.NavBar_Contest') }}</el-menu-item
            >
            <el-menu-item index="/status"
              ><i class="el-icon-s-marketing"></i
              >{{ $t('m.NavBar_Status') }}</el-menu-item
            >
            <el-menu-item index="/rank/contest"
              ><i class="el-icon-s-data"></i>{{ $t('m.NavBar_Rank') }}</el-menu-item
            >
            <el-menu-item index="/discussion"
              v-if="websiteConfig.openPublicDiscussion"
              ><i class="el-icon-s-comment"></i
              >{{ $t('m.NavBar_Discussion') }}</el-menu-item
            >
            <el-menu-item index="/group"
              ><i
                class="fa fa-users navbar-icon"
              ></i
              >{{ $t('m.NavBar_Group') }}</el-menu-item
            >
            <el-submenu index="about" popper-class="cdutetc-nav-popup">
              <template slot="title"
                ><i class="el-icon-info"></i>{{ $t('m.NavBar_About') }}</template
              >
              <el-menu-item index="/introduction">{{
                $t('m.NavBar_Introduction')
              }}</el-menu-item>
              <el-menu-item index="/developer">{{
                $t('m.NavBar_Developer')
              }}</el-menu-item>
            </el-submenu>
        </template>
        <el-submenu v-if="mode != 'defalut'" index="context" class="nav-context" popper-class="cdutetc-nav-popup">
          <template slot="title">{{ contextTitle }}</template>
        <template v-if="mode == 'training'">
            <template v-if="$route.params.groupID">
              <el-menu-item :index="'/group/' + $route.params.groupID"
              ><i
                class="fa fa-users navbar-icon"
              ></i
              >{{ $t('m.NavBar_Group_Home') }}</el-menu-item>
            </template>
            <el-menu-item :index="getTrainingHomePath()"
              ><i class="el-icon-s-claim"></i>{{ $t('m.NavBar_Training_Home') }}</el-menu-item
            >
            <el-menu-item :index="getTrainingProblemListPath()"
              ><i class="fa fa-list navbar-icon"></i>{{ $t('m.Problem_List') }}</el-menu-item
            >
        </template>
        <template v-else-if="mode == 'contest'">
            <el-menu-item :index="'/contest/' + $route.params.contestID"
              ><i class="el-icon-trophy"></i>{{ $t('m.NavBar_Contest_Home') }}</el-menu-item
            >
            <el-menu-item :index="'/contest/' + $route.params.contestID + '/problems'"
              ><i class="fa fa-list navbar-icon"></i>{{ $t('m.Problem_List') }}</el-menu-item
            >
            <el-menu-item :index="'/contest/' + $route.params.contestID + '/submissions?onlyMine=true'"
              ><i class="el-icon-menu"></i>{{ $t('m.NavBar_Contest_Own_Submission') }}</el-menu-item
            >
            <el-menu-item :index="'/contest/' + $route.params.contestID + '/rank'"
              ><i class="fa fa-bar-chart navbar-icon"></i>{{ $t('m.NavBar_Contest_Rank') }}</el-menu-item
            >
        </template>
        <template v-else-if="mode == 'group'">
            <template v-if="$route.params.groupID">
              <el-menu-item :index="'/group/' + $route.params.groupID"
              ><i
                class="fa fa-users navbar-icon"
              ></i
              >{{ $t('m.NavBar_Group_Home') }}</el-menu-item>
            </template>
            <el-menu-item :index="'/group/' + $route.params.groupID + '/problem'"
              ><i class="fa fa-list navbar-icon"></i>{{ $t('m.Problem_List') }}</el-menu-item
            >
        </template>
        </el-submenu>
          </el-menu>
          <div class="nav-actions">
          <ThemeToggle v-if="!isAuthenticated" /><AppearanceSettings v-if="!isAuthenticated" class="nav-appearance" @visibility-change="onDropdownVisibility($event, 'appearance')" />
          <template v-if="!isAuthenticated">
            <div class="btn-menu">
              <el-button 
                type="primary" 
                size="medium" 
                round
                @click="handleBtnClick('Login')"
                >{{ $t('m.NavBar_Login') }}
              </el-button>
              <el-button
                v-if="websiteConfig.register"
                size="medium"
                round
                @click="handleBtnClick('Register')"
                style="margin-left: 5px"
                >{{ $t('m.NavBar_Register') }}
              </el-button>
            </div>
          </template>
          <template v-else>
            <el-dropdown
              class="drop-msg"
              @command="handleRoute"
              placement="bottom"
              trigger="click"
              @visible-change="onDropdownVisibility($event, 'notifications')"
            >
              <button class="el-dropdown-link nav-notifications" type="button" aria-label="通知" @keydown.enter.prevent>
                <i class="el-icon-message-solid"></i>
                <svg
                  v-if="
                    unreadMessage.comment > 0 ||
                      unreadMessage.reply > 0 ||
                      unreadMessage.like > 0 ||
                      unreadMessage.sys > 0 ||
                      unreadMessage.mine > 0
                  "
                  width="10"
                  height="10"
                  style="vertical-align: top;margin-left: -11px;margin-top: 3px;"
                >
                  <circle cx="5" cy="5" r="5" style="fill: red;"></circle>
                </svg>
              </button>

              <el-dropdown-menu slot="dropdown" class="cdutetc-nav-dropdown">
                <el-dropdown-item command="/message/discuss">
                  <span>{{ $t('m.DiscussMsg') }}</span>
                  <span class="drop-msg-count" v-if="unreadMessage.comment > 0">
                    <MsgSvg :total="unreadMessage.comment"></MsgSvg>
                  </span>
                </el-dropdown-item>
                <el-dropdown-item command="/message/reply">
                  <span>{{ $t('m.ReplyMsg') }}</span>
                  <span class="drop-msg-count" v-if="unreadMessage.reply > 0">
                    <MsgSvg :total="unreadMessage.reply"></MsgSvg>
                  </span>
                </el-dropdown-item>
                <el-dropdown-item command="/message/like">
                  <span>{{ $t('m.LikeMsg') }}</span>
                  <span class="drop-msg-count" v-if="unreadMessage.like > 0">
                    <MsgSvg :total="unreadMessage.like"></MsgSvg>
                  </span>
                </el-dropdown-item>
                <el-dropdown-item command="/message/sys">
                  <span>{{ $t('m.SysMsg') }}</span>
                  <span class="drop-msg-count" v-if="unreadMessage.sys > 0">
                    <MsgSvg :total="unreadMessage.sys"></MsgSvg>
                  </span>
                </el-dropdown-item>
                <el-dropdown-item command="/message/mine">
                  <span>{{ $t('m.MineMsg') }}</span>
                  <span class="drop-msg-count" v-if="unreadMessage.mine > 0">
                    <MsgSvg :total="unreadMessage.mine"></MsgSvg>
                  </span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </el-dropdown>
            <ThemeToggle />
            <ProfileMenu :user="userInfo" :avatar="avatar" :is-admin="isAdminRole" @command="handleRoute" @visibility-change="onDropdownVisibility($event, 'account')" />
          </template>
          </div>
        </div>
      </div>
      <div id="header-hidden">
      </div>
    </template>
    <template v-else>
      <div style="top:0px;left:0px;">
      <mu-appbar class="mobile-nav" :class="['nav-' + runtime.navigation, { 'nav-scrolled': scrolled }]" color="primary">
        <ThemeToggle slot="right" /><AppearanceSettings v-if="!isAuthenticated" slot="right" />
        <mu-button icon slot="left" aria-label="打开导航菜单" @click="opendrawer = !opendrawer">
          <i class="el-icon-s-unfold"></i>
        </mu-button>
        <el-tooltip
            :content="$t('m.Click_To_Change_Web_Language')"
            placement="bottom"
            effect="dark"
          >
          <span class="mobile-brand" @click="changeWebLanguage">
            <BrandLogo compact />
          </span>
        </el-tooltip>
        <mu-button
          flat
          slot="right"
          class="mobile-login"
          @click="handleBtnClick('Login')"
          v-show="!isAuthenticated"
          >{{ $t('m.NavBar_Login') }}</mu-button
        >
        <mu-button
          flat
          slot="right"
          class="mobile-register"
          @click="handleBtnClick('Register')"
          v-show="!isAuthenticated && websiteConfig.register"
          >{{ $t('m.NavBar_Register') }}</mu-button
        >

        <mu-menu slot="right" v-show="isAuthenticated" :open.sync="openmsgmenu" popover-class="cdutetc-mobile-popup">
          <mu-button flat aria-label="通知">
            <mu-icon value=":el-icon-message-solid" size="24"></mu-icon>
            <svg
              v-if="
                unreadMessage.comment > 0 ||
                  unreadMessage.reply > 0 ||
                  unreadMessage.like > 0 ||
                  unreadMessage.sys > 0 ||
                  unreadMessage.mine > 0
              "
              width="10"
              height="10"
              style="margin-left: -11px;margin-top: -13px;"
            >
              <circle cx="5" cy="5" r="5" style="fill: red;"></circle>
            </svg>
          </mu-button>
          <mu-list slot="content" @change="handleCommand">
            <mu-list-item button value="/message/discuss">
              <mu-list-item-content>
                <mu-list-item-title>
                  {{ $t('m.DiscussMsg') }}
                  <span class="drop-msg-count" v-if="unreadMessage.comment > 0">
                    <MsgSvg :total="unreadMessage.comment"></MsgSvg>
                  </span>
                </mu-list-item-title>
              </mu-list-item-content>
            </mu-list-item>
            <mu-divider></mu-divider>
            <mu-list-item button value="/message/reply">
              <mu-list-item-content>
                <mu-list-item-title>
                  {{ $t('m.ReplyMsg') }}
                  <span class="drop-msg-count" v-if="unreadMessage.reply > 0">
                    <MsgSvg :total="unreadMessage.reply"></MsgSvg>
                  </span>
                </mu-list-item-title>
              </mu-list-item-content>
            </mu-list-item>
            <mu-divider></mu-divider>
            <mu-list-item button value="/message/like">
              <mu-list-item-content>
                <mu-list-item-title>
                  {{ $t('m.LikeMsg') }}
                  <span class="drop-msg-count" v-if="unreadMessage.like > 0">
                    <MsgSvg :total="unreadMessage.like"></MsgSvg>
                  </span>
                </mu-list-item-title>
              </mu-list-item-content>
            </mu-list-item>
            <mu-divider></mu-divider>
            <mu-list-item button value="/message/sys">
              <mu-list-item-content>
                <mu-list-item-title>
                  {{ $t('m.SysMsg') }}
                  <span class="drop-msg-count" v-if="unreadMessage.sys > 0">
                    <MsgSvg :total="unreadMessage.sys"></MsgSvg>
                  </span>
                </mu-list-item-title>
              </mu-list-item-content>
            </mu-list-item>
            <mu-divider></mu-divider>

            <mu-list-item button value="/message/mine">
              <mu-list-item-content>
                <mu-list-item-title>
                  {{ $t('m.MineMsg') }}
                  <span class="drop-msg-count" v-if="unreadMessage.mine > 0">
                    <MsgSvg :total="unreadMessage.mine"></MsgSvg>
                  </span>
                </mu-list-item-title>
              </mu-list-item-content>
            </mu-list-item>
          </mu-list>
        </mu-menu>

        <ProfileMenu v-if="isAuthenticated" slot="right" :user="userInfo" :avatar="avatar" :is-admin="isAdminRole" @command="handleCommand" />
      </mu-appbar>

      <div class="mobile-nav-spacer" aria-hidden="true"></div>

      <mu-drawer class="cdutetc-nav-drawer" :open.sync="opendrawer" :docked="false" :right="false">
        <mu-list toggle-nested>
          <mu-list-item
            button
            to="/home"
            @click="opendrawer = !opendrawer"
            active-class="mobile-menu-active"
          >
            <mu-list-item-action>
              <mu-icon value=":el-icon-s-home" size="24"></mu-icon>
            </mu-list-item-action>
            <mu-list-item-title>{{ $t('m.NavBar_Home') }}</mu-list-item-title>
          </mu-list-item>

          <mu-list-item
            button
            to="/problem"
            @click="opendrawer = !opendrawer"
            active-class="mobile-menu-active"
          >
            <mu-list-item-action>
              <mu-icon value=":el-icon-s-grid" size="24"></mu-icon>
            </mu-list-item-action>
            <mu-list-item-title>{{
              $t('m.NavBar_Problem')
            }}</mu-list-item-title>
          </mu-list-item>

          <mu-list-item
            button
            to="/training"
            @click="opendrawer = !opendrawer"
            active-class="mobile-menu-active"
          >
            <mu-list-item-action>
              <mu-icon value=":el-icon-s-claim" size="24"></mu-icon>
            </mu-list-item-action>
            <mu-list-item-title>{{
              $t('m.NavBar_Training')
            }}</mu-list-item-title>
          </mu-list-item>

          <mu-list-item button to="/knowledge" @click="opendrawer = !opendrawer" active-class="mobile-menu-active"><mu-list-item-action><mu-icon value=":el-icon-reading" size="24"></mu-icon></mu-list-item-action><mu-list-item-title>{{ $t('m.Knowledge') }}</mu-list-item-title></mu-list-item>

          <mu-list-item
            button
            to="/contest"
            @click="opendrawer = !opendrawer"
            active-class="mobile-menu-active"
          >
            <mu-list-item-action>
              <mu-icon value=":el-icon-trophy" size="24"></mu-icon>
            </mu-list-item-action>
            <mu-list-item-title>{{
              $t('m.NavBar_Contest')
            }}</mu-list-item-title>
          </mu-list-item>

          <mu-list-item
            button
            to="/status"
            @click="opendrawer = !opendrawer"
            active-class="mobile-menu-active"
          >
            <mu-list-item-action>
              <mu-icon value=":el-icon-s-marketing" size="24"></mu-icon>
            </mu-list-item-action>
            <mu-list-item-title>{{ $t('m.NavBar_Status') }}</mu-list-item-title>
          </mu-list-item>

          <mu-list-item
            button
            :ripple="false"
            to="/rank/contest"
            @click="opendrawer = !opendrawer"
            active-class="mobile-menu-active"
          >
            <mu-list-item-action>
              <mu-icon value=":el-icon-s-data" size="24"></mu-icon>
            </mu-list-item-action>
            <mu-list-item-title>{{ $t('m.NavBar_Rank') }}</mu-list-item-title>
          </mu-list-item>

          <mu-list-item
            v-if="websiteConfig.openPublicDiscussion"
            button
            to="/discussion"
            @click="opendrawer = !opendrawer"
            active-class="mobile-menu-active"
          >
            <mu-list-item-action>
              <mu-icon value=":fa fa-comments" size="24"></mu-icon>
            </mu-list-item-action>
            <mu-list-item-title>{{
              $t('m.NavBar_Discussion')
            }}</mu-list-item-title>
          </mu-list-item>

          <mu-list-item
            button
            to="/group"
            @click="opendrawer = !opendrawer"
            active-class="mobile-menu-active"
          >
            <mu-list-item-action>
              <mu-icon value=":fa fa-users" size="24"></mu-icon>
            </mu-list-item-action>
            <mu-list-item-title>{{ $t('m.NavBar_Group') }}</mu-list-item-title>
          </mu-list-item>

          <mu-list-item
            button
            :ripple="false"
            nested
            :open="openSideMenu === 'about'"
            @toggle-nested="openSideMenu = arguments[0] ? 'about' : ''"
          >
            <mu-list-item-action>
              <mu-icon value=":el-icon-info" size="24"></mu-icon>
            </mu-list-item-action>
            <mu-list-item-title>{{ $t('m.NavBar_About') }}</mu-list-item-title>
            <mu-list-item-action>
              <mu-icon
                class="toggle-icon"
                size="24"
                value=":el-icon-arrow-down"
              ></mu-icon>
            </mu-list-item-action>
            <mu-list-item
              button
              :ripple="false"
              slot="nested"
              to="/introduction"
              @click="opendrawer = !opendrawer"
              active-class="mobile-menu-active"
            >
              <mu-list-item-title>{{
                $t('m.NavBar_Introduction')
              }}</mu-list-item-title>
            </mu-list-item>
            <mu-list-item
              button
              :ripple="false"
              slot="nested"
              to="/developer"
              @click="opendrawer = !opendrawer"
              active-class="mobile-menu-active"
            >
              <mu-list-item-title>{{
                $t('m.NavBar_Developer')
              }}</mu-list-item-title>
            </mu-list-item>
          </mu-list-item>
        </mu-list>
      </mu-drawer>
    </div>
    </template>
    
    <el-dialog
      :visible.sync="modalVisible"
      width="370px"
      class="dialog"
      :title="title"
      :close-on-click-modal="false"
    >
      <component :is="modalStatus.mode" v-if="modalVisible"></component>
      <div slot="footer" style="display: none"></div>
    </el-dialog>
  </div>
</template>
<script>
import BrandLogo from '@/components/oj/common/BrandLogo';
import AppearanceSettings from '@/components/oj/common/AppearanceSettings';
import { appearance, appearanceRuntime } from '@/common/appearance';
import ThemeToggle from './ThemeToggle';
import ProfileMenu from './ProfileMenu';
import Login from '@/components/oj/common/Login';
import Register from '@/components/oj/common/Register';
import ResetPwd from '@/components/oj/common/ResetPassword';
import MsgSvg from '@/components/oj/msg/msgSvg';
import { mapGetters, mapActions } from 'vuex';
import api from '@/common/api';
export default {
  components: {
    BrandLogo,
    AppearanceSettings,
    ThemeToggle,
    ProfileMenu,
    Login,
    Register,
    ResetPwd,
    MsgSvg,
  },
  created(){
    this.page_width();
    window.addEventListener('resize', this.onResize);
    window.addEventListener('scroll', this.onScroll, { passive: true });
  },
  mounted() {
    this.switchMode();
    this.onScroll();
    this.setHiddenHeaderHeight();
    if (this.isAuthenticated) {
      this.getUnreadMsgCount();
      this.msgTimer = setInterval(() => {
        this.getUnreadMsgCount();
      }, 120 * 1000);
    }
  },
  beforeDestroy() {
    clearInterval(this.msgTimer);
    window.removeEventListener('resize', this.onResize);
    window.removeEventListener('scroll', this.onScroll);
    cancelAnimationFrame(this.scrollFrame);
    clearTimeout(this.navLeaveTimer);
  },
  data() {
    return {
      appearance,
      runtime: appearanceRuntime,
      scrolled: false,
      navHovered: false,
      navFocused: false,
      openPopups: {},
      mode:'defalut',
      centerDialogVisible: false,
      mobileNar: false,
      opendrawer: false,
      openusermenu: false,
      openmsgmenu: false,
      openSideMenu: '',
      avatarStyle:
        'display: inline-flex;width: 30px;height: 30px;border-radius: 50%;align-items: center;justify-content: center;text-align: center;user-select: none;',
    };
  },
  methods: {
    ...mapActions(['changeModalStatus']),
    onResize() { this.page_width(); },
    onScroll() {
      if (this.scrollFrame) return;
      this.scrollFrame = requestAnimationFrame(() => {
        // Separate thresholds prevent jitter when the scroll position hovers near the boundary.
        if (window.scrollY > 64) this.scrolled = true;
        else if (window.scrollY < 24) this.scrolled = false;
        this.scrollFrame = null;
      });
    },
    onNavEnter() {
      clearTimeout(this.navLeaveTimer);
      this.navHovered = true;
    },
    onNavLeave() {
      clearTimeout(this.navLeaveTimer);
      this.navLeaveTimer = setTimeout(() => { this.navHovered = false; }, 160);
    },
    onNavFocusOut(event) {
      if (!event.currentTarget.contains(event.relatedTarget)) this.navFocused = false;
    },
    onDropdownVisibility(open, key) { this.$set(this.openPopups, key, open); },
    onMenuOpen(index) { this.onDropdownVisibility(true, 'menu:' + index); },
    onMenuClose(index) { this.onDropdownVisibility(false, 'menu:' + index); },
    page_width() {
      const mobile = window.innerWidth < 992;
      if (mobile !== this.mobileNar) {
        clearTimeout(this.navLeaveTimer);
        this.navHovered = false;
        this.navFocused = false;
        this.openPopups = {};
        this.opendrawer = false;
        this.openusermenu = false;
        this.openmsgmenu = false;
      }
      this.mobileNar = mobile;
    },
    handleBtnClick(mode) {
      this.changeModalStatus({
        mode,
        visible: true,
      });
    },
    handleRoute(route) {
      //电脑端导航栏路由跳转事件
      if (route && route.split('/')[1] != 'admin') {
        this.$router.push(route);
      } else {
        window.open('/admin/');
      }
    },
    handleCommand(route) {
      // 移动端导航栏路由跳转事件
      this.openusermenu = false;
      this.openmsgmenu = false;
      if (route && route.split('/')[1] != 'admin') {
        this.$router.push(route);
      } else {
        window.open('/admin/');
      }
    },
    getUnreadMsgCount() {
      api.getUnreadMsgCount().then((res) => {
        let data = res.data.data;
        this.$store.dispatch('updateUnreadMessageCount', data);
        let sumMsg =
          data.comment + data.reply + data.like + data.mine + data.sys;
        if (sumMsg > 0) {
          if (this.webLanguage == 'zh-CN') {
            this.$notify.info({
              title: '未读消息',
              message:
                '亲爱的【' +
                this.userInfo.username +
                '】，您有最新的' +
                sumMsg +
                '条未读消息，请注意查看！',
              position: 'bottom-right',
              duration: 5000,
            });
          } else {
            this.$notify.info({
              title: 'Unread Message',
              message:
                'Dear【' +
                this.userInfo.username +
                '】, you have the latest ' +
                sumMsg +
                ' unread messages. Please check them!',
              position: 'bottom-right',
              duration: 5000,
            });
          }
        }
      });
    },
    changeWebLanguage() {
      this.$store.commit('changeWebLanguage', { language: this.webLanguage == 'zh-CN' ? 'en-US' : 'zh-CN' });
    },
    setHiddenHeaderHeight() { this.onScroll(); },
    switchMode(){
      if(this.$route.meta.fullScreenSource){
        this.mode = this.$route.meta.fullScreenSource;
      }else{
        this.mode = 'defalut';
      }
    },
    getTrainingHomePath(){
      let tid = this.$route.params.trainingID
      let gid = this.$route.params.groupID
      if(gid){
        return `/group/${gid}/training/${tid}`;
      }else{
        return `/training/${tid}`;
      }
    },
    getTrainingProblemListPath(){
      let tid = this.$route.params.trainingID
      let gid = this.$route.params.groupID
      if(gid){
        return `/group/${gid}/training/${tid}/problems`;
      }else{
        return `/training/${tid}/problems`;
      }
    }
  },
  computed: {
    ...mapGetters([
      'modalStatus',
      'userInfo',
      'isAuthenticated',
      'isAdminRole',
      'token',
      'websiteConfig',
      'unreadMessage',
      'webLanguage',
    ]),
    avatar() {
      return this.$store.getters.userInfo.avatar;
    },
    navEngaged() {
      return this.navHovered || this.navFocused || Object.values(this.openPopups).some(Boolean);
    },
    contextTitle() {
      return { training: '当前训练', contest: '当前比赛', group: '当前团队' }[this.mode];
    },
    activeMenuName() {
      if (this.$route.path.split('/')[1] === 'rank') return '/rank/contest';
      if (['objective', 'papers'].includes(this.$route.path.split('/')[1])) return '/problem';
      if (this.$route.path.split('/')[1] == 'submission-detail') {
        return '/status';
      } else if (this.$route.path.split('/')[1] == 'discussion-detail') {
        return '/discussion';
      }
      return '/' + this.$route.path.split('/')[1];
    },
    modalVisible: {
      get() {
        return this.modalStatus.visible;
      },
      set(value) {
        this.changeModalStatus({ visible: value });
      },
    },
    title: {
      get() {
        let ojName = this.websiteConfig.shortName
          ? this.websiteConfig.shortName
          : 'OJ';
        if (this.modalStatus.mode == 'ResetPwd') {
          return this.$i18n.t('m.Dialog_Reset_Password') + ' - ' + ojName;
        } else {
          return (
            this.$i18n.t('m.Dialog_' + this.modalStatus.mode) + ' - ' + ojName
          );
        }
      },
    },
  },
  watch: {
    isAuthenticated() {
      this.openPopups = {};
      if (this.isAuthenticated) {
        if (this.msgTimer) {
          clearInterval(this.msgTimer);
        }
        this.getUnreadMsgCount();
        this.msgTimer = setInterval(() => {
          this.getUnreadMsgCount();
        }, 120 * 1000);
      } else {
        clearInterval(this.msgTimer);
      }
    },
    $route(){
      clearTimeout(this.navLeaveTimer);
      this.navHovered = false;
      this.navFocused = false;
      this.openPopups = {};
      this.switchMode();
      this.onScroll();
      this.$nextTick(() => {
        const header = this.$el.querySelector('#header');
        this.navHovered = !!header && header.matches(':hover');
        this.navFocused = !!header && header.contains(document.activeElement);
      });
    }
  },
};
</script>
<style scoped>
#header {
  min-width: 300px;
  position: fixed;
  top: 0;
  left: 0;
  height: auto;
  width: 100%;
  z-index: 2000;
  background-color: #fff;
  box-shadow: 0 1px 5px 0 rgba(0, 0, 0, 0.1);
}
.mobile-nav {
  position: fixed;
  left: 0px;
  top: 0px;
  z-index: 2500;
  height: auto;
  width: 100%;
}

#drawer {
  position: fixed;
  left: 0px;
  bottom: 0px;
  z-index: 1000;
  width: 100%;
  box-shadow: 00px 0px 00px rgb(255, 255, 255), 0px 0px 10px rgb(255, 255, 255),
    0px 0px 0px rgb(255, 255, 255), 1px 1px 0px rgb(218, 218, 218);
}

.logo {
  cursor: pointer;
  margin-left: 2%;
  margin-right: 2%;
  float: left;
  width: 139px;
  height: 42px;
  margin-top: 5px;
}
.el-dropdown-link {
  cursor: pointer;
  color: #409eff !important;
}
.el-icon-arrow-down {
  font-size: 18px;
}
.drop-menu {
  float: right;
  margin-right: 30px;
  position: relative;
  font-weight: 500;
  right: 10px;
  margin-top: 18px;
  font-size: 18px;
}
.drop-avatar {
  float: right;
  margin-right: 15px;
  position: relative;
  margin-top: 16px;
}
.drop-msg {
  float: right;
  font-size: 25px;
  margin-right: 10px;
  position: relative;
  margin-top: 13px;
}
.drop-msg-count {
  margin-left: 2px;
}
.btn-menu {
  font-size: 16px;
  float: right;
  margin-right: 10px;
  margin-top: 12px;
}
/deep/ .el-dialog {
  border-radius: 10px !important;
  text-align: center;
}
/deep/ .el-dialog__header .el-dialog__title {
  font-size: 22px;
  font-weight: 600;
  font-family: Arial, Helvetica, sans-serif;
  line-height: 1em;
  color: #4e4e4e;
}
.el-submenu__title i {
  color: #495060 !important;
}
.el-menu-item {
  padding: 0 13px;
}
.el-menu-item:hover, .el-menu .el-menu-item:hover{
  border-bottom: 2px solid #2474b5 !important;
}
.el-menu .el-menu-item:hover, 
.el-menu .el-menu-item:hover i,
.el-submenu .el-submenu__title:hover,
.el-submenu .el-submenu__title:hover i{
  outline: 0 !important;
  color: #2E95FB !important;
  background: linear-gradient(270deg, #F2F7FC 0%, #FEFEFE 100%)!important;
  transition: all .2s ease;
}
.el-menu .el-menu-item.is-active, 
.el-menu .el-menu-item.is-active i,
.el-submenu.is-active,
.el-submenu.is-active i
{
  color: #2E95FB !important;
  background: linear-gradient(270deg, #F2F7FC 0%, #FEFEFE 100%)!important;
  transition: all .2s ease;
}
.el-menu--horizontal .el-menu .el-menu-item:hover, 
.el-submenu /deep/.el-submenu__title:hover {
  color: #2E95FB !important;
  background: linear-gradient(270deg, #F2F7FC 0%, #FEFEFE 100%)!important;
}
.el-menu-item i {
  color: #495060;
}
.is-active .el-submenu__title i,
.is-active {
  color: #2196f3 !important;
}
.el-menu-item.is-active i {
  color: #2196f3 !important;
}
.navbar-icon{
  margin-right: 5px !important;
  width: 24px !important;
  text-align: center !important;
}
</style>
