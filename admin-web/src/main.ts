import { createApp, type Component } from 'vue'
import {
  ElBadge, ElButton, ElDialog, ElDrawer, ElDropdown, ElDropdownItem, ElDropdownMenu, ElEmpty,
  ElForm, ElFormItem, ElIcon, ElInput, ElInputNumber, ElLoading, ElMenu, ElMenuItem, ElOption, ElPagination,
  ElRadioButton, ElRadioGroup, ElSelect, ElSwitch, ElTable, ElTableColumn, ElTabPane, ElTabs, ElTag, ElTimeSelect,
} from 'element-plus'
import {
  ArrowDown, ArrowRight, Avatar, Bicycle, ChatDotRound, CircleCheckFilled, Clock, Connection,
  Expand, Fold, Grid, Hide, InfoFilled, Lock, Menu, Money, Plus, Search, Setting, Shop, Tickets,
  User, View, Wallet, WarningFilled,
} from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'
import { pinia } from './stores/pinia'
import './styles/main.css'

const app = createApp(App)
const components = {
  ElBadge, ElButton, ElDialog, ElDrawer, ElDropdown, ElDropdownItem, ElDropdownMenu, ElEmpty,
  ElForm, ElFormItem, ElIcon, ElInput, ElInputNumber, ElMenu, ElMenuItem, ElOption, ElPagination,
  ElRadioButton, ElRadioGroup, ElSelect, ElSwitch, ElTable, ElTableColumn, ElTabPane, ElTabs, ElTag, ElTimeSelect,
  ArrowDown, ArrowRight, Avatar, Bicycle, ChatDotRound, CircleCheckFilled, Clock, Connection,
  Expand, Fold, Grid, Hide, InfoFilled, Lock, Menu, Money, Plus, Search, Setting, Shop, Tickets,
  User, View, Wallet, WarningFilled,
}
for (const [name, component] of Object.entries(components)) app.component(name, component as Component)
app.use(pinia)
app.use(ElLoading)
app.use(router)
app.mount('#app')
