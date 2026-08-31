import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue') },
    { path: '/portal', component: () => import('../views/portal/PortalLoginView.vue') },
  // 员工移动端独立布局（1.1.8）：曾套在桌面 MainLayout 里，PDA 上侧边栏占掉半屏
  { path: '/m', component: () => import('../views/inpatient/MobileWorkView.vue') },
    { path: '/portal/home', component: () => import('../views/portal/PortalHomeView.vue') },
    {
      path: '/',
      component: () => import('../views/MainLayout.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', component: () => import('../views/DashboardView.vue') },
        { path: 'system/users', component: () => import('../views/system/UsersView.vue') },
        { path: 'system/depts', component: () => import('../views/system/DeptsView.vue') },
        { path: 'system/roles', component: () => import('../views/system/RolesView.vue') },
        { path: 'patient/registry', component: () => import('../views/patient/PatientRegistryView.vue') },
        { path: 'outpatient/register', component: () => import('../views/outpatient/RegisterView.vue') },
        { path: 'outpatient/schedules', component: () => import('../views/outpatient/SchedulesView.vue') },
        { path: 'outpatient/doctor', component: () => import('../views/outpatient/DoctorStationView.vue') },
        { path: 'outpatient/charge', component: () => import('../views/outpatient/ChargeView.vue') },
        { path: 'outpatient/refund-approval', component: () => import('../views/outpatient/RefundApprovalView.vue') },
        { path: 'outpatient/pharmacy', component: () => import('../views/outpatient/PharmacyView.vue') },
        { path: 'outpatient/exec', component: () => import('../views/outpatient/ExecStationView.vue') },
        { path: 'integration/monitor', component: () => import('../views/integration/MonitorView.vue') },
        { path: 'cdr/patient360', component: () => import('../views/cdr/Patient360View.vue') },
        { path: 'outpatient/queue', component: () => import('../views/outpatient/QueueBoardView.vue') },
        { path: 'outpatient/review', component: () => import('../views/outpatient/ReviewView.vue') },
        { path: 'outpatient/triage', component: () => import('../views/outpatient/TriageView.vue') },
        { path: 'outpatient/critical-alerts', component: () => import('../views/outpatient/CriticalAlertView.vue') },
        { path: 'masterdata/lab-ref-range', component: () => import('../views/masterdata/LabRefRangeView.vue') },
        { path: 'hrp/assets', component: () => import('../views/hrp/AssetsView.vue') },
        { path: 'inpatient/board', component: () => import('../views/inpatient/NursingBoardView.vue') },
        { path: 'quality', component: () => import('../views/quality/QualityCenterView.vue') },
        { path: 'patientcare', component: () => import('../views/patientcare/PatientCareView.vue') },
        { path: 'hrp/ops', component: () => import('../views/hrp/HrpOpsView.vue') },
        { path: 'datagov', component: () => import('../views/datagov/DataGovView.vue') },
        { path: 'lis', component: () => import('../views/medtech/LisView.vue') },
        { path: 'ris', component: () => import('../views/medtech/RisView.vue') },
        { path: 'lis-qc', component: () => import('../views/medtech/LisQcView.vue') },
        { path: 'outpatient/appointment', component: () => import('../views/outpatient/AppointmentView.vue') },
        { path: 'surgery', component: () => import('../views/inpatient/SurgeryView.vue') },
        { path: 'system/audit', component: () => import('../views/system/AuditView.vue') },
        { path: 'reports/daily', component: () => import('../views/hrp/DailyReportView.vue') },
        { path: 'print', component: () => import('../views/PrintView.vue') },
        { path: 'masterdata/drugs', component: () => import('../views/masterdata/DrugsView.vue') },
        { path: 'masterdata/charge-items', component: () => import('../views/masterdata/ChargeItemsView.vue') },
        { path: 'masterdata/inventory', component: () => import('../views/masterdata/InventoryView.vue') },
        { path: 'masterdata/stock-take', component: () => import('../views/masterdata/StockTakeView.vue') },
        { path: 'masterdata/stock-in-accept', component: () => import('../views/masterdata/StockInAcceptView.vue') },
        { path: 'masterdata/expiry-warning', component: () => import('../views/masterdata/ExpiryWarningView.vue') },
        { path: 'inpatient/admission', component: () => import('../views/inpatient/AdmissionView.vue') },
        { path: 'inpatient/doctor', component: () => import('../views/inpatient/InpDoctorView.vue') },
        { path: 'inpatient/nurse', component: () => import('../views/inpatient/InpNurseView.vue') },
        { path: 'inpatient/discharge', component: () => import('../views/inpatient/DischargeView.vue') },
        { path: 'inpatient/consent', component: () => import('../views/inpatient/ConsentView.vue') },
        { path: 'ops', component: () => import('../views/ops/OpsView.vue') },
        { path: 'outpatient/nurse', component: () => import('../views/outpatient/OutpNurseView.vue') },
        { path: 'medtech/appointments', component: () => import('../views/medtech/ApptQueueView.vue') },
        { path: 'inpatient/blood', component: () => import('../views/inpatient/BloodView.vue') },
        { path: 'quality/single-disease', component: () => import('../views/quality/SingleDiseaseView.vue') },
        { path: 'specialty', component: () => import('../views/medtech/SpecialtyView.vue') },
        { path: 'mgmt', component: () => import('../views/quality/MgmtView.vue') },
        { path: 'datagov/standards', component: () => import('../views/datagov/DataStdView.vue') },
        { path: 'insurance', component: () => import('../views/integration/InsuranceView.vue') },
        { path: 'drg', component: () => import('../views/datagov/DrgView.vue') },
        { path: 'cdss', component: () => import('../views/outpatient/CdssView.vue') },
        { path: 'pay', component: () => import('../views/outpatient/PayView.vue') },
        { path: 'mrstats', component: () => import('../views/quality/MedStatsView.vue') },
        { path: 'mrfront', component: () => import('../views/quality/MedRecordFrontPageView.vue') },
        { path: 'emr-copy', component: () => import('../views/quality/EmrCopyView.vue') },
        { path: 'hrp/equipment', component: () => import('../views/hrp/EquipmentView.vue') },
        { path: 'nursing-plus', component: () => import('../views/quality/NursingPlusView.vue') },
        { path: 'drug-analysis', component: () => import('../views/hrp/DrugAnalysisView.vue') },
        { path: 'int-adapters', component: () => import('../views/integration/AdapterView.vue') },
        { path: 'hr', component: () => import('../views/hrp/HrView.vue') },
        { path: 'infection-plus', component: () => import('../views/quality/InfectionPlusView.vue') },
        { path: 'misc', component: () => import('../views/hrp/MiscView.vue') },
      ],
    },
  ],
})

