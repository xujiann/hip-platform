<template>
  <el-card>
    <template #header>
      <span style="font-weight: 600">病理工作台</span>
      <el-tag type="info" size="small" style="margin-left: 10px">
        登记 → 取材 → 制片 → 诊断（四个工位，按病理科分工组织）</el-tag>
      <el-button link type="primary" size="small" style="float: right" @click="reload">刷新当前工位</el-button>
    </template>

    <el-alert type="info" show-icon :closable="false" class="cav"
              title="双来源域：门诊与住院开的病理申请都在这里，菜单挂在门诊业务目录之下不代表只看门诊。既有的「专科流程」页仍可用，本工作台是它的超集而不是替代——旧路径一个字节没改。" />

    <el-tabs v-model="tab" class="stations">
      <el-tab-pane name="registry" label="① 登记">
        <RegistryPanel ref="registryRef" @changed="onChanged" />
      </el-tab-pane>
      <el-tab-pane name="grossing" label="② 取材" lazy>
        <GrossingPanel ref="grossingRef" @changed="onChanged" />
      </el-tab-pane>
      <el-tab-pane name="processing" label="③ 制片" lazy>
        <ProcessingPanel ref="processingRef" @changed="onChanged" />
      </el-tab-pane>
      <el-tab-pane name="diagnosis" label="④ 诊断" lazy>
        <DiagnosisPanel ref="diagnosisRef" @changed="onChanged" />
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
/**
 * 病理工作台（v48 车道 F1）。
 *
 * <p><b>按四个工位组织而不是做成一个大表单</b>：登记员、取材医师、制片技师、病理医师
 * 是四拨人四个台面，一个大表单意味着谁都要在满屏与自己无关的字段里找自己那一栏。
 * 四个面板各自独立取数、互不阻塞，切换工位才加载（lazy）。
 *
 * <p>路由 {@code /pathology/workbench}、菜单 168（perm {@code path:work}）——
 * 该菜单由 V144 已 seed，本车道<b>不插 sys_menu</b>（菜单 id 易撞，由合版统一整合）。
 */
import { reactive, ref, watch } from 'vue'
import RegistryPanel from './RegistryPanel.vue'
import GrossingPanel from './GrossingPanel.vue'
import ProcessingPanel from './ProcessingPanel.vue'
import DiagnosisPanel from './DiagnosisPanel.vue'

interface Reloadable { reload: () => void | Promise<void> }

const tab = ref('registry')
const registryRef = ref<Reloadable | null>(null)
const grossingRef = ref<Reloadable | null>(null)
const processingRef = ref<Reloadable | null>(null)
const diagnosisRef = ref<Reloadable | null>(null)

function currentPanel(): Reloadable | null {
  return ({
    registry: registryRef.value,
    grossing: grossingRef.value,
    processing: processingRef.value,
    diagnosis: diagnosisRef.value,
  } as Record<string, Reloadable | null>)[tab.value] ?? null
}

function reload() {
  void currentPanel()?.reload()
  seen[tab.value] = changedAt.value
}

/**
 * 工位之间有真实的先后依赖（登记完才谈得上取材），但<b>不做即时的跨面板级联刷新</b>：
 * 别的面板此刻不在屏幕上，替它重查只是白发一轮请求；而各面板都有自己的检索条件，
 * 无差别重查还会把使用者刚设好的筛选洗掉。
 *
 * <p>改为<b>标脏 + 切过去时再刷</b>：任一工位发生写操作就把版本号 +1，
 * 切到某个工位时若它上次取数早于这个版本，才刷新它一次（新挂载的面板自带首次加载，
 * 记为已看过最新版本，不重复发一轮）。
 */
const changedAt = ref(0)
const seen = reactive<Record<string, number>>({ registry: 0 })

function onChanged() {
  changedAt.value += 1
  seen[tab.value] = changedAt.value   // 当前面板已在写操作后自行刷新过
}

watch(tab, (name) => {
  const panel = currentPanel()
  if (!panel) {
    // 首次激活：面板此刻才挂载并自行取数，直接记为已看过最新版本
    seen[name] = changedAt.value
    return
  }
  if (seen[name] !== changedAt.value) {
    void panel.reload()
    seen[name] = changedAt.value
  }
})
</script>

<style scoped>
.cav { margin-bottom: 10px; }
.stations :deep(.el-tabs__item) { font-weight: 600; }
</style>
