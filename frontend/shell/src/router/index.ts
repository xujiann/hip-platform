import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue') },
    { path: '/portal', component: () => import('../views/portal/PortalLoginView.vue') },
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
        { path: 'outpatient/pharmacy', component: () => import('../views/outpatient/PharmacyView.vue') },
        { path: 'outpatient/exec', component: () => import('../views/outpatient/ExecStationView.vue') },
        { path: 'integration/monitor', component: () => import('../views/integration/MonitorView.vue') },
        { path: 'cdr/patient360', component: () => import('../views/cdr/Patient360View.vue') },
        { path: 'outpatient/queue', component: () => import('../views/outpatient/QueueBoardView.vue') },
        { path: 'outpatient/review', component: () => import('../views/outpatient/ReviewView.vue') },
        { path: 'outpatient/triage', component: () => import('../views/outpatient/TriageView.vue') },
        { path: 'hrp/assets', component: () => import('../views/hrp/AssetsView.vue') },
        { path: 'inpatient/board', component: () => import('../views/inpatient/NursingBoardView.vue') },
        { path: 'quality', component: () => import('../views/quality/QualityCenterView.vue') },
        { path: 'patientcare', component: () => import('../views/patientcare/PatientCareView.vue') },
        { path: 'hrp/ops', component: () => import('../views/hrp/HrpOpsView.vue') },
        { path: 'datagov', component: () => import('../views/datagov/DataGovView.vue') },
        { path: 'lis', component: () => import('../views/medtech/LisView.vue') },
        { path: 'ris', component: () => import('../views/medtech/RisView.vue') },
        { path: 'surgery', component: () => import('../views/inpatient/SurgeryView.vue') },
        { path: 'system/audit', component: () => import('../views/system/AuditView.vue') },
        { path: 'reports/daily', component: () => import('../views/hrp/DailyReportView.vue') },
        { path: 'print', component: () => import('../views/PrintView.vue') },
        { path: 'masterdata/drugs', component: () => import('../views/masterdata/DrugsView.vue') },
        { path: 'masterdata/charge-items', component: () => import('../views/masterdata/ChargeItemsView.vue') },
        { path: 'masterdata/inventory', component: () => import('../views/masterdata/InventoryView.vue') },
        { path: 'inpatient/admission', component: () => import('../views/inpatient/AdmissionView.vue') },
        { path: 'inpatient/doctor', component: () => import('../views/inpatient/InpDoctorView.vue') },
        { path: 'inpatient/nurse', component: () => import('../views/inpatient/InpNurseView.vue') },
        { path: 'inpatient/discharge', component: () => import('../views/inpatient/DischargeView.vue') },
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
        { path: 'm', component: () => import('../views/inpatient/MobileWorkView.vue') },
        { path: 'hrp/equipment', component: () => import('../views/hrp/EquipmentView.vue') },
        { path: 'nursing-plus', component: () => import('../views/quality/NursingPlusView.vue') },
        { path: 'drug-analysis', component: () => import('../views/hrp/DrugAnalysisView.vue') },
        { path: 'int-adapters', component: () => import('../views/integration/AdapterView.vue') },
      ],
    },
  ],
})

router.beforeEach((to) => {
  // 患者端独立会话，不走院内登录
  if (to.path.startsWith('/portal')) {
    if (to.path !== '/portal' && !localStorage.getItem('hip_portal_token')) return '/portal'
    return
  }
  const token = localStorage.getItem('hip_token')
  if (!token && to.path !== '/login') return '/login'
  if (token && to.path === '/login') return '/'
})

export default router
