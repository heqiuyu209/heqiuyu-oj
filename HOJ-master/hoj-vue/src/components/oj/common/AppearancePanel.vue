<template>
  <div class="appearance-panel">
    <h3>主题与布局</h3>
    <label>主题</label>
    <el-radio-group class="theme-options" :value="settings.theme" size="small" aria-label="主题" @click.native.capture="rememberOrigin" @keydown.native.capture="rememberOrigin" @input="set('theme', $event)">
      <el-radio-button label="light">浅色</el-radio-button><el-radio-button label="dark">深色</el-radio-button><el-radio-button label="system">跟随系统</el-radio-button>
    </el-radio-group>
    <template v-if="!themeOnly">
      <label>显示模式</label>
      <el-radio-group class="experience-options" :value="settings.experience" size="small" aria-label="显示模式" @input="set('experience', $event)">
        <el-radio-button label="standard">标准效果</el-radio-button><el-radio-button label="minimal">简洁模式</el-radio-button>
      </el-radio-group>
      <p class="appearance-hint" aria-live="polite">{{ runtime.minimal ? '当前为简洁模式：使用普通导航，暂停装饰动效与玻璃模糊。' : '按下面的选项调整本站外观，选择会在此浏览器保留。' }}</p>
      <div class="appearance-setting"><span>比赛中自动简化</span><el-switch aria-label="比赛中自动简化" :value="settings.autoContest" @input="set('autoContest', $event)" /></div>
      <p v-if="runtime.contest && settings.autoContest" class="appearance-hint">已为当前比赛自动简化，离开比赛恢复原设置。可关闭上面的选项。</p>
      <label>导航栏</label>
      <el-radio-group class="navigation-options" :value="settings.navigation" size="small" :disabled="runtime.minimal" aria-label="导航栏" @input="set('navigation', $event)">
        <el-radio-button label="normal">普通</el-radio-button><el-radio-button label="glass">静态玻璃</el-radio-button><el-radio-button label="dynamic">悬浮玻璃</el-radio-button>
      </el-radio-group>
      <p class="appearance-hint">悬浮导航随滚动收拢，鼠标移入或键盘聚焦时展开。</p>
      <div class="appearance-setting"><span>卡片柔光</span><el-switch aria-label="卡片柔光" :value="settings.spotlight" :disabled="runtime.minimal" @input="set('spotlight', $event)" /></div>
      <div class="appearance-setting"><span>首页液态玻璃</span><el-switch aria-label="首页液态玻璃" :value="settings.homeGlass" :disabled="runtime.minimal" @input="set('homeGlass', $event)" /></div>
      <div class="appearance-setting"><span>头像菜单变形动画</span><el-switch aria-label="头像菜单变形动画" :value="settings.morph" :disabled="runtime.minimal" @input="set('morph', $event)" /></div>
      <div class="appearance-setting"><span>紧凑间距</span><el-switch aria-label="紧凑间距" :value="settings.compact" @input="set('compact', $event)" /></div>
    </template>
    <label>动画效果</label>
    <el-radio-group class="motion-options" :value="settings.motion" size="small" :disabled="runtime.minimal" aria-label="动画效果" @input="set('motion', $event)">
      <el-radio-button label="system">跟随系统</el-radio-button><el-radio-button label="full">完整动画</el-radio-button><el-radio-button label="reduced">减少动画</el-radio-button>
    </el-radio-group>
    <p class="appearance-hint">{{ runtime.minimal ? '简洁模式下直接切换主题。' : settings.motion === 'system' && runtime.reduced ? '电脑已开启减少动画；选择完整动画可单独开启本站过渡。' : settings.motion === 'full' ? '已开启主题扩散与交互过渡，仅对本站生效。' : settings.motion === 'reduced' ? '主题与导航直接切换，减少动态效果。' : '动画跟随电脑设置。' }}</p>
  </div>
</template>
<script>
import { appearance, appearanceRuntime, updateAppearance } from '@/common/appearance';
export default {
  name: 'AppearancePanel',
  props: { themeOnly: Boolean },
  data: () => ({ settings: appearance, runtime: appearanceRuntime }),
  methods: {
    rememberOrigin(event) {
      const button = event.target.closest('.el-radio-button');
      if (!button) return;
      const rect = button.getBoundingClientRect();
      this.themeOrigin = { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
    },
    set(key, value) {
      const origin = this.themeOrigin;
      this.themeOrigin = null;
      return updateAppearance(key, value, origin);
    },
  },
};
</script>
<style scoped>
.appearance-panel > label { display: block; margin: 18px 0 9px; font-weight: 600; }
.appearance-panel h3 { margin: 0; font-size: 16px; }
.appearance-hint { font-size: 12px; line-height: 1.8; margin: 10px 0; color: var(--muted); }
.appearance-setting { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 15px 0; font-size: 13px; }
</style>
