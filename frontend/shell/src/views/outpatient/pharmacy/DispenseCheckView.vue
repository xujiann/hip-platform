<template>
  <el-card>
    <div class="toolbar">
      <h3>发药核对 · 双人核对 / 高危药品 / 看似听似</h3>
      <span>
        <el-tag v-if="gate" size="small" :type="gateType(gate)" style="margin-right: 10px">
          发药核对 gate = {{ gate }}
        </el-tag>
        <el-button link type="primary" @click="reload">刷新</el-button>
      </span>
    </div>

    <!-- 本版的已知事实必须写在页面上：发药有两个入口，只有本页这条过 gate 记台账 -->
    <el-alert type="warning" show-icon :closable="false" class="caveat"
              title="发药目前有两个入口，只有本页这条过核对 gate 并记台账"
              description="既有「药房发药」页走的是不过 gate、不记台账的旧入口，零核对也能发。未核对发药台账只能记到经本页发出的药——统计口径请以此为准。" />

    <el-tabs v-model="tab" v-loading="loading" @tab-change="onTabChange">
      <!-- ===================== 待核对工作台 ===================== -->
      <el-tab-pane name="worklist" :label="`待核对（${worklist.length}）`">
        <el-alert v-if="worklistTruncated" type="warning" show-icon :closable="false" class="caveat"
                  title="命中超出上限，仅显示前若干条（后端不做翻页）" />
        <el-table :data="worklist" size="small" border stripe max-height="480">
          <el-table-column prop="patient_no" label="患者号" width="120" />
          <el-table-column prop="patient_name" label="姓名" width="100" />
          <el-table-column label="行数" width="80" align="right">
            <template #default="{ row }">{{ num(row.line_count) }}</template>
          </el-table-column>
          <el-table-column label="高危药" width="110" align="right">
            <template #default="{ row }">
              <el-tag v-if="num(row.high_alert_count) > 0" size="small" type="danger">
                {{ num(row.high_alert_count) }} 行
              </el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="高危待确认" width="120" align="right">
            <template #default="{ row }">
              <el-tag v-if="num(row.high_alert_pending) > 0" size="small" type="danger" effect="dark">
                {{ num(row.high_alert_pending) }}
              </el-tag>
              <span v-else class="muted">0</span>
            </template>
          </el-table-column>
          <el-table-column prop="picker_name" label="摆药人" width="110" />
          <el-table-column label="摆药时间" width="160">
            <template #default="{ row }">{{ fmt(row.picked_at) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openCheck(Number(row.id))">核对</el-button>
              <el-button link type="primary" size="small"
                         @click="openOverview(Number(row.registration_id))">该挂号总览</el-button>
            </template>
          </el-table-column>
          <template #empty>无待核对的核对单</template>
        </el-table>
      </el-tab-pane>

      <!-- ===================== 按挂号建核对单 ===================== -->
      <el-tab-pane name="overview" label="按挂号建单 / 发药">
        <el-form inline size="small">
          <el-form-item label="挂号 id">
            <el-input-number v-model="regId" :min="1" :controls="false" style="width: 150px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" size="small" :loading="loading"
                       @click="openOverview(regId)">查询</el-button>
          </el-form-item>
        </el-form>

        <template v-if="overview">
          <!-- gate 评估：checkPassed / missing / warnings 三样都要显示 -->
          <el-alert :type="overview.checkPassed === true ? 'success' : 'error'" show-icon :closable="false"
                    class="caveat"
                    :title="overview.checkPassed === true
                      ? '本挂号待发处方均已被已通过的核对单覆盖'
                      : `${missing.length} 条待发处方没有已通过的核对记录`" />
          <el-table v-if="missing.length" :data="missing" size="small" border style="margin-bottom: 8px">
            <el-table-column prop="orderId" label="医嘱 id" width="100" />
            <el-table-column prop="itemName" label="药品" min-width="180" />
            <el-table-column prop="qty" label="数量" width="90" align="right" />
          </el-table>
          <el-alert v-for="(w, i) in overviewWarnings" :key="i" type="warning" show-icon :closable="false"
                    class="caveat" :title="w" />

          <!-- unmaintainedCount：高危属性未维护，提示不完整——不代表它们不是高危药 -->
          <el-alert v-if="num(overview.unmaintainedCount) > 0" type="error" show-icon :closable="false"
                    class="caveat"
                    :title="`本次 ${num(overview.unmaintainedCount)} 种药品的高危属性尚未维护，高危提示不完整`"
                    :description="String(overview.note ?? '')" />

          <div class="toolbar">
            <span>待核对处方行</span>
            <span>
              <el-button type="primary" size="small" :loading="saving" :disabled="!previewLines.length"
                         @click="createCheck">摆药完成，提交核对</el-button>
              <el-button type="warning" size="small" :loading="saving" @click="dispenseWithCheck">
                核对后发药
              </el-button>
            </span>
          </div>
          <el-table :data="previewLines" size="small" border stripe max-height="360">
            <el-table-column prop="drugName" label="药品" min-width="170" show-overflow-tooltip />
            <el-table-column prop="spec" label="规格" width="120" show-overflow-tooltip />
            <el-table-column label="数量" width="90" align="right">
              <template #default="{ row }">{{ num(row.qty) }} {{ row.unit }}</template>
            </el-table-column>
            <el-table-column prop="usageRoute" label="用法" width="100" />
            <el-table-column prop="frequency" label="频次" width="90" />
            <!-- highAlert 三态：true 高危 / false 非高危 / null 从未维护。null 绝不折叠成「非高危」 -->
            <el-table-column label="高危" width="110">
              <template #default="{ row }">
                <el-tag v-if="row.highAlert === true" size="small" type="danger" effect="dark">高危</el-tag>
                <el-tag v-else-if="row.highAlert === false" size="small" type="info">非高危</el-tag>
                <el-tag v-else size="small" type="warning">未维护</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="看似听似" min-width="220">
              <template #default="{ row }">
                <span v-if="row.lasaNote" class="warn-text">{{ row.lasaNote }}</span>
                <span v-else class="muted">对照表中无登记</span>
              </template>
            </el-table-column>
            <template #empty>该挂号无待核对的处方行</template>
          </el-table>

          <el-divider content-position="left">该挂号既有的核对单</el-divider>
          <el-table :data="checks" size="small" border stripe max-height="240">
            <el-table-column prop="id" label="核对单" width="90" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="checkStatusType(String(row.status))">
                  {{ checkStatusName(String(row.status)) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="picker_name" label="摆药人" width="110" />
            <el-table-column prop="checker_name" label="核对人" width="110" />
            <el-table-column label="核对时间" width="160">
              <template #default="{ row }">{{ fmt(row.checked_at) }}</template>
            </el-table-column>
            <el-table-column prop="return_reason" label="退回原因" min-width="150" show-overflow-tooltip />
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openCheck(Number(row.id))">打开</el-button>
              </template>
            </el-table-column>
            <template #empty>该挂号尚无核对单</template>
          </el-table>
        </template>
      </el-tab-pane>

      <!-- ===================== 核对记录 ===================== -->
      <el-tab-pane name="records" :label="`核对记录（${records.length}）`">
        <el-form inline size="small">
          <el-form-item label="日期">
            <el-date-picker v-model="recordDate" type="date" value-format="YYYY-MM-DD"
                            style="width: 150px" @change="loadRecords" />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="recordStatus" clearable placeholder="全部" style="width: 140px"
                       @change="loadRecords">
              <el-option v-for="s in CHECK_STATUSES" :key="s.value" :value="s.value" :label="s.label" />
            </el-select>
          </el-form-item>
        </el-form>
        <el-table :data="records" size="small" border stripe max-height="440">
          <el-table-column prop="id" label="核对单" width="90" />
          <el-table-column prop="registration_id" label="挂号" width="90" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="checkStatusType(String(row.status))">
                {{ checkStatusName(String(row.status)) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="picker_name" label="摆药人" width="110" />
          <el-table-column prop="checker_name" label="核对人" width="110" />
          <el-table-column label="摆药时间" width="160">
            <template #default="{ row }">{{ fmt(row.picked_at) }}</template>
          </el-table-column>
          <el-table-column label="核对时间" width="160">
            <template #default="{ row }">{{ fmt(row.checked_at) }}</template>
          </el-table-column>
          <el-table-column label="行数" width="80" align="right">
            <template #default="{ row }">{{ num(row.line_count) }}</template>
          </el-table-column>
          <el-table-column prop="return_reason" label="退回原因" min-width="150" show-overflow-tooltip />
          <template #empty>该日无核对记录</template>
        </el-table>
      </el-tab-pane>

      <!-- ===================== 未核对发药台账 ===================== -->
      <el-tab-pane name="gatelog" :label="`发药台账（${gateLog.length}）`">
        <el-form inline size="small">
          <el-form-item label="日期">
            <el-date-picker v-model="gateLogDate" type="date" value-format="YYYY-MM-DD"
                            style="width: 150px" @change="loadGateLog" />
          </el-form-item>
          <el-form-item>
            <el-checkbox v-model="onlyBypassed" @change="loadGateLog">只看未通过核对即发药的</el-checkbox>
          </el-form-item>
        </el-form>
        <el-alert v-if="gateLogNote" type="warning" show-icon :closable="false" class="caveat"
                  :title="gateLogNote" />
        <el-table :data="gateLog" size="small" border stripe max-height="440">
          <el-table-column prop="registration_id" label="挂号" width="90" />
          <el-table-column label="gate 档位" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="gateType(String(row.gate))">{{ row.gate }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="是否已核对" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="row.check_passed === true ? 'success' : 'danger'">
                {{ row.check_passed === true ? '已核对' : '未核对放行' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="missing_orders" label="未核对医嘱 id" min-width="180" show-overflow-tooltip />
          <el-table-column prop="operator_name" label="操作人" width="110" />
          <el-table-column label="发生时间" width="160">
            <template #default="{ row }">{{ fmt(row.occurred_at) }}</template>
          </el-table-column>
          <template #empty>该日无发药台账</template>
        </el-table>
      </el-tab-pane>

      <!-- ===================== 药剂科维护 ===================== -->
      <el-tab-pane name="maintain" label="药剂科维护">
        <!-- 这两张表是提示能力的唯一来源，且只能靠人维护。前端不做任何字符串相似度自动标红 -->
        <el-alert type="warning" show-icon :closable="false" class="caveat"
                  title="高危目录与看似听似对照只能靠人工维护，本系统不按药名猜、不按字符串相似度自动标红"
                  description="按药名匹配会漏掉全部商品名制剂；相似度既误报又漏报。未维护的药在核对页显示为「未维护」，不显示为「非高危」。" />

        <el-descriptions :column="3" border size="small" class="caveat">
          <el-descriptions-item label="已标记高危">{{ highAlertDrugs.length }}</el-descriptions-item>
          <el-descriptions-item label="尚未维护（全院）">
            <span class="warn-text">{{ num(totalUnmaintained) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="看似听似对照">{{ lasaPairs.length }}</el-descriptions-item>
        </el-descriptions>

        <el-tabs v-model="mtab" @tab-change="loadMaintain">
          <el-tab-pane name="high" :label="`高危目录（${highAlertDrugs.length}）`">
            <el-table :data="highAlertDrugs" size="small" border stripe max-height="340">
              <el-table-column prop="code" label="编码" width="120" />
              <el-table-column prop="name" label="药品" min-width="180" />
              <el-table-column prop="spec" label="规格" width="140" show-overflow-tooltip />
              <el-table-column prop="high_alert_by_name" label="维护人" width="110" />
              <el-table-column label="维护时间" width="160">
                <template #default="{ row }">{{ fmt(row.high_alert_at) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="120" fixed="right">
                <template #default="{ row }">
                  <el-button link type="warning" size="small"
                             @click="setHighAlert(Number(row.id), false, String(row.name))">改为非高危</el-button>
                </template>
              </el-table-column>
              <template #empty>尚未标记任何高危药品</template>
            </el-table>
          </el-tab-pane>

          <el-tab-pane name="unmaintained" :label="`待维护（${unmaintained.length}）`">
            <el-table :data="unmaintained" size="small" border stripe max-height="340">
              <el-table-column prop="code" label="编码" width="120" />
              <el-table-column prop="name" label="药品" min-width="180" />
              <el-table-column prop="spec" label="规格" width="140" show-overflow-tooltip />
              <el-table-column prop="unit" label="单位" width="80" />
              <el-table-column label="逐条判定" width="220" fixed="right">
                <template #default="{ row }">
                  <el-button link type="danger" size="small"
                             @click="setHighAlert(Number(row.id), true, String(row.name))">标为高危</el-button>
                  <el-button link type="info" size="small"
                             @click="setHighAlert(Number(row.id), false, String(row.name))">标为非高危</el-button>
                </template>
              </el-table-column>
              <template #empty>全部已维护</template>
            </el-table>
          </el-tab-pane>

          <el-tab-pane name="lasa" :label="`看似听似（${lasaPairs.length}）`">
            <el-button type="primary" size="small" style="margin-bottom: 8px" @click="openLasa">
              登记一对
            </el-button>
            <el-table :data="lasaPairs" size="small" border stripe max-height="340">
              <el-table-column prop="drug_name_lo" label="药品 A" min-width="160" show-overflow-tooltip />
              <el-table-column prop="drug_name_hi" label="药品 B" min-width="160" show-overflow-tooltip />
              <el-table-column prop="note" label="备注" min-width="180" show-overflow-tooltip />
              <el-table-column prop="created_by_name" label="登记人" width="110" />
              <el-table-column label="登记时间" width="160">
                <template #default="{ row }">{{ fmt(row.created_at) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="90" fixed="right">
                <template #default="{ row }">
                  <el-button link type="danger" size="small" @click="removeLasa(Number(row.id))">撤销</el-button>
                </template>
              </el-table-column>
              <template #empty>尚未登记看似听似对照</template>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </el-tab-pane>
    </el-tabs>
  </el-card>

  <!-- ===================== 核对单抽屉 ===================== -->
  <el-drawer v-model="checkDrawer" size="72%" :title="`核对单 ${check?.id ?? ''}`">
    <template v-if="check">
      <el-alert v-for="(w, i) in checkWarnings" :key="i" type="warning" show-icon :closable="false"
                class="caveat" :title="w" />
      <el-descriptions :column="4" border size="small" class="caveat">
        <el-descriptions-item label="状态">
          <el-tag size="small" :type="checkStatusType(String(check.status))">
            {{ checkStatusName(String(check.status)) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="摆药人">{{ check.picker_name ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="摆药时间">{{ fmt(check.picked_at) }}</el-descriptions-item>
        <el-descriptions-item label="核对人">{{ check.checker_name ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="核对时间">{{ fmt(check.checked_at) }}</el-descriptions-item>
        <el-descriptions-item label="高危待确认">
          <el-tag v-if="num(check.highAlertPending) > 0" size="small" type="danger" effect="dark">
            {{ num(check.highAlertPending) }} 行
          </el-tag>
          <span v-else class="muted">0</span>
        </el-descriptions-item>
        <el-descriptions-item label="高危属性未维护">
          <el-tag v-if="num(check.unmaintainedCount) > 0" size="small" type="warning">
            {{ num(check.unmaintainedCount) }} 行
          </el-tag>
          <span v-else class="muted">0</span>
        </el-descriptions-item>
        <el-descriptions-item v-if="check.return_reason" label="退回原因">
          <span class="warn-text">{{ check.return_reason }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-alert type="info" show-icon :closable="false" class="caveat"
                title="核对人必须与摆药人不同——一个人核两次不构成双人核对"
                description="单人药房请不建核对单直接发药，由 gate 记录并提示，不要自摆自核。" />

      <div class="toolbar">
        <span></span>
        <span v-if="check.status === 'PREPARED'">
          <el-button type="success" size="small" :loading="saving" @click="passCheck">核对通过</el-button>
          <el-button type="danger" size="small" @click="openReturn">核对不通过，退回摆药</el-button>
        </span>
      </div>

      <el-table :data="check.lines as Row[]" size="small" border stripe max-height="calc(100vh - 420px)">
        <el-table-column prop="drug_name" label="药品" min-width="170" show-overflow-tooltip />
        <el-table-column prop="spec" label="规格" width="120" show-overflow-tooltip />
        <el-table-column label="数量" width="90" align="right">
          <template #default="{ row }">{{ num(row.qty) }} {{ row.unit }}</template>
        </el-table-column>
        <el-table-column prop="usage_route" label="用法" width="100" />
        <el-table-column prop="frequency" label="频次" width="90" />
        <!-- 建单时刻的快照：high_alert 为 null 表示当时就没维护，不折叠成 false -->
        <el-table-column label="高危（建单快照）" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.high_alert === true" size="small" type="danger" effect="dark">高危</el-tag>
            <el-tag v-else-if="row.high_alert === false" size="small" type="info">非高危</el-tag>
            <el-tag v-else size="small" type="warning">未维护</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="高危确认" width="200">
          <template #default="{ row }">
            <template v-if="row.high_alert === true">
              <span v-if="row.high_alert_confirmed_at">
                {{ row.high_alert_confirmed_by_name ?? '' }}
                <span class="muted">{{ fmt(row.high_alert_confirmed_at) }}</span>
              </span>
              <el-button v-else-if="check.status === 'PREPARED'" link type="danger" size="small"
                         @click="confirmHighAlert(Number(row.id), String(row.drug_name))">
                单独确认这一行
              </el-button>
              <span v-else class="warn-text">未确认</span>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="看似听似（建单快照）" min-width="220">
          <template #default="{ row }">
            <span v-if="row.lasa_note" class="warn-text">{{ row.lasa_note }}</span>
            <span v-else class="muted">对照表中无登记</span>
          </template>
        </el-table-column>
        <template #empty>本单无明细</template>
      </el-table>
    </template>
  </el-drawer>

  <!-- ===================== 退回 / LASA 登记 ===================== -->
  <el-dialog v-model="returnDialog" title="核对不通过，退回摆药（原因必填）" width="460px">
    <el-alert type="info" :closable="false" show-icon class="caveat"
              title="不删记录：退回率与退回原因是药房质控的分母" />
    <el-input v-model="returnReason" type="textarea" :rows="3" placeholder="如：阿托品与阿托伐他汀拿混，重新配" />
    <template #footer>
      <el-button size="small" @click="returnDialog = false">取消</el-button>
      <el-button type="danger" size="small" :loading="saving" @click="submitReturn">确认退回</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="lasaDialog" title="登记一对看似听似药品" width="520px">
    <el-alert type="info" :closable="false" show-icon class="caveat"
              title="逐对人工登记，无序对只存一行（A-B 与 B-A 是同一条）"
              description="系统不按字符串相似度自动配对：相似度既会把不相干的药凑成一对，也会漏掉真正会拿混的两种。" />
    <el-form label-width="90px" size="small">
      <el-form-item label="药品 A" required>
        <el-select v-model="lasaForm.drugIdA" filterable remote placeholder="输入药名检索"
                   :remote-method="searchDrugs" :loading="drugLoading" style="width: 100%">
          <el-option v-for="d in drugs" :key="d.id" :value="d.id" :label="`${d.code}　${d.name}　${d.spec ?? ''}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="药品 B" required>
        <el-select v-model="lasaForm.drugIdB" filterable remote placeholder="输入药名检索"
                   :remote-method="searchDrugs" :loading="drugLoading" style="width: 100%">
          <el-option v-for="d in drugs" :key="d.id" :value="d.id" :label="`${d.code}　${d.name}　${d.spec ?? ''}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="lasaForm.note" placeholder="如：包装同色同规格，取药时须双人复述药名" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="lasaDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submitLasa">登记</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../../api/client'
import { fmtDateTime } from '../../../utils/date'

type Row = Record<string, unknown>
interface Drug { id: number; code: string; name: string; spec?: string }

const BASE = '/outpatient/dispense-check'

const CHECK_STATUSES = [
  { value: 'PREPARED', label: '待核对' },
  { value: 'CHECKED', label: '核对通过' },
  { value: 'RETURNED', label: '退回摆药' },
]

const tab = ref('worklist')
const mtab = ref('high')
const loading = ref(false)
const saving = ref(false)
const gate = ref('')

const worklist = ref<Row[]>([])
const worklistTruncated = ref(false)

const regId = ref<number>(1)
const overview = ref<Row | null>(null)
const previewLines = computed<Row[]>(() => (overview.value?.lines ?? []) as Row[])
const missing = computed<Row[]>(() => (overview.value?.missing ?? []) as Row[])
const overviewWarnings = computed<string[]>(() => (overview.value?.warnings ?? []) as string[])
const checks = computed<Row[]>(() => (overview.value?.checks ?? []) as Row[])

const records = ref<Row[]>([])
const recordDate = ref('')
const recordStatus = ref('')

const gateLog = ref<Row[]>([])
const gateLogDate = ref('')
const gateLogNote = ref('')
const onlyBypassed = ref(false)

const highAlertDrugs = ref<Row[]>([])
const unmaintained = ref<Row[]>([])
const totalUnmaintained = ref(0)
const lasaPairs = ref<Row[]>([])

const drugs = ref<Drug[]>([])
const drugLoading = ref(false)

function num(v: unknown): number {
  return Number(v ?? 0)
}

function fmt(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—'
  const s = String(v)
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(s) ? fmtDateTime(s) : s
}

function gateType(g: unknown): 'success' | 'warning' | 'danger' | 'info' {
  return g === 'block' ? 'danger' : g === 'warn' ? 'warning' : 'info'
}

function checkStatusName(s: string): string {
  return CHECK_STATUSES.find((x) => x.value === s)?.label ?? s
}

function checkStatusType(s: string): 'success' | 'warning' | 'danger' | 'info' {
  return s === 'CHECKED' ? 'success' : s === 'RETURNED' ? 'danger' : 'warning'
}

async function searchDrugs(keyword: string) {
  drugLoading.value = true
  try {
    drugs.value = (await client.get('/masterdata/drugs', { params: { keyword } })).data.data as Drug[]
  } finally {
    drugLoading.value = false
  }
}

async function loadWorklist() {
  const d = (await client.get(`${BASE}/worklist`, { params: { limit: 100 } })).data.data as Row
  worklist.value = (d.items ?? []) as Row[]
  worklistTruncated.value = d.truncated === true
}

async function openOverview(id: number) {
  regId.value = id
  tab.value = 'overview'
  loading.value = true
  try {
    overview.value = (await client.get(`${BASE}/registrations/${id}`)).data.data as Row
    gate.value = String(overview.value.gate ?? gate.value)
  } finally {
    loading.value = false
  }
}

async function loadRecords() {
  const d = (await client.get(`${BASE}/records`, {
    params: { date: recordDate.value || undefined, status: recordStatus.value || undefined, limit: 100 },
  })).data.data as Row
  records.value = (d.items ?? []) as Row[]
  if (!recordDate.value) recordDate.value = String(d.date ?? '')
}

async function loadGateLog() {
  const d = (await client.get(`${BASE}/gate-log`, {
    params: { date: gateLogDate.value || undefined, onlyBypassed: onlyBypassed.value, limit: 100 },
  })).data.data as Row
  gateLog.value = (d.items ?? []) as Row[]
  gateLogNote.value = String(d.note ?? '')
  if (!gateLogDate.value) gateLogDate.value = String(d.date ?? '')
}

async function loadMaintain() {
  const [ha, un, lp] = await Promise.all([
    client.get(`${BASE}/drugs/high-alert`, { params: { limit: 200 } }),
    client.get(`${BASE}/drugs/unmaintained`, { params: { limit: 200 } }),
    client.get(`${BASE}/lasa`, { params: { limit: 200 } }),
  ])
  highAlertDrugs.value = (ha.data.data.items ?? []) as Row[]
  unmaintained.value = (un.data.data.items ?? []) as Row[]
  totalUnmaintained.value = num(un.data.data.totalUnmaintained)
  lasaPairs.value = (lp.data.data.items ?? []) as Row[]
}

async function onTabChange() {
  await reload()
}

async function reload() {
  loading.value = true
  try {
    if (tab.value === 'worklist') await loadWorklist()
    else if (tab.value === 'overview') { if (overview.value) await openOverview(regId.value) }
    else if (tab.value === 'records') await loadRecords()
    else if (tab.value === 'gatelog') await loadGateLog()
    else await loadMaintain()
  } finally {
    loading.value = false
  }
}

/* ---------------- 核对单 ---------------- */
const checkDrawer = ref(false)
const check = ref<Row | null>(null)
const checkWarnings = computed<string[]>(() => (check.value?.warnings ?? []) as string[])

async function openCheck(checkId: number) {
  check.value = (await client.get(`${BASE}/${checkId}`)).data.data as Row
  gate.value = String(check.value.gate ?? gate.value)
  checkDrawer.value = true
}

async function createCheck() {
  saving.value = true
  try {
    const d = (await client.post(`${BASE}/registrations/${regId.value}`)).data.data as Row
    check.value = d
    checkDrawer.value = true
    ElMessage.success(`已建核对单 ${d.id}`)
    await openOverview(regId.value)
  } finally {
    saving.value = false
  }
}

async function confirmHighAlert(lineId: number, drugName: string) {
  if (!check.value) return
  const ok = await ElMessageBox.confirm(
    `确认已逐支核对高危药品【${drugName}】的品名、规格、数量与用法？`,
    '高危药品单独确认', { type: 'warning' },
  ).catch(() => null)
  if (!ok) return
  check.value = (await client.post(
    `${BASE}/${Number(check.value.id)}/lines/${lineId}/high-alert-confirm`)).data.data as Row
  ElMessage.success('已确认')
}

async function passCheck() {
  if (!check.value) return
  saving.value = true
  try {
    check.value = (await client.put(`${BASE}/${Number(check.value.id)}/check`)).data.data as Row
    ElMessage.success('核对通过')
    await reload()
  } finally {
    saving.value = false
  }
}

const returnDialog = ref(false)
const returnReason = ref('')

function openReturn() {
  returnReason.value = ''
  returnDialog.value = true
}

async function submitReturn() {
  if (!check.value) return
  if (!returnReason.value.trim()) {
    ElMessage.warning('退回原因必填')
    return
  }
  saving.value = true
  try {
    check.value = (await client.post(`${BASE}/${Number(check.value.id)}/return`,
      { reason: returnReason.value })).data.data as Row
    returnDialog.value = false
    ElMessage.success('已退回摆药')
    await reload()
  } finally {
    saving.value = false
  }
}

/* ---------------- 核对后发药 ---------------- */
async function dispenseWithCheck() {
  const ok = await ElMessageBox.confirm(
    `确认对挂号 ${regId.value} 发药？未通过核对的处方将按当前 gate（${gate.value}）处理并记入台账。`,
    '核对后发药', { type: 'warning' },
  ).catch(() => null)
  if (!ok) return
  saving.value = true
  try {
    const d = (await client.post(`${BASE}/registrations/${regId.value}/dispense`)).data.data as Row
    const warns = (d.warnings ?? []) as string[]
    if (warns.length) {
      // warn 档放行了什么必须让人看见，并已记入未核对发药台账
      await ElMessageBox.alert(warns.map((w) => `· ${w}`).join('\n'),
        '已发药，但本次被 warn 档放行（已记入发药台账）',
        { type: 'warning', confirmButtonText: '我已知悉' })
    } else {
      ElMessage.success(`已发药 ${((d.orders ?? []) as unknown[]).length} 条医嘱`)
    }
    await openOverview(regId.value)
  } finally {
    saving.value = false
  }
}

/* ---------------- 药剂科维护 ---------------- */
async function setHighAlert(drugId: number, value: boolean, name: string) {
  const ok = await ElMessageBox.confirm(
    `将【${name}】标记为${value ? '高危药品' : '非高危药品'}？`
    + '\n该判定不可清空回「未维护」——清空等于伪造一条没人看过的维护历史。',
    '维护高危属性', { type: value ? 'warning' : 'info' },
  ).catch(() => null)
  if (!ok) return
  await client.put(`${BASE}/drugs/${drugId}/high-alert`, { highAlert: value })
  ElMessage.success('已维护')
  await loadMaintain()
}

const lasaDialog = ref(false)
const lasaForm = reactive({
  drugIdA: undefined as number | undefined,
  drugIdB: undefined as number | undefined,
  note: '',
})

function openLasa() {
  Object.assign(lasaForm, { drugIdA: undefined, drugIdB: undefined, note: '' })
  lasaDialog.value = true
}

async function submitLasa() {
  if (!lasaForm.drugIdA || !lasaForm.drugIdB) {
    ElMessage.warning('两个药品都必须选择')
    return
  }
  saving.value = true
  try {
    await client.post(`${BASE}/lasa`, {
      drugIdA: lasaForm.drugIdA, drugIdB: lasaForm.drugIdB, note: lasaForm.note || undefined,
    })
    ElMessage.success('已登记')
    lasaDialog.value = false
    await loadMaintain()
  } finally {
    saving.value = false
  }
}

async function removeLasa(pairId: number) {
  const ok = await ElMessageBox.confirm('撤销这一对看似听似对照？撤销后核对页将不再对这两种药给出提示。',
    '撤销对照', { type: 'warning' }).catch(() => null)
  if (!ok) return
  await client.delete(`${BASE}/lasa/${pairId}`)
  ElMessage.success('已撤销')
  await loadMaintain()
}

onMounted(async () => {
  await searchDrugs('')
  await loadWorklist()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
.caveat { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
.warn-text { color: var(--el-color-danger); }
</style>
