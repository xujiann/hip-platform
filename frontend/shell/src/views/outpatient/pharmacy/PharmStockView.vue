<template>
  <el-card>
    <div class="toolbar">
      <h3>药房批次库存 · 入库 / 批次 / 流水 / 报损 / 对账</h3>
      <span>
        <el-button size="small" @click="stockInDialog = true">入库登记</el-button>
        <el-button size="small" @click="openPickPreview">取批预览</el-button>
        <el-button link type="primary" @click="reload">刷新</el-button>
      </span>
    </div>

    <!-- ============ 诚实标注一：批次管理覆盖率 ============
         覆盖率低不是丢人的事，藏起来才是。没有这一段，「批次库存都对得上」
         会被读成「全院库存都对得上」，而它很可能只是「全院 3 个药启用了批次管理」。 -->
    <el-descriptions v-if="coverage" :column="5" border size="small" class="caveat"
                     title="批次管理覆盖率（先看这一段，再看下面的库存数）">
      <el-descriptions-item label="启用药品数">{{ num(coverage.enabledDrugs) }}</el-descriptions-item>
      <el-descriptions-item label="已有批次记录">{{ num(coverage.drugsWithBatchRecord) }}</el-descriptions-item>
      <el-descriptions-item label="批次层有余量">{{ num(coverage.drugsWithLiveBatchStock) }}</el-descriptions-item>
      <el-descriptions-item label="仍为旧口径">
        <span class="warn-text">{{ num(coverage.legacyOnlyDrugs) }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="纳管覆盖率">
        <el-tag size="small" :type="ratePct < 50 ? 'danger' : ratePct < 90 ? 'warning' : 'success'">
          {{ ratePct }}%
        </el-tag>
      </el-descriptions-item>
    </el-descriptions>
    <el-alert v-if="coverage" type="info" :closable="false" class="caveat"
              :title="String(coverage.rateFormula ?? '')" :description="String(coverage.note ?? '')" show-icon />

    <!-- gate 与取批规则：前端不另写一份口径，一律显示后端 /settings 的实际生效值 -->
    <el-descriptions v-if="settings" :column="6" border size="small" class="caveat" title="当前生效的 gate 与规则">
      <el-descriptions-item label="批号/效期 gate">
        <el-tag size="small" :type="gateType(settings.batchGate)">{{ settings.batchGate }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="效期合格 gate">
        <el-tag size="small" :type="gateType(settings.expiryGate)">{{ settings.expiryGate }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="取批规则">{{ settings.pickRule }}</el-descriptions-item>
      <el-descriptions-item label="最短剩余效期">{{ num(settings.minShelfLifeDays) }} 天</el-descriptions-item>
      <el-descriptions-item label="近效期阈值">{{ num(settings.nearExpiryDays) }} 天</el-descriptions-item>
      <el-descriptions-item label="默认库房">{{ settings.defaultLocation }}</el-descriptions-item>
    </el-descriptions>
    <el-alert v-if="settings?.note" type="info" :closable="false" class="caveat"
              :title="String(settings.note)" />

    <el-form inline size="small" style="margin-top: 4px">
      <el-form-item label="药品">
        <el-select v-model="drugId" filterable remote clearable placeholder="全部药品（输入药名检索）"
                   :remote-method="searchDrugs" :loading="drugLoading" style="width: 300px" @change="reload">
          <el-option v-for="d in drugs" :key="d.id" :value="d.id" :label="`${d.code}　${d.name}　${d.spec ?? ''}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="库房/药柜">
        <el-input v-model="locationCode" clearable placeholder="默认全部" style="width: 140px" @change="reload" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" size="small" :loading="loading" @click="reload">查询</el-button>
      </el-form-item>
    </el-form>

    <el-tabs v-model="tab" v-loading="loading" @tab-change="onTabChange">
      <!-- ===================== 库存总览：两本账 + drift ===================== -->
      <el-tab-pane name="balance" :label="`库存总览（${balanceRows.length}）`">
        <el-alert v-for="(c, i) in caveats" :key="i" type="warning" :closable="false" class="caveat"
                  show-icon :title="c" />
        <!-- 两类 drift 分开报：混成一个数会让「迁移没走完」和「已纳管却对不上」看起来一样严重，
             结果是药师对满屏红字脱敏，真正要查的那几行反而被淹掉 -->
        <el-alert v-if="managedDriftRows.length" type="error" show-icon :closable="false" class="caveat"
                  :title="`${managedDriftRows.length} 个已纳入批次管理的药品，两本账对不上（drift ≠ 0）——这些要人去核对实物`"
                  description="drift = 批次层账面 − 药品汇总库存。发药出库目前只扣汇总不扣批次，批次层会单向偏高；逐行成因见「漂移说明」列。" />
        <el-alert v-if="unmanagedDriftRows.length" type="warning" show-icon :closable="false" class="caveat"
                  :title="`另有 ${unmanagedDriftRows.length} 个药品尚未纳入批次管理，其 drift 等于全部汇总库存`"
                  description="这不是账错，是批次迁移还没走到这些药——覆盖率见本页顶部。它们的可发量以汇总库存为准。" />
        <el-table :data="balanceRows" size="small" border stripe max-height="460"
                  :row-class-name="driftRowClass">
          <el-table-column prop="drug_code" label="药品编码" width="110" />
          <el-table-column prop="drug_name" label="药品名称" min-width="160" show-overflow-tooltip />
          <el-table-column prop="unit" label="单位" width="70" />
          <el-table-column label="汇总库存(可发量口径)" width="160" align="right">
            <template #default="{ row }">{{ num(row.aggregate_stock) }}</template>
          </el-table-column>
          <el-table-column label="批次层账面" width="110" align="right">
            <template #default="{ row }">{{ num(row.batch_stock) }}</template>
          </el-table-column>
          <el-table-column label="drift 差额" width="110" align="right">
            <template #default="{ row }">
              <el-tag v-if="num(row.drift) !== 0" size="small" type="danger" effect="dark">
                {{ num(row.drift) > 0 ? '+' : '' }}{{ num(row.drift) }}
              </el-tag>
              <span v-else class="muted">0</span>
            </template>
          </el-table-column>
          <el-table-column label="批次管理" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.batchManaged === true" size="small" type="success">已纳管</el-tag>
              <el-tag v-else size="small" type="warning">旧口径</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最早效期" width="180">
            <template #default="{ row }">
              <span v-if="row.earliest_expire">
                {{ fmt(row.earliest_expire) }}
                <span class="muted">（{{ num(row.earliestDaysToExpire) }} 天）</span>
              </span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <!-- driftNote 必须显示出来：drift 那一列只是个数字，为什么差、以哪个为准全在这句话里。
               注意「无 driftNote」不等于「两本账一致」：后端只在 batch>agg 或 (batch<agg 且 batch>0) 时给
               driftNote，批次层为 0 的药 drift 等于负的全部汇总库存却没有 note，
               那时若显示「一致」就是把一个几万的差额说成没有差额。判据一律用 drift 本身。 -->
          <el-table-column label="漂移说明" min-width="260">
            <template #default="{ row }">
              <span v-if="row.driftNote" class="warn-text">{{ row.driftNote }}</span>
              <span v-else-if="num(row.drift) !== 0" class="warn-text">
                该药尚未纳入批次管理（批次层无数据），drift 即其全部汇总库存 {{ num(row.aggregate_stock) }}。
                可发量以汇总库存为准；批次层要靠后续逐批入库增量补齐，本版不反推初始批次。
              </span>
              <span v-else class="muted">两本账一致</span>
            </template>
          </el-table-column>
          <template #empty>无库存数据（两本账均为 0 的药品不列出）</template>
        </el-table>
      </el-tab-pane>

      <!-- ===================== 批次 ===================== -->
      <el-tab-pane name="batches" :label="`批次（${batches.length}）`">
        <el-form inline size="small">
          <el-form-item label="批号前缀">
            <el-input v-model="batchNo" clearable style="width: 140px" @change="loadBatches" />
          </el-form-item>
          <el-form-item label="效期窗口">
            <el-input-number v-model="expiringInDays" :min="0" :max="3650" :step="30" controls-position="right"
                             style="width: 130px" @change="loadBatches" />
            <span class="muted" style="margin-left: 6px">天内到期（留空为全部）</span>
          </el-form-item>
        </el-form>
        <el-table :data="batches" size="small" border stripe max-height="460">
          <el-table-column prop="drug_name" label="药品" min-width="150" show-overflow-tooltip />
          <el-table-column prop="batch_no" label="批号" width="130">
            <template #default="{ row }">
              <span v-if="row.batch_no">{{ row.batch_no }}</span>
              <el-tag v-else size="small" type="warning">未录批号</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="生产日期" width="110">
            <template #default="{ row }">{{ fmt(row.produced_on) }}</template>
          </el-table-column>
          <el-table-column label="有效期至" width="110">
            <template #default="{ row }">{{ fmt(row.expire_on) }}</template>
          </el-table-column>
          <el-table-column label="效期状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="expiryTagType(String(row.expiryStatus))">
                {{ expiryText(String(row.expiryStatus)) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="剩余天数" width="90" align="right">
            <template #default="{ row }">{{ row.daysToExpire ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="在库量" width="90" align="right">
            <template #default="{ row }">{{ num(row.on_hand) }}</template>
          </el-table-column>
          <el-table-column prop="supplier" label="供应商" min-width="130" show-overflow-tooltip />
          <el-table-column label="进价" width="90" align="right">
            <template #default="{ row }">{{ row.purchase_price ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="showBatchStock(row)">批次余额</el-button>
              <el-button link type="danger" size="small" @click="openScrap(row)">报损</el-button>
            </template>
          </el-table-column>
          <template #empty>无批次数据</template>
        </el-table>
      </el-tab-pane>

      <!-- ===================== 流水 ===================== -->
      <el-tab-pane name="flows" :label="`出入库流水（${flows.length}）`">
        <el-form inline size="small">
          <el-form-item label="类型">
            <el-select v-model="flowType" clearable placeholder="全部" style="width: 130px" @change="loadFlows">
              <el-option v-for="t in FLOW_TYPES" :key="t.value" :value="t.value" :label="t.label" />
            </el-select>
          </el-form-item>
          <el-form-item label="日期区间">
            <el-date-picker v-model="range" type="daterange" unlink-panels value-format="YYYY-MM-DD"
                            range-separator="至" start-placeholder="起始日" end-placeholder="截止日"
                            style="width: 250px" @change="loadFlows" />
          </el-form-item>
        </el-form>
        <el-table :data="flows" size="small" border stripe max-height="460">
          <el-table-column prop="flow_no" label="流水号" width="180" />
          <el-table-column prop="drug_name" label="药品" min-width="140" show-overflow-tooltip />
          <el-table-column prop="batch_no" label="批号" width="120" />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="flowTagType(String(row.flow_type))">
                {{ flowText(String(row.flow_type)) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="增减" width="90" align="right">
            <template #default="{ row }">
              <span :class="num(row.qty_delta) < 0 ? 'warn-text' : ''">
                {{ num(row.qty_delta) > 0 ? '+' : '' }}{{ num(row.qty_delta) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="结存" width="90" align="right">
            <template #default="{ row }">{{ num(row.qty_after) }}</template>
          </el-table-column>
          <el-table-column prop="location_code" label="库房" width="110" />
          <el-table-column prop="reason" label="原因" min-width="150" show-overflow-tooltip />
          <el-table-column prop="ref_no" label="关联单号" width="140" />
          <el-table-column label="发生时间" width="160">
            <template #default="{ row }">{{ fmt(row.occurred_at) }}</template>
          </el-table-column>
          <template #empty>无流水记录</template>
        </el-table>
      </el-tab-pane>

      <!-- ===================== 对账 ===================== -->
      <el-tab-pane name="reconcile" :label="`对账（${reconcileRows.length}）`">
        <el-alert v-if="reconcile" :type="reconcile.balanced === true ? 'success' : 'error'" show-icon
                  :closable="false" class="caveat"
                  :title="`已校验 ${num(reconcile.checked)} 组（批次×库房），`
                          + (reconcile.balanced === true ? '全部自洽' : `${num(reconcile.mismatched)} 组不平——请逐行核对`)"
                  :description="String(reconcile.invariant ?? '')" />
        <el-table :data="reconcileRows" size="small" border stripe max-height="460"
                  :row-class-name="reconcileRowClass">
          <el-table-column prop="batchNo" label="批号" width="140" />
          <el-table-column prop="drugName" label="药品" min-width="160" show-overflow-tooltip />
          <el-table-column prop="locationCode" label="库房" width="110" />
          <el-table-column label="余额" width="90" align="right">
            <template #default="{ row }">{{ num(row.balanceQty) }}</template>
          </el-table-column>
          <el-table-column label="流水累计" width="100" align="right">
            <template #default="{ row }">{{ num(row.flowSumQty) }}</template>
          </el-table-column>
          <el-table-column label="差额" width="90" align="right">
            <template #default="{ row }">
              <el-tag v-if="num(row.diff) !== 0" size="small" type="danger" effect="dark">{{ num(row.diff) }}</el-tag>
              <span v-else class="muted">0</span>
            </template>
          </el-table-column>
          <el-table-column label="流水条数" width="100" align="right">
            <template #default="{ row }">{{ num(row.flowCount) }}</template>
          </el-table-column>
          <el-table-column label="结论" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.balanced === true ? 'success' : 'danger'">
                {{ row.balanced === true ? '自洽' : '不平' }}
              </el-tag>
            </template>
          </el-table-column>
          <template #empty>暂无可对账的批次余额</template>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>

  <!-- ===================== 入库登记 ===================== -->
  <el-dialog v-model="stockInDialog" title="批次入库登记（登记即入账）" width="560px">
    <el-alert type="info" :closable="false" show-icon class="caveat"
              :title="`当前批号/效期 gate = ${settings?.batchGate ?? '?'}`"
              :description="settings?.batchGate === 'block'
                ? '批号与有效期至为必填，缺一不予入账。'
                : '批号与有效期至可缺省，缺省时后端照常入账并回带 warnings —— 那些警告会显示在保存后的提示里，请如实处理。'" />
    <el-form label-width="110px" size="small">
      <el-form-item label="药品" required>
        <el-select v-model="stockInForm.drugId" filterable remote placeholder="输入药名检索"
                   :remote-method="searchDrugs" :loading="drugLoading" style="width: 100%">
          <el-option v-for="d in drugs" :key="d.id" :value="d.id" :label="`${d.code}　${d.name}　${d.spec ?? ''}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="入库数量" required>
        <el-input-number v-model="stockInForm.qty" :min="1" :max="999999" />
      </el-form-item>
      <el-form-item label="批号" :required="settings?.batchGate === 'block'">
        <el-input v-model="stockInForm.batchNo" placeholder="厂家批号，原样录入" />
      </el-form-item>
      <el-form-item label="生产日期">
        <el-date-picker v-model="stockInForm.producedOn" type="date" value-format="YYYY-MM-DD"
                        placeholder="yyyy-MM-dd" style="width: 100%" />
      </el-form-item>
      <el-form-item label="有效期至" :required="settings?.batchGate === 'block'">
        <el-date-picker v-model="stockInForm.expireOn" type="date" value-format="YYYY-MM-DD"
                        placeholder="yyyy-MM-dd" style="width: 100%" />
      </el-form-item>
      <el-form-item label="供应商"><el-input v-model="stockInForm.supplier" /></el-form-item>
      <el-form-item label="进价">
        <el-input v-model="stockInForm.purchasePrice" placeholder="元 / 销售单位，可留空" />
      </el-form-item>
      <el-form-item label="采购单号"><el-input v-model="stockInForm.purchaseNo" /></el-form-item>
      <el-form-item label="库房/药柜">
        <el-input v-model="stockInForm.locationCode" :placeholder="`留空取默认 ${settings?.defaultLocation ?? ''}`" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="stockInDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="submitStockIn">入库</el-button>
    </template>
  </el-dialog>

  <!-- ===================== 报损 ===================== -->
  <el-dialog v-model="scrapDialog" title="批次报损（不可逆，原因必填）" width="500px">
    <el-alert type="warning" show-icon :closable="false" class="caveat"
              title="报损同时扣减批次余额与药品汇总库存——货是真的没了"
              description="原因是审计与药监检查的唯一线索，请写清楚是破损、过期还是丢失，以及数量怎么来的。" />
    <el-descriptions :column="2" border size="small" class="caveat">
      <el-descriptions-item label="药品">{{ scrapForm.drugName }}</el-descriptions-item>
      <el-descriptions-item label="批号">{{ scrapForm.batchNo || '未录批号' }}</el-descriptions-item>
      <el-descriptions-item label="有效期至">{{ scrapForm.expireOn || '—' }}</el-descriptions-item>
      <el-descriptions-item label="当前在库">{{ scrapForm.onHand }}</el-descriptions-item>
    </el-descriptions>
    <el-form label-width="110px" size="small">
      <el-form-item label="报损数量" required>
        <el-input-number v-model="scrapForm.qty" :min="1" :max="999999" />
      </el-form-item>
      <el-form-item label="库房/药柜">
        <el-input v-model="scrapForm.locationCode" :placeholder="`留空取默认 ${settings?.defaultLocation ?? ''}`" />
      </el-form-item>
      <el-form-item label="报损原因" required>
        <el-input v-model="scrapForm.reason" type="textarea" :rows="3"
                  placeholder="如：2026-09-01 例行检查发现 3 盒瓶身破裂，已隔离待销毁" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="scrapDialog = false">取消</el-button>
      <el-button type="danger" size="small" :loading="saving" @click="submitScrap">确认报损</el-button>
    </template>
  </el-dialog>

  <!-- ===================== 取批预览 ===================== -->
  <el-dialog v-model="pickDialog" title="取批预览（只读，不占用也不预留库存）" width="700px">
    <el-form inline size="small">
      <el-form-item label="药品">
        <el-select v-model="pickForm.drugId" filterable remote placeholder="输入药名检索"
                   :remote-method="searchDrugs" :loading="drugLoading" style="width: 280px">
          <el-option v-for="d in drugs" :key="d.id" :value="d.id" :label="`${d.code}　${d.name}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="需求量">
        <el-input-number v-model="pickForm.qty" :min="1" :max="99999" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" size="small" :loading="picking" @click="runPickPreview">预览</el-button>
      </el-form-item>
    </el-form>
    <template v-if="pickResult">
      <el-alert :type="pickResult.satisfied === true ? 'success' : 'warning'" show-icon :closable="false"
                class="caveat"
                :title="`规则 ${pickResult.rule}：需求 ${num(pickResult.requested)}，可配 ${num(pickResult.allocated)}`
                        + (pickResult.satisfied === true ? '，已配齐' : `，缺 ${num(pickResult.shortfall)}`)" />
      <!-- caveat 必须显示：这条结果不是自动扣减依据，不说清楚就会被当成可发量 -->
      <el-alert v-if="pickResult.caveat" type="warning" :closable="false" class="caveat" show-icon
                :title="String(pickResult.caveat)" />
      <el-table :data="pickLines" size="small" border>
        <el-table-column prop="batchNo" label="批号" width="140" />
        <el-table-column label="有效期至" width="120">
          <template #default="{ row }">{{ fmt(row.expireOn) }}</template>
        </el-table-column>
        <el-table-column prop="locationCode" label="库房" width="120" />
        <el-table-column label="该批可用" width="100" align="right">
          <template #default="{ row }">{{ num(row.available) }}</template>
        </el-table-column>
        <el-table-column label="建议取用" width="100" align="right">
          <template #default="{ row }">{{ num(row.picked) }}</template>
        </el-table-column>
        <template #empty>该药品在批次层无可用余量</template>
      </el-table>
    </template>
  </el-dialog>

  <!-- ===================== 批次余额明细 ===================== -->
  <el-drawer v-model="batchStockDrawer" size="60%" :title="`批次余额明细 — ${batchStockTitle}`">
    <el-table :data="batchStocks" size="small" border height="calc(100vh - 160px)">
      <el-table-column prop="location_code" label="库房/药柜" width="140" />
      <el-table-column label="余量" width="100" align="right">
        <template #default="{ row }">{{ num(row.qty) }}</template>
      </el-table-column>
      <el-table-column label="有效期至" width="120">
        <template #default="{ row }">{{ fmt(row.expire_on) }}</template>
      </el-table-column>
      <el-table-column label="效期状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="expiryTagType(String(row.expiryStatus))">
            {{ expiryText(String(row.expiryStatus)) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="supplier" label="供应商" min-width="140" show-overflow-tooltip />
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }">{{ fmt(row.updated_at) }}</template>
      </el-table-column>
      <template #empty>该批次在任何库房均无余额行</template>
    </el-table>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../../api/client'
import { fmtDateTime } from '../../../utils/date'

type Row = Record<string, unknown>
interface Drug { id: number; code: string; name: string; spec?: string; unit?: string }

const FLOW_TYPES = [
  { value: 'IN', label: '入库' },
  { value: 'OUT', label: '出库' },
  { value: 'RET', label: '退回' },
  { value: 'ADJ', label: '调整' },
  { value: 'SCRAP', label: '报损' },
]

const tab = ref('balance')
const loading = ref(false)
const saving = ref(false)

const drugId = ref<number | undefined>()
const locationCode = ref('')
const drugs = ref<Drug[]>([])
const drugLoading = ref(false)

/** /balance 的三段返回体：rows（每行三个数 + drift）、coverage、caveats——三段都要显示出来 */
const balanceRows = ref<Row[]>([])
const coverage = ref<Row | null>(null)
const caveats = ref<string[]>([])
const settings = ref<Row | null>(null)

const batches = ref<Row[]>([])
const batchNo = ref('')
const expiringInDays = ref<number | undefined>()

const flows = ref<Row[]>([])
const flowType = ref('')
const range = ref<[string, string] | null>(null)

const reconcile = ref<Row | null>(null)
const reconcileRows = computed<Row[]>(() => (reconcile.value?.rows ?? []) as Row[])

const driftRows = computed(() => balanceRows.value.filter((r) => num(r.drift) !== 0))
/** 已纳管却对不上：真正要人去查实物的那几行 */
const managedDriftRows = computed(() => driftRows.value.filter((r) => r.batchManaged === true))
/** 未纳管：drift 等于全部汇总库存，是迁移进度问题而非账错 */
const unmanagedDriftRows = computed(() => driftRows.value.filter((r) => r.batchManaged !== true))
const ratePct = computed(() => Number(coverage.value?.batchCoverageRatePct ?? 0))

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

function expiryText(s: string): string {
  return ({ OK: '正常', NEAR_EXPIRY: '近效期', EXPIRED: '已过期', UNKNOWN: '未录效期' } as Record<string, string>)[s] ?? s
}

function expiryTagType(s: string): 'success' | 'warning' | 'danger' | 'info' {
  return s === 'EXPIRED' ? 'danger' : s === 'NEAR_EXPIRY' ? 'warning' : s === 'UNKNOWN' ? 'info' : 'success'
}

function flowText(s: string): string {
  return FLOW_TYPES.find((t) => t.value === s)?.label ?? s
}

function flowTagType(s: string): 'success' | 'warning' | 'danger' | 'info' {
  return s === 'IN' ? 'success' : s === 'SCRAP' ? 'danger' : s === 'OUT' ? 'warning' : 'info'
}

/**
 * 只把「已纳管却对不上」标红——那才是要去查实物的行。
 * 未纳管的药 drift 必然≠0（批次层为 0），全标红等于满屏红字，
 * 药师两天就对红色脱敏，真正的不平行反而看不见了。
 */
function driftRowClass({ row }: { row: Row }): string {
  return num(row.drift) !== 0 && row.batchManaged === true ? 'row-drift' : ''
}

function reconcileRowClass({ row }: { row: Row }): string {
  return row.balanced === true ? '' : 'row-drift'
}

async function searchDrugs(keyword: string) {
  drugLoading.value = true
  try {
    drugs.value = (await client.get('/masterdata/drugs', { params: { keyword } })).data.data as Drug[]
  } finally {
    drugLoading.value = false
  }
}

async function loadSettings() {
  settings.value = (await client.get('/pharm/stock/settings')).data.data as Row
}

async function loadBalance() {
  const d = (await client.get('/pharm/stock/balance', {
    params: { drugId: drugId.value, locationCode: locationCode.value || undefined, limit: 200 },
  })).data.data as Row
  balanceRows.value = (d.rows ?? []) as Row[]
  coverage.value = (d.coverage ?? null) as Row | null
  caveats.value = (d.caveats ?? []) as string[]
}

async function loadBatches() {
  batches.value = (await client.get('/pharm/stock/batches', {
    params: {
      drugId: drugId.value,
      batchNo: batchNo.value || undefined,
      expiringInDays: expiringInDays.value ?? undefined,
      limit: 200,
    },
  })).data.data as Row[]
}

async function loadFlows() {
  flows.value = (await client.get('/pharm/stock/flows', {
    params: {
      drugId: drugId.value,
      flowType: flowType.value || undefined,
      from: range.value?.[0],
      to: range.value?.[1],
      limit: 200,
    },
  })).data.data as Row[]
}

async function loadReconcile() {
  reconcile.value = (await client.get('/pharm/stock/reconcile', {
    params: { drugId: drugId.value, limit: 200 },
  })).data.data as Row
}

async function onTabChange() {
  await reload()
}

async function reload() {
  loading.value = true
  try {
    if (tab.value === 'balance') await loadBalance()
    else if (tab.value === 'batches') await loadBatches()
    else if (tab.value === 'flows') await loadFlows()
    else await loadReconcile()
  } finally {
    loading.value = false
  }
}

/* ---------------- 入库 ---------------- */
const stockInDialog = ref(false)
const stockInForm = reactive({
  drugId: undefined as number | undefined,
  qty: 1,
  batchNo: '',
  producedOn: '',
  expireOn: '',
  supplier: '',
  purchasePrice: '',
  purchaseNo: '',
  locationCode: '',
})

async function submitStockIn() {
  if (!stockInForm.drugId) {
    ElMessage.warning('请选择药品')
    return
  }
  saving.value = true
  try {
    const d = (await client.post('/pharm/stock/stock-in', {
      drugId: stockInForm.drugId,
      qty: stockInForm.qty,
      batchNo: stockInForm.batchNo || undefined,
      producedOn: stockInForm.producedOn || undefined,
      expireOn: stockInForm.expireOn || undefined,
      supplier: stockInForm.supplier || undefined,
      purchasePrice: stockInForm.purchasePrice || undefined,
      purchaseNo: stockInForm.purchaseNo || undefined,
      locationCode: stockInForm.locationCode || undefined,
    })).data.data as Row
    const warns = (d.warnings ?? []) as string[]
    // warn 档的 warnings 必须弹出来给人看：后端照常入账了，问题只在这个数组里
    if (warns.length) {
      await ElMessageBox.alert(warns.map((w) => `· ${w}`).join('\n'),
        `已入账（批次 ${d.batchNo ?? '未录批号'}，结存 ${num(d.batchQtyAfter)}），但有 ${warns.length} 条告警`,
        { type: 'warning', confirmButtonText: '我已知悉' })
    } else {
      ElMessage.success(`已入账：批次结存 ${num(d.batchQtyAfter)}，流水 ${d.flowNo}`)
    }
    stockInDialog.value = false
    await reload()
  } finally {
    saving.value = false
  }
}

/* ---------------- 报损 ---------------- */
const scrapDialog = ref(false)
const scrapForm = reactive({
  batchId: 0, drugName: '', batchNo: '', expireOn: '', onHand: 0,
  qty: 1, reason: '', locationCode: '',
})

function openScrap(row: Row) {
  Object.assign(scrapForm, {
    batchId: Number(row.id),
    drugName: String(row.drug_name ?? ''),
    batchNo: String(row.batch_no ?? ''),
    expireOn: row.expire_on ? String(row.expire_on) : '',
    onHand: num(row.on_hand),
    qty: 1,
    reason: '',
    locationCode: '',
  })
  scrapDialog.value = true
}

async function submitScrap() {
  if (!scrapForm.reason.trim()) {
    ElMessage.warning('报损原因必填')
    return
  }
  saving.value = true
  try {
    const d = (await client.post('/pharm/stock/scrap', {
      batchId: scrapForm.batchId,
      qty: scrapForm.qty,
      reason: scrapForm.reason,
      locationCode: scrapForm.locationCode || undefined,
    })).data.data as Row
    ElMessage.success(`已报损：批次余额 ${num(d.batchQtyAfter)}，汇总库存 ${num(d.aggregateStockAfter)}`)
    scrapDialog.value = false
    await reload()
  } finally {
    saving.value = false
  }
}

/* ---------------- 取批预览 ---------------- */
const pickDialog = ref(false)
const picking = ref(false)
const pickForm = reactive({ drugId: undefined as number | undefined, qty: 1 })
const pickResult = ref<Row | null>(null)
const pickLines = computed<Row[]>(() => (pickResult.value?.lines ?? []) as Row[])

function openPickPreview() {
  pickResult.value = null
  pickForm.drugId = drugId.value
  pickDialog.value = true
}

async function runPickPreview() {
  if (!pickForm.drugId) {
    ElMessage.warning('请选择药品')
    return
  }
  picking.value = true
  try {
    pickResult.value = (await client.get('/pharm/stock/pick-preview', {
      params: { drugId: pickForm.drugId, qty: pickForm.qty, locationCode: locationCode.value || undefined },
    })).data.data as Row
  } finally {
    picking.value = false
  }
}

/* ---------------- 批次余额明细 ---------------- */
const batchStockDrawer = ref(false)
const batchStockTitle = ref('')
const batchStocks = ref<Row[]>([])

async function showBatchStock(row: Row) {
  batchStockTitle.value = `${row.drug_name} / ${row.batch_no ?? '未录批号'}`
  batchStocks.value = []
  batchStockDrawer.value = true
  batchStocks.value = (await client.get('/pharm/stock/balance/by-batch', {
    params: { batchId: Number(row.id), limit: 200 },
  })).data.data as Row[]
}

onMounted(async () => {
  await loadSettings()
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
:deep(.row-drift) { background: var(--el-color-danger-light-9); }
</style>
