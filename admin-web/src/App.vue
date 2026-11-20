<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import { adminApi } from './api/admin'
import { financePages } from './router'
import { useAuthStore } from './stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)
const showMobileNav = ref(false)
const compactNav = computed(() => collapsed.value && !showMobileNav.value)
const mobileNavButton = ref<HTMLButtonElement>()
const mobileCloseButton = ref<HTMLButtonElement>()
const mainContent = ref<HTMLElement>()
const desktopViewport = window.matchMedia('(min-width: 761px)')
const passwordOpen=ref(false)
const passwordDraft=reactive({oldPassword:'',newPassword:'',confirm:''})

type MenuEntry = { name: string; icon: string } | { id: string; title: string; icon: string; children: string[] }
const menuGroups: { label: string; items: MenuEntry[] }[] = [
  {
    label: '运营工作台',
    items: [
      { name: 'overview', icon: 'Grid' },
      { id: 'merchant-nav', title: '商户管理', icon: 'Shop', children: ['merchants', 'products'] },
      { name: 'food-categories', icon: 'Grid' },
      { name: 'riders', icon: 'Bicycle' },
      { id: 'order-nav', title: '订单管理', icon: 'Tickets', children: ['orders', 'aftersales'] },
      { id: 'finance-nav', title: '财务对账', icon: 'Wallet', children: financePages.map(({ section }) => `finance-${section}`) },
      { name: 'withdrawals', icon: 'Money' },
      { name: 'config', icon: 'Setting' },
    ],
  },
  { label: '系统设置', items: [{ name: 'wechat', icon: 'Connection' }, { name: 'administrators', icon: 'User' }] },
]
function menuLink(name: string) {
  const { meta } = router.resolve({ name })
  return { name, title: String(meta.title), permission: String(meta.permission || '') }
}
const visibleMenuGroups = computed(() => menuGroups.map(group => ({
  ...group,
  items: group.items.map(item => 'children' in item
    ? { ...item, children: item.children.map(menuLink).filter(link => auth.can(link.permission)) }
    : { ...item, ...menuLink(item.name) },
  ).filter(item => 'children' in item ? item.children.length : auth.can(item.permission)),
})).filter(group => group.items.length))
const activeParent = computed(() => visibleMenuGroups.value.flatMap(group => group.items).find(
  item => 'children' in item && item.children.some(link => link.name === route.name),
))
const parentTitle = computed(() => activeParent.value?.title)
const openDrawer = ref<string>()
watch([() => route.name, activeParent], () => {
  const parent = activeParent.value
  if (parent && 'id' in parent) openDrawer.value = parent.id
}, { immediate: true })
function toggleDrawer(id: string) {
  if (compactNav.value) {
    collapsed.value = false
    openDrawer.value = id
  } else {
    openDrawer.value = openDrawer.value === id ? undefined : id
  }
}
async function openMobileNav() {
  showMobileNav.value = true
  await nextTick()
  mobileCloseButton.value?.focus({ preventScroll: true })
}
async function closeMobileNav(focusPage = false) {
  if (!showMobileNav.value) return
  showMobileNav.value = false
  await nextTick()
  const destination = focusPage ? mainContent.value : mobileNavButton.value
  destination?.focus({ preventScroll: true })
}
function onNavLinkClick(event: MouseEvent) {
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  void closeMobileNav(true)
}
function closeMobileNavOnDesktop(event: MediaQueryListEvent) {
  if (event.matches && showMobileNav.value) void closeMobileNav(true)
}
onMounted(() => desktopViewport.addEventListener('change', closeMobileNavOnDesktop))
onUnmounted(() => desktopViewport.removeEventListener('change', closeMobileNavOnDesktop))
const title = computed(() => String(route.meta.title || '运营概览'))
const group = computed(() => route.meta.group ? String(route.meta.group) : '运营工作台')
async function logout() {
  try {
    await ElMessageBox.confirm('退出当前管理员账号？', '确认退出', { confirmButtonText: '退出', cancelButtonText: '取消' })
    await auth.logout()
  } catch (error) {
    if (error instanceof Error) ElMessage.error(error.message)
  }
}
watch(() => auth.authenticated, (authenticated) => {
  if (!authenticated && !route.meta.public) void router.replace({ name: 'login' })
})
function handleCommand(command: string) { if(command==='password') passwordOpen.value=true; else if(command==='logout') void logout() }
async function changePassword() {
  if(passwordDraft.newPassword!==passwordDraft.confirm){ElMessage.error('两次输入的新密码不一致。');return}
  try { await adminApi.changePassword(passwordDraft.oldPassword,passwordDraft.newPassword); passwordOpen.value=false; passwordDraft.oldPassword='';passwordDraft.newPassword='';passwordDraft.confirm='';auth.clear();ElMessage.success('密码已更新，请重新登录。') }
  catch(error){ElMessage.error(error instanceof Error?error.message:'修改失败')}
}

</script>

