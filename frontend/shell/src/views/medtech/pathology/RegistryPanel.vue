<template>
  <!-- ============ 工位一：登记（双来源申请 → 病理号 → 接收核对 / 拒收） ============ -->
  <el-alert type="info" show-icon :closable="false" class="cav"
            title="病理号 ≠ 条码：条码（PB…）是院内流转用、扫码枪扫的就是它；病理号（如 2026-C-000123）是对外出报告的法定编号，按年 + 类别连续。两者都生成、都落库，谁也不替代谁。" />

  <el-tabs v-model="tab">
    <!-- ---------------- 待登记申请 ---------------- -->
    <el-tab-pane name="pending" :label="`待登记申请（${pending.length}）`">
      <el-form inline size="small">
        <el-form-item label="来源">
          <el-select v-model="pendingQuery.source" clearable placeholder="全部" style="width: 110px">
            <el-option v-for="s in SOURCES" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="pendingQuery.keyword" clearable placeholder="患者姓名 / 项目名"
                    style="width: 200px" @keyup.enter="loadPending" />
        </el-form-item>
        <el-form-item label="含已登记">
          <el-switch v-model="pendingQuery.includeRegistered" />
          <span class="muted" style="margin-left: 6px">多部位分送时，同一申请要登记多条</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="pendingLoading" @click="loadPending">查询</el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="pendingTruncated" type="warning" show-icon :closable="false" class="cav"
                :title="`命中超过 ${pendingLimit} 条，仅显示前 ${pendingLimit} 条（本端点不做翻页）；请收窄条件`" />
      <el-alert type="info" :closable="false" class="cav"
                title="住院来源的「临床摘要」「加急」两列恒为空：这两列是 V137 只给门诊医嘱加的，住院医嘱没有——如实留空，不拿别的列凑数。门诊判据是「已收费」，住院判据是「未作废」，两条业务线本来就不同。" />

      <el-table :data="pending" v-loading="pendingLoading" size="small" border stripe max-height="420">
        <el-table-column label="来源" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.source === 'OUTP' ? '' : 'success'">
              {{ sourceName(row.source) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="patient_name" label="患者" width="100" />
        <el-table-column label="性别" width="60">
          <template #default="{ row }">{{ fmt(row.sex) }}</template>
        </el-table-column>
        <el-table-column prop="item_name" label="申请项目" min-width="160" show-overflow-tooltip />
        <el-table-column label="医嘱状态" width="90">
          <template #default="{ row }">{{ fmt(row.order_status) }}</template>
        </el-table-column>
        <el-table-column label="加急" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.urgent === true" size="small" type="danger">加急</el-tag>
            <span v-else-if="row.urgent === false">否</span>
            <span v-else class="muted">—（住院无此列）</span>
          </template>
        </el-table-column>
        <el-table-column label="临床摘要" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.clinical_summary">{{ row.clinical_summary }}</span>
            <span v-else-if="row.source === 'INP'" class="muted">—（住院医嘱无此列）</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="已登记部位" width="100">
          <template #default="{ row }">{{ num(row.registered_parts) }}</template>
        </el-table-column>
        <el-table-column label="申请时刻" width="140">
          <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openRegister(row, false)">登记</el-button>
            <el-button link type="warning" size="small" @click="openRegister(row, true)">手工登记</el-button>
          </template>
        </el-table-column>
        <template #empty>无待登记申请</template>
      </el-table>
    </el-tab-pane>

    <!-- ---------------- 标本检索与接收核对 / 拒收 ---------------- -->
    <el-tab-pane name="search" :label="`标本检索（${specimens.length}）`">
      <el-form inline size="small">
        <el-form-item label="病理号">
          <el-input v-model="searchQuery.pathNo" clearable placeholder="前缀匹配" style="width: 140px"
                    @keyup.enter="loadSpecimens" />
        </el-form-item>
        <el-form-item label="条码">
          <el-input v-model="searchQuery.barcode" clearable placeholder="扫码或手输前几位" style="width: 140px"
                    @keyup.enter="loadSpecimens" />
        </el-form-item>
        <el-form-item label="患者">
          <el-input v-model="searchQuery.patientName" clearable style="width: 110px"
                    @keyup.enter="loadSpecimens" />
        </el-form-item>
        <el-form-item label="登记日期">
          <el-date-picker v-model="searchRange" type="daterange" unlink-panels value-format="YYYY-MM-DD"
                          range-separator="至" start-placeholder="起" end-placeholder="止"
                          style="width: 230px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchQuery.status" clearable placeholder="全部" style="width: 110px">
            <el-option v-for="s in SPECIMEN_STATUSES" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="searchQuery.specimenType" clearable placeholder="全部" style="width: 120px">
            <el-option v-for="t in SPECIMEN_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源">
          <el-select v-model="searchQuery.source" clearable placeholder="全部" style="width: 100px">
            <el-option v-for="s in SOURCES" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="拒收">
          <el-select v-model="rejectedFlag" clearable placeholder="全部" style="width: 120px">
            <el-option label="仅已拒收" value="Y" />
            <el-option label="仅未拒收" value="N" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="searchLoading" @click="loadSpecimens">检索</el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="searchTruncated" type="warning" show-icon :closable="false" class="cav"
                :title="`命中超过 ${searchLimit} 条，仅显示前 ${searchLimit} 条（不做翻页）；请收窄检索条件`" />
      <el-alert type="info" :closable="false" class="cav"
                title="「已拒收」看 rejected_at 而不是 status——status 的值域仍是 已登记 / 已核收 / 已诊断 三档（v48 刻意不扩值域）。拒收不删记录：删了行就永远算不出「送检多少、拒了多少」。" />

      <el-table :data="specimens" v-loading="searchLoading" size="small" border stripe max-height="460">
        <el-table-column prop="path_no" label="病理号" width="130">
          <template #default="{ row }">{{ fmt(row.path_no) }}</template>
        </el-table-column>
        <el-table-column prop="barcode" label="条码" width="100" />
        <el-table-column label="部位" width="60">
          <template #default="{ row }">{{ fmt(row.part_no) }}</template>
        </el-table-column>
        <el-table-column label="患者" width="100">
          <template #default="{ row }">{{ fmt(row.patient_name) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="70">
          <template #default="{ row }">{{ sourceName(row.source) }}</template>
        </el-table-column>
        <el-table-column label="类别" width="90">
          <template #default="{ row }">{{ typeName(row.specimen_type) }}</template>
        </el-table-column>
        <!-- v60（2530 尾）：登记时录入的标本描述此前只写不读——四个读端点都 select 了它，前端却没有一处只读展示 -->
        <el-table-column label="标本描述" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ fmt(row.specimen_desc) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="180">
          <template #default="{ row }">
            <el-tag size="small">{{ statusName(row.status) }}</el-tag>
            <el-tag v-if="row.rejected_at" size="small" type="danger" style="margin-left: 4px">已拒收</el-tag>
            <el-tag v-if="row.urgent === true" size="small" type="warning" style="margin-left: 4px">加急</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="登记时刻" width="140">
          <template #default="{ row }">{{ fmtTime(row.collected_at) }}</template>
        </el-table-column>
        <el-table-column label="签收时刻" width="140">
          <template #default="{ row }">{{ fmtTime(row.received_at) }}</template>
        </el-table-column>
        <el-table-column label="拒收原因" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ fmt(row.reject_reason) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small"
                       :disabled="row.status !== 'COLLECTED' || !!row.rejected_at"
                       @click="openReceive(row)">接收核对</el-button>
            <el-button link type="danger" size="small"
                       :disabled="!!row.rejected_at || row.status === 'DIAGNOSED'"
                       @click="openReject(row)">拒收</el-button>
            <el-button link type="info" size="small" @click="openHistory(row)">既往病理</el-button>
          </template>
        </el-table-column>
        <template #empty>无命中标本</template>
      </el-table>
    </el-tab-pane>
  </el-tabs>

  <!-- ============ 登记弹窗 ============ -->
  <el-dialog v-model="regDialog" :title="regForm.manual ? '手工登记（无电子病理申请单）' : '病理申请登记'"
             width="640px">
    <el-alert v-if="regForm.manual" type="warning" show-icon :closable="false" class="cav"
              title="手工登记是受限版：仍需一个院内医嘱 id（path_specimen 无 patient_id 列、chk_path_specimen_source 要求门诊/住院恰有其一）；放宽的是「项目名不含病理二字」「门诊未收费」两道闸。手工登记直接置为已核收，之后不能再走接收核对（否则会落两条 RECEIVE，把接收及时率的分母算成两倍）。申请科室/申请医师无结构化落点，只写进流转备注，不参与任何统计。" />
    <el-descriptions :column="2" border size="small" class="cav">
      <el-descriptions-item label="来源">{{ sourceName(regForm.source) }}</el-descriptions-item>
      <el-descriptions-item label="患者">{{ regForm.patientName }}</el-descriptions-item>
      <el-descriptions-item label="申请项目" :span="2">{{ regForm.itemName }}</el-descriptions-item>
    </el-descriptions>

    <el-form label-width="100px" size="small">
      <el-form-item label="部位序号">
        <el-input-number v-model="regForm.partNo" :min="1" :max="99" />
        <span class="muted" style="margin-left: 8px">
          多部位分别送检时逐条登记；部位序号是蜡块编码与报告上「3 号蜡块」的来源（已拒收的不占号）
        </span>
      </el-form-item>
      <el-form-item label="标本类别" required>
        <el-select v-model="regForm.specimenType" style="width: 220px">
          <el-option v-for="t in SPECIMEN_TYPES" :key="t.value" :value="t.value"
                     :label="`${t.label}（病理号类别码 ${t.code}）`" />
        </el-select>
      </el-form-item>
      <el-form-item label="标本描述">
        <el-input v-model="regForm.specimenDesc" type="textarea" :rows="2" maxlength="255" show-word-limit
                  placeholder="上限 255 字，超长会被拒绝（不静默截断——诊断依据不许悄悄截掉后半段）" />
      </el-form-item>
      <el-form-item label="取材部位">
        <el-input v-model="regForm.samplingSite" maxlength="128" show-word-limit />
      </el-form-item>
      <el-form-item label="临床诊断">
        <el-input v-model="regForm.clinicalDiagnosis" type="textarea" :rows="2"
                  maxlength="500" show-word-limit />
      </el-form-item>
      <el-form-item label="固定液">
        <el-input v-model="regForm.fixative" maxlength="32" show-word-limit style="width: 220px"
                  placeholder="自由文本，无字典" />
        <span class="muted" style="margin-left: 8px">
          本平台不按关键字（如含「福尔马林」）猜规范性，质控只判「录没录全」
        </span>
      </el-form-item>
      <el-form-item label="离体固定时刻">
        <el-date-picker v-model="regForm.fixedAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
                        placeholder="可留空；格式不合法直接报错，不会静默当作此刻" style="width: 260px" />
      </el-form-item>
      <el-form-item label="加急">
        <el-switch v-model="regForm.urgent" />
      </el-form-item>
      <template v-if="regForm.manual">
        <el-form-item label="申请科室">
          <el-input v-model="regForm.applyDept" placeholder="仅写入流转备注，不参与统计" style="width: 220px" />
        </el-form-item>
        <el-form-item label="申请医师">
          <el-input v-model="regForm.applyDoctor" placeholder="仅写入流转备注，不参与统计" style="width: 220px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="regForm.note" />
        </el-form-item>
      </template>
    </el-form>

    <template #footer>
      <el-button size="small" @click="regDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submitRegister">登记</el-button>
    </template>
  </el-dialog>

  <!-- ============ 登记结果（病理号与条码并列） ============ -->
  <el-dialog v-model="resultDialog" title="登记成功" width="480px">
    <el-descriptions :column="1" border size="small">
      <el-descriptions-item label="病理号（对外出报告）">
        <b class="code">{{ fmt(regResult.pathNo) }}</b>
      </el-descriptions-item>
      <el-descriptions-item label="条码（院内流转 / 扫码枪）">
        <b class="code">{{ fmt(regResult.barcode) }}</b>
      </el-descriptions-item>
      <el-descriptions-item label="部位序号">{{ fmt(regResult.partNo) }}</el-descriptions-item>
      <el-descriptions-item label="标本类别">{{ typeName(regResult.specimenType) }}</el-descriptions-item>
      <el-descriptions-item label="状态">
        {{ statusName(regResult.status) }}
        <el-tag v-if="regResult.manual === true" size="small" type="warning" style="margin-left: 6px">
          手工登记（已直接置为已核收）</el-tag>
      </el-descriptions-item>
    </el-descriptions>
    <template #footer>
      <el-button type="primary" size="small" @click="resultDialog = false">知道了</el-button>
    </template>
  </el-dialog>

  <!-- ============ 接收核对 ============ -->
  <el-dialog v-model="receiveDialog" title="接收核对" width="520px">
    <el-alert type="info" :closable="false" show-icon class="cav"
              title="核对不相符的标本不要在这里「先核收再说」——直接走拒收，否则台账上就成了一份已正常核收的标本。" />
    <el-form label-width="90px" size="small">
      <el-form-item label="标本">
        <span class="code">{{ fmt(current.path_no) }} / {{ fmt(current.barcode) }}</span>
        　{{ fmt(current.patient_name) }}
      </el-form-item>
      <el-form-item label="接收时刻">
        <el-date-picker v-model="receiveForm.receivedAt" type="datetime"
                        value-format="YYYY-MM-DD HH:mm:ss" placeholder="留空取此刻；不得早于登记时刻"
                        style="width: 100%" />
      </el-form-item>
      <el-form-item label="核对备注">
        <el-input v-model="receiveForm.remark" maxlength="255" show-word-limit
                  placeholder="如：与申请单部位一致、标本量足" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="receiveDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submitReceive">核收</el-button>
    </template>
  </el-dialog>

  <!-- ============ 拒收 ============ -->
  <el-dialog v-model="rejectDialog" title="拒收标本" width="520px">
    <el-alert type="warning" show-icon :closable="false" class="cav"
              title="拒收不删记录：只写拒收原因/时刻/人并落一条 REJECT 流转节点，行还在，仍计入送检总数的分母。拒收后临床重送同一部位是常态，该部位序号不会被烧掉。" />
    <el-form label-width="90px" size="small">
      <el-form-item label="标本">
        <span class="code">{{ fmt(current.path_no) }} / {{ fmt(current.barcode) }}</span>
        　{{ fmt(current.patient_name) }}
      </el-form-item>
      <el-form-item label="拒收原因" required>
        <el-input v-model="rejectForm.reason" type="textarea" :rows="2" maxlength="255" show-word-limit
                  placeholder="必填——没有原因的拒收在质控上等于没发生过" />
      </el-form-item>
      <el-form-item label="拒收时刻">
        <el-date-picker v-model="rejectForm.rejectedAt" type="datetime"
                        value-format="YYYY-MM-DD HH:mm:ss" placeholder="留空取此刻" style="width: 100%" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="rejectDialog = false">取消</el-button>
      <el-button type="danger" size="small" :loading="saving" @click="submitReject">确认拒收</el-button>
    </template>
  </el-dialog>

  <!-- ============ 既往病理 ============ -->
  <el-drawer v-model="historyDrawer" size="66%"
             :title="`既往病理 — ${fmt(current.patient_name)}（${fmt(current.path_no)}）`">
    <el-alert v-if="historyError" type="error" show-icon :closable="false" class="cav"
              :title="historyError" />
    <template v-else>
      <el-form inline size="small">
        <el-form-item label="同名他人提醒">
          <el-switch v-model="includeSameName" @change="loadHistory" />
          <span class="muted" style="margin-left: 8px">
            只给「有几份、最近一次何时」，不给别人的诊断——那是越界
          </span>
        </el-form-item>
        <el-form-item label="含拒收">
          <el-switch v-model="includeRejected" @change="loadHistory" />
          <span class="muted" style="margin-left: 8px">
            默认不含：拒收不删行，但一份因未固定被拒收、从未受检的标本不是既往病理（与诊断页既往页签同口径）
          </span>
        </el-form-item>
      </el-form>
      <el-alert v-if="historyNote" type="info" :closable="false" class="cav" :title="historyNote" />
      <el-alert v-if="historyTruncated" type="warning" :closable="false" class="cav"
                :title="`既往记录超过 ${historyLimit} 条，仅显示最近 ${historyLimit} 条`" />
      <el-table :data="history" v-loading="historyLoading" size="small" border max-height="360">
        <el-table-column prop="path_no" label="病理号" width="130">
          <template #default="{ row }">{{ fmt(row.path_no) }}</template>
        </el-table-column>
        <el-table-column label="类别" width="90">
          <template #default="{ row }">{{ typeName(row.specimen_type) }}</template>
        </el-table-column>
        <!-- v60（2530 尾）：既往行同带 specimen_desc（SPECIMEN_SELECT 片段），医师对比既往时该看得见当年送的是什么 -->
        <el-table-column label="标本描述" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ fmt(row.specimen_desc) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag size="small">{{ statusName(row.status) }}</el-tag>
            <el-tag v-if="row.rejected_at" size="small" type="danger" style="margin-left: 4px">已拒收</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="登记时刻" width="140">
          <template #default="{ row }">{{ fmtTime(row.collected_at) }}</template>
        </el-table-column>
        <el-table-column label="诊断" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ fmt(row.diagnosis) }}</template>
        </el-table-column>
        <el-table-column label="签发时刻" width="140">
          <template #default="{ row }">{{ fmtTime(row.report_issued_at) }}</template>
        </el-table-column>
        <el-table-column v-if="includeRejected" label="拒收原因" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.rejected_at">{{ fmtTime(row.rejected_at) }}　{{ fmt(row.reject_reason) }}</template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <template #empty>该患者无其它病理记录{{ includeRejected ? '' : '（不含拒收）' }}</template>
      </el-table>

      <template v-if="includeSameName">
        <h4>同名他人（不同患者 ID）</h4>
        <el-table :data="sameName" size="small" border max-height="220">
          <el-table-column prop="patient_name" label="姓名" width="100" />
          <el-table-column label="性别" width="70">
            <template #default="{ row }">{{ fmt(row.sex) }}</template>
          </el-table-column>
          <el-table-column label="出生日期" width="120">
            <template #default="{ row }">{{ fmt(row.birth_date) }}</template>
          </el-table-column>
          <el-table-column label="病理标本份数（不含拒收）" width="170">
            <template #default="{ row }">{{ num(row.specimen_count) }}</template>
          </el-table-column>
          <el-table-column v-if="includeRejected" label="其中已拒收" width="100">
            <template #default="{ row }">{{ num(row.rejected_count) }}</template>
          </el-table-column>
          <el-table-column label="最近一次登记（不含拒收）" width="190">
            <template #default="{ row }">{{ fmtTime(row.latest_collected_at) }}</template>
          </el-table-column>
          <template #empty>无同名他人</template>
        </el-table>
      </template>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * 工位一：登记。
 *
 * <p>对接 {@code PathologyRegistryController}（/api/pathology/registry）。
 * 返回体键名照后端原样消费：列表行是蛇形（JdbcTemplate 直出），
 * 登记返回体是驼峰（控制器手工装配的 LinkedHashMap）——两者不在前端归一。
 */
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../../api/client'
import {
  SOURCES, SPECIMEN_STATUSES, SPECIMEN_TYPES,
  fmt, fmtTime, num, sourceName, statusName, typeName, type Row,
} from './format'

