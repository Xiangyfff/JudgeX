<script setup lang="ts">
import { onMounted } from 'vue'
import ToastHost from './components/ToastHost.vue'
import { useAuth } from './stores/auth'

const auth = useAuth()
onMounted(() => {
  if (auth.token) auth.refresh()
})
</script>

<template>
  <router-view />
  <ToastHost />
</template>
