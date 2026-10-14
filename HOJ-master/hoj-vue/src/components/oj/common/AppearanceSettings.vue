<template>
  <div class="appearance-control">
    <el-popover placement="bottom-end" width="290" trigger="click" @show="$emit('visibility-change', true)" @hide="$emit('visibility-change', false)">
      <div class="appearance-panel">
        <h3>界面外观</h3>
        <label>主题</label>
        <el-radio-group :value="settings.theme" size="small" @input="set('theme', $event)">
          <el-radio-button label="light">浅色</el-radio-button><el-radio-button label="dark">深色</el-radio-button><el-radio-button label="system">跟随系统</el-radio-button>
        </el-radio-group>
        <label>导航栏</label>
        <el-select :value="settings.navigation" size="small" @input="set('navigation', $event)">
          <el-option label="普通导航" value="normal" /><el-option label="静态玻璃导航" value="glass" /><el-option label="悬浮玻璃导航" value="dynamic" />
        </el-select>
        <p class="muted">动态模式下，向下滚动后导航变透明并向中间收拢，鼠标移入或键盘聚焦时展开。</p>
        <el-switch :value="settings.compact" active-text="紧凑间距" @input="set('compact', $event)" />
      </div>
      <el-button slot="reference" circle size="small" icon="el-icon-magic-stick" aria-label="界面外观" title="界面外观" />
    </el-popover>
  </div>
</template>
<script>
import { appearance, updateAppearance } from '@/common/appearance';
export default { data: () => ({ settings: appearance }), methods: { set: updateAppearance } };
</script>
<style scoped>
.appearance-panel label { display: block; margin: 18px 0 9px; font-weight: 600; }
.appearance-panel h3 { margin: 0; }
.appearance-panel p { font-size: 12px; line-height: 1.8; }
.appearance-panel .el-select { width: 100%; }
</style>
