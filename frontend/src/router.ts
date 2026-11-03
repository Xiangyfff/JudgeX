import { createRouter, createWebHistory } from 'vue-router'
import { session } from './lib/api'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('./views/LoginView.vue'),
      meta: { public: true, title: '登录' }
    },
    { path: '/', redirect: '/problems' },
    {
      path: '/problems',
      name: 'problems',
      component: () => import('./views/ProblemsView.vue'),
      meta: { title: '题库' }
    },
    {
      path: '/problems/:id',
      name: 'problem',
      component: () => import('./views/ProblemView.vue'),
      meta: { title: '题目' }
    },
    {
      path: '/submissions',
      name: 'submissions',
      component: () => import('./views/SubmissionsView.vue'),
      meta: { title: '提交记录' }
    },
    {
      path: '/dashboard',
      name: 'dashboard',
      component: () => import('./views/DashboardView.vue'),
      meta: { title: '运行监控' }
    },
    {
      path: '/admin',
      name: 'admin',
      component: () => import('./views/AdminView.vue'),
      meta: { title: '题库管理', admin: true }
    },
    { path: '/:pathMatch(.*)*', redirect: '/problems' }
  ]
})

router.beforeEach((to) => {
  const token = session.token
  if (!to.meta.public && !token) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.admin && session.user?.role !== 'ADMIN') {
    return { name: 'problems' }
  }
  document.title = `${to.meta.title ?? 'JudgeX'} · JudgeX`
  return true
})

export default router
