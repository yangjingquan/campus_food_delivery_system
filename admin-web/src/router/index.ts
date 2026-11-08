import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const LoginView = () => import('../views/LoginView.vue')
const DashboardView = () => import('../views/DashboardView.vue')
const WorkspaceView = () => import('../views/WorkspaceView.vue')
const ConfigView = () => import('../views/ConfigView.vue')
const WechatView = () => import('../views/WechatView.vue')
const AdminUsersView = () => import('../views/AdminUsersView.vue')

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/overview', name: 'overview', component: DashboardView, meta: { title: '运营概览' } },
    { path: '/merchants', name: 'merchants', component: WorkspaceView, meta: { title: '商户管理' } },
    { path: '/riders', name: 'riders', component: WorkspaceView, meta: { title: '骑手管理' } },
    { path: '/orders', name: 'orders', component: WorkspaceView, meta: { title: '订单管理' } },
    { path: '/aftersales', name: 'aftersales', component: WorkspaceView, meta: { title: '售后管理' } },
    { path: '/finance', name: 'finance', component: WorkspaceView, meta: { title: '财务对账与欠款' } },
    { path: '/withdrawals', name: 'withdrawals', component: WorkspaceView, meta: { title: '提现审核' } },
    { path: '/config', name: 'config', component: ConfigView, meta: { title: '运营配置' } },
    { path: '/wechat', name: 'wechat', component: WechatView, meta: { title: '微信设置', group: '系统设置' } },
    { path: '/administrators', name: 'administrators', component: AdminUsersView, meta: { title: '管理员与角色', group: '系统设置', permission: 'admin.manage' } },
    { path: '/', redirect: '/overview' },
    { path: '/:pathMatch(.*)*', redirect: '/overview' },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.token) return { name: 'login' }
  if (to.name === 'login' && auth.token) return { name: 'overview' }
  if (to.meta.permission && !auth.can(String(to.meta.permission))) return { name: 'overview' }
})