/** 不受菜单授权约束的路径：首页、登录、打印页与患者端 */
const ALWAYS_ALLOWED = ['/', '/dashboard', '/login', '/print']   // '/403' 曾是无对应路由的死条目

router.beforeEach(async (to) => {
  // 患者端独立会话，不走院内登录
  if (to.path.startsWith('/portal')) {
    if (to.path !== '/portal' && !localStorage.getItem('hip_portal_token')) return '/portal'
    return
  }
  const token = localStorage.getItem('hip_token')
  if (!token && to.path !== '/login') return '/login'
  if (token && to.path === '/login') return '/'
  if (!token) return

  // 角色守卫：以「/auth/me 返回的菜单」为准——后端已按角色过滤过，
  // 前端跟随即可零维护。此前只判 token，医生手输 /insurance 页面照常渲染，
  // 然后并发 5-10 个请求各弹一次 403，用户完全不知道是没权限。
  if (ALWAYS_ALLOWED.some((p) => to.path === p || to.path.startsWith(p + '/'))) return
  const auth = useAuthStore()
  if (!auth.user) {
    try {
      await auth.fetchMe()
    } catch {
      return   // 拉取失败不拦路，交给接口层的 401/403 处理
    }
  }
  // 退费审批台（v30）无 sys_menu 种子（前端新增页），改按角色守卫，与后端 @PreAuthorize('hasRole ADMIN') 对齐：
  // 仅授权人（ADMIN）可进；跳过下方基于菜单命中的判断（该路径本就不在菜单里，否则会误判无权限）。
  if (to.path === '/outpatient/refund-approval') {
    if (auth.user?.roles?.includes('ADMIN')) return
    ElMessage.error('退费审批仅授权人（管理员）可访问')
    return '/dashboard'
  }
  const menus = auth.user?.menus ?? []
  if (menus.length === 0) return   // 菜单未知时不拦，避免把人锁在门外
  const allowed = menus.filter((m) => m.path).map((m) => m.path)
  const hit = allowed.some((p) => to.path === p || to.path.startsWith(p + '/'))
  if (!hit) {
    ElMessage.error('无该功能权限，请联系管理员分配角色')
    // 跳回首页而非 return false：整页加载（手输地址/刷新）时 return false 会停在空白页
    return to.path === '/dashboard' ? false : '/dashboard'
  }
})

export default router
