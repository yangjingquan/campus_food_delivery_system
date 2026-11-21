<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, ElUpload, type UploadInstance, type UploadProps } from 'element-plus'
import { adminApi, type HomeBanner } from '../api/admin'
import ListPagination from '../components/ListPagination.vue'
import { useListPagination } from '../domain/useListPagination'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const canEdit = computed(() => auth.can('home-banner.edit'))
const rows = ref<HomeBanner[]>([])
const { page, pageSize, total, pageRows } = useListPagination(() => rows.value)
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)
const dialog = ref(false)
const editing = ref<HomeBanner>()
const draft = ref<{ name: string; linkType: HomeBanner['linkType']; linkUrl: string; sortOrder?: number }>({
  name: '', linkType: 'page', linkUrl: '', sortOrder: 0,
})
const image = ref<File>()
const imageUpload = ref<UploadInstance>()
const draftPreview = ref('')
const previews = ref<Record<number, string>>({})
const formError = ref('')
const imageError = ref('')
const preview = computed(() => draftPreview.value || (editing.value && previews.value[editing.value.id]))

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    rows.value = await adminApi.homeBanners()
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '轮播图读取失败'
  } finally {
    loading.value = false
  }
}

watch(pageRows, (items, _, onCleanup) => {
  let active = true
  const urls: string[] = []
  previews.value = {}
  onCleanup(() => {
    active = false
    urls.forEach(url => URL.revokeObjectURL(url))
  })
  for (const item of items) {
    void adminApi.homeBannerImage(item.id).then(blob => {
      if (!active) return
      const url = URL.createObjectURL(blob)
      urls.push(url)
      previews.value[item.id] = url
    }).catch(() => { /* 图片读取失败时仍可编辑或更换图片。 */ })
  }
})

function clearDraftImage() {
  if (draftPreview.value) URL.revokeObjectURL(draftPreview.value)
  draftPreview.value = ''
  image.value = undefined
}

function openEditor(item?: HomeBanner) {
  editing.value = item
  draft.value = {
    name: item?.name ?? '', linkType: item?.linkType ?? 'page',
    linkUrl: item?.linkUrl ?? '', sortOrder: item?.sortOrder ?? 0,
  }
  clearDraftImage()
  formError.value = ''
  imageError.value = ''
  dialog.value = true
}

const chooseImage: UploadProps['onChange'] = ({ raw: file }) => {
  imageUpload.value?.clearFiles()
  if (!file || busy.value || !dialog.value) return
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 768 * 1024 || !file.size) {
    imageError.value = '请选择不超过 768 KB 的 JPG 或 PNG 图片'
    return
  }
  clearDraftImage()
  image.value = file
  draftPreview.value = URL.createObjectURL(file)
  imageError.value = ''
}

async function save() {
  if (busy.value) return
  formError.value = ''
  const { name, linkType, linkUrl, sortOrder } = draft.value
  if (!name.trim() || !linkUrl.trim() || !Number.isInteger(sortOrder)) {
    formError.value = '请填写轮播图名称、跳转地址和整数排序值'
    return
  }
  if (!editing.value && !image.value) {
    imageError.value = '请选择轮播图片'
    return
  }
  const form = new FormData()
  form.append('name', name.trim())
  form.append('linkType', linkType)
  form.append('linkUrl', linkUrl.trim())
  form.append('sortOrder', String(sortOrder))
  if (image.value) form.append('image', image.value)
  busy.value = true
  try {
    await adminApi.saveHomeBanner(form, editing.value?.id)
    dialog.value = false
    ElMessage.success('轮播图已保存')
    await load()
  } catch (error) {
    formError.value = error instanceof Error ? error.message : '保存失败，请重试'
  } finally {
    busy.value = false
  }
}

async function remove(item: HomeBanner) {
  if (busy.value) return
  busy.value = true
  try {
    await ElMessageBox.confirm(`删除「${item.name}」后，它将从小程序首页移除。`, '删除轮播图', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
    })
    await adminApi.deleteHomeBanner(item.id)
    ElMessage.success('轮播图已删除')
    await load()
  } catch (error) {
    if (error instanceof Error) ElMessage.error(error.message)
  } finally {
    busy.value = false
  }
}

onMounted(load)
onUnmounted(clearDraftImage)
</script>

