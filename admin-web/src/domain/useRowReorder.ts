import { ref } from 'vue'
import { ElMessage } from 'element-plus'

export interface RowOrder {
  previousIds: string[]
  ids: string[]
}

export function useRowReorder(
  getIds: () => string[],
  save: (order: RowOrder) => Promise<unknown>,
  reload: () => Promise<void>,
) {
  const saving = ref(false)
  const targetId = ref('')
  let draggedId = ''
  let dragOrder: string[] = []

  function end() {
    draggedId = ''
    targetId.value = ''
    dragOrder = []
  }

  function start(event: DragEvent, id: string) {
    const ids = getIds()
    if (saving.value || !event.dataTransfer || !ids.includes(id)) {
      event.preventDefault()
      return
    }
    draggedId = id
    dragOrder = ids
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', id)
  }

  function target(event: DragEvent) {
    if (!draggedId || saving.value || !(event.target instanceof Element)) return ''
    const id = event.target.closest('tr')?.querySelector<HTMLElement>('[data-reorder-id]')?.dataset.reorderId
    return id && id !== draggedId && dragOrder.includes(id) ? id : ''
  }

  function over(event: DragEvent) {
    targetId.value = target(event)
    if (!targetId.value) return
    event.preventDefault()
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  }

  function drop(event: DragEvent) {
    const id = target(event)
    const previous = dragOrder
    const source = draggedId
    end()
    if (!id) return
    event.preventDefault()
    const current = getIds()
    if (current.length !== previous.length || current.some((value, i) => value !== previous[i])) return
    void move(previous, source, previous.indexOf(id))
  }

  function keydown(event: KeyboardEvent, id: string) {
    const direction = event.key === 'ArrowUp' ? -1 : event.key === 'ArrowDown' ? 1 : 0
    if (!direction) return
    event.preventDefault()
    const ids = getIds()
    if (ids.includes(id)) void move(ids, id, ids.indexOf(id) + direction)
  }

  async function move(previousIds: string[], id: string, destination: number) {
    const from = previousIds.indexOf(id)
    if (saving.value || from < 0 || from === destination || destination < 0 || destination >= previousIds.length) return
    const ids = [...previousIds]
    ids.splice(from, 1)
    ids.splice(destination, 0, id)
    saving.value = true
    try {
      await save({ previousIds, ids })
      ElMessage.success('排序已保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '排序保存失败')
    } finally {
      try {
        await reload()
      } finally {
        saving.value = false
      }
    }
  }

  return { saving, targetId, start, end, over, drop, keydown }
}
