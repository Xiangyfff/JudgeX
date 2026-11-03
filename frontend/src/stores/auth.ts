import { defineStore } from 'pinia'
import { api, session, type User } from '../lib/api'

export const useAuth = defineStore('auth', {
  state: () => ({
    token: session.token as string | null,
    user: session.user as User | null,
    loading: false
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    isAdmin: (s) => s.user?.role === 'ADMIN',
    initials: (s) => (s.user?.displayName ?? '?').slice(0, 1)
  },
  actions: {
    async login(username: string, password: string) {
      this.loading = true
      try {
        const res = await api.login(username, password)
        this.token = res.token
        this.user = res.user
        session.save(res.token, res.user)
        return res.user
      } finally {
        this.loading = false
      }
    },
    async refresh() {
      if (!this.token) return
      try {
        this.user = await api.me()
        if (this.token && this.user) session.save(this.token, this.user)
      } catch {
        this.logout()
      }
    },
    logout() {
      this.token = null
      this.user = null
      session.clear()
    }
  }
})
