<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElImage, ElMessage, ElMessageBox, ElUpload, type UploadInstance, type UploadProps } from 'element-plus'
import { Search, Picture, Upload } from '@element-plus/icons-vue'
import { adminApi, type FoodCategory } from '../api/admin'
import ListPagination from '../components/ListPagination.vue'
import ProductThumbnail from '../components/ProductThumbnail.vue'
import { encodePhoto, isPhotoFile } from '../domain/photo'
import { money } from '../domain/rules'
import { useAuthStore } from '../stores/auth'

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

interface MerchantOption { id: string; name: string; auditStatus: string }

const auth = useAuthStore()
const editable = computed(() => auth.can('product.manage'))
const loading = ref(false)
const rows = ref<ProductRow[]>([])
const merchants = ref<MerchantOption[]>([])
const keyword = ref('')
const merchantId = ref('')
const availability = ref('')
const appliedFilters = ref({ keyword: '', merchantId: '', availability: '' })
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
let productRequest = 0
const photoOpen = ref(false)
const photoLoading = ref(false)
const photoUrl = ref('')
const photoTitle = ref('')
let photoRequest = 0
const editorOpen = ref(false)
const editing = ref<ProductRow | null>(null)
const saving = ref(false)
const busyId = ref('')
const categories = ref<FoodCategory[]>([])
const approvedMerchants = computed(() => merchants.value.filter(store => store.auditStatus === 'APPROVED'))
const form = reactive({
  merchantId: '', name: '', category: '', description: '',
  price: undefined as number | undefined, stock: 0 as number | undefined,
})
const imageUpload = ref<UploadInstance>()
const selectedImage = ref<File>()
const imagePreview = ref('')
const photoRevisions = reactive<Record<string, number>>({})

function isAvailable(value: ProductRow['available']) {
  return value === true || value === 1 || value === '1'
}

function hasPhoto(value: ProductRow['hasPhoto']) {
  return value === true || value === 1 || value === '1'
}

async function loadProducts() {
  const request = ++productRequest
  loading.value = true
  try {
    const result = await adminApi.products({
      ...appliedFilters.value,
      page: page.value,
      pageSize: pageSize.value,
    })
    if (request !== productRequest) return
    total.value = result.total
    const lastPage = Math.max(1, Math.ceil(total.value / pageSize.value))
    if (page.value > lastPage) {
      page.value = lastPage
      return
    }
    rows.value = result.rows as unknown as ProductRow[]
  } catch (error) {
    if (request === productRequest) ElMessage.error(error instanceof Error ? error.message : '商品目录加载失败')
  } finally {
    if (request === productRequest) loading.value = false
  }
}

async function loadMerchants() {
  try {
    const result = await adminApi.workspace<{ rows: MerchantOption[] }>('merchants')
    merchants.value = (result.rows || []).filter(store => !store.id.startsWith('revision:'))
  } catch {
    // 商户筛选不可用时仍允许管理员查看完整商品列表。
  }
}

async function openEditor(product?: ProductRow) {
  editing.value = product ?? null
  clearSelectedImage()
  Object.assign(form, {
    merchantId: product?.merchantId || approvedMerchants.value.find(store => store.id === merchantId.value)?.id || '',
    name: product?.name || '', category: product?.category || '', description: product?.description || '',
    price: product ? product.priceCents / 100 : undefined, stock: product?.stock ?? 0,
  })
  editorOpen.value = true
  try {
    categories.value = await adminApi.foodCategories()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '食品分类加载失败')
  }
}

function clearSelectedImage() {
  if (imagePreview.value) URL.revokeObjectURL(imagePreview.value)
  imagePreview.value = ''
  selectedImage.value = undefined
}

const selectImage: UploadProps['onChange'] = ({ raw: file }) => {
  imageUpload.value?.clearFiles()
  if (!file || saving.value || !editorOpen.value) return
  if (!isPhotoFile(file)) {
    ElMessage.warning('请选择不超过 1 MB 的 JPEG 或 PNG 图片')
    return
  }
  clearSelectedImage()
  selectedImage.value = file
  imagePreview.value = URL.createObjectURL(file)
}

