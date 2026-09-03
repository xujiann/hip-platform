<template>
  <div class="inp-doctor">
    <el-card class="list">
      <template #header>
        在院患者
        <el-button link type="primary" size="small" style="float: right" @click="openOrderSearch">医嘱检索</el-button>
      </template>
      <!-- 2012★/2013★：多维检索区。后端 GET /admissions 的过滤参数全部可选，
           一个都不填时与旧行为逐字相同（不会因为挂了这个区块就改变默认列表）。 -->
      <div class="filters">
        <el-input v-model="q.keyword" size="small" clearable placeholder="姓名 / 住院号"
                  @keyup.enter="loadList" @clear="loadList" />
        <el-select v-model="q.deptId" size="small" clearable placeholder="科室" @change="loadList">
          <el-option v-for="d in clinicalDepts" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-select v-model="q.careLevel" size="small" clearable placeholder="护理级别" @change="loadList">
          <el-option v-for="l in careLevels" :key="l" :label="`${l}护理`" :value="l" />
        </el-select>
        <el-select v-model="q.transferred" size="small" clearable placeholder="是否转科" @change="loadList">
          <el-option label="转过科" :value="true" />
          <el-option label="未转科" :value="false" />
        </el-select>
        <el-select v-model="q.doctorId" size="small" clearable filterable placeholder="主管医生" @change="loadList">
          <el-option v-for="d in doctorOptions" :key="d.id as number"
                     :label="String(d.real_name)" :value="d.id as number" />
        </el-select>
        <div class="filter-row">
          <el-checkbox v-model="q.mine" size="small" @change="loadList">只看我的病人</el-checkbox>
          <el-button link size="small" @click="resetFilters">重置</el-button>
        </div>
      </div>
      <el-alert v-if="listError" type="warning" show-icon :closable="false" :title="listError"
                style="margin-bottom: 6px" />
      <div class="list-count">共 {{ admissions.length }} 人{{ filterActive ? '（已筛选）' : '' }}</div>
      <el-table :data="admissions" highlight-current-row height="calc(100vh - 400px)" @current-change="open">
        <el-table-column prop="bedNo" label="床" width="50" />
        <el-table-column prop="patientName" label="姓名" width="80" />
        <el-table-column prop="admitDiagName" label="诊断" show-overflow-tooltip />
      </el-table>
    </el-card>

    <el-card v-if="current" class="workspace">
      <template #header>
        <b>{{ current.patientName }}</b>（{{ current.admissionNo }} · {{ current.wardName }} {{ current.bedNo }}床）
        <!-- 2013★：doctor_id 此前建了表却无任何读写路径，主管医生在界面上完全不存在 -->
        <el-tag size="small" :type="current.doctorName ? 'success' : 'info'" style="margin-left: 8px">
          主管医生：{{ current.doctorName ?? '未指定' }}
        </el-tag>
        <el-button size="small" style="margin-left: 8px" @click="openAttending">设主管医生</el-button>
        <el-button size="small" style="margin-left: 8px" @click="openTransfer">转科</el-button>
        <span class="fees">
          费用 ¥{{ totalAmount }} / 押金 ¥{{ depositAmount }} /
          <span :class="{ owed: account?.owed }">余额 ¥{{ account ? account.balance : '-' }}</span>
        </span>
      </template>
      <el-tabs v-model="tab">
        <el-tab-pane label="医嘱" name="orders">
      <!-- 收尾环·阻塞1：押金/余额条，余额为负标红提醒（不硬拦开单，医疗行为不因欠费停摆） -->
      <el-alert v-if="account?.owed" type="error" show-icon :closable="false" style="margin-bottom: 8px"
                :title="`欠费 ¥${Math.abs(Number(account?.balance)).toFixed(2)}，请提醒患者续交押金`" />
      <div class="add-row">
        <el-select v-model="drugId" filterable remote :remote-method="searchDrugs" placeholder="药品" style="width: 240px">
          <el-option v-for="d in drugOptions" :key="d.id as number"
                     :label="`${d.name}（¥${d.price}，存${d.stock}）`" :value="d.id as number" />
        </el-select>
        <el-input v-model="dose" placeholder="单次量" style="width: 80px" />
        <el-select v-model="freq" style="width: 80px">
          <el-option v-for="f in ['qd', 'bid', 'tid', 'q8h', 'st']" :key="f" :label="f" :value="f" />
        </el-select>
        <el-select v-model="route" style="width: 90px">
          <el-option v-for="u in ['口服', '静滴', '肌注']" :key="u" :label="u" :value="u" />
        </el-select>
        <el-input-number v-model="qty" :min="1" :max="999" style="width: 90px" />
        <!-- v39：长期医嘱按执行行逐日计费，临时医嘱开立即计费 -->
        <el-select v-model="orderNature" style="width: 80px">
          <el-option label="临时" value="TEMP" />
          <el-option label="长期" value="LONG" />
        </el-select>
        <el-button type="primary" @click="addDrug">开药</el-button>
        <el-select v-model="itemId" filterable remote :remote-method="searchItems" placeholder="检查/检验/治疗"
                   style="width: 220px">
          <el-option v-for="c in itemOptions" :key="c.id as number" :label="`${c.name}（¥${c.price}）`"
                     :value="c.id as number" />
        </el-select>
        <el-button type="primary" @click="addItem">开申请</el-button>
      </div>
      <el-table :data="orders" size="small" height="calc(100vh - 330px)">
        <el-table-column prop="groupNo" label="医嘱号" width="140" />
        <el-table-column label="类型" width="60">
          <template #default="{ row }">{{ { DRUG: '药', LAB: '验', EXAM: '查', TREAT: '治' }[row.orderType as string] }}</template>
        </el-table-column>
        <el-table-column prop="itemName" label="项目" />
        <el-table-column label="用法" width="150">
          <template #default="{ row }">
            <span v-if="row.orderType === 'DRUG'">{{ row.usageRoute }} {{ row.dosePerTime }} {{ row.frequency }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="qty" label="量" width="50" />
        <el-table-column prop="amount" label="金额" width="80" />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="{ CREATED: 'warning', EXECUTED: 'success', CANCELLED: 'info' }[row.status as string]">
              {{ { CREATED: '未执行', EXECUTED: '已执行', CANCELLED: '作废' }[row.status as string] }}
            </el-tag>
            <el-tag v-if="row.orderNature === 'LONG'" size="small" :type="row.stopAt ? 'info' : 'primary'" style="margin-left:4px">
              {{ row.stopAt ? '已停' : '长期' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="{ row }">
            <el-button v-if="row.orderNature === 'LONG' && !row.stopAt" link type="danger" size="small"
                       @click="stopLong(row)">停嘱</el-button>
          </template>
        </el-table-column>
      </el-table>
        </el-tab-pane>

        <el-tab-pane label="病历" name="records">
          <el-form inline>
            <el-form-item>
              <el-select v-model="recordType" style="width: 130px">
                <el-option label="入院记录" value="ADMISSION" />
                <el-option label="首次病程" value="FIRST_PROGRESS" />
                <el-option label="病程记录" value="PROGRESS" />
                <el-option label="三级查房" value="ROUND" />
                <!-- v42：术前小结此前只存在于 EmrIntegrityService 的判定里，下拉五项没有它——
                     手术病例因此常亮一条「缺术前小结」而医生无法自救。补上即闭环。 -->
                <el-option label="术前小结" value="PREOP" />
                <el-option label="出院小结" value="DISCHARGE" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="recordType === 'ROUND'">
              <el-select v-model="roundLevel" style="width: 120px">
                <el-option label="主任查房" value="CHIEF" />
                <el-option label="主治查房" value="ATTENDING" />
                <el-option label="住院医查房" value="RESIDENT" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-input v-model="recordTitle" placeholder="标题（可空）" style="width: 180px" />
            </el-form-item>
            <!-- v42：病历模板套用（本科室模板 + 全院通用模板）。模板后端 CRUD 与 EMR 分类早已就位，
                 此前唯一消费方是 RIS 报告页，医生写住院病历时用不到任何模板。 -->
            <el-form-item>
              <el-select v-model="emrTemplateId" clearable placeholder="套用病历模板" style="width: 200px"
                         no-data-text="本科室暂无病历模板（在「数据中心 · 病历模板」维护）" @change="applyEmrTemplate">
                <el-option v-for="t in emrTemplates" :key="t.id as number"
                           :label="`${t.name}${t.dept_id ? '' : '（通用）'}`" :value="t.id as number" />
              </el-select>
            </el-form-item>
          </el-form>
          <el-input v-model="recordContent" type="textarea" :rows="4"
                    :placeholder="recordType === 'ROUND' ? '查房意见' : '病历内容'" />
          <el-input v-if="recordType === 'ROUND'" v-model="superiorCorrection" type="textarea" :rows="2"
                    placeholder="上级修正意见（可空）" style="margin-top: 6px" />
          <el-button type="primary" style="margin-top: 8px" @click="addRecord">保存记录</el-button>
          <el-timeline style="margin-top: 16px">
            <el-timeline-item v-for="r in records" :key="r.id as number"
                              :timestamp="`${String(r.createdAt).slice(0, 16).replace('T', ' ')} · ${recordTypeNames[r.recordType as string]}`">
              <b>{{ r.title }}</b>
              <el-tag v-if="r.signature" size="small" type="success" style="margin-left: 6px">已签名</el-tag>
              <el-button v-else size="small" link type="primary" style="margin-left: 6px"
                         @click="signRecord(r)">签名</el-button>
              <!-- 阻塞4：签名冻结病历只能追加补正，不能改原文 -->
              <el-button v-if="r.signature" size="small" link type="warning" style="margin-left: 6px"
                         @click="openAmend(r)">补正</el-button>
              <p class="record-content">{{ r.content }}</p>
            </el-timeline-item>
          </el-timeline>
        </el-tab-pane>

        <el-tab-pane label="体征" name="vitals">
          <!-- v42 合版补：体温单打印入口。规划文档把它划给车道5、任务书划给车道1，两边都没落，
               合版时统一补在此处（PrintView 的 temp-sheet 分支由车道1 落地，此处不是死链）。 -->
          <div style="margin-bottom: 8px">
            <el-button size="small" @click="printTempSheet">打印体温单（三测单）</el-button>
          </div>
          <VitalsChart :vitals="vitals" />
          <el-table :data="vitals" size="small" height="calc(100vh - 560px)">
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ String(row.measuredAt).slice(0, 16).replace('T', ' ') }}</template>
            </el-table-column>
            <el-table-column prop="temperature" label="体温℃" width="80" />
            <el-table-column prop="pulse" label="脉搏" width="70" />
            <el-table-column prop="respiration" label="呼吸" width="70" />
            <el-table-column label="血压" width="100">
              <template #default="{ row }">
                <span v-if="row.sbp">{{ row.sbp }}/{{ row.dbp }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="spo2" label="SpO₂%" width="80" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
    <el-empty v-else class="workspace" description="选择在院患者" />

    <!-- 2013★：设置/变更主管医生（入院后唯一的修改路径；只 update doctor_id 一列） -->
    <el-dialog v-model="attendingVisible" title="设置主管医生" width="420px">
      <el-form label-width="90px">
        <el-form-item label="患者">
          <span>{{ current?.patientName }}（{{ current?.admissionNo }}）</span>
        </el-form-item>
        <el-form-item label="当前主管">
          <span>{{ current?.doctorName ?? '未指定' }}</span>
        </el-form-item>
        <el-form-item label="设为" required>
          <el-select v-model="attendingDoctorId" filterable placeholder="选择主管医生" style="width: 100%">
            <el-option v-for="d in doctorOptions" :key="d.id as number"
                       :label="`${d.real_name}${d.title ? '（' + d.title + '）' : ''}`" :value="d.id as number" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="attendingVisible = false">取消</el-button>
        <el-button type="primary" :loading="attendingSaving" @click="saveAttending">保存</el-button>
      </template>
    </el-dialog>

    <!-- 2028★：跨患者的病区级医嘱检索（按床号 / 姓名 / 医嘱内容）。
         此前住院医嘱只能"先选患者再看该患者医嘱"，"3 床那瓶头孢是谁开的"无从查起。 -->
    <el-dialog v-model="orderSearchVisible" title="医嘱检索（按床号 / 姓名 / 医嘱内容）" width="1000px">
      <div class="add-row">
        <el-input v-model="oq.bedNo" placeholder="床号" style="width: 90px" @keyup.enter="doOrderSearch" />
        <el-input v-model="oq.patientName" placeholder="患者姓名" style="width: 130px" @keyup.enter="doOrderSearch" />
        <el-input v-model="oq.keyword" placeholder="医嘱内容（药品名 / 项目名）" style="width: 240px"
                  @keyup.enter="doOrderSearch" />
        <el-select v-model="oq.deptId" clearable placeholder="科室" style="width: 130px">
          <el-option v-for="d in clinicalDepts" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-select v-model="oq.status" clearable placeholder="状态" style="width: 110px">
          <el-option label="未执行" value="CREATED" />
          <el-option label="已执行" value="EXECUTED" />
          <el-option label="作废" value="CANCELLED" />
        </el-select>
        <el-checkbox v-model="oq.includeDischarged">含已出院</el-checkbox>
        <el-button type="primary" :loading="orderSearching" @click="doOrderSearch">检索</el-button>
      </div>
      <el-alert v-if="orderSearchMsg" type="warning" show-icon :closable="false" :title="orderSearchMsg"
                style="margin-bottom: 8px" />
      <!-- 照抄 mr-workqueue 纪律：硬限 200 条 + truncated 提示，不做翻页 -->
      <el-alert v-if="orderTruncated" type="info" show-icon :closable="false" style="margin-bottom: 8px"
                title="命中超过 200 条，仅显示前 200 条——请收窄检索条件（加床号或姓名），本页不提供翻页" />
      <el-table :data="orderHits" size="small" height="420" empty-text="输入条件后检索">
        <el-table-column prop="bed_no" label="床" width="55" />
        <el-table-column prop="patient_name" label="姓名" width="85" />
        <el-table-column prop="ward_name" label="病区" width="100" />
        <el-table-column label="类型" width="55">
          <template #default="{ row }">{{ orderTypeNames[row.order_type as string] ?? row.order_type }}</template>
        </el-table-column>
        <el-table-column prop="item_name" label="医嘱内容" show-overflow-tooltip />
        <el-table-column label="用法" width="140">
          <template #default="{ row }">
            <span v-if="row.order_type === 'DRUG'">{{ row.usage_route }} {{ row.dose_per_time }} {{ row.frequency }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="amount" label="金额" width="80" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="{ CREATED: 'warning', EXECUTED: 'success', CANCELLED: 'info' }[row.status as string]">
              {{ { CREATED: '未执行', EXECUTED: '已执行', CANCELLED: '作废' }[row.status as string] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="order_doctor_name" label="开单医师" width="90" />
        <el-table-column prop="executor_name" label="执行人" width="90" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="jumpToPatient(row)">打开患者</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <span class="fees">共 {{ orderHits.length }} 条</span>
        <el-button @click="orderSearchVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 收尾环·阻塞3：转科转床（选目标科室 + 空床 + 原因，调已有 transfer 接口） -->
    <el-dialog v-model="transferVisible" title="转科转床" width="600px">
      <el-form label-width="90px">
        <el-form-item label="目标科室" required>
          <el-select v-model="tf.toDeptId" placeholder="选择收治科室" style="width: 100%">
            <el-option v-for="d in clinicalDepts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标病区" required>
          <el-select v-model="tfWardId" placeholder="选择病区后挑选空床" style="width: 100%" @change="loadTransferBeds">
            <el-option v-for="w in wards" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标床位" required>
          <el-radio-group v-model="tf.toBedId">
            <el-radio v-for="b in transferBeds" :key="b.id as number" :value="b.id as number"
                      :disabled="b.status !== 'FREE'" border style="margin: 2px">
              {{ b.bedNo }}{{ b.status !== 'FREE' ? `(${b.patientName ?? '占'})` : '' }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="转科原因">
          <el-input v-model="tf.reason" type="textarea" :rows="2" placeholder="如：病情变化需专科处理" />
        </el-form-item>
      </el-form>
      <el-divider>转科历史</el-divider>
      <el-table :data="transferHistory" size="small" height="160" empty-text="暂无转科记录">
        <el-table-column label="时间" width="140">
          <template #default="{ row }">{{ String(row.created_at).slice(0, 16).replace('T', ' ') }}</template>
        </el-table-column>
        <el-table-column label="由">
          <template #default="{ row }">{{ row.from_dept_name }} {{ row.from_bed_no }}床</template>
        </el-table-column>
        <el-table-column label="至">
          <template #default="{ row }">{{ row.to_dept_name }} {{ row.to_bed_no }}床</template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button @click="transferVisible = false">取消</el-button>
        <el-button type="primary" :loading="transferring" @click="doTransfer">确认转科</el-button>
      </template>
    </el-dialog>

    <!-- 阻塞4：住院病历补正（签名冻结病历追加法定留痕，不改原文） -->
    <el-dialog v-model="amendVisible" title="病历补正" width="560px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px"
                :title="`《${amendTarget?.title ?? ''}》已签名冻结，原文保留，追加补正记录留痕可追溯`" />
      <el-form label-width="80px">
        <el-form-item label="补正内容" required>
          <el-input v-model="amendForm.amendText" type="textarea" :rows="3" placeholder="正确的表述/更正说明" />
        </el-form-item>
        <el-form-item label="补正原因" required>
          <el-input v-model="amendForm.reason" placeholder="如：录入笔误、诊断补充" />
        </el-form-item>
      </el-form>
      <el-divider>补正历史</el-divider>
      <el-timeline v-if="recordAmendments.length">
        <el-timeline-item v-for="a in recordAmendments" :key="a.id as number"
                          :timestamp="`${String(a.amended_at).slice(0, 16).replace('T', ' ')} · ${a.amended_by_name ?? ('用户' + a.amended_by)}`">
          <b>补正：</b>{{ a.amend_text }}
          <div class="record-content">原因：{{ a.reason }}</div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无补正记录" :image-size="60" />
      <template #footer>
        <el-button @click="amendVisible = false">关闭</el-button>
        <el-button type="warning" :loading="amending" @click="submitAmend">提交补正</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import VitalsChart from '../../components/VitalsChart.vue'

/** v42：体温单打印（周次由打印页自行翻页，此处固定从第 1 住院周进） */
function printTempSheet() {
  if (!current.value) return
  window.open(`/print?type=temp-sheet&id=${current.value.id}&week=1`, '_blank')
}

const admissions = ref<Record<string, unknown>[]>([])
const current = ref<Record<string, unknown> | null>(null)
const orders = ref<Record<string, unknown>[]>([])
const totalAmount = ref(0)
const depositAmount = ref(0)
// 收尾环·阻塞1：住院账户实时状态（押金/已发生费用/余额/是否欠费）
const account = ref<{ balance: number; owed: boolean } | null>(null)
const drugOptions = ref<Record<string, unknown>[]>([])
const itemOptions = ref<Record<string, unknown>[]>([])
const drugId = ref<number | null>(null)
const itemId = ref<number | null>(null)
const dose = ref('1粒')
const freq = ref('bid')
const route = ref('口服')
const qty = ref(1)
const orderNature = ref('TEMP')   // v39：临时/长期
const tab = ref('orders')
const records = ref<Record<string, unknown>[]>([])
const vitals = ref<Record<string, unknown>[]>([])
const recordType = ref('PROGRESS')
const recordTitle = ref('')
const recordContent = ref('')
// v42：PREOP 补入中文名——此前 EmrIntegrityService 判「缺术前小结」，而列表里 PREOP 行显示为 undefined
const recordTypeNames: Record<string, string> = { ADMISSION: '入院记录', FIRST_PROGRESS: '首次病程', PROGRESS: '病程记录', ROUND: '三级查房', PREOP: '术前小结', DISCHARGE: '出院小结' }
const roundLevel = ref('ATTENDING')
const superiorCorrection = ref('')

// v42 病历模板：GET /emr-templates?type=EMR&deptId=当前科室（后端口径为「本科室 或 全院通用」）
const emrTemplates = ref<Record<string, unknown>[]>([])
const emrTemplateId = ref<number | null>(null)

// 转科转床（收尾环·阻塞3）
const depts = ref<{ id: number; name: string; type: string }[]>([])
const clinicalDepts = computed(() => depts.value.filter((d) => d.type === 'CLINICAL'))
const wards = computed(() => depts.value.filter((d) => d.type === 'NURSING'))
const transferVisible = ref(false)
const transferring = ref(false)
const tfWardId = ref<number | null>(null)
const transferBeds = ref<{ id: number; bedNo: string; status: string; patientName?: string }[]>([])
const transferHistory = ref<Record<string, unknown>[]>([])
const tf = reactive({ toDeptId: null as number | null, toBedId: null as number | null, reason: '' })

// 2012★/2013★：在院患者多维检索（全部可选；一个都不填 = 旧的"全院在院一览"）
const careLevels = ['特级', '一级', '二级', '三级']
const q = reactive({
  keyword: '',
  deptId: null as number | null,
  careLevel: null as string | null,
  transferred: null as boolean | null,
  doctorId: null as number | null,
  mine: false,
})
const listError = ref('')
const filterActive = computed(() =>
  !!q.keyword.trim() || q.deptId != null || !!q.careLevel || q.transferred != null
  || q.doctorId != null || q.mine)

// 2013★：主管医生字典 + 设置主管医生
const doctorOptions = ref<Record<string, unknown>[]>([])
const attendingVisible = ref(false)
const attendingSaving = ref(false)
const attendingDoctorId = ref<number | null>(null)

// 2028★：跨患者医嘱检索
const orderTypeNames: Record<string, string> = { DRUG: '药', LAB: '验', EXAM: '查', TREAT: '治' }
const orderSearchVisible = ref(false)
const orderSearching = ref(false)
const orderSearchMsg = ref('')
const orderTruncated = ref(false)
const orderHits = ref<Record<string, unknown>[]>([])
const oq = reactive({
  bedNo: '',
  patientName: '',
  keyword: '',
  deptId: null as number | null,
  status: null as string | null,
  includeDischarged: false,
})

// 病历补正（阻塞4）
const amendVisible = ref(false)
const amending = ref(false)
const amendTarget = ref<Record<string, unknown> | null>(null)
const amendForm = reactive({ amendText: '', reason: '' })
const recordAmendments = ref<Record<string, unknown>[]>([])

/**
 * 2012★/2013★ 多维检索。
 *
 * <p>只把**填了的**条件放进 params——后端"零条件 = 旧行为"的契约由此在前端侧也成立：
 * 空条件时这里发出的就是一个不带任何 query 的 GET /inpatient/admissions。
 */
async function loadList() {
  const params: Record<string, unknown> = {}
  if (q.keyword.trim()) params.keyword = q.keyword.trim()
  if (q.deptId != null) params.deptId = q.deptId
  if (q.careLevel) params.careLevel = q.careLevel
  if (q.transferred != null) params.transferred = q.transferred
  if (q.doctorId != null) params.doctorId = q.doctorId
  if (q.mine) params.mine = true
  const resp = await client.get('/inpatient/admissions', { params })
  if (resp.data.code !== 0) {
    // 4880 检索条件非法 / 4881 护理级别非法：就地提示，不清空已有列表
    listError.value = resp.data.message
    return
  }
  listError.value = ''
  admissions.value = resp.data.data
}

function resetFilters() {
  q.keyword = ''
  q.deptId = null
  q.careLevel = null
  q.transferred = null
  q.doctorId = null
  q.mine = false
  loadList()
}

/** 主管医生字典：/system/users 是 ADMIN 专属，医生站用住院线的只读字典端点 */
async function loadDoctorOptions() {
  doctorOptions.value = (await client.get('/inpatient/doctors')).data.data
}

function openAttending() {
  attendingDoctorId.value = (current.value?.doctorId as number | null) ?? null
  attendingVisible.value = true
}

async function saveAttending() {
  if (!current.value || !attendingDoctorId.value) {
    ElMessage.warning('请选择主管医生')
    return
  }
  attendingSaving.value = true
  try {
    const resp = await client.put(`/inpatient/admissions/${current.value.id}/attending-doctor`,
      { doctorId: attendingDoctorId.value })
    if (resp.data.code !== 0) {
      ElMessage.error(resp.data.message)
      return
    }
    ElMessage.success('主管医生已更新')
    attendingVisible.value = false
    const id = current.value.id
    await loadList()
    // 列表行带 doctorName，重新指向刷新后的那一行以更新表头
    current.value = admissions.value.find((a) => a.id === id) ?? current.value
  } finally {
    attendingSaving.value = false
  }
}

function openOrderSearch() {
  orderSearchVisible.value = true
}

/** 2028★：跨患者医嘱检索。后端要求至少一个条件（否则 4880），提示直接透传 */
async function doOrderSearch() {
  const params: Record<string, unknown> = {}
  if (oq.bedNo.trim()) params.bedNo = oq.bedNo.trim()
  if (oq.patientName.trim()) params.patientName = oq.patientName.trim()
  if (oq.keyword.trim()) params.keyword = oq.keyword.trim()
  if (oq.deptId != null) params.deptId = oq.deptId
  if (oq.status) params.status = oq.status
  if (oq.includeDischarged) params.includeDischarged = true
  orderSearching.value = true
  try {
    const resp = await client.get('/inpatient/orders/search', { params })
    if (resp.data.code !== 0) {
      orderSearchMsg.value = resp.data.message
      orderHits.value = []
      orderTruncated.value = false
      return
    }
    orderSearchMsg.value = ''
    orderHits.value = resp.data.data.items
    orderTruncated.value = resp.data.data.truncated
  } finally {
    orderSearching.value = false
  }
}

/** 从医嘱检索结果跳回该患者工作区（命中的可能是当前未加载/被筛掉的患者，故先按 id 兜底拉全量） */
async function jumpToPatient(row: Record<string, unknown>) {
  const admissionId = Number(row.admission_id)
  let hit = admissions.value.find((a) => Number(a.id) === admissionId)
  if (!hit) {
    resetFilters()
    await loadList()
    hit = admissions.value.find((a) => Number(a.id) === admissionId)
  }
  if (!hit) {
    ElMessage.warning('该患者已不在当前在院列表（可能已出院）')
    return
  }
  orderSearchVisible.value = false
  await open(hit)
}

async function loadAccount(id: unknown) {
  account.value = (await client.get(`/inpatient/admissions/${id}/account`)).data.data
}

/**
 * v42：拉本科室可用的 EMR 模板。住院病案 DTO 只带 deptName 不带 deptId（InpatientController.toDto），
 * 用已加载的科室字典按名反查 id；查不到就退化为不带 deptId 的全量查询（只会多出别科模板，不会漏）。
 */
async function loadEmrTemplates() {
  const deptName = String(current.value?.deptName ?? '')
  const deptId = depts.value.find((d) => d.name === deptName)?.id
  const params: Record<string, unknown> = { type: 'EMR' }
  if (deptId) params.deptId = deptId
  emrTemplates.value = (await client.get('/emr-templates', { params })).data.data
}

/** 套用模板到病历正文。已有内容时先确认——医生写了一半被模板冲掉是不可撤销的损失。 */
async function applyEmrTemplate() {
  const t = emrTemplates.value.find((x) => x.id === emrTemplateId.value)
  if (!t) return
  if (recordContent.value.trim()) {
    const ok = await ElMessageBox.confirm('当前病历内容将被模板覆盖，是否继续？', '套用模板', { type: 'warning' })
      .catch(() => null)
    if (!ok) {
      emrTemplateId.value = null
      return
    }
  }
  recordContent.value = String(t.content ?? '')
  if (!recordTitle.value.trim()) recordTitle.value = String(t.name ?? '')
}

async function open(row: Record<string, unknown> | null) {
  current.value = row
  account.value = null
  emrTemplateId.value = null
  emrTemplates.value = []
  if (!row) return
  const [ws, rec, vit] = await Promise.all([
    client.get(`/inpatient/admissions/${row.id}/workspace`),
    client.get(`/inpatient/admissions/${row.id}/records`),
    client.get(`/inpatient/admissions/${row.id}/vitals`),
    loadAccount(row.id),
    loadEmrTemplates(),
  ])
  orders.value = ws.data.data.orders
  totalAmount.value = ws.data.data.totalAmount
  depositAmount.value = ws.data.data.depositAmount
  records.value = rec.data.data
  vitals.value = vit.data.data
}

function openTransfer() {
  if (!current.value) return
  tf.toDeptId = null
  tf.toBedId = null
  tf.reason = ''
  tfWardId.value = null
  transferBeds.value = []
  loadTransferHistory()
  transferVisible.value = true
}

async function loadTransferHistory() {
  if (!current.value) return
  transferHistory.value = (await client.get(`/inpatient/admissions/${current.value.id}/transfers`)).data.data
}

async function loadTransferBeds() {
  if (!tfWardId.value) return
  tf.toBedId = null
  transferBeds.value = (await client.get('/inpatient/beds', { params: { wardId: tfWardId.value } })).data.data
}

async function doTransfer() {
  if (!current.value) return
  if (!tf.toDeptId || !tf.toBedId) {
    ElMessage.warning('请选择目标科室与空床')
    return
  }
  transferring.value = true
  try {
    await client.post(`/inpatient/admissions/${current.value.id}/transfer`, {
      toDeptId: tf.toDeptId, toBedId: tf.toBedId, reason: tf.reason || null,
    })
    ElMessage.success('转科成功')
    transferVisible.value = false
    await loadList()
    // 转科改了科室/床位，刷新当前工作区表头
    const updated = admissions.value.find((a) => a.id === current.value?.id) ?? null
    await open(updated)
  } finally {
    transferring.value = false
  }
}

async function addRecord() {
  if (!current.value || !recordContent.value) {
    ElMessage.warning(recordType.value === 'ROUND' ? '请填写查房意见' : '请填写病历内容')
    return
  }
  if (recordType.value === 'ROUND') {
    // v34 三级查房走结构化端点（记录级别/查房意见/上级修正意见）
    await client.post(`/inpatient/admissions/${current.value.id}/records/round`, {
      roundLevel: roundLevel.value,
      roundOpinion: recordContent.value,
      superiorCorrection: superiorCorrection.value || undefined,
      title: recordTitle.value || undefined,
    })
    superiorCorrection.value = ''
  } else {
    await client.post(`/inpatient/admissions/${current.value.id}/records`, {
      recordType: recordType.value,
      title: recordTitle.value || recordTypeNames[recordType.value],
      content: recordContent.value,
    })
  }
  ElMessage.success('病历已保存')
  recordContent.value = ''
  recordTitle.value = ''
  emrTemplateId.value = null
  await open(current.value)
}

// 1.0.4：病历 CA 签名（签名后冻结标识）
async function signRecord(r: Record<string, unknown>) {
  if (!current.value) return
  await client.post(`/inpatient/admissions/${current.value.id}/records/${r.id}/sign`)
  ElMessage.success('已签名')
  await open(current.value)
}

// 阻塞4：签名冻结病历追加补正记录（原文保留，法定留痕）
async function openAmend(r: Record<string, unknown>) {
  amendTarget.value = r
  amendForm.amendText = ''
  amendForm.reason = ''
  recordAmendments.value = await loadRecordAmendments(r.id as number)
  amendVisible.value = true
}

async function loadRecordAmendments(recordId: number) {
  if (!current.value) return []
  const resp = await client.get(`/inpatient/admissions/${current.value.id}/records/${recordId}/amendments`)
  return (resp.data.data ?? []) as Record<string, unknown>[]
}

async function submitAmend() {
  if (!current.value || !amendTarget.value) return
  if (!amendForm.amendText.trim() || !amendForm.reason.trim()) {
    ElMessage.warning('补正内容与补正原因均须填写')
    return
  }
  amending.value = true
  try {
    const resp = await client.post(
      `/inpatient/admissions/${current.value.id}/records/${amendTarget.value.id}/amend`,
      { amendText: amendForm.amendText, reason: amendForm.reason },
    )
    if (resp.data.code !== 0) {
      ElMessage.error(resp.data.message)
      return
    }
    ElMessage.success('补正已留痕')
    amendForm.amendText = ''
    amendForm.reason = ''
    recordAmendments.value = await loadRecordAmendments(amendTarget.value.id as number)
  } finally {
    amending.value = false
  }
}

async function searchDrugs(kw: string) {
  const resp = await client.get('/masterdata/drugs', { params: { keyword: kw } })
  drugOptions.value = resp.data.data
}

async function searchItems(kw: string) {
  const resp = await client.get('/masterdata/charge-items', { params: { keyword: kw } })
  itemOptions.value = resp.data.data
}

async function addDrug() {
  if (!current.value || !drugId.value) return
  await client.post(`/inpatient/admissions/${current.value.id}/orders`, {
    lines: [{ orderType: 'DRUG', itemId: drugId.value, qty: qty.value, usageRoute: route.value, frequency: freq.value, dosePerTime: dose.value, orderNature: orderNature.value }],
  })
  ElMessage.success(orderNature.value === 'LONG' ? '长期医嘱已开立（按执行行逐日计费）' : '医嘱已开立')
  drugId.value = null
  await open(current.value)
}

async function stopLong(row: Record<string, unknown>) {
  await ElMessageBox.confirm('停止该长期医嘱？未执行的当日执行行将跳过，费用固化。', '停嘱确认', { type: 'warning' })
    .catch(() => null)
    .then(async (ok: unknown) => {
      if (ok) {
        await client.post(`/inpatient/orders/${row.id}/stop`)
        ElMessage.success('已停嘱')
        await open(current.value!)
      }
    })
}

async function addItem() {
  if (!current.value || !itemId.value) return
  const item = itemOptions.value.find((c) => c.id === itemId.value)
  await client.post(`/inpatient/admissions/${current.value.id}/orders`, {
    lines: [{ orderType: item?.category ?? 'TREAT', itemId: itemId.value, qty: 1 }],
  })
  ElMessage.success('申请已开立')
  itemId.value = null
  await open(current.value)
}

onMounted(async () => {
  depts.value = (await client.get('/system/depts')).data.data
  await loadDoctorOptions()
  await loadList()
})
</script>

<style scoped>
.inp-doctor { display: grid; grid-template-columns: 340px 1fr; gap: 12px; }
.filters { display: flex; flex-direction: column; gap: 6px; margin-bottom: 8px; }
.filter-row { display: flex; justify-content: space-between; align-items: center; }
.list-count { color: #909399; font-size: 12px; margin-bottom: 4px; }
.add-row { display: flex; gap: 6px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.fees { float: right; color: #909399; font-size: 13px; }
.owed { color: #d03050; font-weight: 700; }
.record-content { white-space: pre-wrap; color: #555; margin: 4px 0 0; }
</style>
