import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const LoginView = () => import('../views/LoginView.vue')
const DashboardView = () => import('../views/DashboardView.vue')
const WorkspaceView = () => import('../views/WorkspaceView.vue')
const ConfigView = () => import('../views/ConfigView.vue')
const WechatView = () => import('../views/WechatView.vue')
const AdminUsersView = () => import('../views/AdminUsersView.vue')
const ProductCatalogView = () => import('../views/ProductCatalogView.vue')
const FoodCategoriesView = () => import('../views/FoodCategoriesView.vue')

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/overview', name: 'overview', component: DashboardView, meta: { title: '运营概览' } },
    { path: '/merchants', name: 'merchants', component: WorkspaceView, meta: { title: '商户管理' } },
    { path: '/products', name: 'products', component: ProductCatalogView, meta: { title: '商户商品', permission: 'merchant.view' } },
    { path: '/food-categories', name: 'food-categories', component: FoodCategoriesView, meta: { title: '食品分类', permission: 'food-category.view' } },
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

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  try {
    await auth.restore()
  } catch {
    ElMessage.warning('暂时无法验证登录状态，请稍后刷新重试。')
  }
  if (!to.meta.public && !auth.authenticated) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.name === 'login' && auth.authenticated) return { name: 'overview' }
  if (to.meta.permission && !auth.can(String(to.meta.permission))) return { name: 'overview' }
})
