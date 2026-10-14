<template>
  <span class="brand-lockup">
    <img src="@/assets/brand/cdutetc.webp" alt="成都理工大学工程技术学院校徽" />
    <span v-if="!iconOnly"><strong>CDUTETC</strong><small>成理工程在线测评平台</small></span>
  </span>
</template>
<script>
export default { props: { iconOnly: Boolean } };
</script>
<style scoped>
.brand-lockup { display: inline-flex; align-items: center; gap: 10px; text-align: left; vertical-align: middle; color: var(--text); }
.brand-lockup img { width: 44px; height: 44px; object-fit: contain; clip-path: circle(49% at 50% 50%); flex-shrink: 0; filter: none; }
.brand-lockup strong { display: block; font-size: 20px; line-height: 1.2; letter-spacing: .4px; }
.brand-lockup small { display: block; font-size: 10px; line-height: 1.8; color: var(--muted); }
</style>
