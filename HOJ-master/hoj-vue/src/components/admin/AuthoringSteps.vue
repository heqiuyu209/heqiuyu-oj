<template>
  <nav class="authoring-steps" aria-label="编辑步骤">
    <button v-for="(label, index) in steps" :key="label" type="button"
      :aria-current="value === index ? 'step' : null" :disabled="disabled"
      @click="$emit('input', index)">
      <span class="step-number">{{ index + 1 }}</span><span>{{ label }}</span>
    </button>
  </nav>
</template>
<script>
export default {
  name: 'AuthoringSteps',
  props: { value: { type: Number, default: 0 }, steps: { type: Array, required: true }, disabled: Boolean },
};
</script>
<style scoped>
.authoring-steps { display: flex; gap: 8px; margin-bottom: 24px; padding-bottom: 16px; border-bottom: 1px solid var(--line); }
.authoring-steps button { display: flex; flex: 1; align-items: center; gap: 10px; padding: 10px 12px; border: 0; border-radius: 8px; background: transparent; color: var(--muted); cursor: pointer; font: inherit; text-align: left; }
.step-number { display: grid; place-items: center; flex-shrink: 0; width: 28px; height: 28px; border: 1px solid var(--line); border-radius: 50%; font-weight: 600; }
button[aria-current] { background: var(--soft); color: var(--accent); font-weight: 600; }button[aria-current] .step-number { background: var(--accent); border-color: var(--accent); color: #fff; }
button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }button:disabled { cursor: wait; }
@media(max-width:600px) { .authoring-steps { gap: 4px; }.authoring-steps button { flex-direction: column; gap: 8px; padding: 8px 4px; text-align: center; font-size: 12px; } }
</style>
