<template>
  <el-card>
    <div class="toolbar">
      <h3>拆零与效期盘点 · 拆零发药 / 近效期 / 拆零余量盘点</h3>
      <span>
        <el-tag v-if="warnDays" size="small" :type="gateType(expiryGate)" style="margin-right: 10px">
          效期 gate = {{ expiryGate || '?' }}
        </el-tag>
        <el-button link type="primary" @click="reload">刷新</el-button>
      </span>
    </div>

    <el-tabs v-model="tab" v-loading="loading" @tab-change="onTabChange">
      <!-- ===================== 拆零 ===================== -->
      <el-tab-pane name="split" label="拆零发药">
        <el-form inline size="small">
          <el-form-item label="药品">
            <el-select v-model="drugId" filterable remote clearable placeholder="输入药名检索"
                       :remote-method="searchDrugs" :loading="drugLoading" style="width: 320px"
                       @change="loadSplitInfo">
              <el-option v-for="d in drugs" :key="d.id" :value="d.id"
                         :label="`${d.code}　${d.name}　${d.spec ?? ''}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="挂号 id">
            <el-input-number v-model="registrationId" :min="1" :controls="false" style="width: 130px" />
            <span class="muted" style="margin-left: 6px">可留空（病区备用药补充等无处方场景）</span>
          </el-form-item>
        </el-form>

        <template v-if="splitInfo">
          <!-- 未维护换算系数：引导去维护，而不是只弹一个错误码 -->
          <el-alert v-if="splitInfo.splittable !== true" type="error" show-icon :closable="false" class="caveat"
                    title="该药品当前不允许拆零">
            <div>{{ splitInfo.reason }}</div>
            <el-button type="primary" size="small" style="margin-top: 8px" @click="openPackSpec">
              去维护换算系数
            </el-button>
          </el-alert>

          <el-descriptions :column="4" border size="small" class="caveat">
            <el-descriptions-item label="药品">{{ splitInfo.drugName }}</el-descriptions-item>
            <el-descriptions-item label="规格">{{ splitInfo.spec ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="换算系数">
              <span v-if="splitInfo.packSize">
                1 {{ splitInfo.unit }} = {{ num(splitInfo.packSize) }} {{ splitInfo.minUnit }}
              </span>
              <el-tag v-else size="small" type="danger">未维护</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="整包库存">
              {{ num(splitInfo.packStock) }} {{ splitInfo.unit }}
            </el-descriptions-item>
            <el-descriptions-item label="散装余量">
              {{ num(splitInfo.remainQty) }} {{ splitInfo.minUnit ?? '' }}
            </el-descriptions-item>
            <el-descriptions-item label="操作" :span="3">
              <el-button size="small" @click="openPackSpec">维护换算系数</el-button>
              <el-button size="small" @click="loadTxns">查看余量流水</el-button>
              <el-button size="small" @click="runExpiryCheck">发药前效期校验</el-button>
            </el-descriptions-item>
          </el-descriptions>

          <el-form inline size="small" v-if="splitInfo.splittable === true">
            <el-form-item :label="`发出数量（${splitInfo.minUnit}）`">
              <el-input-number v-model="splitQty" :min="1" :max="99999" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" size="small" :loading="saving" @click="submitSplit">拆零发出</el-button>
            </el-form-item>
          </el-form>
        </template>

        <el-divider content-position="left">拆零发药记录（退回入口）</el-divider>
        <el-table :data="dispenses" size="small" border stripe max-height="340">
          <el-table-column prop="dispense_no" label="拆零单号" width="170" />
          <el-table-column prop="drug_name" label="药品" min-width="160" show-overflow-tooltip />
          <el-table-column label="发出" width="110" align="right">
            <template #default="{ row }">{{ num(row.qty_min_unit) }} {{ row.min_unit }}</template>
          </el-table-column>
          <el-table-column label="已退" width="90" align="right">
            <template #default="{ row }">{{ num(row.returned_qty) }}</template>
          </el-table-column>
          <el-table-column label="开盒" width="90" align="right">
            <template #default="{ row }">{{ num(row.packs_opened) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="splitStatusType(String(row.status))">
                {{ splitStatusName(String(row.status)) }}
              </el-tag>
            </template>
          </el-table-column>
          <!-- expiry_warned：warn 档带警发出的要留痕并显示，不能只躺在库里 -->
          <el-table-column label="效期告警" min-width="200">
            <template #default="{ row }">
              <span v-if="row.expiry_warned === true" class="warn-text">
                带警发出：{{ row.expiry_note ?? '（未记原文）' }}
              </span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ fmt(row.created_at) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status !== 'RETURNED'" link type="warning" size="small"
                         @click="openReturn(row)">退回</el-button>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <template #empty>无拆零发药记录</template>
        </el-table>
      </el-tab-pane>

      <!-- ===================== 近效期 ===================== -->
      <el-tab-pane name="expiry" :label="`近效期（${expiryRows.length}）`">
        <el-form inline size="small">
          <el-form-item label="预警天数">
            <el-input-number v-model="days" :min="1" :max="3650" :step="30" controls-position="right"
                             style="width: 130px" />
            <span class="muted" style="margin-left: 6px">留空按配置解析</span>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" size="small" @click="loadExpiry">查询</el-button>
          </el-form-item>
        </el-form>

        <!-- 天数从哪儿来的必须显示：配置写错时回落链路是排查的第一现场 -->
        <el-descriptions v-if="expiry" :column="4" border size="small" class="caveat">
          <el-descriptions-item label="生效预警天数">{{ num(expiry.warnDays) }} 天</el-descriptions-item>
          <el-descriptions-item label="取值来源">{{ expiry.warnDaysSource }}</el-descriptions-item>
          <el-descriptions-item label="效期 gate">
            <el-tag size="small" :type="gateType(String(expiry.gate))">{{ expiry.gate }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="已过期批次">
            <el-tag v-if="num(expiry.expiredCount) > 0" size="small" type="danger" effect="dark">
              {{ num(expiry.expiredCount) }}
            </el-tag>
            <span v-else class="muted">0</span>
          </el-descriptions-item>
        </el-descriptions>
        <!-- caveats：在库量是估算值，这句话不显示出来，药师会把它当账 -->
        <el-alert v-for="(c, i) in expiryCaveats" :key="i" type="warning" show-icon :closable="false"
                  class="caveat" :title="c" />

        <el-table :data="expiryRows" size="small" border stripe max-height="440"
                  :row-class-name="expiryRowClass">
          <el-table-column prop="drugName" label="药品" min-width="170" show-overflow-tooltip />
          <el-table-column prop="batchNo" label="批号" width="130">
            <template #default="{ row }">
              <span v-if="row.batchNo">{{ row.batchNo }}</span>
              <el-tag v-else size="small" type="warning">未填</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="有效期至" width="120">
            <template #default="{ row }">{{ fmt(row.expireDate) }}</template>
          </el-table-column>
          <el-table-column label="剩余天数" width="100" align="right">
            <template #default="{ row }">{{ row.daysToExpire }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'EXPIRED' ? 'danger' : 'warning'">
                {{ row.status === 'EXPIRED' ? '已过期' : '近效期' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="入库量" width="100" align="right">
            <template #default="{ row }">{{ num(row.batchQty) }}</template>
          </el-table-column>
          <el-table-column label="估算在库（非账面）" width="160" align="right">
            <template #default="{ row }">
              {{ num(row.estimatedRemaining) }}
              <el-tag size="small" type="info" style="margin-left: 4px">估算</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="散装余量" width="110" align="right">
            <template #default="{ row }">{{ num(row.splitRemainQty) }}</template>
          </el-table-column>
          <template #empty>该窗口内无近效期或已过期批次</template>
        </el-table>
      </el-tab-pane>

      <!-- ===================== 拆零余量盘点 ===================== -->
      <el-tab-pane name="take" :label="`拆零盘点（${takes.length}）`">
        <div class="toolbar">
          <el-alert type="info" :closable="false" show-icon style="flex: 1; margin-right: 10px"
                    title="本单只盘拆零散装余量（最小单位）"
                    description="整包库存盘点走药库的库存盘点页，两者不重叠——同一概念两套盘点单就是两套账。" />
          <el-button type="primary" size="small" @click="openCreateTake">新建盘点单</el-button>
        </div>
        <el-table :data="takes" size="small" border stripe max-height="240">
          <el-table-column prop="take_no" label="盘点单号" width="180" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="takeStatusType(String(row.status))">
                {{ takeStatusName(String(row.status)) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="行数" width="80" align="right">
            <template #default="{ row }">{{ num(row.line_count) }}</template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
          <el-table-column label="建单时间" width="160">
            <template #default="{ row }">{{ fmt(row.created_at) }}</template>
          </el-table-column>
          <el-table-column label="确认时间" width="160">
            <template #default="{ row }">{{ fmt(row.confirmed_at) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openTake(Number(row.id))">打开</el-button>
            </template>
          </el-table-column>
          <template #empty>暂无拆零盘点单</template>
        </el-table>

        <template v-if="take">
          <el-divider content-position="left">
            盘点单 {{ take.take_no }}（{{ takeStatusName(String(take.status)) }}）
          </el-divider>
          <el-alert type="info" :closable="false" show-icon class="caveat"
                    :title="String(take.scopeNote ?? '')" />
          <!-- 差异必须逐行列出来给药师核，不做成一个「确认」按钮把差异一键吞掉 -->
          <el-descriptions :column="5" border size="small" class="caveat" title="盘点差异汇总">
            <el-descriptions-item label="盘点行数">{{ num(take.lineCount) }}</el-descriptions-item>
            <el-descriptions-item label="已录实盘">{{ num(take.countedLines) }}</el-descriptions-item>
            <el-descriptions-item label="盘盈行">
              <span :class="num(take.gainLines) > 0 ? 'warn-text' : ''">{{ num(take.gainLines) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="盘亏行">
              <span :class="num(take.lossLines) > 0 ? 'warn-text' : ''">{{ num(take.lossLines) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="净差异">
              <el-tag v-if="num(take.netDiff) !== 0" size="small" type="danger" effect="dark">
                {{ num(take.netDiff) > 0 ? '+' : '' }}{{ num(take.netDiff) }}
              </el-tag>
              <span v-else class="muted">0</span>
            </el-descriptions-item>
          </el-descriptions>
          <el-alert v-if="diffLines.length" type="error" show-icon :closable="false" class="caveat"
                    :title="`${diffLines.length} 行账实不符，确认前请逐行核对实物`"
                    description="确认会按账面快照条件更新余量并写 TAKEADJ 流水；余量在盘点期间被并发拆零改动过的行会整单拒绝（5504），须重新盘点。" />

          <el-table :data="takeLines" size="small" border stripe max-height="360"
                    :row-class-name="takeRowClass">
            <el-table-column prop="drug_name" label="药品" min-width="180" show-overflow-tooltip />
            <el-table-column prop="min_unit" label="最小单位" width="100" />
            <el-table-column label="账面" width="100" align="right">
              <template #default="{ row }">{{ num(row.book_qty) }}</template>
            </el-table-column>
            <el-table-column label="实盘" width="150" align="right">
              <template #default="{ row }">
                <el-input-number v-if="take && take.status === 'DRAFT'"
                                 :model-value="countDraft[String(row.drug_id)]"
                                 :min="0" :max="999999" size="small" controls-position="right"
                                 style="width: 120px"
                                 @update:model-value="(v: number | undefined) => setCount(row, v)" />
                <span v-else>{{ row.actual_qty ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="差异" width="110" align="right">
              <template #default="{ row }">
                <el-tag v-if="row.diff !== null && row.diff !== undefined && num(row.diff) !== 0"
                        size="small" type="danger" effect="dark">
                  {{ num(row.diff) > 0 ? '+' : '' }}{{ num(row.diff) }}
                </el-tag>
                <span v-else-if="row.diff === null || row.diff === undefined" class="muted">未录</span>
                <span v-else class="muted">0</span>
              </template>
            </el-table-column>
            <template #empty>本单无盘点行</template>
          </el-table>

          <div v-if="take.status === 'DRAFT'" class="toolbar" style="margin-top: 10px">
            <span class="muted">
              录入实盘数后先保存，再逐行核对上表的差异，最后确认。确认会真正调整余量并留流水。
            </span>
            <span>
              <el-button size="small" :loading="saving" @click="saveCounts">保存实盘数</el-button>
              <el-button type="primary" size="small" :loading="saving"
                         :disabled="num(take.countedLines) === 0" @click="confirmTake">
                确认盘点（调整余量）
              </el-button>
              <el-button type="danger" size="small" :loading="saving" @click="cancelTake">作废</el-button>
            </span>
          </div>
        </template>
      </el-tab-pane>
    </el-tabs>
  </el-card>

  <!-- ===================== 换算系数维护 ===================== -->
  <el-dialog v-model="packSpecDialog" title="维护拆零换算系数" width="520px">
    <!-- 明确写在页面上：不提供批量导入，不按规格串解析 -->
    <el-alert type="error" show-icon :closable="false" class="caveat"
              title="逐条人工录入，不提供批量导入，也不按规格文本自动解析"
              description="同一通用名不同厂家、不同规格的换算系数都不一样（同是阿莫西林胶囊，甲厂 24 粒/盒、乙厂 12 粒/盒）。从药名或规格串里解析必然出错，而这个数填错一倍，患者拿到的药就差一倍。" />
    <el-form label-width="130px" size="small">
      <el-form-item label="药品">{{ packSpecForm.drugName }}</el-form-item>
      <el-form-item label="销售单位">{{ packSpecForm.unit }}</el-form-item>
      <el-form-item label="1 个销售单位 =" required>
        <el-input-number v-model="packSpecForm.packSize" :min="2" :max="99999" />
        <span style="margin-left: 8px">
          <el-input v-model="packSpecForm.minUnit" placeholder="最小单位，如 片/粒/支"
                    style="width: 140px" maxlength="8" />
        </span>
      </el-form-item>
      <el-form-item>
        <span class="muted">
          系数须 ≥ 2（等于 1 说明该药本就不需要拆零，请改用「清空」）。请照实物包装数清点后填写。
        </span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="packSpecDialog = false">取消</el-button>
      <el-button size="small" :loading="saving" @click="submitPackSpec(true)">清空（该药不可拆零）</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submitPackSpec(false)">保存</el-button>
    </template>
  </el-dialog>

  <!-- ===================== 拆零退回 ===================== -->
  <el-dialog v-model="returnDialog" title="拆零退回（支持部分退）" width="440px">
    <el-form label-width="110px" size="small">
      <el-form-item label="拆零单">{{ returnForm.dispenseNo }}</el-form-item>
      <el-form-item label="已发出">{{ returnForm.dispensed }} {{ returnForm.minUnit }}</el-form-item>
      <el-form-item label="已退回">{{ returnForm.returned }}</el-form-item>
      <el-form-item label="本次退回" required>
        <el-input-number v-model="returnForm.qtyMinUnit" :min="1"
                         :max="Math.max(1, returnForm.dispensed - returnForm.returned)" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="returnDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submitReturn">确认退回</el-button>
    </template>
  </el-dialog>

  <!-- ===================== 新建盘点单 ===================== -->
  <el-dialog v-model="createTakeDialog" title="新建拆零盘点单" width="560px">
    <el-alert type="info" :closable="false" show-icon class="caveat"
              title="账面数在加行时快照"
              description="盘点的意义是账实对账，账面必须是开盘那一刻的值。从未拆过的药账面记 0，允许盘盈。停用药的散装余量照样在架上、照样要盘。" />
    <el-form label-width="90px" size="small">
      <el-form-item label="药品" required>
        <el-select v-model="createTakeForm.drugIds" multiple filterable remote placeholder="输入药名检索，可多选"
                   :remote-method="searchDrugs" :loading="drugLoading" style="width: 100%">
          <el-option v-for="d in drugs" :key="d.id" :value="d.id" :label="`${d.code}　${d.name}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="备注"><el-input v-model="createTakeForm.remark" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="createTakeDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submitCreateTake">建单</el-button>
    </template>
  </el-dialog>

  <!-- ===================== 余量流水 ===================== -->
  <el-drawer v-model="txnDrawer" size="60%" title="拆零余量流水（最小单位）">
    <el-table :data="txns" size="small" border height="calc(100vh - 160px)">
      <el-table-column label="类型" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="txnTagType(String(row.type))">{{ txnName(String(row.type)) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="增减" width="100" align="right">
        <template #default="{ row }">
          <span :class="num(row.qty) < 0 ? 'warn-text' : ''">
            {{ num(row.qty) > 0 ? '+' : '' }}{{ num(row.qty) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="结存" width="100" align="right">
        <template #default="{ row }">{{ num(row.remain_after) }}</template>
      </el-table-column>
      <el-table-column prop="min_unit" label="单位" width="80" />
      <el-table-column prop="ref_no" label="关联单号" width="180" />
      <el-table-column label="时间" width="160">
        <template #default="{ row }">{{ fmt(row.created_at) }}</template>
      </el-table-column>
      <template #empty>该药品无余量流水</template>
    </el-table>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../../api/client'
import type { BizError } from '../../../api/client'

type Row = Record<string, unknown>
interface Drug { id: number; code: string; name: string; spec?: string; unit?: string }

const BASE = '/outpatient/pharm-ops'

const tab = ref('split')
const loading = ref(false)
const saving = ref(false)

const drugs = ref<Drug[]>([])
const drugLoading = ref(false)
const drugId = ref<number | undefined>()
const registrationId = ref<number | undefined>()

const splitInfo = ref<Row | null>(null)
const splitQty = ref(1)
const dispenses = ref<Row[]>([])

const expiry = ref<Row | null>(null)
const days = ref<number | undefined>()
const warnDays = ref<Row | null>(null)
const expiryRows = computed<Row[]>(() => (expiry.value?.rows ?? []) as Row[])
const expiryCaveats = computed<string[]>(() => (expiry.value?.caveats ?? []) as string[])
const expiryGate = computed(() => String(expiry.value?.gate ?? ''))

const takes = ref<Row[]>([])
const take = ref<Row | null>(null)
const takeLines = computed<Row[]>(() => (take.value?.lines ?? []) as Row[])
const diffLines = computed(() =>
  takeLines.value.filter((l) => l.diff !== null && l.diff !== undefined && num(l.diff) !== 0))
/** 实盘数草稿：键是 drug_id 的字符串形式（后端按 drugId 做 upsert，不按行 id） */
const countDraft = reactive<Record<string, number | undefined>>({})

function num(v: unknown): number {
  return Number(v ?? 0)
}

function fmt(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—'
  const s = String(v)
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(s) ? s.slice(0, 16).replace('T', ' ') : s
}

function gateType(g: unknown): 'success' | 'warning' | 'danger' | 'info' {
  return g === 'block' ? 'danger' : g === 'warn' ? 'warning' : 'info'
}

function splitStatusName(s: string): string {
  return ({ DISPENSED: '已发', PART_RETURNED: '部分退回', RETURNED: '已全退' } as Record<string, string>)[s] ?? s
}

function splitStatusType(s: string): 'success' | 'warning' | 'info' {
  return s === 'RETURNED' ? 'info' : s === 'PART_RETURNED' ? 'warning' : 'success'
}

function takeStatusName(s: string): string {
  return ({ DRAFT: '草稿', CONFIRMED: '已确认', CANCELLED: '已作废' } as Record<string, string>)[s] ?? s
}

function takeStatusType(s: string): 'success' | 'warning' | 'info' {
  return s === 'CONFIRMED' ? 'success' : s === 'CANCELLED' ? 'info' : 'warning'
}

function txnName(s: string): string {
  return ({ OPEN: '开盒入池', OUT: '拆零发出', RET: '拆零退回', TAKEADJ: '盘点调整' } as Record<string, string>)[s] ?? s
}

function txnTagType(s: string): 'success' | 'warning' | 'danger' | 'info' {
  return s === 'OPEN' ? 'success' : s === 'OUT' ? 'warning' : s === 'TAKEADJ' ? 'danger' : 'info'
}

function expiryRowClass({ row }: { row: Row }): string {
  return row.status === 'EXPIRED' ? 'row-bad' : ''
}

function takeRowClass({ row }: { row: Row }): string {
  return row.diff !== null && row.diff !== undefined && num(row.diff) !== 0 ? 'row-bad' : ''
}

async function searchDrugs(keyword: string) {
  drugLoading.value = true
  try {
    drugs.value = (await client.get('/masterdata/drugs', { params: { keyword } })).data.data as Drug[]
  } finally {
    drugLoading.value = false
  }
}

/* ---------------- 拆零 ---------------- */
async function loadSplitInfo() {
  if (!drugId.value) {
    splitInfo.value = null
    dispenses.value = []
    return
  }
  splitInfo.value = (await client.get(`${BASE}/split/drugs/${drugId.value}`)).data.data as Row
  await loadDispenses()
}

async function loadDispenses() {
  dispenses.value = (await client.get(`${BASE}/split/dispenses`, {
    params: { drugId: drugId.value, registrationId: registrationId.value },
  })).data.data as Row[]
}

async function submitSplit() {
  if (!drugId.value) return
  saving.value = true
  try {
    const d = (await client.post(`${BASE}/split/dispense`, {
      drugId: drugId.value,
      qtyMinUnit: splitQty.value,
      registrationId: registrationId.value,
    }, {
      // 5460/5461 自行处理：这两个码要引导去维护换算系数，不能只弹一句红字
      __silentCodes: [5460, 5461],
    })).data.data as Row
    const warns = (d.warnings ?? []) as string[]
    if (warns.length) {
      await ElMessageBox.alert(warns.map((w) => `· ${w}`).join('\n'),
        `已发出 ${num(d.qtyMinUnit)} ${d.minUnit}（开盒 ${num(d.packsOpened)}，余量 ${num(d.remainQty)}），`
        + `但 gate=${d.expiryGate} 放行了 ${warns.length} 条效期告警`,
        { type: 'warning', confirmButtonText: '我已知悉' })
    } else {
      ElMessage.success(`已发出 ${num(d.qtyMinUnit)} ${d.minUnit}，余量 ${num(d.remainQty)}`)
    }
    await loadSplitInfo()
  } catch (e) {
    const code = (e as BizError).bizCode
    if (code === 5460 || code === 5461) {
      await ElMessageBox.alert(
        `${(e as Error).message}\n\n请先逐条维护该药品的换算系数后再拆零。`,
        '该药品不允许拆零', { type: 'error', confirmButtonText: '去维护' },
      ).catch(() => null)
      openPackSpec()
    }
  } finally {
    saving.value = false
  }
}

async function runExpiryCheck() {
  if (!drugId.value) return
  const d = (await client.get(`${BASE}/expiry/check/${drugId.value}`)).data.data as Row
  const warns = (d.warnings ?? []) as string[]
  if (!warns.length) {
    ElMessage.success(`无已过期批次（gate=${d.gate}）`)
    return
  }
  await ElMessageBox.alert(warns.map((w) => `· ${w}`).join('\n'),
    d.blocked === true ? `gate=block：本药品当前不得发药` : `gate=${d.gate}：有过期批次告警，放行但须复核`,
    { type: 'warning', confirmButtonText: '我已知悉' })
}

/* ---------------- 换算系数 ---------------- */
const packSpecDialog = ref(false)
const packSpecForm = reactive({
  drugId: 0, drugName: '', unit: '', packSize: 2 as number | undefined, minUnit: '',
})

function openPackSpec() {
  if (!drugId.value || !splitInfo.value) {
    ElMessage.warning('请先选择药品')
    return
  }
  Object.assign(packSpecForm, {
    drugId: drugId.value,
    drugName: String(splitInfo.value.drugName ?? ''),
    unit: String(splitInfo.value.unit ?? ''),
    packSize: splitInfo.value.packSize ? num(splitInfo.value.packSize) : undefined,
    minUnit: String(splitInfo.value.minUnit ?? ''),
  })
  packSpecDialog.value = true
}

async function submitPackSpec(clearing: boolean) {
  if (clearing) {
    const ok = await ElMessageBox.confirm(
      `清空【${packSpecForm.drugName}】的换算系数？清空后该药不再允许拆零。`,
      '清空换算系数', { type: 'warning' }).catch(() => null)
    if (!ok) return
  } else if (!packSpecForm.packSize || packSpecForm.packSize < 2 || !packSpecForm.minUnit.trim()) {
    ElMessage.warning('换算系数须 ≥ 2，且最小单位必填')
    return
  } else {
    const ok = await ElMessageBox.confirm(
      `确认【${packSpecForm.drugName}】：1 ${packSpecForm.unit} = ${packSpecForm.packSize} ${packSpecForm.minUnit}？`
      + '\n请照实物包装清点后确认——这个数错一倍，患者拿到的药就差一倍。',
      '核对换算系数', { type: 'warning' }).catch(() => null)
    if (!ok) return
  }
  saving.value = true
  try {
    const d = (await client.put(`${BASE}/drugs/${packSpecForm.drugId}/pack-spec`, {
      packSize: clearing ? null : packSpecForm.packSize,
      minUnit: clearing ? null : packSpecForm.minUnit,
    })).data.data as Row
    ElMessage.success(String(d.note ?? '已保存'))
    packSpecDialog.value = false
    await loadSplitInfo()
  } finally {
    saving.value = false
  }
}

/* ---------------- 拆零退回 ---------------- */
const returnDialog = ref(false)
const returnForm = reactive({
  dispenseId: 0, dispenseNo: '', dispensed: 0, returned: 0, minUnit: '', qtyMinUnit: 1,
})

function openReturn(row: Row) {
  Object.assign(returnForm, {
    dispenseId: Number(row.id),
    dispenseNo: String(row.dispense_no ?? ''),
    dispensed: num(row.qty_min_unit),
    returned: num(row.returned_qty),
    minUnit: String(row.min_unit ?? ''),
    qtyMinUnit: 1,
  })
  returnDialog.value = true
}

async function submitReturn() {
  saving.value = true
  try {
    const d = (await client.post(`${BASE}/split/dispenses/${returnForm.dispenseId}/return`,
      { qtyMinUnit: returnForm.qtyMinUnit })).data.data as Row
    ElMessage.success(`已退回，累计退回 ${num(d.returnedQty)}，余量 ${num(d.remainQty)}`)
    returnDialog.value = false
    await loadSplitInfo()
  } finally {
    saving.value = false
  }
}

/* ---------------- 余量流水 ---------------- */
const txnDrawer = ref(false)
const txns = ref<Row[]>([])

async function loadTxns() {
  if (!drugId.value) return
  txns.value = []
  txnDrawer.value = true
  txns.value = (await client.get(`${BASE}/split/drugs/${drugId.value}/txns`,
    { params: { limit: 200 } })).data.data as Row[]
}

/* ---------------- 近效期 ---------------- */
async function loadWarnDays() {
  warnDays.value = (await client.get(`${BASE}/expiry/warn-days`)).data.data as Row
}

async function loadExpiry() {
  expiry.value = (await client.get(`${BASE}/expiry/warnings`,
    { params: { days: days.value ?? undefined } })).data.data as Row
}

/* ---------------- 拆零盘点 ---------------- */
async function loadTakes() {
  takes.value = (await client.get(`${BASE}/stock-take/split`)).data.data as Row[]
}

function setTake(d: Row) {
  take.value = d
  for (const k of Object.keys(countDraft)) delete countDraft[k]
  for (const l of (d.lines ?? []) as Row[]) {
    countDraft[String(l.drug_id)] = l.actual_qty === null || l.actual_qty === undefined
      ? undefined : num(l.actual_qty)
  }
}

async function openTake(id: number) {
  setTake((await client.get(`${BASE}/stock-take/split/${id}`)).data.data as Row)
}

function setCount(row: Row, v: number | undefined) {
  countDraft[String(row.drug_id)] = v
}

async function saveCounts() {
  if (!take.value) return
  const entries = takeLines.value
    .map((l) => ({ drugId: Number(l.drug_id), actualQty: countDraft[String(l.drug_id)] }))
    .filter((e) => e.actualQty !== undefined && e.actualQty !== null)
  if (!entries.length) {
    ElMessage.warning('请先录入至少一行实盘数')
    return
  }
  saving.value = true
  try {
    setTake((await client.post(`${BASE}/stock-take/split/${Number(take.value.id)}/counts`,
      { entries })).data.data as Row)
    ElMessage.success('已保存实盘数，请核对下表差异后再确认')
  } finally {
    saving.value = false
  }
}

async function confirmTake() {
  if (!take.value) return
  // 确认前把差异逐行摆出来让药师认；一键吞掉差异正是这个按钮最容易被做坏的地方
  const detailText = diffLines.value.length
    ? diffLines.value.map((l) =>
        `· ${l.drug_name}：账面 ${num(l.book_qty)}，实盘 ${num(l.actual_qty)}，差 ${num(l.diff) > 0 ? '+' : ''}${num(l.diff)} ${l.min_unit ?? ''}`,
      ).join('\n')
    : '（本单无账实差异）'
  const ok = await ElMessageBox.confirm(
    `确认后将按账面快照调整余量并写 TAKEADJ 流水，差异如下，请逐行核对实物后再确认：\n\n${detailText}`,
    `确认盘点：${diffLines.value.length} 行账实不符`,
    { type: 'warning', confirmButtonText: '差异已核对，确认', cancelButtonText: '再看看' },
  ).catch(() => null)
  if (!ok) return
  saving.value = true
  try {
    setTake((await client.post(`${BASE}/stock-take/split/${Number(take.value.id)}/confirm`)).data.data as Row)
    ElMessage.success('盘点已确认')
    await loadTakes()
  } finally {
    saving.value = false
  }
}

async function cancelTake() {
  if (!take.value) return
  const ok = await ElMessageBox.confirm('作废这张盘点单？作废后余量不做任何调整。',
    '作废盘点单', { type: 'warning' }).catch(() => null)
  if (!ok) return
  saving.value = true
  try {
    setTake((await client.post(`${BASE}/stock-take/split/${Number(take.value.id)}/cancel`)).data.data as Row)
    ElMessage.success('已作废')
    await loadTakes()
  } finally {
    saving.value = false
  }
}

const createTakeDialog = ref(false)
const createTakeForm = reactive({ drugIds: [] as number[], remark: '' })

function openCreateTake() {
  Object.assign(createTakeForm, { drugIds: [], remark: '' })
  createTakeDialog.value = true
}

async function submitCreateTake() {
  if (!createTakeForm.drugIds.length) {
    ElMessage.warning('至少选择一种药品')
    return
  }
  saving.value = true
  try {
    setTake((await client.post(`${BASE}/stock-take/split`, {
      drugIds: createTakeForm.drugIds, remark: createTakeForm.remark || undefined,
    })).data.data as Row)
    createTakeDialog.value = false
    ElMessage.success('已建单')
    await loadTakes()
  } finally {
    saving.value = false
  }
}

/* ---------------- 载入 ---------------- */
async function onTabChange() {
  await reload()
}

async function reload() {
  loading.value = true
  try {
    if (tab.value === 'split') await loadSplitInfo()
    else if (tab.value === 'expiry') { await loadWarnDays(); await loadExpiry() }
    else await loadTakes()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await searchDrugs('')
  await reload()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
.caveat { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
.warn-text { color: var(--el-color-danger); }
:deep(.row-bad) { background: var(--el-color-danger-light-9); }
</style>
