<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const formRef = ref()
const loading = ref(false)
const showPassword = ref(false)
const form = reactive({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入管理员账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入管理员密码', trigger: 'blur' }],
}

async function submit() {
  await formRef.value?.validate(async (valid: boolean) => {
    if (!valid) return
    loading.value = true
    try {
      await auth.login(form.username, form.password)
      ElMessage.success('登录成功。')
      const redirect = route.query.redirect
      const target = typeof redirect === 'string' && /^\/(?![\/\\])/.test(redirect) ? redirect : '/overview'
      await router.replace(target)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '登录失败。')
    } finally { loading.value = false }
  })
}
</script>

<template>
  <div class="login-page">
    <div class="login-aside">
      <div class="login-brand"><div class="brand-mark"><span></span><span></span><span></span></div><div><strong>校园食递</strong><small>CAMPUS DELIVERY</small></div></div>
      <div class="login-story">
        <h1>让每一笔订单<br />都清晰可查。</h1>
        <p class="login-intro">从入驻审核到订单协同，<br />让校园配送的每个环节井然有序。</p>
        <div class="login-features" aria-label="管理工作台功能">
          <div class="login-feature"><span class="login-feature-icon" aria-hidden="true"><el-icon><Shop /></el-icon></span><div><strong>商户与骑手</strong><small>入驻审核</small></div></div>
          <div class="login-feature"><span class="login-feature-icon" aria-hidden="true"><el-icon><Tickets /></el-icon></span><div><strong>订单与售后</strong><small>进度跟踪</small></div></div>
          <div class="login-feature"><span class="login-feature-icon" aria-hidden="true"><el-icon><Wallet /></el-icon></span><div><strong>资金与配置</strong><small>账务查询</small></div></div>
        </div>
      </div>
      <div class="login-aside-foot">校园食递 · 管理后台</div>
    </div>
    <main class="login-main">
      <div class="login-card">
        <div class="login-heading"><h2>管理员登录</h2></div>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
          <el-form-item label="管理员账号" prop="username"><el-input v-model="form.username" size="large" placeholder="请输入管理员账号" autocomplete="username"><template #prefix><el-icon><User /></el-icon></template></el-input></el-form-item>
          <el-form-item label="管理员密码" prop="password"><el-input v-model="form.password" size="large" :type="showPassword ? 'text' : 'password'" placeholder="请输入管理员密码" autocomplete="current-password"><template #prefix><el-icon><Lock /></el-icon></template><template #suffix><button type="button" class="reveal-button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword"><el-icon><View v-if="!showPassword" /><Hide v-else /></el-icon></button></template></el-input></el-form-item>
          <el-button type="primary" native-type="submit" size="large" class="login-submit" :loading="loading">进入管理后台<el-icon class="arrow-icon"><ArrowRight /></el-icon></el-button>
        </el-form>
      </div>
      <div class="login-footer">校园食递 · 管理后台</div>
    </main>
  </div>
</template>