const emit = defineEmits<{ (e: 'changed'): void }>()

const tab = ref('pending')
const saving = ref(false)

/* ---------------- 待登记 ---------------- */
const pending = ref<Row[]>([])
const pendingTruncated = ref(false)
const pendingLimit = ref(200)
const pendingLoading = ref(false)
const pendingQuery = reactive({ source: '', keyword: '', includeRegistered: false })

async function loadPending() {
  pendingLoading.value = true
  try {
    const d = (await client.get('/pathology/registry/pending', {
      params: {
        source: pendingQuery.source || undefined,
        keyword: pendingQuery.keyword || undefined,
        includeRegistered: pendingQuery.includeRegistered || undefined,
      },
    })).data.data as Row
    pending.value = (d.items ?? []) as Row[]
    pendingTruncated.value = d.truncated === true
    pendingLimit.value = num(d.limit) || 200
  } finally {
    pendingLoading.value = false
  }
}

/* ---------------- 标本检索 ---------------- */
const specimens = ref<Row[]>([])
const searchTruncated = ref(false)
const searchLimit = ref(200)
const searchLoading = ref(false)
const searchRange = ref<[string, string] | null>(null)
const rejectedFlag = ref('')
const searchQuery = reactive({
  pathNo: '', barcode: '', patientName: '', status: '', specimenType: '', source: '',
})