async function saveProduct() {
  if (saving.value) return
  const priceCents = Math.round(Number(form.price) * 100)
  const stock = form.stock
  if (!form.merchantId || !form.name.trim() || !form.category || !Number.isInteger(priceCents) || priceCents < 1
      || stock === undefined || !Number.isInteger(stock) || stock < 0 || (!editing.value && !selectedImage.value)) {
    ElMessage.warning('请填写商品资料、有效价格和库存，并为新增商品选择图片')
    return
  }
  saving.value = true
  try {
    const image = selectedImage.value ? await encodePhoto(selectedImage.value) : ''
    const editedId = editing.value?.id
    await adminApi.saveProduct({
      merchantId: form.merchantId, name: form.name.trim(), category: form.category, description: form.description.trim(),
      priceCents, stock, previousStock: editing.value?.stock ?? 0, image,
    }, editedId)
    if (editedId && image) photoRevisions[editedId] = (photoRevisions[editedId] ?? 0) + 1
    editorOpen.value = false
    ElMessage.success('商品已保存')
    await loadProducts()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '商品保存失败')
  } finally {
    saving.value = false
  }
}

async function changeProduct(product: ProductRow, remove = false) {
  if (busyId.value) return
  const available = !isAvailable(product.available)
  const label = remove ? '删除' : available ? '上架' : '下架'
  busyId.value = product.id
  try {
    if (remove || !available) {
      await ElMessageBox.confirm(remove ? '商品将从列表和用户端移除，已有订单仍可处理。'
        : '下架后，用户无法新购该商品。', label + '商品 ' + product.name, {
        confirmButtonText: label, cancelButtonText: '取消', type: 'warning',
      })
    }
    if (remove) await adminApi.deleteProduct(product.id)
    else await adminApi.setProductAvailability(product.id, available)
    ElMessage.success('商品已' + label)
    await loadProducts()
  } catch (error) {
    if (error instanceof Error) ElMessage.error(error.message)
  } finally {
    busyId.value = ''
  }
}

function search() {
  page.value = 1
  appliedFilters.value = { keyword: keyword.value.trim(), merchantId: merchantId.value, availability: availability.value }
}

function resetFilters() {
  keyword.value = ''
  merchantId.value = ''
  availability.value = ''
  search()
}

function displayTime(value: ProductRow['updatedAt']) {
  if (!value) return '—'
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date)
}

async function viewPhoto(product: ProductRow) {
  if (!hasPhoto(product.hasPhoto)) return
  clearPhoto()
  const request = photoRequest
  photoTitle.value = product.name
  photoOpen.value = true
  photoLoading.value = true
  try {
    const blob = await adminApi.productPhoto(product.id)
    if (request === photoRequest) photoUrl.value = URL.createObjectURL(blob)
  } catch (error) {
    if (request === photoRequest) {
      photoOpen.value = false
      ElMessage.error(error instanceof Error ? error.message : '商品图片加载失败')
    }
  } finally {
    if (request === photoRequest) photoLoading.value = false
  }
}

function clearPhoto() {
  photoRequest++
  if (photoUrl.value) URL.revokeObjectURL(photoUrl.value)
  photoUrl.value = ''
}

watch([page, pageSize, appliedFilters], loadProducts, { immediate: true })
onMounted(loadMerchants)
onUnmounted(() => {
  productRequest++
  clearPhoto()
  clearSelectedImage()
})
</script>

