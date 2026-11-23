<script setup lang="ts">
import { maxFixedAmount, pricingFields, type PricingForm, type PricingMode } from '../domain/pricing'

const props = defineProps<{ form: PricingForm; lockMode?: boolean }>()

function setMode(key: keyof PricingForm, mode: PricingMode) {
  props.form[key].mode = mode
  props.form[key].value = undefined
}
</script>

<template>
  <el-form-item v-for="field in pricingFields" :key="field.key" :label="field.label" required>
    <div class="pricing-inputs">
      <el-select :model-value="form[field.key].mode" :disabled="lockMode" :aria-label="field.label + '计算方式'"
        @update:model-value="setMode(field.key, $event)">
        <el-option label="固定金额" value="FIXED" />
        <el-option label="百分比" value="PERCENTAGE" />
      </el-select>
      <el-input-number v-model="form[field.key].value" :min="0" :max="form[field.key].mode === 'FIXED' ? (lockMode ? 1000 : maxFixedAmount) : 100"
        :precision="2" :controls="false" :aria-label="field.label + '数值'">
        <template #suffix>{{ form[field.key].mode === 'FIXED' ? '元' : '%' }}</template>
      </el-input-number>
    </div>
  </el-form-item>
</template>

<style scoped>
.pricing-inputs { display: grid; grid-template-columns: minmax(100px, 1fr) minmax(86px, 1fr); gap: 8px; width: 100%; }
.pricing-inputs :deep(.el-input-number) { width: 100%; }
</style>
