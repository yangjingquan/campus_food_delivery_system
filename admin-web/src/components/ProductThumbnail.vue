<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { Picture } from '@element-plus/icons-vue'
import { adminApi } from '../api/admin'

const props = defineProps<{ productId: string }>()
const anchor = ref<HTMLElement>()
const imageUrl = ref('')
const request = new AbortController()
let observer: IntersectionObserver | undefined

function releaseImage() {
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value)
  imageUrl.value = ''
}

async function load() {
  try {
    const blob = await adminApi.productPhoto(props.productId, request.signal)
    if (!request.signal.aborted) imageUrl.value = URL.createObjectURL(blob)
  } catch {
    // The placeholder stays visible. Clicking still opens the existing preview.
  }
}

onMounted(() => {
  if (!anchor.value || !('IntersectionObserver' in window)) {
    void load()
    return
  }
  observer = new IntersectionObserver(entries => {
    if (entries.some(entry => entry.isIntersecting)) {
      observer?.disconnect()
      void load()
    }
  }, { rootMargin: '100px' })
  observer.observe(anchor.value)
})

onUnmounted(() => {
  observer?.disconnect()
  request.abort()
  releaseImage()
})
</script>

<template>
  <span ref="anchor" class="thumbnail-content" aria-hidden="true">
    <img v-if="imageUrl" :src="imageUrl" alt="" @error="releaseImage">
    <el-icon v-else><Picture /></el-icon>
  </span>
</template>

<style scoped>
.thumbnail-content { display: grid; place-items: center; width: 100%; height: 100%; }
.thumbnail-content img { display: block; width: 100%; height: 100%; object-fit: cover; }
</style>