<template>
  <div class="page-heading">
    <h1>首页轮播图</h1>
    <el-button v-if="canEdit" type="primary" :disabled="busy" @click="openEditor()">新增轮播图</el-button>
  </div>

  <section class="panel-card workbench-panel">
    <el-table v-loading="loading" :data="pageRows" class="data-table" row-key="id" stripe>
      <el-table-column label="图片" width="134">
        <template #default="{ row }">
          <img v-if="previews[row.id]" :src="previews[row.id]" :alt="row.name" class="banner-thumbnail" />
          <span v-else class="banner-help">暂无预览</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
      <el-table-column label="跳转方式" width="112">
        <template #default="{ row }">{{ row.linkType === 'page' ? '小程序页面' : '站外链接' }}</template>
      </el-table-column>
      <el-table-column prop="linkUrl" label="跳转地址" min-width="228" show-overflow-tooltip />
      <el-table-column prop="sortOrder" label="排序" width="88" align="right" />
      <el-table-column v-if="canEdit" label="操作" width="128">
        <template #default="{ row }">
          <el-button type="primary" link :disabled="busy" @click="openEditor(row as HomeBanner)">编辑</el-button>
          <el-button type="danger" link :disabled="busy" @click="remove(row as HomeBanner)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="loadError || '暂无轮播图，小程序首页显示默认横幅'">
          <el-button v-if="loadError" @click="load">重新加载</el-button>
        </el-empty>
      </template>
    </el-table>
    <ListPagination v-model:page="page" v-model:page-size="pageSize" :total="total" :disabled="loading" />
  </section>

  <el-dialog v-model="dialog" class="banner-editor-dialog" align-center
    :title="editing ? '编辑轮播图' : '新增轮播图'" width="min(560px, calc(100vw - 32px))"
    :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @closed="clearDraftImage">
    <el-form class="banner-editor-form" label-position="top" @submit.prevent="save">
      <div class="banner-editor-details">
        <el-form-item label="轮播图名称" required>
          <el-input v-model="draft.name" maxlength="80" :disabled="busy" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="draft.sortOrder" :precision="0" :min="-2147483648" :max="2147483647"
            :disabled="busy" aria-describedby="banner-sort-hint" />
          <span id="banner-sort-hint" class="banner-help">数值越大，展示越靠前。</span>
        </el-form-item>
      </div>
      <el-form-item label="轮播图片" :required="!editing" :error="imageError">
        <ElUpload ref="imageUpload" class="banner-upload" drag :auto-upload="false" :show-file-list="false"
          :disabled="busy" accept="image/png,image/jpeg" :on-change="chooseImage">
          <span class="banner-upload-content">
            <img v-if="preview" :src="preview" alt="轮播图预览" class="banner-preview" />
            <el-icon v-else class="banner-upload-icon" aria-hidden="true"><Upload /></el-icon>
            <span class="banner-upload-text">
              <span class="banner-upload-action">{{ preview || editing ? '更换图片' : '选择图片' }}</span>
              <span class="banner-help banner-upload-caption" :title="image?.name">
                {{ image?.name || '也可将图片拖到此处' }}
              </span>
            </span>
          </span>
        </ElUpload>
        <span class="banner-help">JPG 或 PNG，最大 768 KB。建议使用约 2∶1 的横图。</span>
        <template #error="{ error }">
          <span class="banner-error" role="alert">{{ error }}</span>
        </template>
      </el-form-item>
      <el-form-item label="跳转方式" required>
        <el-radio-group v-model="draft.linkType" :disabled="busy">
          <el-radio-button value="page">小程序页面</el-radio-button>
          <el-radio-button value="external">站外链接</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="跳转地址" required>
        <el-input v-model="draft.linkUrl" maxlength="2048" :disabled="busy"
          :placeholder="draft.linkType === 'page' ? '/pages/store/index?id=商户编号' : 'https://example.com/activity'" />
        <span class="banner-help">{{ draft.linkType === 'page'
          ? '普通页面可带参数，底部导航页面只填路径。'
          : '请使用已在微信公众平台配置为业务域名的 HTTPS 地址。' }}</span>
      </el-form-item>
      <p v-if="formError" class="banner-error" role="alert">{{ formError }}</p>
    </el-form>
    <template #footer>
      <div class="banner-editor-actions">
        <el-button :disabled="busy" @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="save">保存</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
:global(.banner-editor-dialog) {
  --el-dialog-padding-primary: clamp(16px, 5vw, 24px);
  display: flex;
  flex-direction: column;
  max-height: calc(100dvh - 32px);
}

:global(.banner-editor-dialog .el-dialog__body) {
  min-height: 0;
  overflow-y: auto;
}

.banner-editor-form, .banner-editor-details {
  display: grid;
  gap: 16px;
}

.banner-editor-details { grid-template-columns: minmax(0, 1fr) 152px; }
.banner-editor-form :deep(.el-form-item) { min-width: 0; margin: 0; }
.banner-editor-form :deep(.el-form-item__label) { margin-bottom: 6px; line-height: 20px; }
.banner-editor-form :deep(.el-form-item__content) { display: grid; gap: 6px; }
.banner-editor-details :deep(.el-input-number) { width: 100%; }

.banner-thumbnail, .banner-preview {
  display: block;
  aspect-ratio: 2 / 1;
  object-fit: cover;
  border-radius: 8px;
}

.banner-thumbnail { width: 100px; }
.banner-preview { width: clamp(88px, 22vw, 128px); }

.banner-upload :deep(.el-upload) {
  display: block;
  border-radius: var(--el-border-radius-base);
}

.banner-upload :deep(.el-upload:focus-visible) {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

.banner-upload :deep(.el-upload-dragger) {
  padding: 12px;
  border-color: var(--el-border-color-darker);
  background: var(--el-fill-color-light);
  text-align: left;
}

.banner-upload :deep(.el-upload:not(.is-disabled) .el-upload-dragger:hover),
.banner-upload :deep(.el-upload-dragger.is-dragover) {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.banner-upload :deep(.el-upload.is-disabled .el-upload-dragger) { cursor: not-allowed; }

.banner-upload-content {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 64px;
}

.banner-upload-icon {
  flex: 0 0 40px;
  width: 40px;
  height: 40px;
  border-radius: var(--el-border-radius-base);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary-dark-2);
  font-size: 20px;
}

.banner-upload-text { display: grid; gap: 4px; min-width: 0; }
.banner-upload-action { color: var(--el-color-primary-dark-2); font-weight: 600; line-height: 1.4; }
.banner-upload-caption { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.banner-help { color: var(--el-text-color-regular); font-size: 12px; line-height: 1.5; }
.banner-error { margin: 0; color: var(--el-color-danger-dark-2); font-size: 13px; line-height: 1.5; }

.banner-editor-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color-light);
}

.banner-editor-actions .el-button { min-width: 76px; margin: 0; }

@media (max-width: 480px) {
  .banner-editor-details { grid-template-columns: minmax(0, 1fr); }
  .banner-editor-details :deep(.el-input-number) { width: 152px; }
}
</style>
