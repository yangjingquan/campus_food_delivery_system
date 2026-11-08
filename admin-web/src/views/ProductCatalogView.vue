<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Picture } from '@element-plus/icons-vue'
import { adminApi } from '../api/admin'
import { money } from '../domain/rules'

interface ProductRow {
  id: string
  merchantId: string
  merchantName: string
  name: string
  category: string
  description: string
  priceCents: number
  stock: number
  reserved: number
  availableStock: number
  soldCount: number
  available: boolean | number | string
  hasPhoto: boolean | number | string
  updatedAt: string | number | Date
}

interface MerchantOption { id: string; name: string }

const loading = ref(false)
const rows = ref<ProductRow[]>([])
const merchants = ref<MerchantOption[]>([])
const keyword = ref('')
const merchantId = ref('')
const availability = ref('')
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const photoOpen = ref(false)
const photoLoading = ref(false)
const photoUrl = ref('')
const photoTitle = ref('')

function isAvailable(value: ProductRow['available']) {
  return value === true || value === 1 || value === '1'
}

function hasPhoto(value: ProductRow['hasPhoto']) {
  return value === true || value === 1 || value === '1'
}

async function loadProducts() {
  loading.value = true
  try {
    const result = await adminApi.products({
      keyword: keyword.value.trim(),
      merchantId: merchantId.value,
      availability: availability.value,
      page: page.value,
      pageSize: pageSize.value,
    })
    rows.value = result.rows as unknown as ProductRow[]
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '商品目录加载失败')
  } finally {
    loading.value = false
  }
}

async function loadMerchants() {
  try {
    const result = await adminApi.workspace<{ rows: MerchantOption[] }>('merchants')
    merchants.value = result.rows || []
  } catch {
    // 商户筛选不可用时仍允许管理员查看完整商品列表。
  }
}

function search() {
  page.value = 1
  void loadProducts()
}

function resetFilters() {
  keyword.value = ''
  merchantId.value = ''
  availability.value = ''
  page.value = 1
  void loadProducts()
}

function changePageSize(size: number) {
  pageSize.value = size
  page.value = 1
  void loadProducts()
}

function displayTime(value: ProductRow['updatedAt']) {
  if (!value) return '—'
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date)
}

async function viewPhoto(product: ProductRow) {
  if (!hasPhoto(product.hasPhoto)) return
  photoTitle.value = product.name
  photoOpen.value = true
  photoLoading.value = true
  if (photoUrl.value) URL.revokeObjectURL(photoUrl.value)
  photoUrl.value = ''
  try {
    const blob = await adminApi.productPhoto(product.id)
    photoUrl.value = URL.createObjectURL(blob)
  } catch (error) {
    photoOpen.value = false
    ElMessage.error(error instanceof Error ? error.message : '商品图片加载失败')
  } finally {
    photoLoading.value = false
  }
}

function clearPhoto() {
  if (photoUrl.value) URL.revokeObjectURL(photoUrl.value)
  photoUrl.value = ''
}

onMounted(() => {
  void loadProducts()
  void loadMerchants()
})
onUnmounted(clearPhoto)
</script>

<template>
  <div class="page-heading product-heading">
    <h1>商户商品</h1>
    <el-tag effect="plain" round>共 {{ total }} 件</el-tag>
  </div>

  <section class="panel-card product-catalog-panel">
    <div class="product-toolbar">
      <div class="product-filters">
        <el-input
          v-model="keyword"
          clearable
          class="product-search"
          placeholder="搜索商品、商户或分类"
          aria-label="搜索商品、商户或分类"
          :prefix-icon="Search"
          @keyup.enter="search"
        />
        <el-select v-model="merchantId" clearable filterable placeholder="全部商户" aria-label="按商户筛选" class="product-merchant-filter">
          <el-option v-for="merchant in merchants" :key="merchant.id" :label="merchant.name" :value="merchant.id" />
        </el-select>
        <el-select v-model="availability" clearable placeholder="全部状态" aria-label="按上下架状态筛选" class="product-status-filter">
          <el-option label="出售中" value="on" />
          <el-option label="已下架" value="off" />
        </el-select>
        <el-button type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
    </div>

    <el-table :data="rows" row-key="id" v-loading="loading" stripe class="data-table product-table">
      <el-table-column label="商品" min-width="270">
        <template #default="scope">
          <div class="product-cell">
            <button
              v-if="hasPhoto(scope.row.hasPhoto)"
              type="button"
              class="product-thumb product-thumb-button"
              :aria-label="`查看${scope.row.name}图片`"
              @click="viewPhoto(scope.row as ProductRow)"
            >
              <el-icon><Picture /></el-icon>
            </button>
            <span v-else class="product-thumb product-thumb-empty"><el-icon><Picture /></el-icon></span>
            <div class="product-cell-copy">
              <strong>{{ scope.row.name }}</strong>
              <span>{{ scope.row.description || '暂无商品描述' }}</span>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="merchantName" label="所属商户" min-width="150" show-overflow-tooltip />
      <el-table-column prop="category" label="分类" width="110" show-overflow-tooltip />
      <el-table-column label="单价" width="110" align="right">
        <template #default="scope"><strong class="product-price">{{ money(Number(scope.row.priceCents || 0)) }}</strong></template>
      </el-table-column>
      <el-table-column label="库存" width="150" align="center">
        <template #default="scope">
          <div class="stock-cell"><strong>{{ scope.row.availableStock }}</strong><span>/ {{ scope.row.stock }}</span></div>
          <small v-if="scope.row.reserved > 0" class="reserved-stock">预占 {{ scope.row.reserved }}</small>
        </template>
      </el-table-column>
      <el-table-column prop="soldCount" label="已售" width="90" align="right" />
      <el-table-column label="状态" width="110" align="center">
        <template #default="scope">
          <el-tag :type="isAvailable(scope.row.available) ? 'success' : 'info'" effect="light" round>
            {{ isAvailable(scope.row.available) ? '出售中' : '已下架' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" min-width="165">
        <template #default="scope">{{ displayTime(scope.row.updatedAt) }}</template>
      </el-table-column>
      <template #empty><el-empty description="没有符合条件的商品" /></template>
    </el-table>

    <div class="product-pagination">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="loadProducts"
        @size-change="changePageSize"
      />
    </div>
  </section>

  <el-dialog v-model="photoOpen" :title="photoTitle" width="min(680px, 92vw)" @closed="clearPhoto">
    <div v-loading="photoLoading" class="product-photo-viewer">
      <img v-if="photoUrl" :src="photoUrl" :alt="`${photoTitle}商品图片`" />
    </div>
  </el-dialog>
</template>
