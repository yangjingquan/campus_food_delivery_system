import type * as ElementPlus from 'element-plus'
import type * as ElementPlusIcons from '@element-plus/icons-vue'
import type * as VueRouter from 'vue-router'

declare module '@vue/runtime-core' {
  export interface GlobalComponents {
    ElBadge: typeof ElementPlus.ElBadge
    ElButton: typeof ElementPlus.ElButton
    ElDialog: typeof ElementPlus.ElDialog
    ElDrawer: typeof ElementPlus.ElDrawer
    ElDropdown: typeof ElementPlus.ElDropdown
    ElDropdownItem: typeof ElementPlus.ElDropdownItem
    ElDropdownMenu: typeof ElementPlus.ElDropdownMenu
    ElEmpty: typeof ElementPlus.ElEmpty
    ElForm: typeof ElementPlus.ElForm
    ElFormItem: typeof ElementPlus.ElFormItem
    ElIcon: typeof ElementPlus.ElIcon
    ElInput: typeof ElementPlus.ElInput
    ElInputNumber: typeof ElementPlus.ElInputNumber
    ElMenu: typeof ElementPlus.ElMenu
    ElMenuItem: typeof ElementPlus.ElMenuItem
    ElOption: typeof ElementPlus.ElOption
    ElRadioButton: typeof ElementPlus.ElRadioButton
    ElRadioGroup: typeof ElementPlus.ElRadioGroup
    ElSelect: typeof ElementPlus.ElSelect
    ElSwitch: typeof ElementPlus.ElSwitch
    ElTable: typeof ElementPlus.ElTable
    ElTableColumn: typeof ElementPlus.ElTableColumn
    ElTabPane: typeof ElementPlus.ElTabPane
    ElTabs: typeof ElementPlus.ElTabs
    ElTag: typeof ElementPlus.ElTag
    ElTimeSelect: typeof ElementPlus.ElTimeSelect
    RouterLink: typeof VueRouter.RouterLink
    RouterView: typeof VueRouter.RouterView
    ArrowDown: typeof ElementPlusIcons.ArrowDown
    ArrowRight: typeof ElementPlusIcons.ArrowRight
    Avatar: typeof ElementPlusIcons.Avatar
    Bicycle: typeof ElementPlusIcons.Bicycle
    ChatDotRound: typeof ElementPlusIcons.ChatDotRound
    CircleCheckFilled: typeof ElementPlusIcons.CircleCheckFilled
    Clock: typeof ElementPlusIcons.Clock
    Connection: typeof ElementPlusIcons.Connection
    Expand: typeof ElementPlusIcons.Expand
    Fold: typeof ElementPlusIcons.Fold
    Grid: typeof ElementPlusIcons.Grid
    Hide: typeof ElementPlusIcons.Hide
    InfoFilled: typeof ElementPlusIcons.InfoFilled
    Lock: typeof ElementPlusIcons.Lock
    Menu: typeof ElementPlusIcons.Menu
    Money: typeof ElementPlusIcons.Money
    Plus: typeof ElementPlusIcons.Plus
    Search: typeof ElementPlusIcons.Search
    Setting: typeof ElementPlusIcons.Setting
    Shop: typeof ElementPlusIcons.Shop
    Tickets: typeof ElementPlusIcons.Tickets
    User: typeof ElementPlusIcons.User
    View: typeof ElementPlusIcons.View
    Wallet: typeof ElementPlusIcons.Wallet
    WarningFilled: typeof ElementPlusIcons.WarningFilled
  }
}

export {}
