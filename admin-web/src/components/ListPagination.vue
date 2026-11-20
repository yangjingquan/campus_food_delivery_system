<script setup lang="ts">
import { ElConfigProvider, ElPagination } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

withDefaults(defineProps<{ total: number; label?: string; disabled?: boolean; jumper?: boolean }>(), {
  label: '列表分页',
})
const page = defineModel<number>('page', { required: true })
const pageSize = defineModel<number>('pageSize', { required: true })
const pageSizes = [10, 20, 50, 100]

function changePageSize(size: number) {
  pageSize.value = size
  page.value = 1
}
</script>

<template>
  <nav class="list-pagination" :aria-label="label">
    <el-config-provider :locale="zhCn">
      <el-pagination v-model:current-page="page" :page-size="pageSize" :page-sizes="pageSizes"
        :total="total" :disabled="disabled" :pager-count="5"
        :layout="jumper ? 'total, sizes, prev, pager, next, jumper' : 'total, sizes, prev, pager, next'"
        @update:page-size="changePageSize" />
    </el-config-provider>
  </nav>
</template>

<style scoped>
.list-pagination {
  display: flex;
  justify-content: flex-end;
  padding: 12px 16px;
  border-top: 1px solid var(--line);
}
.list-pagination :deep(.el-pagination) {
  flex-wrap: wrap;
  row-gap: 8px;
  justify-content: flex-end;
  white-space: nowrap;
}
@media (max-width: 480px) {
  .list-pagination { justify-content: flex-start; padding-inline: 12px; }
  .list-pagination :deep(.el-pagination) { justify-content: flex-start; }
}
</style>
