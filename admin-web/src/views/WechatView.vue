<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api/admin'
import { useAuthStore } from '../stores/auth'
import { stateLabel } from '../domain/businessStates'

type Config = { version:number; userAppId:string; merchantAppId:string; riderAppId:string; merchantNo:string; serialNo:string; publicKeyId:string; paymentCallback:string; refundCallback:string; transferCallback:string; userSecretSet:boolean; merchantSecretSet:boolean; riderSecretSet:boolean; apiV3KeySet:boolean; merchantPrivateKeySet:boolean; wechatPublicKeySet:boolean; enabled:boolean; operator:string; createdAt:string }
const auth=useAuthStore()
type Audit = { actor:string; actionName:string; targetId:string; beforeState?:string; afterState?:string; reason:string; createdAt:string }
const settings=ref<Config>(), history=ref<Config[]>([]), audits=ref<Audit[]>([]), loading=ref(false), saving=ref(false)
const activeTab=ref('apps')
const versionOpen=ref(false), versionDetail=ref<Config>()
const appDraft=reactive({appId:'',appSecret:''})
const payDraft=reactive({merchantNo:'',serialNo:'',publicKeyId:'',paymentCallback:'',refundCallback:'',transferCallback:'',apiV3Key:'',merchantPrivateKey:'',wechatPublicKey:''})
const privateKeyFile=ref<HTMLInputElement>(), publicKeyFile=ref<HTMLInputElement>()
async function importPem(event:Event,field:'merchantPrivateKey'|'wechatPublicKey'){const input=event.target as HTMLInputElement;const file=input.files?.[0];if(!file)return;try{if(file.size>16384)throw new Error('文件不能超过 16 KB');payDraft[field]=await file.text();ElMessage.info('材料已载入草稿，保存后加密存储。')}catch(e){ElMessage.error(e instanceof Error?e.message:'文件读取失败')}finally{input.value=''}}
const canEdit=computed(()=>auth.can('wechat.edit'))
const canRollback=computed(()=>canEdit.value && auth.can('wechat.toggle'))
function displayTime(value:string){const date=new Date(value);return Number.isNaN(date.getTime())?value:date.toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false})}
const appStatus = computed(() => {
  const config = settings.value
  if (config?.userAppId && config.userSecretSet) return '已配置'
  return config?.userAppId || config?.userSecretSet ? '部分配置' : '未配置'
})

function syncApp() {
  appDraft.appId = settings.value?.userAppId || ''
  appDraft.appSecret = ''
}
function syncPay(){if(!settings.value)return;Object.assign(payDraft,{merchantNo:settings.value.merchantNo,serialNo:settings.value.serialNo,publicKeyId:settings.value.publicKeyId,paymentCallback:settings.value.paymentCallback,refundCallback:settings.value.refundCallback,transferCallback:settings.value.transferCallback,apiV3Key:'',merchantPrivateKey:'',wechatPublicKey:''})}
async function load() {
  loading.value = true
  try {
    const data = await adminApi.workspace<{config:Config;history:Config[];audit:Audit[]}>('wechat')
    settings.value = data.config
    history.value = data.history
    audits.value = data.audit
    syncApp()
    syncPay()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '配置读取失败')
  } finally {
    loading.value = false
  }
}

