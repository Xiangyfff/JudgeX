<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'

const props = defineProps<{
  title?: string
  section?: string
  flush?: boolean
}>()

const route = useRoute()
const router = useRouter()
const auth = useAuth()

const crumb = computed(() => props.title ?? (route.meta.title as string) ?? '')
const section = computed(() => props.section ?? 'JudgeX')

const nav = computed(() => {
  const items = [
    { to: '/problems', label: '题库', icon: 'grid' },
    { to: '/submissions', label: '提交记录', icon: 'list' },
    { to: '/dashboard', label: '运行监控', icon: 'pulse' }
  ]
  if (auth.isAdmin) items.push({ to: '/admin', label: '题库管理', icon: 'shield' })
  return items
})

function isActive(path: string) {
  return route.path === path || route.path.startsWith(path + '/')
}

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">JX</div>
        <div>
          <div class="brand-name">JUDGEX</div>
          <div class="brand-sub">judge platform</div>
        </div>
      </div>

      <nav class="nav">
        <div class="nav-label">工作台</div>
        <router-link
          v-for="item in nav"
          :key="item.to"
          :to="item.to"
          class="nav-item"
          :class="{ active: isActive(item.to) }"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <template v-if="item.icon === 'grid'">
              <rect x="3" y="3" width="7" height="7" rx="1.5" />
              <rect x="14" y="3" width="7" height="7" rx="1.5" />
              <rect x="3" y="14" width="7" height="7" rx="1.5" />
              <rect x="14" y="14" width="7" height="7" rx="1.5" />
            </template>
            <template v-else-if="item.icon === 'list'">
              <path d="M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01" />
            </template>
            <template v-else-if="item.icon === 'pulse'">
              <path d="M2 12h4l3-8 4 16 3-8h6" />
            </template>
            <template v-else>
              <path d="M12 3l7 3v6c0 4.4-3 8-7 9-4-1-7-4.6-7-9V6l7-3z" />
            </template>
          </svg>
          <span>{{ item.label }}</span>
        </router-link>
      </nav>

      <div class="sidebar-foot">
        <div class="node-card">
          <i class="pulse"></i>
          <span>node-1 · online</span>
        </div>
        <div class="nav-item" style="padding: 8px 11px" @click="logout">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path d="M15 17l5-5-5-5M20 12H9M12 3H5a2 2 0 00-2 2v14a2 2 0 002 2h7" />
          </svg>
          <span>退出登录</span>
        </div>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="crumb">
          <span>{{ section }}</span>
          <span class="sep">/</span>
          <b>{{ crumb }}</b>
        </div>
        <div style="display: flex; align-items: center; gap: 12px">
          <span class="pill pill-live">
            <i class="pulse" style="width: 6px; height: 6px"></i>
            sandbox online
          </span>
          <div style="display: flex; align-items: center; gap: 9px">
            <div
              style="
                width: 30px;
                height: 30px;
                border-radius: 9px;
                display: grid;
                place-items: center;
                background: linear-gradient(135deg, rgba(34, 211, 238, 0.22), rgba(139, 92, 246, 0.22));
                border: 1px solid rgba(148, 163, 184, 0.2);
                font-size: 13px;
                font-weight: 600;
              "
            >
              {{ auth.initials }}
            </div>
            <div style="line-height: 1.25">
              <div style="font-size: 12.5px">{{ auth.user?.displayName ?? '未登录' }}</div>
              <div style="font-size: 10.5px; color: var(--mute); letter-spacing: 0.08em">
                {{ auth.isAdmin ? 'ADMIN' : 'STUDENT' }}
              </div>
            </div>
          </div>
        </div>
      </header>

      <main class="page" :class="{ flush: props.flush }">
        <slot />
      </main>
    </div>
  </div>
</template>

<style scoped>
.page.flush {
  padding: 0;
  overflow: hidden;
}
</style>
