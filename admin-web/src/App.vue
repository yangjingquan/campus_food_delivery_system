<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from './api/admin'
import { useAuthStore } from './stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)
const showMobileNav = ref(false)
const passwordOpen=ref(false)
const passwordDraft=reactive({oldPassword:'',newPassword:'',confirm:''})

const menuGroups = [
  {
    label: '运营工作台',
    items: [
      { path: '/overview', title: '运营概览', icon: 'Grid' },
      { path: '/merchants', title: '商户管理', icon: 'Shop' },
      { path: '/products', title: '商户商品', icon: 'Tickets' },
      { path: '/food-categories', title: '食品分类', icon: 'Grid' },
      { path: '/riders', title: '骑手管理', icon: 'Bicycle' },
      { path: '/orders', title: '订单管理', icon: 'Tickets', badge: 'intervene' },
      { path: '/aftersales', title: '售后管理', icon: 'ChatDotRound' },
      { path: '/finance', title: '财务对账与欠款', icon: 'Wallet' },
      { path: '/withdrawals', title: '提现审核', icon: 'Money' },
      { path: '/config', title: '运营配置', icon: 'Setting' },
    ],
  },
  { label: '系统设置', items: [{ path: '/wechat', title: '微信设置', icon: 'Connection' }, { path: '/administrators', title: '管理员与角色', icon: 'User' }] },
]
const menuPermission: Record<string, string> = { '/overview': 'overview.view', '/merchants': 'merchant.view', '/products': 'merchant.view', '/food-categories': 'food-category.view', '/riders': 'rider.view', '/orders': 'order.view', '/aftersales': 'refund.view', '/finance': 'finance.view', '/withdrawals': 'withdrawal.view', '/config': 'config.view', '/wechat': 'wechat.view', '/administrators': 'admin.manage' }
const visibleMenuGroups = computed(() => menuGroups.map((group) => ({ ...group, items: group.items.filter((item) => auth.can(menuPermission[item.path])) })).filter((group) => group.items.length))
const title = computed(() => String(route.meta.title || '运营概览'))
const group = computed(() => route.meta.group ? String(route.meta.group) : '运营工作台')
async function logout() {
  try {
    await ElMessageBox.confirm('退出当前管理员账号？', '确认退出', { confirmButtonText: '退出', cancelButtonText: '取消' })
    auth.logout()
    await router.push('/login')
  } catch { /* 用户取消 */ }
}
function handleCommand(command: string) { if(command==='password') passwordOpen.value=true; else if(command==='logout') void logout() }
async function changePassword() {
  if(passwordDraft.newPassword!==passwordDraft.confirm){ElMessage.error('两次输入的新密码不一致。');return}
  try { await adminApi.changePassword(passwordDraft.oldPassword,passwordDraft.newPassword); passwordOpen.value=false; passwordDraft.oldPassword='';passwordDraft.newPassword='';passwordDraft.confirm='';auth.logout();ElMessage.success('密码已更新，请重新登录。');await router.push('/login') }
  catch(error){ElMessage.error(error instanceof Error?error.message:'修改失败')}
}

</script>

<template>
  <div v-if="route.meta.public" class="public-shell"><RouterView /></div>
  <div v-else class="app-frame" :class="{ 'nav-collapsed': collapsed, 'mobile-nav-open': showMobileNav }">
    <div class="mobile-scrim" @click="showMobileNav = false"></div>
    <aside class="sidebar">
      <div class="brand-lockup">
        <div class="brand-mark" aria-hidden="true"><span></span><span></span><span></span></div>
        <div class="brand-wordmark"><strong>校园食递</strong><span>CAMPUS DELIVERY</span></div>
      </div>
      <div class="nav-scroll">
        <section v-for="navGroup in visibleMenuGroups" :key="navGroup.label" class="nav-group">
          <p class="nav-heading">{{ navGroup.label }}</p>
          <el-menu :default-active="route.path" router class="side-menu" @select="showMobileNav = false">
            <el-menu-item v-for="item in navGroup.items" :key="item.path" :index="item.path">
              <el-icon><component :is="item.icon" /></el-icon>
              <span>{{ item.title }}</span>
            </el-menu-item>
          </el-menu>
        </section>
      </div>
    </aside>

    <section class="main-shell">
      <header class="topbar">
        <div class="topbar-left">
          <el-button class="mobile-menu-button" text circle :aria-label="showMobileNav ? '关闭导航' : '打开导航'" :aria-expanded="showMobileNav" @click="showMobileNav = !showMobileNav">
            <el-icon><Menu /></el-icon>
          </el-button>
          <el-button class="collapse-button" text circle :aria-label="collapsed ? '展开导航' : '收起导航'" @click="collapsed = !collapsed">
            <el-icon><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
          </el-button>
          <div class="breadcrumb"><span>管理后台</span><el-icon><ArrowRight /></el-icon><strong>{{ group }}</strong><template v-if="title !== group"><el-icon><ArrowRight /></el-icon><strong>{{ title }}</strong></template></div>
        </div>
        <div class="topbar-actions">
          <el-dropdown trigger="click" @command="handleCommand">
            <button class="profile-button" type="button"><span class="avatar">管</span><span class="profile-text"><strong>{{ auth.name }}</strong><small>{{ auth.role }}</small></span><el-icon><ArrowDown /></el-icon></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="password">修改密码</el-dropdown-item><el-dropdown-item command="logout">退出账号</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </header>
      <main class="page-content"><RouterView /></main>
    </section>
    <el-dialog v-model="passwordOpen" title="修改密码" width="min(460px,94vw)" @closed="passwordDraft.oldPassword='';passwordDraft.newPassword='';passwordDraft.confirm=''">
      <el-form label-position="top"><el-form-item label="原密码"><el-input v-model="passwordDraft.oldPassword" type="password" autocomplete="current-password" /></el-form-item><el-form-item label="新密码（至少 12 个字符）"><el-input v-model="passwordDraft.newPassword" type="password" autocomplete="new-password" /></el-form-item><el-form-item label="再次输入新密码"><el-input v-model="passwordDraft.confirm" type="password" autocomplete="new-password" /></el-form-item></el-form>
      <template #footer><el-button @click="passwordOpen=false">取消</el-button><el-button type="primary" @click="changePassword">保存</el-button></template>
    </el-dialog>
  </div>
</template>
