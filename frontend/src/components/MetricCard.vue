<template>
  <article class="monitor-metric" :class="tone">
    <span>{{ label }}</span>
    <strong>{{ value }}</strong>
    <small v-if="hint">{{ hint }}</small>
  </article>
</template>

<script setup lang="ts">
defineProps<{
  label: string
  value: string
  hint?: string
  tone?: 'positive' | 'warning' | 'danger' | 'neutral'
}>()
</script>