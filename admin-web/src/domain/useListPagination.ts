import { computed, ref, watch } from 'vue'

/** 对已加载的数据做分页，按筛选后的数量计算可用页。 */
export function useListPagination<T>(rows: () => readonly T[]) {
  const page = ref(1)
  const pageSize = ref(20)
  const total = computed(() => rows().length)
  const lastPage = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
  const pageRows = computed(() => {
    const start = (Math.min(page.value, lastPage.value) - 1) * pageSize.value
    return rows().slice(start, start + pageSize.value)
  })

  watch(lastPage, (last) => {
    if (page.value > last) page.value = last
  })

  return { page, pageSize, total, pageRows }
}