<template>
  <div v-if="route.meta.public" class="public-shell"><RouterView /></div>
  <div v-else class="app-frame" :class="{ 'nav-collapsed': compactNav, 'mobile-nav-open': showMobileNav }">
    <div class="mobile-scrim" aria-hidden="true" @click="closeMobileNav()"></div>
    <aside id="admin-sidebar" class="sidebar" :role="showMobileNav ? 'dialog' : undefined"
      :aria-modal="showMobileNav ? true : undefined" :aria-label="showMobileNav ? '后台导航' : undefined"
      @keydown.esc="closeMobileNav()">
      <div class="brand-lockup">
        <div class="brand-mark" aria-hidden="true"><span></span><span></span><span></span></div>
        <div class="brand-wordmark"><strong>校园食递</strong><span>CAMPUS DELIVERY</span></div>
        <button ref="mobileCloseButton" type="button" class="mobile-nav-close" aria-label="关闭导航" @click="closeMobileNav()">
          <el-icon aria-hidden="true"><Close /></el-icon>
        </button>
      </div>
      <nav class="nav-scroll" aria-label="后台导航">
        <section v-for="navGroup in visibleMenuGroups" :key="navGroup.label" class="nav-group">
          <p class="nav-heading">{{ navGroup.label }}</p>
          <ul class="side-nav">
            <li v-for="item in navGroup.items" :key="'children' in item ? item.id : item.name">
              <template v-if="'children' in item">
                <button class="side-nav-row" :class="{ 'has-current': activeParent === item }" type="button"
                  :aria-label="item.title" :title="compactNav ? item.title : undefined"
                  :aria-expanded="!compactNav && openDrawer === item.id" :aria-controls="item.id" @click="toggleDrawer(item.id)">
                  <el-icon class="side-nav-icon" aria-hidden="true"><component :is="item.icon" /></el-icon>
                  <span class="side-nav-label">{{ item.title }}</span>
                  <el-icon class="side-nav-chevron" aria-hidden="true"><ArrowRight /></el-icon>
                </button>
                <div :id="item.id" class="side-nav-disclosure" :class="{ 'is-open': !compactNav && openDrawer === item.id }"
                  :inert="compactNav || openDrawer !== item.id">
                  <div class="side-nav-disclosure-inner">
                    <ul class="side-nav-children">
                      <li v-for="child in item.children" :key="child.name">
                        <RouterLink class="side-nav-row" :to="{ name: child.name }" @click="onNavLinkClick">{{ child.title }}</RouterLink>
                      </li>
                    </ul>
                  </div>
                </div>
              </template>
              <RouterLink v-else class="side-nav-row" :to="{ name: item.name }" :aria-label="item.title"
                :title="compactNav ? item.title : undefined" @click="onNavLinkClick">
                <el-icon class="side-nav-icon" aria-hidden="true"><component :is="item.icon" /></el-icon>
                <span class="side-nav-label">{{ item.title }}</span>
              </RouterLink>
            </li>
          </ul>
        </section>
      </nav>
    </aside>

    <section class="main-shell" :inert="showMobileNav">
      <header class="topbar">
        <div class="topbar-left">
          <button ref="mobileNavButton" type="button" class="mobile-menu-button" aria-label="打开导航"
            :aria-expanded="showMobileNav" aria-controls="admin-sidebar" @click="openMobileNav">
            <el-icon aria-hidden="true"><Menu /></el-icon>
          </button>
          <el-button class="collapse-button" text circle :aria-label="collapsed ? '展开导航' : '收起导航'"
            :aria-expanded="!collapsed" aria-controls="admin-sidebar" @click="collapsed = !collapsed">
            <el-icon><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
          </el-button>
          <div class="breadcrumb">
            <span class="breadcrumb-context">管理后台 <el-icon><ArrowRight /></el-icon> {{ group }} <el-icon><ArrowRight /></el-icon></span>
            <template v-if="parentTitle"><strong>{{ parentTitle }}</strong><el-icon><ArrowRight /></el-icon></template>
            <strong>{{ title }}</strong>
          </div>
        </div>
        <div class="topbar-actions">
          <el-dropdown trigger="click" @command="handleCommand">
            <button class="profile-button" type="button"><span class="avatar">管</span><span class="profile-text"><strong>{{ auth.name }}</strong><small>{{ auth.role }}</small></span><el-icon><ArrowDown /></el-icon></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="password">修改密码</el-dropdown-item><el-dropdown-item command="logout">退出账号</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </header>
      <main ref="mainContent" class="page-content" tabindex="-1"><RouterView /></main>
    </section>
    <el-dialog v-model="passwordOpen" title="修改密码" width="min(460px,94vw)" @closed="passwordDraft.oldPassword='';passwordDraft.newPassword='';passwordDraft.confirm=''">
      <el-form label-position="top"><el-form-item label="原密码"><el-input v-model="passwordDraft.oldPassword" type="password" autocomplete="current-password" /></el-form-item><el-form-item label="新密码（至少 12 个字符）"><el-input v-model="passwordDraft.newPassword" type="password" autocomplete="new-password" /></el-form-item><el-form-item label="再次输入新密码"><el-input v-model="passwordDraft.confirm" type="password" autocomplete="new-password" /></el-form-item></el-form>
      <template #footer><el-button @click="passwordOpen=false">取消</el-button><el-button type="primary" @click="changePassword">保存</el-button></template>
    </el-dialog>
  </div>
</template>