async function loadSpecimens() {
  searchLoading.value = true
  try {
    const d = (await client.get('/pathology/registry/specimens/search', {
      params: {
        pathNo: searchQuery.pathNo || undefined,
        barcode: searchQuery.barcode || undefined,
        patientName: searchQuery.patientName || undefined,
        from: searchRange.value?.[0] || undefined,
        to: searchRange.value?.[1] || undefined,
        status: searchQuery.status || undefined,
        specimenType: searchQuery.specimenType || undefined,
        source: searchQuery.source || undefined,
        rejected: rejectedFlag.value === 'Y' ? true : rejectedFlag.value === 'N' ? false : undefined,
      },
    })).data.data as Row
    specimens.value = (d.items ?? []) as Row[]
    searchTruncated.value = d.truncated === true
    searchLimit.value = num(d.limit) || 200
  } finally {
    searchLoading.value = false
  }
}

/* ---------------- 登记 ---------------- */
const regDialog = ref(false)
const resultDialog = ref(false)
const regResult = ref<Row>({})
const regForm = reactive({
  manual: false,
  source: '',
  orderId: undefined as number | undefined,
  inpOrderId: undefined as number | undefined,
  patientName: '',
  itemName: '',
  partNo: 1,
  specimenType: 'ROUTINE',
  specimenDesc: '',
  samplingSite: '',
  clinicalDiagnosis: '',
  fixative: '',
  fixedAt: '',
  urgent: false,
  applyDept: '',
  applyDoctor: '',
  note: '',
})

