<script setup lang="ts">
import { toasts } from '../lib/toast'
</script>

<template>
  <div class="toast-host">
    <transition-group name="toast">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="`toast-${t.kind}`">
        <span class="toast-dot"></span>
        {{ t.text }}
      </div>
    </transition-group>
  </div>
</template>

<style scoped>
.toast-host {
  position: fixed;
  right: 22px;
  bottom: 22px;
  z-index: 999;
  display: flex;
  flex-direction: column;
  gap: 9px;
  pointer-events: none;
}
.toast {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px 16px;
  border-radius: 10px;
  font-size: 13px;
  background: rgba(13, 21, 35, 0.95);
  border: 1px solid var(--border-hi);
  box-shadow: 0 16px 40px -18px #000;
  backdrop-filter: blur(10px);
  max-width: 380px;
}
.toast-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: none;
}
.toast-ok {
  border-color: rgba(52, 211, 153, 0.4);
}
.toast-ok .toast-dot {
  background: var(--green);
  box-shadow: 0 0 10px var(--green);
}
.toast-err {
  border-color: rgba(248, 113, 113, 0.4);
}
.toast-err .toast-dot {
  background: var(--red);
  box-shadow: 0 0 10px var(--red);
}
.toast-info .toast-dot {
  background: var(--cyan);
  box-shadow: 0 0 10px var(--cyan);
}
.toast-enter-active,
.toast-leave-active {
  transition: all 0.22s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(8px) scale(0.98);
}
</style>