<template>
  <div class="page-heading product-heading">
    <h1>商户商品</h1>
    <el-button v-if="editable" type="primary" @click="openEditor()">新增商品</el-button>
    <el-tag v-else effect="plain" round>共 {{ total }} 件</el-tag>
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
              <ProductThumbnail
                :key="`${scope.row.id}:${scope.row.updatedAt}:${photoRevisions[scope.row.id] ?? 0}`"
                :product-id="scope.row.id"
              />
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
      <el-table-column v-if="editable" label="操作" width="175" fixed="right">
        <template #default="scope">
          <el-button link type="primary" @click="openEditor(scope.row as ProductRow)">编辑</el-button>
          <el-button link :type="isAvailable(scope.row.available) ? 'warning' : 'success'"
            :disabled="busyId === scope.row.id" @click="changeProduct(scope.row as ProductRow)">
            {{ isAvailable(scope.row.available) ? '下架' : '上架' }}
          </el-button>
          <el-button link type="danger" :disabled="busyId === scope.row.id" @click="changeProduct(scope.row as ProductRow, true)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="没有符合条件的商品" /></template>
    </el-table>

    <ListPagination v-model:page="page" v-model:page-size="pageSize" :total="total" :disabled="loading" jumper />
  </section>

  <el-dialog v-model="editorOpen" :title="editing ? '编辑商品' : '新增商品'" width="min(540px, 94vw)"
    :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving"
    @closed="clearSelectedImage">
    <el-form label-position="top" :disabled="saving" @submit.prevent="saveProduct">
      <el-form-item label="所属商户" required>
        <el-input v-if="editing" :model-value="editing.merchantName" readonly />
        <el-select v-else v-model="form.merchantId" filterable placeholder="请选择已通过审核的商户">
          <el-option v-for="merchant in approvedMerchants" :key="merchant.id" :label="merchant.name" :value="merchant.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="商品名称" required><el-input v-model.trim="form.name" maxlength="120" /></el-form-item>
      <el-form-item label="食品分类" required>
        <el-select v-model="form.category" filterable placeholder="请选择食品分类">
          <el-option v-for="category in categories" :key="category.id" :label="category.name" :value="category.name" />
        </el-select>
      </el-form-item>
      <el-form-item label="商品价格（元）" required>
        <el-input-number v-model="form.price" :min="0.01" :max="100000" :precision="2" :step="0.01" />
      </el-form-item>
      <el-form-item label="库存" required>
        <el-input-number v-model="form.stock" :min="editing?.reserved ?? 0" :max="1000000" :precision="0" />
      </el-form-item>
      <el-form-item label="商品描述"><el-input v-model="form.description" type="textarea" maxlength="500" /></el-form-item>
      <el-form-item label="商品图片" :required="!editing">
        <div class="product-image-field">
          <el-upload ref="imageUpload" drag accept="image/jpeg,image/png" :auto-upload="false"
            :show-file-list="false" :disabled="saving" :on-change="selectImage">
            <el-icon class="el-icon--upload"><Upload /></el-icon>
            <div class="el-upload__text">拖拽图片到此处，或<em>{{ selectedImage || (editing && hasPhoto(editing.hasPhoto)) ? '点击更换' : '点击选择' }}</em></div>
            <template #tip><p class="el-upload__tip">JPG 或 PNG，最大 1 MB</p></template>
          </el-upload>
          <div v-if="selectedImage" class="product-image-selection">
            <el-image class="product-image-preview" :src="imagePreview" :alt="selectedImage.name" fit="cover"
              :preview-src-list="[imagePreview]" preview-teleported />
            <div>
              <div class="product-image-name" :title="selectedImage.name">{{ selectedImage.name }}</div>
              <el-button link type="danger" :disabled="saving" @click="clearSelectedImage">移除选中的图片</el-button>
            </div>
          </div>
          <el-button v-else-if="editing && hasPhoto(editing.hasPhoto)" link type="primary" @click="viewPhoto(editing)">查看原图</el-button>
        </div>
      </el-form-item>
      <div class="form-actions">
        <el-button @click="editorOpen = false">取消</el-button>
        <el-button native-type="submit" type="primary" :loading="saving">保存</el-button>
      </div>
    </el-form>
  </el-dialog>

  <el-dialog v-model="photoOpen" :title="photoTitle" width="min(680px, 92vw)" append-to-body @close="clearPhoto">
    <div v-loading="photoLoading" class="product-photo-viewer">
      <el-image v-if="photoUrl" :src="photoUrl" :alt="`${photoTitle}商品图片`" fit="contain" class="product-photo-full" />
    </div>
  </el-dialog>
</template>

<style scoped>
.product-image-field { width: 100%; }
.product-image-field :deep(.el-upload-dragger) { padding: 12px; }
.product-image-field :deep(.el-icon--upload) { margin: 0 0 6px; font-size: 30px; }
.product-image-selection { display: flex; align-items: center; gap: 12px; min-width: 0; margin-top: 12px; }
.product-image-selection > div:last-child { min-width: 0; }
.product-image-preview { flex: 0 0 72px; width: 72px; height: 72px; border-radius: 8px; }
.product-image-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.product-photo-full { width: 100%; height: min(64vh, 560px); }
</style>