function openRegister(row: Row, manual: boolean) {
  const src = String(row.source ?? '')
  Object.assign(regForm, {
    manual,
    source: src,
    orderId: row.order_id == null ? undefined : Number(row.order_id),
    inpOrderId: row.inp_order_id == null ? undefined : Number(row.inp_order_id),
    patientName: String(row.patient_name ?? ''),
    itemName: String(row.item_name ?? ''),
    // 已登记 n 个部位时默认续下一个号；后端仍会按「已拒收不算」的口径复核
    partNo: num(row.registered_parts) + 1,
    specimenType: 'ROUTINE',
    specimenDesc: '',
    samplingSite: '',
    clinicalDiagnosis: '',
    fixative: '',
    fixedAt: '',
    urgent: row.urgent === true,
    applyDept: '',
    applyDoctor: '',
    note: '',
  })
  regDialog.value = true
}

async function submitRegister() {
  if (!regForm.orderId && !regForm.inpOrderId) {
    ElMessage.warning('该申请没有可用的院内医嘱 id，无法登记')
    return
  }
  saving.value = true
  try {
    const body: Record<string, unknown> = {
      orderId: regForm.orderId,
      inpOrderId: regForm.inpOrderId,
      partNo: regForm.partNo,
      specimenType: regForm.specimenType,
      specimenDesc: regForm.specimenDesc || undefined,
      samplingSite: regForm.samplingSite || undefined,
      clinicalDiagnosis: regForm.clinicalDiagnosis || undefined,
      fixative: regForm.fixative || undefined,
      fixedAt: regForm.fixedAt || undefined,
      urgent: regForm.urgent,
    }
    if (regForm.manual) {
      body.applyDept = regForm.applyDept || undefined
      body.applyDoctor = regForm.applyDoctor || undefined
      body.note = regForm.note || undefined
    }
    const url = regForm.manual
      ? '/pathology/registry/specimens/manual'
      : '/pathology/registry/specimens'
    regResult.value = (await client.post(url, body)).data.data as Row
    regDialog.value = false
    resultDialog.value = true
    await loadPending()
    emit('changed')
  } finally {
    saving.value = false
  }
}

