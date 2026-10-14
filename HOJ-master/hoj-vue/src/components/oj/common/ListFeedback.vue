<template>
  <div v-if="error" class="collection-feedback" role="alert">
    <i class="el-icon-warning-outline" aria-hidden="true" />
    <p>加载失败，请重试。</p>
    <el-button size="small" @click="$emit('retry')">重新加载</el-button>
  </div>
  <el-empty v-else-if="!loading && empty" class="collection-feedback" :description="$t('m.No_Data')" />
</template>

<script>
export default { props: { error: Boolean, loading: Boolean, empty: Boolean } };
</script>
