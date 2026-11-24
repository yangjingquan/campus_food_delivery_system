<script setup lang="ts">
import { Rank } from '@element-plus/icons-vue'

defineProps<{ id: string; label: string; disabled: boolean; target: boolean }>()
defineEmits<{
  start: [event: DragEvent]
  end: []
  move: [event: KeyboardEvent]
}>()
</script>

<template>
  <button type="button" class="row-drag-handle" :class="{ 'is-drop-target': target }"
    :data-reorder-id="id" :draggable="!disabled" :disabled="disabled"
    :aria-label="'调整' + label + '的顺序，使用上下方向键'" title="拖拽排序，也可使用上下方向键"
    @dragstart="$emit('start', $event)" @dragend="$emit('end')" @keydown="$emit('move', $event)">
    <el-icon aria-hidden="true"><Rank /></el-icon>
  </button>
</template>

<style scoped>
.row-drag-handle {
  display: inline-grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--el-text-color-secondary);
  font-size: 18px;
  cursor: grab;
}
.row-drag-handle:hover:not(:disabled), .is-drop-target {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.row-drag-handle:disabled { opacity: .45; cursor: default; }
</style>
