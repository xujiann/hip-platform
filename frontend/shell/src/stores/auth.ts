import { defineStore } from 'pinia'
import client from '../api/client'

export interface MenuItem {
  id: number
  parentId: number
  name: string
  type: 'DIR' | 'MENU' | 'BUTTON'
  path: string
  perm: string
  icon: string
}

export interface UserInfo {
  id: number
  username: string
  realName: string
  roles: string[]
  menus: MenuItem[]
  mustChangePassword?: boolean
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('hip_token') || '',
    user: null as UserInfo | null,
    // 首登/管理员重置后强制改密标志：login 与 /auth/me 都会回填（刷新页面不丢）
    mustChangePassword: false,
  }),
  getters: {
    loggedIn: (s) => !!s.token,
    menuTree(s): (MenuItem & { children: MenuItem[] })[] {
      const menus = s.user?.menus ?? []
      return menus
        .filter((m) => m.type === 'DIR')
        .map((dir) => ({
          ...dir,
          children: menus.filter((m) => m.parentId === dir.id && m.type === 'MENU'),
        }))
    },
  },
  actions: {
    async login(username: string, password: string) {
      const resp = await client.post<{ data: { token: string; mustChangePassword?: boolean } }>(
        '/auth/login', { username, password })
      this.token = resp.data.data.token
      this.mustChangePassword = !!resp.data.data.mustChangePassword
      localStorage.setItem('hip_token', this.token)
      await this.fetchMe()
    },
    async fetchMe() {
      const resp = await client.get<{ data: UserInfo }>('/auth/me')
      this.user = resp.data.data
      this.mustChangePassword = !!resp.data.data.mustChangePassword
    },
    logout() {
      this.token = ''
      this.user = null
      this.mustChangePassword = false
      localStorage.removeItem('hip_token')
    },
  },
})
