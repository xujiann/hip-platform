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
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('bureau_token') || '',
    user: null as UserInfo | null,
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
      const resp = await client.post<{ data: { token: string } }>('/auth/login', { username, password })
      this.token = resp.data.data.token
      localStorage.setItem('bureau_token', this.token)
      await this.fetchMe()
    },
    async fetchMe() {
      const resp = await client.get<{ data: UserInfo }>('/auth/me')
      this.user = resp.data.data
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('bureau_token')
    },
  },
})
