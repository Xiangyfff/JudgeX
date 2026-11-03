<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  verdict?: string | null
  status?: string
  size?: 'sm' | 'md'
}>()

const cls = computed(() => {
  const v = props.verdict
  if (!v) return props.status === 'RUNNING' ? 'pill-live' : ''
  if (v === 'AC') return 'pill-ac'
  if (v === 'WA') return 'pill-wa'
  if (v === 'TLE' || v === 'MLE' || v === 'OLE') return 'pill-tle'
  if (v === 'RE' || v === 'CE') return 'pill-re'
  return 'pill-sys'
})

const label = computed(() => {
  if (props.verdict) {
    if (props.verdict === 'SYSTEM_ERROR') return 'SYSTEM'
    return props.verdict
  }
  if (props.status === 'RUNNING') return 'RUNNING'
  if (props.status === 'QUEUED') return 'QUEUED'
  return 'PENDING'
})

const spin = computed(() => !props.verdict && (props.status === 'RUNNING' || props.status === 'QUEUED'))
</script>

<template>
  <span class="pill" :class="[cls, size === 'sm' ? 'pill-sm' : '']">
    <i v-if="spin" class="dot-spin"></i>
    {{ label }}
  </span>
</template>

<style scoped>
.pill-sm {
  font-size: 10px;
  padding: 1px 7px;
}
.dot-spin {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  animation: blink 1s infinite ease-in-out;
}
@keyframes blink {
  0%,
  100% {
    opacity: 0.25;
  }
  50% {
    opacity: 1;
  }
}
</style>