async function saveApp() {
  if (!settings.value) return
  try {
    await ElMessageBox.confirm('此配置用于用户、商户和骑手。AppID 未修改时，AppSecret 留空会保留原值。', '保存统一小程序配置')
    saving.value = true
    await adminApi.action('wechat.save-app', { version: settings.value.version, appType: 'user', ...appDraft })
    ElMessage.success('统一小程序配置已保存')
    await load()
  } catch (error) {
    if (error instanceof Error && !['cancel', 'close'].includes(error.message)) ElMessage.error(error.message)
  } finally {
    saving.value = false
  }
}
async function savePay(){if(!settings.value)return;try{await ElMessageBox.confirm('敏感材料留空保留原值；支付状态由部署配置决定。','保存微信支付配置',{confirmButtonText:'确认保存',cancelButtonText:'取消'});saving.value=true;await adminApi.action('wechat.save-payment',{version:settings.value.version,...payDraft});ElMessage.success('微信支付配置已保存。');await load()}catch(e){if(e instanceof Error&&!['cancel','close'].includes(e.message))ElMessage.error(e.message)}finally{saving.value=false}}
const missing=computed(()=>{const c=settings.value;if(!c)return ['配置未加载'];const fields:[string,unknown][]=[['小程序 AppID',c.userAppId],['小程序 AppSecret',c.userSecretSet],['微信支付商户号',c.merchantNo],['商户证书序列号',c.serialNo],['商户私钥',c.merchantPrivateKeySet],['API v3 密钥',c.apiV3KeySet],['微信支付公钥 ID',c.publicKeyId],['微信支付公钥',c.wechatPublicKeySet],['支付回调地址',c.paymentCallback]];return fields.filter(([,v])=>!v).map(([name])=>name)})
async function showChecks(){await ElMessageBox.alert(missing.value.length?'缺少：'+missing.value.join('、')+'。微信绑定与渠道联调状态仍需验证。':'必填字段已保存；微信绑定、权限和渠道联调状态仍需验证。','配置检查',{confirmButtonText:'知道了'})}
function openVersion(row:Config){versionDetail.value=row;versionOpen.value=true}
async function rollback(row:Config){if(!settings.value)return;try{const r=await ElMessageBox.prompt('回滚会创建新版本，不删除历史配置。','回滚至版本 '+row.version,{inputPlaceholder:'填写业务原因',inputValidator:(v)=>!!v.trim()||'原因必填',confirmButtonText:'确认回滚',cancelButtonText:'取消'});await adminApi.action('wechat.rollback',{version:settings.value.version,targetVersion:row.version,reason:r.value});versionOpen.value=false;ElMessage.success('已创建回滚版本。');await load()}catch(e){if(e instanceof Error&&!['cancel','close'].includes(e.message))ElMessage.error(e.message)}}
onMounted(load)
</script>
<template>
  <div class="page-heading"><h1>微信设置</h1></div>
  <div v-if="settings" v-loading="loading" class="wechat-page">
    <div class="wechat-status-grid"><div class="status-tile"><span>平台微信支付</span><strong><el-tag :type="settings.enabled?'success':'info'" effect="light">{{ settings.enabled?'已启用':'未启用' }}</el-tag></strong></div><div class="status-tile"><span>生效配置版本</span><strong>WXV{{ String(settings.version).padStart(3,'0') }}</strong><small>最近修改 {{ displayTime(settings.createdAt) }}</small></div><div class="status-tile"><span>微信侧权限 / 绑定</span><strong><el-tag type="info" effect="light">未核实 · 未实际联调</el-tag></strong></div></div>
    <section class="panel-card wechat-panel"><el-tabs v-model="activeTab" class="wechat-tabs">
      <el-tab-pane label="小程序配置" name="apps">
        <div class="payment-layout">
          <div class="payment-form-wrap">
            <div class="section-heading">
              <div><h2>统一小程序</h2><p>用户、商户和骑手共用此配置</p></div>
              <el-tag effect="light" type="info">{{ appStatus }}</el-tag>
            </div>
            <el-form label-position="top" class="wechat-form">
              <el-form-item label="小程序 AppID">
                <el-input v-model="appDraft.appId" :disabled="!canEdit" placeholder="wx 开头的 AppID" />
              </el-form-item>
              <el-form-item label="小程序 AppSecret">
                <el-tag :type="settings.userSecretSet ? 'success' : 'info'" effect="light">
                  {{ settings.userSecretSet ? '已配置（不回显）' : '未配置' }}
                </el-tag>
                <el-input v-model="appDraft.appSecret" type="password" :disabled="!canEdit"
                  placeholder="AppID 未修改时可留空" autocomplete="new-password" />
              </el-form-item>
            </el-form>
            <div class="form-actions">
              <el-button @click="syncApp">取消修改</el-button>
              <el-button type="primary" :disabled="!canEdit" :loading="saving" @click="saveApp">保存小程序配置</el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane label="微信支付配置" name="payment"><div class="payment-panel-top"><div><h2>平台统一微信支付商户</h2></div><div class="payment-controls"><el-button @click="showChecks">检查配置</el-button></div></div><div class="payment-layout"><div class="payment-form-wrap"><el-form label-position="top" class="wechat-form"><div class="config-subhead">基础身份与解密</div><el-form-item label="微信支付商户号"><el-input v-model="payDraft.merchantNo" :disabled="!canEdit" /></el-form-item><el-form-item label="API v3 密钥"><el-tag :type="settings.apiV3KeySet?'success':'info'">{{ settings.apiV3KeySet?'已配置（不回显）':'未配置' }}</el-tag><el-input v-model="payDraft.apiV3Key" type="password" :disabled="!canEdit" placeholder="留空保留已有值" autocomplete="new-password" /></el-form-item><div class="config-subhead">商户请求签名</div><el-form-item label="商户 API 证书序列号"><el-input v-model="payDraft.serialNo" :disabled="!canEdit" /></el-form-item><el-form-item label="商户私钥 PEM"><el-tag :type="settings.merchantPrivateKeySet?'success':'info'">{{ settings.merchantPrivateKeySet?'已配置（不回显）':'未配置' }}</el-tag><el-input v-model="payDraft.merchantPrivateKey" type="password" :disabled="!canEdit" placeholder="留空保留已有值，或导入 PKCS#8 PEM" autocomplete="new-password" /><el-button :disabled="!canEdit" @click="privateKeyFile?.click()">导入私钥 PEM</el-button><input ref="privateKeyFile" type="file" accept=".pem,.key" hidden @change="importPem($event,'merchantPrivateKey')" /></el-form-item><div class="config-subhead">微信应答与通知验签</div><el-form-item label="微信支付公钥 ID"><el-input v-model="payDraft.publicKeyId" :disabled="!canEdit" /></el-form-item><el-form-item label="微信支付公钥 PEM"><el-tag :type="settings.wechatPublicKeySet?'success':'info'">{{ settings.wechatPublicKeySet?'已配置（不回显）':'未配置' }}</el-tag><el-input v-model="payDraft.wechatPublicKey" type="password" :disabled="!canEdit" placeholder="留空保留已有值，或导入公钥 PEM" autocomplete="new-password" /><el-button :disabled="!canEdit" @click="publicKeyFile?.click()">导入公钥 PEM</el-button><input ref="publicKeyFile" type="file" accept=".pem,.pub" hidden @change="importPem($event,'wechatPublicKey')" /></el-form-item><div class="config-subhead">回调配置</div><el-form-item label="支付回调地址"><el-input v-model="payDraft.paymentCallback" :disabled="!canEdit" /></el-form-item><el-form-item label="退款结果回调地址"><el-input v-model="payDraft.refundCallback" :disabled="!canEdit" /></el-form-item><el-form-item label="商家转账结果回调地址"><el-input v-model="payDraft.transferCallback" :disabled="!canEdit" /></el-form-item></el-form><div class="form-actions"><el-button @click="syncPay">取消修改</el-button><el-button type="primary" :disabled="!canEdit" :loading="saving" @click="savePay">保存支付配置</el-button></div></div></div></el-tab-pane>
      <el-tab-pane label="配置版本与审计" name="history">
        <div class="history-layout">
          <section>
            <h2>微信配置版本</h2>
            <div class="wechat-version-row">
              <div><strong>WXV{{ String(settings.version).padStart(3, '0') }}</strong><p>当前版本 · {{ settings.operator }} · {{ displayTime(settings.createdAt) }}</p></div>
              <el-tag type="success">当前版本</el-tag>
            </div>
            <div v-for="item in history" :key="item.version" class="wechat-version-row">
              <div><strong>WXV{{ String(item.version).padStart(3, '0') }}</strong><p>{{ item.operator }} · {{ displayTime(item.createdAt) }}</p></div>
              <el-button @click="openVersion(item)">查看版本</el-button>
            </div>
          </section>
          <section>
            <h2>配置审计</h2>
            <el-empty v-if="!audits.length" description="暂无操作记录" :image-size="70" />
            <div v-for="(item, index) in audits" :key="index" class="wechat-version-row">
              <div><strong>{{ item.actionName }}</strong><p>{{ item.actor }} · {{ displayTime(item.createdAt) }}</p><small v-if="item.beforeState && item.afterState">{{ stateLabel(item.beforeState) }} → {{ stateLabel(item.afterState) }} · </small><small>{{ item.reason }}</small></div>
            </div>
          </section>
        </div>
      </el-tab-pane>
    </el-tabs></section>
  </div>
  <el-dialog v-model="versionOpen" :title="`配置版本 · WXV${String(versionDetail?.version || 0).padStart(3,'0')}`" width="min(540px,94vw)">
    <div v-if="versionDetail" class="version-detail"><div><span>操作人 / 时间</span><strong>{{ versionDetail.operator }} · {{ displayTime(versionDetail.createdAt) }}</strong></div><div><span>统一小程序配置</span><strong>{{ versionDetail.userAppId || '未配置' }} · {{ versionDetail.userSecretSet ? 'AppSecret 已配置' : 'AppSecret 未配置' }}</strong></div><div v-if="versionDetail.merchantAppId || versionDetail.riderAppId"><span>原商户 / 骑手 AppID</span><strong>{{ versionDetail.merchantAppId || '未配置' }} / {{ versionDetail.riderAppId || '未配置' }}</strong></div><div><span>材料安全</span><strong>只显示配置状态，不显示或下载材料原文</strong></div></div>
    <div class="dialog-note warning"><el-icon><WarningFilled /></el-icon><span>回滚将生成新版本，保留原历史和在途关联。</span></div>
    <template #footer><el-button @click="versionOpen=false">关闭</el-button><el-button v-if="versionDetail" type="primary" :disabled="!canRollback" @click="rollback(versionDetail)">受控回滚到此版本</el-button></template>
  </el-dialog>
</template>
