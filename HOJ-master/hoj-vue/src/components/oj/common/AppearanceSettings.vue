<template>
  <MorphPopover class="appearance-control" label="主题与布局" @visibility-change="$emit('visibility-change', $event)">
    <template slot="trigger" slot-scope="{ open, toggle }">
      <button class="appearance-button el-button el-button--default el-button--small is-circle" type="button" aria-label="主题与布局" aria-haspopup="dialog" :aria-expanded="String(open)" title="主题与布局" @click="toggle"><i class="el-icon-magic-stick" /></button>
    </template>
    <AppearancePanel ref="panel" :theme-only="themeOnly" />
  </MorphPopover>
</template>
<script>
import { appearance } from '@/common/appearance';
import AppearancePanel from './AppearancePanel';
import MorphPopover from './MorphPopover';
export default {
  name: 'AppearanceSettings',
  components: { AppearancePanel, MorphPopover },
  props: { themeOnly: Boolean },
  data: () => ({ settings: appearance }),
  methods: { set(key, value) { return this.$refs.panel.set(key, value); } },
};
</script>
