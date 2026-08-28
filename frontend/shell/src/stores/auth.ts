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
    /**
     * 后端下发的按钮级权限点集合（menus 里 type='BUTTON' 且有 perm 的项）。
     * 当前 sys_menu 种子只种了 DIR/MENU、尚无 BUTTON 行——此集合为空，hasPerm 走「未启用即放行」降级。
     */
    buttonPerms(s): Set<string> {
      const menus = s.user?.menus ?? []
      return new Set(menus.filter((m) => m.type === 'BUTTON' && m.perm).map((m) => m.perm))
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
    /**
     * 按钮级权限判定（UX 收口，非安全边界——后端 @PreAuthorize 才是兜底）。
     *
     * 安全降级：若后端尚未种任何 BUTTON 权限点（buttonPerms 为空），说明按钮级权限体系还没启用，
     * 一律放行——否则会把所有受控按钮误藏光。只有当该 perm 已被后端定义、且当前用户不具备时才隐藏。
     */
    hasPerm(perm: string): boolean {
      const perms = this.buttonPerms
      if (perms.size === 0) return true
      return perms.has(perm)
    },
  },
})
