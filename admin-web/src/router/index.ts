import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const LoginView = () => import('../views/LoginView.vue')
const DashboardView = () => import('../views/DashboardView.vue')
const WorkspaceView = () => import('../views/WorkspaceView.vue')
const ConfigView = () => import('../views/ConfigView.vue')
const WechatView = () => import('../views/WechatView.vue')
const AdminUsersView = () => import('../views/AdminUsersView.vue')
const UserDirectoryView = () => import('../views/UserDirectoryView.vue')
const ProductCatalogView = () => import('../views/ProductCatalogView.vue')
const FoodCategoriesView = () => import('../views/FoodCategoriesView.vue')
const HomeBannersView = () => import('../views/HomeBannersView.vue')

export const financePages = [
  { section: 'accounts', title: '商户 / 骑手余额' },
  { section: 'ledger', title: '订单账务' },
  { section: 'journal', title: '账户流水' },
  { section: 'debts', title: '欠款与平台垫付' },
] as const

export type FinanceSection = (typeof financePages)[number]['section']

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/overview', name: 'overview', component: DashboardView, meta: { title: '运营概览', permission: 'overview.view' } },
    { path: '/users', name: 'users', component: UserDirectoryView, meta: { title: '用户列表', permission: 'user.view' } },
    { path: '/merchants', name: 'merchants', component: WorkspaceView, meta: { title: '商户列表', permission: 'merchant.view' } },
    { path: '/products', name: 'products', component: ProductCatalogView, meta: { title: '商户商品', permission: 'merchant.view' } },
    { path: '/food-categories', name: 'food-categories', component: FoodCategoriesView, meta: { title: '食品分类', permission: 'food-category.view' } },
    { path: '/riders', name: 'riders', component: WorkspaceView, meta: { title: '骑手管理', permission: 'rider.view' } },
    { path: '/orders', name: 'orders', component: WorkspaceView, meta: { title: '订单列表', permission: 'order.view' } },
    { path: '/aftersales', name: 'aftersales', component: WorkspaceView, meta: { title: '售后管理', permission: 'refund.view' } },
    { path: '/finance', name: 'finance', redirect: { name: 'finance-accounts' } },
    ...financePages.map(({ section, title }) => ({
      path: `/finance/${section}`,
      name: `finance-${section}`,
      component: WorkspaceView,
      meta: { title, permission: 'finance.view', financeSection: section },
    })),
    { path: '/withdrawals', name: 'withdrawals', component: WorkspaceView, meta: { title: '提现审核', permission: 'withdrawal.view' } },
    { path: '/config', name: 'config', component: ConfigView, meta: { title: '运营配置', permission: 'config.view' } },
    { path: '/home-banners', name: 'home-banners', component: HomeBannersView, meta: { title: '首页轮播图', permission: 'home-banner.view' } },
    { path: '/wechat', name: 'wechat', component: WechatView, meta: { title: '微信设置', group: '系统设置', permission: 'wechat.view' } },
    { path: '/administrators', name: 'administrators', component: AdminUsersView, meta: { title: '管理员与角色', group: '系统设置', permission: 'admin.manage' } },
    { path: '/no-access', name: 'no-access', component: () => import('../views/NoAccessView.vue'), meta: { title: '暂无可用权限' } },
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
  if ((to.name === 'login' && auth.authenticated) || (to.meta.permission && !auth.can(String(to.meta.permission)))) {
    const allowed = router.getRoutes().find(route => route.name && route.meta.permission && auth.can(String(route.meta.permission)))
    return { name: allowed?.name ?? 'no-access' }
  }
})