/* ---------------- 接收核对 / 拒收 ---------------- */
const current = ref<Row>({})
const receiveDialog = ref(false)
const rejectDialog = ref(false)
const receiveForm = reactive({ receivedAt: '', remark: '' })
const rejectForm = reactive({ reason: '', rejectedAt: '' })

function openReceive(row: Row) {
  current.value = row
  receiveForm.receivedAt = ''
  receiveForm.remark = ''
  receiveDialog.value = true
}

async function submitReceive() {
  saving.value = true
  try {
    await client.put(`/pathology/registry/specimens/${Number(current.value.id)}/receive-check`, {
      receivedAt: receiveForm.receivedAt || undefined,
      remark: receiveForm.remark || undefined,
    })
    ElMessage.success('已核收')
    receiveDialog.value = false
    await loadSpecimens()
    emit('changed')
  } finally {
    saving.value = false
  }
}

function openReject(row: Row) {
  current.value = row
  rejectForm.reason = ''
  rejectForm.rejectedAt = ''
  rejectDialog.value = true
}

async function submitReject() {
  if (!rejectForm.reason.trim()) {
    ElMessage.warning('拒收原因必填')
    return
  }
  saving.value = true
  try {
    await client.put(`/pathology/registry/specimens/${Number(current.value.id)}/reject`, {
      reason: rejectForm.reason,
      rejectedAt: rejectForm.rejectedAt || undefined,
    })
    ElMessage.success('已拒收（记录保留，仍计入送检分母）')
    rejectDialog.value = false
    await loadSpecimens()
    emit('changed')
  } finally {
    saving.value = false
  }
}

