<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi, type FoodCategory } from '../api/admin'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const canEdit = computed(() => auth.can('food-category.edit'))
const rows = ref<FoodCategory[]>([])
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)
const dialog = ref(false)
const editing = ref<FoodCategory>()
const name = ref('')
const formError = ref('')

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    rows.value = await adminApi.foodCategories()
  } catch (error) {
    rows.value = []
    loadError.value = error instanceof Error ? error.message : '食品分类读取失败'
  } finally {
    loading.value = false
  }
}

function openEditor(category?: FoodCategory) {
  editing.value = category
  name.value = category?.name ?? ''
  formError.value = ''
  dialog.value = true
}

async function save() {
  if (busy.value) return
  const value = name.value.trim()
  if (!value || value === '全部') {
    formError.value = '请输入分类名称，不能使用「全部」'
    return
  }
  busy.value = true
  formError.value = ''
  try {
    await adminApi.saveFoodCategory(value, editing.value?.id)
    dialog.value = false
    ElMessage.success('分类已保存')
    await load()
  } catch (error) {
    formError.value = error instanceof Error ? error.message : '分类保存失败'
  } finally {
    busy.value = false
  }
}

async function remove(category: FoodCategory) {
  if (busy.value || category.productCount > 0) return
  busy.value = true
  try {
    await ElMessageBox.confirm(`删除「${category.name}」分类？`, '删除食品分类', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
    })
    await adminApi.deleteFoodCategory(category.id)
    ElMessage.success('分类已删除')
    await load()
  } catch (error) {
    if (error instanceof Error) {
      ElMessage.error(error.message)
      await load()
    }
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-heading">
    <h1>食品分类</h1>
    <el-button v-if="canEdit" type="primary" :disabled="busy" @click="openEditor()">新增分类</el-button>
  </div>

  <section class="panel-card workbench-panel">
    <el-table v-loading="loading" :data="rows" class="data-table" row-key="id" stripe>
      <el-table-column prop="name" label="分类名称" min-width="120" show-overflow-tooltip />
      <el-table-column prop="productCount" label="关联商品" width="100" align="right" />
      <el-table-column v-if="canEdit" label="操作" width="150">
        <template #default="{ row }">
          <div class="category-actions">
            <el-button type="primary" link :disabled="busy" @click="openEditor(row as FoodCategory)">改名</el-button>
            <el-button v-if="row.productCount === 0" type="danger" link :disabled="busy" @click="remove(row as FoodCategory)">删除</el-button>
          </div>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="loadError || '暂无食品分类'">
          <el-button v-if="loadError" @click="load">重新加载</el-button>
        </el-empty>
      </template>
    </el-table>
  </section>

  <el-dialog v-model="dialog" :title="editing ? '修改食品分类' : '新增食品分类'"
    width="min(440px, 92vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy">
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="分类名称" :error="formError" required>
        <el-input v-model="name" maxlength="40" show-word-limit :disabled="busy" />
      </el-form-item>
    </el-form>
    <template #footer>
      <div class="category-actions category-footer">
        <el-button :disabled="busy" @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="save">保存</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.category-actions { display: flex; gap: 12px; align-items: center; }
.category-footer { justify-content: flex-end; }
</style>
