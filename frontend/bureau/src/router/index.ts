import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue') },
    {
      path: '/',
      component: () => import('../views/MainLayout.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', component: () => import('../views/case/StatsView.vue') },
        { path: 'case/clues', component: () => import('../views/case/CluesView.vue') },
        { path: 'case/list', component: () => import('../views/case/CasesView.vue') },
        { path: 'case/detail/:id', component: () => import('../views/case/CaseDetailView.vue') },
        { path: 'case/causes', component: () => import('../views/case/CausesView.vue') },
        { path: 'case/supervise', component: () => import('../views/case/SupervisionView.vue') },
        { path: 'case/stats', component: () => import('../views/case/StatsView.vue') },
        { path: 'system/users', component: () => import('../views/system/UsersView.vue') },
        { path: 'system/depts', component: () => import('../views/system/DeptsView.vue') },
        { path: 'system/roles', component: () => import('../views/system/RolesView.vue') },
      ],
    },
  ],
})

router.beforeEach((to) => {
  const token = localStorage.getItem('bureau_token')
  if (!token && to.path !== '/login') return '/login'
  if (token && to.path === '/login') return '/'
})

export default router