/* ---------------- 既往病理 ---------------- */
const historyDrawer = ref(false)
const historyLoading = ref(false)
const history = ref<Row[]>([])
const sameName = ref<Row[]>([])
const includeSameName = ref(false)
/** v59（2558）：既往默认排除拒收——与诊断页 /prior 同口径；开关打开才带拒收行并标「已拒收」+ 原因 */
const includeRejected = ref(false)
const historyTruncated = ref(false)
const historyLimit = ref(50)
const historyError = ref('')
const historyNote = ref('')

function openHistory(row: Row) {
  current.value = row
  includeSameName.value = false
  includeRejected.value = false
  historyError.value = ''
  historyNote.value = ''
  historyDrawer.value = true
  void loadHistory()
}

async function loadHistory() {
  historyLoading.value = true
  historyError.value = ''
  try {
    const d = (await client.get(
      `/pathology/registry/specimens/${Number(current.value.id)}/history`,
      {
        params: {
          includeSameName: includeSameName.value || undefined,
          includeRejected: includeRejected.value || undefined,
        },
      },
    )).data.data as Row
    history.value = (d.items ?? []) as Row[]
    sameName.value = (d.sameName ?? []) as Row[]
    historyTruncated.value = d.truncated === true
    historyLimit.value = num(d.limit) || 50
    historyNote.value = String(d.note ?? '')
  } catch (e) {
    // 5211：来源解不出患者身份。**不能显示成「该患者无既往病理」**——
    // 「没有既往病理」与「不知道这是谁」是两回事，后者伪装成前者就是把身份核对失败当阴性结果。
    history.value = []
    sameName.value = []
    historyError.value = (e as Error).message
      || '无法从来源解出患者身份，既往病理不可用（这不等于该患者没有既往病理）'
  } finally {
    historyLoading.value = false
  }
}

defineExpose({ reload: loadPending })

void loadPending()
</script>

<style scoped>
.cav { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
.code { font-family: Consolas, Monaco, monospace; }
h4 { margin: 14px 0 6px; }
</style>
