<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api/admin'
import { useAuthStore } from '../stores/auth'

type UserRow = { id: number; username: string; enabled: boolean; roles: string }
type Role = { code: string; name: string }
const auth=useAuthStore()
const users=ref<UserRow[]>([]), roles=ref<Role[]>([]), loading=ref(false), createOpen=ref(false)
const draft=reactive({ username:'', password:'', role:'readonly' })
async function load(){ loading.value=true; try { const data=await adminApi.administrators(); users.value=data.users; roles.value=data.roles } catch(e){ElMessage.error(e instanceof Error?e.message:'加载失败')} finally {loading.value=false} }
async function create(){try { await adminApi.createAdministrator({...draft}); createOpen.value=false; draft.username='';draft.password='';draft.role='readonly'; ElMessage.success('管理员已创建'); await load() }catch(e){ElMessage.error(e instanceof Error?e.message:'创建失败')} }
async function save(row:UserRow){try {await ElMessageBox.confirm(`确认更新 ${row.username} 的角色和启用状态？`,'修改权限',{confirmButtonText:'确认',cancelButtonText:'取消'});await adminApi.updateAdministrator(row.id,{role:row.roles,enabled:Boolean(row.enabled)}); ElMessage.success('权限已更新，后端立即生效');await load()}catch(e){if(e instanceof Error&&!['cancel','close'].includes(e.message))ElMessage.error(e.message)} }
async function resetPassword(row:UserRow){try { const result=await ElMessageBox.prompt(`为 ${row.username} 设置新密码；所有已登录会话将失效。`,'重置密码',{inputType:'password',inputValidator:(value)=>value.length>=12||'至少 12 个字符',confirmButtonText:'重置密码',cancelButtonText:'取消'}); await adminApi.resetAdministratorPassword(row.id,result.value); ElMessage.success('密码已重置。') }catch(e){if(e instanceof Error&&!['cancel','close'].includes(e.message))ElMessage.error(e.message)} }
onMounted(load)
</script>
<template>
  <div class="page-heading"><h1>管理员与角色</h1><el-button type="primary" @click="createOpen=true">添加管理员</el-button></div>
  <div class="panel-card workbench-panel"><el-table :data="users" v-loading="loading" row-key="id"><el-table-column prop="username" label="管理员账号" min-width="160"/><el-table-column label="角色" min-width="160"><template #default="scope"><el-select v-model="scope.row.roles"><el-option v-for="role in roles" :key="role.code" :value="role.code" :label="role.name" /></el-select></template></el-table-column><el-table-column label="状态" width="150"><template #default="scope"><el-select v-model="scope.row.enabled"><el-option :value="true" label="启用"/><el-option :value="false" label="停用"/></el-select></template></el-table-column><el-table-column label="操作" width="160"><template #default="scope"><el-button link type="primary" @click="save(scope.row as unknown as UserRow)">保存</el-button><el-button v-if="scope.row.username !== auth.name" link @click="resetPassword(scope.row as unknown as UserRow)">重置密码</el-button></template></el-table-column></el-table></div>
  <el-dialog v-model="createOpen" title="添加管理员" width="min(480px,94vw)"><el-form label-position="top"><el-form-item label="账号"><el-input v-model="draft.username" autocomplete="off" /></el-form-item><el-form-item label="初始密码（至少 12 位）"><el-input v-model="draft.password" type="password" show-password autocomplete="new-password" /></el-form-item><el-form-item label="角色"><el-select v-model="draft.role"><el-option v-for="role in roles" :key="role.code" :value="role.code" :label="role.name" /></el-select></el-form-item></el-form><template #footer><el-button @click="createOpen=false">取消</el-button><el-button type="primary" @click="create">创建</el-button></template></el-dialog>
</template>
