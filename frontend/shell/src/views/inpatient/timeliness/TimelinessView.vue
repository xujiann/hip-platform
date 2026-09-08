<template>
  <el-card>
    <template #header>
      <span style="font-weight: 600">病历书写时限质控</span>
      <el-tag type="info" size="small" style="margin-left: 10px">
        《病历书写基本规范》（卫医政发〔2010〕11 号）
      </el-tag>
      <el-tag :type="gateTagType" size="small" style="margin-left: 8px">
        gate {{ cfg?.gateKey ?? 'emr.gate.timeliness' }} = {{ gate || '—' }}（{{ gateCn }}）
      </el-tag>
      <el-button size="small" style="margin-left: 8px" :loading="cfgLoading" @click="loadConfig">
        刷新配置
      </el-button>
    </template>

    <!-- ========== gate 档位：院方要看得见现在拦不拦 ========== -->
    <el-alert :type="gateAlertType" show-icon :closable="false" class="caveat"
              :title="`当前 gate 档位 ${gate || '未读取'}：${gateCn}`" />
    <!-- gate 键没登记进 sys_config 时，它是「调不动」的——不说清楚，院方会以为改了没生效 -->
    <el-alert v-if="cfg && cfg.gateKeyRegistered === false" type="warning" show-icon :closable="false"
              class="caveat" title="本 gate 档位当前调不动">
      <div class="pre">{{ plain(cfg.gateKeyNote) }}</div>
    </el-alert>

    <el-collapse v-if="cfg" class="caveat">
      <el-collapse-item title="口径与纪律说明（本页所有数字的前提，建议先读）" name="notes">
        <el-alert type="info" :closable="false" class="caveat" title="超时率的分子分母口径">
          <div class="pre">{{ plain(cfg.rateNote) }}</div>
        </el-alert>
        <el-descriptions :column="1" border size="small" class="caveat" title="判定结论码含义">
          <el-descriptions-item v-for="(v, k) in cfg.statusMeaning" :key="k" :label="String(k)">
            <span class="pre">{{ plain(v) }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <ul class="notelist">
          <li v-for="(n, i) in cfg.notes ?? []" :key="i" class="pre">{{ plain(n) }}</li>
        </ul>
      </el-collapse-item>
    </el-collapse>

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <!-- ============================================================ -->
      <!-- 一、超时率统计                                                -->
      <!-- ============================================================ -->
      <el-tab-pane label="超时率统计" name="indicators">
        <div class="bar">
          <el-date-picker v-model="range" type="daterange" size="small" unlink-panels
                          value-format="YYYY-MM-DD" range-separator="至"
                          start-placeholder="起始日" end-placeholder="截止日" style="width: 250px" />
          <el-select v-model="pickedRule" size="small" clearable placeholder="全部规则"
                     style="width: 300px; margin-left: 8px">
            <el-option v-for="r in catalog" :key="r.code" :value="r.code" :label="`${r.code}　${r.name}`">
              <span>{{ r.code }}　{{ r.name }}</span>
              <el-tag v-if="!r.available" type="danger" size="small" style="margin-left: 6px">缺数据源</el-tag>
              <el-tag v-else-if="!r.enabled" type="info" size="small" style="margin-left: 6px">已停用</el-tag>
            </el-option>
          </el-select>
          <el-select v-model="groupBy" size="small" style="width: 130px; margin-left: 8px">
            <el-option value="dept" label="按科室" />
            <el-option value="doctor" label="按责任医师" />
            <el-option value="ward" label="按病区" />
            <el-option value="none" label="不分组" />
          </el-select>
          <el-button type="primary" size="small" :loading="indLoading" style="margin-left: 8px"
                     @click="loadIndicators">查询</el-button>
        </div>

        <template v-if="indBody">
          <div class="sub">
            统计区间 {{ indBody.from }} 至 {{ indBody.to }}（{{ indBody.days }} 天）
            ｜ 在用规则 {{ (indBody.rulesInPlay ?? []).length }} 条
          </div>
          <WarnList :warnings="indBody.warnings" />
          <SkippedRules :skipped="indBody.skippedRules" />

          <!-- 全窗口锚点可得性：锚点覆盖率低时算出来的超时率本身就不可信 -->
          <el-alert type="warning" show-icon :closable="false" class="caveat"
                    title="时间锚点可得性（请先看这一段再看任何超时率）" />
          <el-descriptions v-if="indBody.coverage" :column="4" border size="small" class="caveat">
            <el-descriptions-item v-for="k in coverageKeys" :key="k" :label="zh(k)">
              {{ fmt(indBody.coverage[k]) }}
            </el-descriptions-item>
          </el-descriptions>
          <el-alert v-if="indBody.coverage && indBody.coverage.note" type="info" :closable="false"
                    class="caveat">
            <div class="pre">{{ plain(indBody.coverage.note) }}</div>
          </el-alert>

          <!-- 逐规则 -->
          <div v-for="ind in indBody.indicators" :key="ind.code" class="indicator">
            <div class="ind-head">
              <span class="ind-code">{{ ind.code }}</span>
              <span class="ind-name">{{ ind.name }}</span>
              <RuleTags :rule="ind" />
              <span v-if="ind.available && ind.executable && ind.enabled" style="float: right">
                <el-button link type="primary" size="small" @click="drillDown(ind)">查看超时清单</el-button>
                <el-button link type="primary" size="small" @click="openRule(ind.code)">规则详情</el-button>
              </span>
            </div>
            <SourceLine :rule="ind" />

            <!-- 缺数据源：只说为什么没有，绝不渲染表格、绝不显示成 0 -->
            <el-alert v-if="!ind.available" type="error" show-icon :closable="false" class="caveat"
                      title="本平台算不出这条指标——不给 0、不给近似值、不渲染表格">
              <div class="pre">{{ plain(ind.unavailableReason) }}</div>
              <div v-if="ind.missingFields && ind.missingFields.length" class="missing">
                <div class="missing-t">补齐以下字段后本指标才可用：</div>
                <ul>
                  <li v-for="(mf, i) in ind.missingFields" :key="i" class="pre">{{ plain(mf) }}</li>
                </ul>
              </div>
              <div v-if="ind.note" class="pre dim">{{ plain(ind.note) }}</div>
            </el-alert>

            <!-- 库里声明的锚点与代码实际执行的锚点对不上：降级，同样不给数字 -->
            <el-alert v-else-if="!ind.executable" type="error" show-icon :closable="false" class="caveat"
                      title="锚点口径对不上，该规则已降级为不可执行——不按其中任何一个蒙着算">
              <div class="pre">{{ plain(ind.note) }}</div>
            </el-alert>

            <el-alert v-else-if="!ind.enabled" type="info" show-icon :closable="false" class="caveat"
                      title="该规则当前停用，不进统计">
              <div class="pre">{{ plain(ind.note) }}</div>
            </el-alert>

            <template v-else>
              <!-- 口径警示原样上屏。INP_DISCHARGE_24H 的这一段说明「本指标超时率系统性偏高」 -->
              <el-alert v-if="ind.caliberNote" type="warning" show-icon :closable="false" class="caveat"
                        title="本规则口径警示（原文，看数前必读）">
                <div class="pre">{{ plain(ind.caliberNote) }}</div>
              </el-alert>

              <el-descriptions v-if="ind.summary" :column="3" border size="small" class="caveat"
                               title="全院汇总">
                <el-descriptions-item label="超时率（超时数 / 可判定数）">
                  <b :class="ind.summary.overtimeRatePct === null ? 'dim' : 'hot'">
                    {{ rateFrac(ind.summary.overtime, ind.summary.judgeable, ind.summary.overtimeRatePct) }}
                  </b>
                </el-descriptions-item>
                <el-descriptions-item label="及时率（及时数 / 可判定数）">
                  {{ rateFrac(ind.summary.onTime, ind.summary.judgeable, ind.summary.onTimeRatePct) }}
                </el-descriptions-item>
                <el-descriptions-item label="主体总数">{{ ind.summary.total }}</el-descriptions-item>
                <el-descriptions-item label="ON_TIME 及时">{{ ind.summary.onTime }}</el-descriptions-item>
                <el-descriptions-item label="LATE 已写超时">{{ ind.summary.late }}</el-descriptions-item>
                <el-descriptions-item label="MISSING_OVERDUE 超时未写">
                  {{ ind.summary.missingOverdue }}
                </el-descriptions-item>
                <el-descriptions-item label="PENDING 未到时限（不进分母）">
                  {{ ind.summary.pending }}
                </el-descriptions-item>
                <el-descriptions-item label="带口径提示行">{{ ind.summary.warning }}</el-descriptions-item>
                <el-descriptions-item label="判定阈值">
                  {{ ind.limitMinutes }} 分钟（{{ (ind.limitMinutes / 60).toFixed(1) }} 小时）
                </el-descriptions-item>
              </el-descriptions>

              <!-- anomalyNegative 单独显示并解释，绝不混进及时率 -->
              <el-alert v-if="ind.summary" :type="ind.summary.anomalyNegative > 0 ? 'error' : 'info'"
                        show-icon :closable="false" class="caveat"
                        :title="`ANOMALY_NEGATIVE 终点早于起点：${ind.summary.anomalyNegative} 例（既不进分子也不进分母）`">
                <div class="pre">{{ ANOMALY_NOTE }}</div>
                <div v-if="ind.summary.anomalyNegative > 0" class="pre">
                  本规则本窗口有 {{ ind.summary.anomalyNegative }} 例因「终点早于起点」被判为数据异常而出局。
                  这一批越大，说明本规则的锚点方向越不适用于本院的实际流程，
                  余下进入分母的例数越不能代表全院水平。
                </div>
              </el-alert>

              <!-- 锚点覆盖：一律「分子 / 分母（百分比）」，不给孤零零一个百分比 -->
              <el-descriptions v-if="ind.coverage" :column="2" border size="small" class="caveat"
                               title="本规则的锚点覆盖（分子 / 分母）">
                <el-descriptions-item label="起点锚点">
                  {{ ind.coverage.startAnchorCn ?? '—' }}
                  <span class="dim">（{{ ind.coverage.startAnchor ?? '—' }}）</span>
                </el-descriptions-item>
                <el-descriptions-item label="终点锚点">
                  {{ ind.coverage.endAnchorCn ?? '—' }}
                  <span class="dim">（{{ ind.coverage.endAnchor ?? '—' }}）</span>
                </el-descriptions-item>
                <el-descriptions-item label="有终点锚点 / 窗口内主体">
                  {{ rateFrac(ind.coverage.withEndAnchor, ind.coverage.subjectsInWindow,
                              ind.coverage.endAnchorFillRatePct) }}
                </el-descriptions-item>
                <el-descriptions-item label="真正可判定 / 窗口内主体">
                  {{ rateFrac(ind.coverage.judgeable, ind.coverage.subjectsInWindow,
                              ind.coverage.judgeableRatePct) }}
                </el-descriptions-item>
                <el-descriptions-item label="结论未定（PENDING）">
                  {{ ind.coverage.undetermined }}
                </el-descriptions-item>
                <el-descriptions-item label="数据异常出局（ANOMALY_NEGATIVE）">
                  {{ ind.coverage.anomalyNegative }}
                </el-descriptions-item>
                <el-descriptions-item label="带口径提示的主体数">{{ ind.coverage.caveatRows }}</el-descriptions-item>
                <el-descriptions-item v-if="ind.coverage.dischargedStatusWithoutTimestamp !== undefined"
                                      label="状态为已出院但无出院时刻">
                  {{ ind.coverage.dischargedStatusWithoutTimestamp }}
                </el-descriptions-item>
              </el-descriptions>
              <el-alert v-if="ind.coverage && ind.coverage.caveatNote" type="warning" :closable="false"
                        class="caveat">
                <div class="pre">{{ plain(ind.coverage.caveatNote) }}</div>
              </el-alert>
              <el-alert v-if="ind.coverage && ind.coverage.dischargedStatusNote" type="warning"
                        :closable="false" class="caveat">
                <div class="pre">{{ plain(ind.coverage.dischargedStatusNote) }}</div>
              </el-alert>
              <el-alert v-if="ind.coverage && ind.coverage.note" type="info" :closable="false" class="caveat">
                <div class="pre">{{ plain(ind.coverage.note) }}</div>
              </el-alert>

              <el-alert v-if="ind.groupNote" type="info" :closable="false" class="caveat">
                <div class="pre">{{ plain(ind.groupNote) }}</div>
              </el-alert>
              <el-table v-if="ind.rows && ind.rows.length" :data="ind.rows" size="small" border
                        max-height="380">
                <el-table-column prop="groupName" :label="groupLabel(ind.groupBy)" min-width="150">
                  <template #default="{ row }">
                    {{ row.groupName ?? (row.groupId === null ? '（未登记）' : row.groupId) }}
                  </template>
                </el-table-column>
                <el-table-column label="超时率（超时 / 可判定）" min-width="180">
                  <template #default="{ row }">
                    <span :class="row.overtimeRatePct === null ? 'dim' : 'hot'">
                      {{ rateFrac(row.overtime, row.judgeable, row.overtimeRatePct) }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column prop="total" label="主体总数" width="90" />
                <el-table-column prop="onTime" label="及时" width="80" />
                <el-table-column prop="late" label="已写超时" width="90" />
                <el-table-column prop="missingOverdue" label="超时未写" width="90" />
                <el-table-column prop="pending" label="未到时限" width="90" />
                <el-table-column label="异常出局" width="100">
                  <template #default="{ row }">
                    <span :class="row.anomalyNegative > 0 ? 'hot' : ''">{{ row.anomalyNegative }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </template>
          </div>
        </template>
        <el-empty v-else-if="!indLoading" description="请选择统计区间后查询" />
      </el-tab-pane>

      <!-- ============================================================ -->
      <!-- 二、超时清单                                                  -->
      <!-- ============================================================ -->
      <el-tab-pane label="超时清单" name="overdue">
        <div class="bar">
          <el-date-picker v-model="odRange" type="daterange" size="small" unlink-panels
                          value-format="YYYY-MM-DD" range-separator="至"
                          start-placeholder="起始日" end-placeholder="截止日" style="width: 250px" />
          <el-select v-model="odRule" size="small" clearable placeholder="全部在用规则"
                     style="width: 280px; margin-left: 8px">
            <el-option v-for="r in inPlayCatalog" :key="r.code" :value="r.code"
                       :label="`${r.code}　${r.name}`" />
          </el-select>
          <el-select v-model="odStatus" size="small" clearable placeholder="全部结论"
                     style="width: 190px; margin-left: 8px">
            <el-option v-for="s in statusCodes" :key="s" :value="s" :label="s" />
          </el-select>
          <el-checkbox v-model="odOverdueOnly" size="small" style="margin-left: 10px">
            只看已超时
          </el-checkbox>
          <el-button type="primary" size="small" :loading="odLoading" style="margin-left: 8px"
                     @click="loadOverdue(0)">查询</el-button>
        </div>
        <div class="sub">
          未勾「只看已超时」时会带出 PENDING（尚未到时限、结论未定）——那既不是及时也不是超时。
        </div>

        <template v-if="odBody">
          <WarnList :warnings="odBody.warnings" />
          <SkippedRules :skipped="odBody.skippedRules" />
          <!-- 截断不静默 -->
          <el-alert v-if="odBody.truncated" type="warning" show-icon :closable="false" class="caveat"
                    title="本次清单不完整（跨规则合并取数达到上限）">
            <div class="pre">
              达到上限的规则：{{ (odBody.truncatedRules ?? []).join('、') }}。
              盘点存量请在上方按单条规则筛选后再查——该路径不合并、不截断。
            </div>
          </el-alert>
          <div class="sub">
            {{ odBody.from }} 至 {{ odBody.to }}
            ｜ 合并候选 {{ odBody.mergedCandidateCount }} 条
            ｜ 本页 {{ odBody.matchedInPage }} 条
            <el-tag v-if="odBody.mergedAcrossRules" type="info" size="small" style="margin-left: 6px">
              跨规则合并
            </el-tag>
          </div>

          <el-table :data="odBody.items" size="small" border max-height="520" v-loading="odLoading">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="expand">
                  <el-descriptions :column="3" border size="small">
                    <el-descriptions-item label="规则">
                      {{ row.ruleCode }} {{ row.ruleName }}（{{ row.limitMinutes }} 分钟）
                    </el-descriptions-item>
                    <el-descriptions-item label="主体">
                      {{ row.subjectKind }} #{{ row.subjectId }} {{ row.subjectNo ?? '' }}
                    </el-descriptions-item>
                    <el-descriptions-item label="住院ID">{{ fmt(row.admissionId) }}</el-descriptions-item>
                    <el-descriptions-item label="起点时刻">{{ fmt(row.startAt) }}</el-descriptions-item>
                    <el-descriptions-item label="终点时刻">{{ fmt(row.endAt) }}</el-descriptions-item>
                    <el-descriptions-item label="应完成时刻">{{ fmt(row.dueAt) }}</el-descriptions-item>
                    <el-descriptions-item label="预警时刻">{{ fmt(row.warnAt) }}</el-descriptions-item>
                    <el-descriptions-item label="书写人">{{ fmt(row.writtenByName) }}</el-descriptions-item>
                    <el-descriptions-item label="病区">{{ fmt(row.wardName) }}</el-descriptions-item>
                  </el-descriptions>
                  <el-alert v-if="row.caveatText" type="warning" show-icon :closable="false"
                            class="caveat" :title="`口径提示 ${row.caveat}`">
                    <div class="pre">{{ plain(row.caveatText) }}</div>
                  </el-alert>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="ruleCode" label="规则" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">{{ row.ruleName }}</template>
            </el-table-column>
            <el-table-column prop="patientName" label="患者" width="100" />
            <el-table-column prop="subjectNo" label="主体编号" width="130" show-overflow-tooltip />
            <el-table-column prop="deptName" label="科室" width="120" show-overflow-tooltip />
            <el-table-column prop="dutyDoctorName" label="责任医师" width="100">
              <template #default="{ row }">{{ row.dutyDoctorName ?? '（未登记）' }}</template>
            </el-table-column>
            <el-table-column label="应完成时刻" width="140">
              <template #default="{ row }">{{ fmt(row.dueAt) }}</template>
            </el-table-column>
            <el-table-column label="已用时长" width="120">
              <template #default="{ row }">{{ minutesCn(row.elapsedMinutes) }}</template>
            </el-table-column>
            <!--
              超出时限 = 已用时长 − 该规则时限。**不直接显示后端的 overMinutes**：
              该字段名叫「over」，值却是 elapsed_minutes 原样（见 EmrTimelinessService.overMinutes），
              照着字面渲染会把「入院 30 小时才写、超时 6 小时」显示成「超时 30 小时」。
              这里用 elapsedMinutes 与 limitMinutes 两个已发布的数相减，口径与后端 due_at 完全一致
              （due_at = start_at + limit_min，limit_min 即 limitMinutes）。已写进 cross_lane。
            -->
            <el-table-column label="超出时限" width="120">
              <template #default="{ row }">
                <span v-if="!isOverdue(row.status)" class="dim">—</span>
                <span v-else-if="row.elapsedMinutes === null || row.elapsedMinutes === undefined"
                      class="dim">—</span>
                <span v-else class="hot">{{ minutesCn(row.elapsedMinutes - row.limitMinutes) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="结论" width="150">
              <template #default="{ row }">
                <el-tag :type="statusTag(row.status)" size="small">{{ row.status }}</el-tag>
                <el-tag v-if="row.caveat" type="warning" size="small" style="margin-left: 4px">
                  口径存疑
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="(odBody.items ?? []).length === 0" description="该条件下无记录" :image-size="60" />
          <div class="bar" style="justify-content: flex-end">
            <el-pagination size="small" layout="prev, pager, next, total" background
                           :total="odBody.mergedCandidateCount" :page-size="odBody.limit"
                           :current-page="Math.floor(odBody.offset / odBody.limit) + 1"
                           @current-change="gotoPage" />
          </div>
        </template>
        <el-empty v-else-if="!odLoading" description="请点击查询" />
      </el-tab-pane>

      <!-- ============================================================ -->
      <!-- 三、即将超时提醒                                              -->
      <!-- ============================================================ -->
      <el-tab-pane label="即将超时提醒" name="upcoming">
        <div class="bar">
          <el-select v-model="upRule" size="small" clearable placeholder="全部在用规则"
                     style="width: 280px">
            <el-option v-for="r in inPlayCatalog" :key="r.code" :value="r.code"
                       :label="`${r.code}　${r.name}`" />
          </el-select>
          <el-button type="primary" size="small" :loading="upLoading" style="margin-left: 8px"
                     @click="loadUpcoming">刷新</el-button>
        </div>

        <template v-if="upBody">
          <el-alert type="info" show-icon :closable="false" class="caveat" title="本列表的口径">
            <div class="pre">{{ plain(upBody.note) }}</div>
          </el-alert>
          <WarnList :warnings="upBody.warnings" />
          <SkippedRules :skipped="upBody.skippedRules" />
          <div class="sub">
            扫描区间 {{ upBody.scanFrom }} 至 {{ upBody.scanTo }}
            ｜ 命中 {{ upBody.candidateCount }} 条（当前显示 {{ (upBody.items ?? []).length }} 条）
          </div>
          <el-table :data="upBody.items" size="small" border max-height="520" v-loading="upLoading">
            <el-table-column prop="ruleName" label="规则" min-width="170" show-overflow-tooltip />
            <el-table-column prop="patientName" label="患者" width="100" />
            <el-table-column prop="subjectNo" label="主体编号" width="130" show-overflow-tooltip />
            <el-table-column prop="deptName" label="科室" width="120" show-overflow-tooltip />
            <el-table-column prop="dutyDoctorName" label="责任医师" width="100">
              <template #default="{ row }">{{ row.dutyDoctorName ?? '（未登记）' }}</template>
            </el-table-column>
            <el-table-column label="起点时刻" width="140">
              <template #default="{ row }">{{ fmt(row.startAt) }}</template>
            </el-table-column>
            <el-table-column label="应完成时刻" width="140">
              <template #default="{ row }">{{ fmt(row.dueAt) }}</template>
            </el-table-column>
            <el-table-column label="已用" width="110">
              <template #default="{ row }">{{ minutesCn(row.elapsedMinutes) }}</template>
            </el-table-column>
            <el-table-column label="剩余" width="110">
              <template #default="{ row }">
                <span class="hot">{{ minutesCn(row.limitMinutes - (row.elapsedMinutes ?? 0)) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="口径提示" min-width="140">
              <template #default="{ row }">
                <el-tooltip v-if="row.caveatText" :content="plain(row.caveatText)" placement="top">
                  <el-tag type="warning" size="small">{{ row.caveat }}</el-tag>
                </el-tooltip>
                <span v-else class="dim">—</span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="(upBody.items ?? []).length === 0" description="当前没有即将超时的病历"
                    :image-size="60" />
        </template>
        <el-empty v-else-if="!upLoading" description="请点击刷新" />
      </el-tab-pane>

      <!-- ============================================================ -->
      <!-- 四、规则维护                                                  -->
      <!-- ============================================================ -->
      <el-tab-pane label="规则维护" name="rules">
        <div class="bar">
          <el-button size="small" :loading="catLoading" @click="loadCatalog">刷新目录</el-button>
          <span v-if="catBody" class="sub" style="margin-left: 12px">
            共 {{ (catBody.rules ?? []).length }} 条
            ｜ <b class="hot">算不出来 {{ catBody.unavailableCount }} 条</b>
            ｜ <b class="hot">阈值待病案科确认 {{ catBody.unverifiedCount }} 条</b>
            ｜ 在用 {{ catBody.inPlayCount }} 条
          </span>
        </div>

        <template v-if="catBody">
          <el-alert type="warning" show-icon :closable="false" class="caveat"
                    title="未经病案科核对原文的阈值不得用于对外考核">
            <div class="pre">
              标「阈值待病案科确认」的规则，其时限阈值与条文号是按现行规范填写的<b>默认值</b>，
              未经病案科取原文逐条核对。这类默认值不是本院制度，确认前不要拿它考核医师或对外举证。
              核对完成后在本页「编辑」中把「已核对原文」置为是，系统会记下核对人与核对时刻。
            </div>
          </el-alert>
          <WarnList :warnings="catBody.warnings" />

          <el-table :data="catBody.rules" size="small" border max-height="560" v-loading="catLoading">
            <el-table-column prop="code" label="规则码" min-width="200" show-overflow-tooltip />
            <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
            <el-table-column prop="scope" label="域" width="70" />
            <el-table-column label="状态" min-width="230">
              <template #default="{ row }"><RuleTags :rule="row" /></template>
            </el-table-column>
            <el-table-column label="生效阈值" width="150">
              <template #default="{ row }">
                <span v-if="!row.available" class="dim">—（算不出来）</span>
                <span v-else>
                  {{ row.limitMinutes }} 分钟
                  <el-tooltip v-if="row.configKey" :content="plain(row.limitSourceNote)" placement="top">
                    <el-tag type="info" size="small">来自配置键</el-tag>
                  </el-tooltip>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="预警点" width="90">
              <template #default="{ row }">{{ row.warnRatioPct }}%</template>
            </el-table-column>
            <el-table-column label="出处（原文照显）" min-width="300">
              <template #default="{ row }">
                <div class="pre">{{ row.sourceDoc ?? '—' }}</div>
                <div class="pre" :class="row.sourceVerified ? '' : 'hot'">{{ row.sourceArticle ?? '—' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openRule(row.code)">详情</el-button>
                <el-button v-if="canEdit" link type="primary" size="small" @click="openEdit(row)">
                  编辑
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </el-tab-pane>

      <!-- ============================================================ -->
      <!-- 五、锚点体检（ADMIN / QUALITY / OPERATION）                    -->
      <!-- ============================================================ -->
      <el-tab-pane v-if="canSeeAnchors" label="锚点体检" name="anchors">
        <div class="bar">
          <el-button size="small" :loading="anLoading" @click="loadAnchors">重新体检</el-button>
          <span class="sub" style="margin-left: 12px">
            填充率是全表顺序扫描，非热路径，表大时请错峰调用。
          </span>
        </div>
        <template v-if="anBody">
          <el-alert type="info" show-icon :closable="false" class="caveat"
                    title="列级事实（列在不在、有没有值），不是规则级结论">
            <div class="pre">{{ plain(anBody.note) }}</div>
          </el-alert>
          <div v-for="r in anBody.rules" :key="r.ruleCode" class="indicator">
            <div class="ind-head">
              <span class="ind-code">{{ r.ruleCode }}</span>
              <span class="ind-name">{{ r.ruleName }}</span>
              <RuleTags :rule="anchorAsRule(r)" />
            </div>
            <el-alert v-if="!r.executable && r.available" type="error" show-icon :closable="false"
                      class="caveat" title="库里声明的锚点与代码实际执行的锚点对不上">
              <div class="pre">{{ plain(r.anchorNote) }}</div>
            </el-alert>
            <el-alert v-if="!r.available" type="error" show-icon :closable="false" class="caveat"
                      title="该规则不可用（原因照抄 unavailable_reason 原文）">
              <div class="pre">{{ plain(r.unavailableReason) }}</div>
              <ul v-if="r.missingFields && r.missingFields.length">
                <li v-for="(mf, i) in r.missingFields" :key="i" class="pre">{{ plain(mf) }}</li>
              </ul>
            </el-alert>
            <el-descriptions :column="2" border size="small" class="caveat">
              <el-descriptions-item label="声明的起点锚点">
                <div class="pre">{{ r.declaredStartAnchorCn ?? '—' }}</div>
                <div class="pre dim">{{ r.declaredStartAnchor ?? '—' }}</div>
              </el-descriptions-item>
              <el-descriptions-item label="声明的终点锚点">
                <div class="pre">{{ r.declaredEndAnchorCn ?? '—' }}</div>
                <div class="pre dim">{{ r.declaredEndAnchor ?? '—' }}</div>
              </el-descriptions-item>
              <el-descriptions-item label="实际执行的起点锚点">
                <span class="pre" :class="mismatch(r.declaredStartAnchor, r.executedStartAnchor) ? 'hot' : ''">
                  {{ r.executedStartAnchor ?? '—（本规则不可执行）' }}
                </span>
              </el-descriptions-item>
              <el-descriptions-item label="实际执行的终点锚点">
                <span class="pre" :class="mismatch(r.declaredEndAnchor, r.executedEndAnchor) ? 'hot' : ''">
                  {{ r.executedEndAnchor ?? '—（本规则不可执行）' }}
                </span>
              </el-descriptions-item>
              <el-descriptions-item label="起点列体检"><ColumnFacts :facts="r.startColumn" /></el-descriptions-item>
              <el-descriptions-item label="终点列体检"><ColumnFacts :facts="r.endColumn" /></el-descriptions-item>
            </el-descriptions>
          </div>
        </template>
        <el-empty v-else-if="!anLoading" description="请点击体检" />
      </el-tab-pane>
    </el-tabs>
  </el-card>

  <!-- ================= 规则详情抽屉 ================= -->
  <el-drawer v-model="ruleDrawer" size="62%" :title="`规则详情 ${ruleDetailCode}`">
    <template v-if="ruleDetail">
      <div class="ind-head">
        <span class="ind-code">{{ ruleDetail.rule.code }}</span>
        <span class="ind-name">{{ ruleDetail.rule.name }}</span>
        <RuleTags :rule="ruleDetail.rule" />
      </div>
      <WarnList :warnings="ruleDetail.warnings" />
      <el-alert v-if="!ruleDetail.rule.sourceVerified" type="warning" show-icon :closable="false"
                class="caveat" title="阈值待病案科确认——不要把未经确认的默认值当成本院制度">
        <div class="pre">{{ plain(ruleDetail.rule.sourceVerifiedNote) }}</div>
      </el-alert>
      <el-alert v-if="!ruleDetail.rule.available" type="error" show-icon :closable="false" class="caveat"
                title="本规则算不出来——不给 0、不给近似值">
        <div class="pre">{{ plain(ruleDetail.rule.unavailableReason) }}</div>
        <ul v-if="ruleDetail.rule.missingFields && ruleDetail.rule.missingFields.length">
          <li v-for="(mf, i) in ruleDetail.rule.missingFields" :key="i" class="pre">{{ plain(mf) }}</li>
        </ul>
      </el-alert>

      <el-descriptions :column="2" border size="small" class="caveat">
        <el-descriptions-item label="生效阈值">
          {{ ruleDetail.rule.limitMinutes }} 分钟
        </el-descriptions-item>
        <el-descriptions-item label="阈值来源">{{ ruleDetail.rule.limitSource ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="配置键">{{ ruleDetail.rule.configKey ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="回落值">
          {{ ruleDetail.rule.fallbackLimitMinutes ?? '—' }} 分钟
        </el-descriptions-item>
        <el-descriptions-item label="预警触发点">{{ ruleDetail.rule.warnRatioPct }}%</el-descriptions-item>
        <el-descriptions-item label="出处生效日">
          {{ ruleDetail.rule.sourceEffectiveFrom ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="出处文件" :span="2">
          {{ ruleDetail.rule.sourceDoc ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="条文号（原文照显）" :span="2">
          <span :class="ruleDetail.rule.sourceVerified ? '' : 'hot'">
            {{ ruleDetail.rule.sourceArticle ?? '—' }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="起点锚点" :span="2">
          {{ ruleDetail.rule.startAnchorCn ?? '—' }}
          <span class="dim">（{{ ruleDetail.rule.startAnchor ?? '—' }}）</span>
        </el-descriptions-item>
        <el-descriptions-item label="终点锚点" :span="2">
          {{ ruleDetail.rule.endAnchorCn ?? '—' }}
          <span class="dim">（{{ ruleDetail.rule.endAnchor ?? '—' }}）</span>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert v-if="ruleDetail.rule.limitSourceNote" type="info" :closable="false" class="caveat">
        <div class="pre">{{ plain(ruleDetail.rule.limitSourceNote) }}</div>
      </el-alert>
      <el-alert v-if="ruleDetail.rule.remark" type="warning" show-icon :closable="false" class="caveat"
                title="口径备注（原文）">
        <div class="pre">{{ plain(ruleDetail.rule.remark) }}</div>
      </el-alert>
      <el-alert v-if="ruleDetail.rule.anchorNote" type="info" :closable="false" class="caveat">
        <div class="pre">{{ plain(ruleDetail.rule.anchorNote) }}</div>
      </el-alert>
      <el-alert type="info" :closable="false" class="caveat" title="哪些字段不开放修改，为什么">
        <div class="pre">{{ plain(ruleDetail.editableNote) }}</div>
      </el-alert>

      <div class="sub" style="margin-top: 10px; font-weight: 600">变更留痕（新到旧）</div>
      <el-table :data="ruleDetail.changeLog" size="small" border max-height="300">
        <el-table-column prop="field" label="字段" width="130" />
        <el-table-column prop="old_value" label="原值" min-width="140" show-overflow-tooltip />
        <el-table-column prop="new_value" label="新值" min-width="140" show-overflow-tooltip />
        <el-table-column prop="reason" label="变更原因" min-width="200" show-overflow-tooltip />
        <el-table-column prop="changed_by_name" label="操作人" width="100" />
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ fmt(row.changed_at) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="(ruleDetail.changeLog ?? []).length === 0" description="该规则自建立以来未被修改过"
                :image-size="60" />
    </template>
  </el-drawer>

  <!-- ================= 规则编辑 ================= -->
  <el-dialog v-model="editDialog" title="修改时限规则" width="640px">
    <el-alert type="warning" show-icon :closable="false" class="caveat"
              title="这里改的是法定时限阈值——每个字段的变更都会留痕，变更原因必填" />
    <el-alert v-if="editRow && editRow.configKey" type="info" show-icon :closable="false" class="caveat">
      <div class="pre">{{ plain(editRow.limitSourceNote) }}</div>
    </el-alert>
    <el-form v-if="editRow" label-width="180px" size="small">
      <el-form-item label="规则">{{ editRow.code }}　{{ editRow.name }}</el-form-item>
      <el-form-item label="时限（分钟）">
        <el-input-number v-model="editForm.limitMinutes" :min="1" :max="527040" />
      </el-form-item>
      <el-form-item label="预警触发点（%）">
        <el-input-number v-model="editForm.warnRatioPct" :min="1" :max="99" />
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="editForm.enabled" :disabled="!editRow.available" />
        <span v-if="!editRow.available" class="dim" style="margin-left: 8px">
          该规则缺时间锚点、算不出来，不能启用
        </span>
      </el-form-item>
      <el-form-item label="已由病案科核对原文">
        <el-switch v-model="editForm.sourceVerified" />
        <span class="dim" style="margin-left: 8px">
          置为「是」表示已取规范原文逐条核对过阈值与条文号，系统会记下核对人与核对时刻
        </span>
      </el-form-item>
      <el-form-item label="口径备注">
        <el-input v-model="editForm.remark" type="textarea" :rows="4" maxlength="2000" show-word-limit />
      </el-form-item>
      <el-form-item label="变更原因（必填）">
        <el-input v-model="editForm.reason" type="textarea" :rows="2" maxlength="500" show-word-limit
                  placeholder="改法定时限没有原因，事后就说不清当时为什么是这个值" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="editDialog = false">取消</el-button>
      <el-button type="primary" size="small" :loading="editSaving" @click="saveRule">保存并留痕</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, h, defineComponent, type PropType } from 'vue'
import { ElMessage, ElTag, ElAlert } from 'element-plus'
import client from '../../../api/client'
import { fmtDateTime, localDateOffset, todayLocal } from '../../../utils/date'
import { useAuthStore } from '../../../stores/auth'

/* ==================================================================
 * 契约说明（照 EmrTimelinessService 源码逐键核过，不是猜的）
 * ------------------------------------------------------------------
 * 明细行（overdue / upcoming 的 items）走 decorate()，**全部驼峰**。
 * 汇总行（indicators 的 summary / rows）走 summarize()，**全部驼峰**。
 * 但有两处例外，是 JdbcTemplate 直出、没有过转换层的**蛇形**：
 *   1. indicators.coverage（全窗口锚点可得性）——globalCoverage() 里
 *      `m.putAll(adm/dis/rec/con)` 把原始列名直接铺进来：admissions、
 *      with_admit_at、first_progress_typed… 同一个对象里还混着一个驼峰的
 *      dischargedStatusWithoutTimestamp。**这一段必须按蛇形取键。**
 *   2. /rules/{code} 的 changeLog——changeLog() 直接 return queryForList：
 *      old_value / new_value / changed_by_name / changed_at 全是蛇形。
 * 其余（indicators[].coverage 单规则覆盖段）反而是驼峰，因为它走 ruleCoverage()。
 * ================================================================== */

type Row = Record<string, unknown>

interface RuleView {
  code: string
  name: string
  scope: string
  available: boolean
  enabled: boolean
  executable: boolean
  limitMinutes: number
  warnRatioPct: number
  startAnchor: string | null
  startAnchorCn: string | null
  endAnchor: string | null
  endAnchorCn: string | null
  sourceDoc: string | null
  sourceArticle: string | null
  sourceArticleRaw: string | null
  sourceEffectiveFrom: string | null
  sourceVerified: boolean
  sourceVerifiedNote?: string
  unavailableReason?: string | null
  limitSource?: string
  configKey?: string | null
  configUnit?: string | null
  fallbackLimitMinutes?: number
  configRawValue?: string | null
  limitSourceNote?: string
  missingFields?: string[]
  remark?: string | null
  anchorNote?: string | null
}

interface Summary {
  groupId?: number | string | null
  groupName?: string | null
  total: number
  onTime: number
  late: number
  missingOverdue: number
  pending: number
  anomalyNegative: number
  warning: number
  judgeable: number
  overtime: number
  overtimeRatePct: number | null
  onTimeRatePct: number | null
  rateNote?: string
}

interface RuleCoverage {
  startAnchor: string | null
  startAnchorCn: string | null
  endAnchor: string | null
  endAnchorCn: string | null
  subjectsInWindow: number
  withStartAnchor: number
  withEndAnchor: number
  endAnchorFillRatePct: number | null
  judgeable: number
  judgeableRatePct: number | null
  undetermined: number
  anomalyNegative: number
  caveatRows: number
  caveatNote?: string
  dischargedStatusWithoutTimestamp?: number
  dischargedStatusNote?: string
  note: string
}

interface Indicator extends RuleView {
  note?: string
  summary?: Summary
  rows?: Summary[]
  groupBy?: string
  groupNote?: string
  coverage?: RuleCoverage
  detailEndpoint?: string
  caliberNote?: string | null
}

interface SkipEntry {
  code: string
  name: string
  available: boolean
  enabled: boolean
  executable: boolean
  reason: string
}

interface IndicatorsBody {
  from: string
  to: string
  days: number
  gate: string
  rulesInPlay: string[]
  skippedRules: SkipEntry[]
  by: string
  coverage: Row
  rateNote: string
  indicators: Indicator[]
  warnings: string[]
}

interface OverdueBody {
  from: string
  to: string
  days: number
  gate: string
  rulesInPlay: string[]
  skippedRules: SkipEntry[]
  overdueOnly: boolean
  limit: number
  offset: number
  mergedAcrossRules: boolean
  truncated: boolean
  truncatedRules: string[]
  matchedInPage: number
  mergedCandidateCount: number
  items: Row[]
  warnings: string[]
}

interface UpcomingBody {
  now: string
  scanFrom: string
  scanTo: string
  gate: string
  rulesInPlay: string[]
  skippedRules: SkipEntry[]
  candidateCount: number
  items: Row[]
  note: string
  warnings: string[]
}

interface CatalogBody {
  gate: string
  gateKey: string
  rules: RuleView[]
  unverifiedCount: number
  unavailableCount: number
  inPlayCount: number
  warnings: string[]
}

interface ConfigBody {
  gate: string
  gateKey: string
  gateKeyRegistered: boolean
  gateKeyNote?: string
  maxWindowDays: number
  defaultWindowDays: number
  maxLimit: number
  statusMeaning: Record<string, string>
  rateNote: string
  unverifiedSuffix: string
  notes: string[]
}

interface ColumnFactsData {
  declared: boolean
  expression?: string | null
  resolved?: boolean
  table?: string
  column?: string
  exists?: boolean
  rowCount?: number
  filled?: number
  fillRatePct?: number | null
  note?: string
}

interface AnchorRule {
  ruleCode: string
  ruleName: string
  scope: string
  available: boolean
  enabled: boolean
  executable: boolean
  declaredStartAnchor: string | null
  declaredStartAnchorCn: string | null
  declaredEndAnchor: string | null
  declaredEndAnchorCn: string | null
  startColumn: ColumnFactsData
  endColumn: ColumnFactsData
  executedStartAnchor: string | null
  executedEndAnchor: string | null
  anchorNote: string | null
  unavailableReason: string | null
  missingFields: string[]
}

interface AnchorsBody {
  rules: AnchorRule[]
  note: string
  fillRateNote: string
}

interface RuleDetailBody {
  rule: RuleView
  changeLog: Row[]
  editableFields: string[]
  editableNote: string
  warnings: string[]
}

const auth = useAuthStore()
const roles = computed<string[]>(() => auth.user?.roles ?? [])
const canEdit = computed(() => roles.value.some((r) => r === 'ADMIN' || r === 'QUALITY'))
const canSeeAnchors = computed(() =>
  roles.value.some((r) => r === 'ADMIN' || r === 'QUALITY' || r === 'OPERATION'))

const ANOMALY_NOTE =
  'ANOMALY_NEGATIVE 是「终点时刻早于起点时刻」的数据异常，本平台把它单列、既不进分子也不进分母。'
  + '不单列会更糟：负数时长在及时率里会被算成「极其及时」，等于把一处数据缺陷伪装成一项优异指标。'
  + '但单列本身也有代价——被剔出去的那一批不是随机的，剔完之后剩下的分母可能已经不代表全院了，'
  + '所以这个数不为 0 时，上方的超时率要连同本条一起看。'

const tab = ref('indicators')

/* ---------------- 配置与目录 ---------------- */
const cfg = ref<ConfigBody | null>(null)
const cfgLoading = ref(false)
const catBody = ref<CatalogBody | null>(null)
const catLoading = ref(false)

const gate = computed(() => cfg.value?.gate ?? catBody.value?.gate ?? '')
const gateCn = computed(() => {
  switch (gate.value) {
    case 'off': return '不拦截、也不提示'
    case 'warn': return '有缺项照样放行，只记提示'
    case 'block': return '有缺项即拦截出院/归档'
    default: return '未读取到档位'
  }
})
const gateTagType = computed<'success' | 'warning' | 'danger' | 'info'>(() => {
  switch (gate.value) {
    case 'block': return 'danger'
    case 'warn': return 'warning'
    case 'off': return 'info'
    default: return 'info'
  }
})
const gateAlertType = computed<'success' | 'warning' | 'error' | 'info'>(() => {
  switch (gate.value) {
    case 'block': return 'error'
    case 'warn': return 'warning'
    default: return 'info'
  }
})

/** 结论码取自 /config 的 statusMeaning，不在前端写死第二份 */
const statusCodes = computed<string[]>(() => Object.keys(cfg.value?.statusMeaning ?? {}))

const catalog = computed<RuleView[]>(() => catBody.value?.rules ?? [])
const inPlayCatalog = computed<RuleView[]>(() =>
  catalog.value.filter((r) => r.available && r.executable && r.enabled))

async function loadConfig() {
  cfgLoading.value = true
  try {
    cfg.value = (await client.get<{ data: ConfigBody }>('/emr-timeliness/config')).data.data
  } finally {
    cfgLoading.value = false
  }
}

async function loadCatalog() {
  catLoading.value = true
  try {
    catBody.value = (await client.get<{ data: CatalogBody }>('/emr-timeliness/catalog')).data.data
  } finally {
    catLoading.value = false
  }
}

/* ---------------- 一、超时率统计 ---------------- */
/** 最近 30 天（含今天）——本地日期。不能用 toISOString()：北京 0–8 点切出来的是 UTC 的昨天（同 pathology/format.ts defaultRange） */
function defaultRange(): [string, string] {
  return [localDateOffset(-29), todayLocal()]
}

const range = ref<[string, string]>(defaultRange())
const pickedRule = ref('')
const groupBy = ref('dept')
const indBody = ref<IndicatorsBody | null>(null)
const indLoading = ref(false)

async function loadIndicators() {
  if (!range.value || range.value.length !== 2) {
    ElMessage.warning('请选择统计区间')
    return
  }
  indLoading.value = true
  try {
    const resp = await client.get<{ data: IndicatorsBody }>('/emr-timeliness/indicators', {
      params: {
        from: range.value[0], to: range.value[1],
        rule: pickedRule.value || undefined, by: groupBy.value,
      },
    })
    indBody.value = resp.data.data
  } finally {
    indLoading.value = false
  }
}

/**
 * 全窗口覆盖段的键序。**写死蛇形**，因为 globalCoverage() 是 putAll 原始列名。
 * note 单独用 alert 渲染，不进描述列表。
 */
const COVERAGE_KEYS = [
  'admissions', 'with_admit_at', 'with_duty_doctor', 'discharged_in_window',
  'dischargedStatusWithoutTimestamp', 'records', 'admission_records', 'first_progress_typed',
  'progress_records', 'discharge_records', 'round_records', 'round_level_typed',
  'with_writer', 'consults', 'consults_with_done_at',
]
const coverageKeys = computed<string[]>(() => {
  const c = indBody.value?.coverage
  if (!c) return []
  // 后端加了新键也要显示出来（漏显示等于把事实藏起来），故取「已知序 + 剩余」
  const known = COVERAGE_KEYS.filter((k) => k in c)
  const rest = Object.keys(c).filter((k) => k !== 'note' && !known.includes(k))
  return [...known, ...rest]
})

/* ---------------- 二、超时清单 ---------------- */
const odRange = ref<[string, string]>(defaultRange())
const odRule = ref('')
const odStatus = ref('')
const odOverdueOnly = ref(true)
const odBody = ref<OverdueBody | null>(null)
const odLoading = ref(false)

async function loadOverdue(offset: number) {
  // 「查看超时清单」会先切 tab 再取数，而切 tab 本身也会触发一次首次加载——
  // 不挡住就是同一份清单发两次请求（GET 不在 client 的在途去重范围内）
  if (odLoading.value) return
  odLoading.value = true
  try {
    const resp = await client.get<{ data: OverdueBody }>('/emr-timeliness/overdue', {
      params: {
        from: odRange.value?.[0], to: odRange.value?.[1],
        rule: odRule.value || undefined,
        status: odStatus.value || undefined,
        overdueOnly: odOverdueOnly.value,
        limit: 100, offset,
      },
    })
    odBody.value = resp.data.data
  } finally {
    odLoading.value = false
  }
}

function gotoPage(p: number) {
  void loadOverdue((p - 1) * (odBody.value?.limit ?? 100))
}

/** 只有 LATE / MISSING_OVERDUE 才谈得上「超出时限」，其余一律显示「—」而不是 0。 */
function isOverdue(status: unknown): boolean {
  return status === 'LATE' || status === 'MISSING_OVERDUE'
}

function drillDown(ind: Indicator) {
  odRule.value = ind.code
  odRange.value = [indBody.value?.from ?? range.value[0], indBody.value?.to ?? range.value[1]]
  odOverdueOnly.value = true
  odStatus.value = ''
  tab.value = 'overdue'
  void loadOverdue(0)
}

/* ---------------- 三、即将超时 ---------------- */
const upRule = ref('')
const upBody = ref<UpcomingBody | null>(null)
const upLoading = ref(false)

async function loadUpcoming() {
  if (upLoading.value) return
  upLoading.value = true
  try {
    const resp = await client.get<{ data: UpcomingBody }>('/emr-timeliness/upcoming', {
      params: { rule: upRule.value || undefined, limit: 200 },
    })
    upBody.value = resp.data.data
  } finally {
    upLoading.value = false
  }
}

/* ---------------- 四、规则详情与维护 ---------------- */
const ruleDrawer = ref(false)
const ruleDetailCode = ref('')
const ruleDetail = ref<RuleDetailBody | null>(null)

async function openRule(code: string) {
  ruleDetailCode.value = code
  ruleDetail.value = null
  ruleDrawer.value = true
  const resp = await client.get<{ data: RuleDetailBody }>(
    `/emr-timeliness/rules/${encodeURIComponent(code)}`)
  ruleDetail.value = resp.data.data
}

const editDialog = ref(false)
const editSaving = ref(false)
const editRow = ref<RuleView | null>(null)
/** 打开时的备注原文。只有真被改过才把 remark 发上去，避免无意抹掉口径警示。 */
const editOriginalRemark = ref('')
const editForm = ref({
  limitMinutes: 0,
  warnRatioPct: 0,
  enabled: false,
  sourceVerified: false,
  remark: '',
  reason: '',
})

/** 打开编辑框前先取整条规则。 */
async function openEdit(row: RuleView) {
  // /catalog 走的是 ruleBody(r, false)，**返回体里没有 remark**。
  // 若拿列表行直接预填，remark 会是 undefined→空串，用户不碰备注点保存也会把
  // remark 改成空——INP_DISCHARGE_24H 那段「超时率系统性偏高、不可直接上报」的口径警示
  // 会被一次无意的保存抹掉。所以这里必须先取 /rules/{code}（full=true，带 remark）。
  const full = (await client.get<{ data: RuleDetailBody }>(
    `/emr-timeliness/rules/${encodeURIComponent(row.code)}`)).data.data.rule
  editRow.value = full
  editOriginalRemark.value = full.remark ?? ''
  editForm.value = {
    // 后端比对的是 fallbackLimitMinutes（规则表里的回落值），不是生效阈值——
    // 拿生效阈值预填会让「什么都没改」被当成一次变更
    limitMinutes: full.fallbackLimitMinutes ?? full.limitMinutes,
    warnRatioPct: full.warnRatioPct,
    enabled: full.enabled,
    sourceVerified: full.sourceVerified,
    remark: editOriginalRemark.value,
    reason: '',
  }
  editDialog.value = true
}

async function saveRule() {
  if (!editRow.value) return
  if (!editForm.value.reason.trim()) {
    ElMessage.warning('变更原因必填')
    return
  }
  editSaving.value = true
  try {
    await client.put(`/emr-timeliness/rules/${encodeURIComponent(editRow.value.code)}`, {
      limitMinutes: editForm.value.limitMinutes,
      warnRatioPct: editForm.value.warnRatioPct,
      enabled: editForm.value.enabled,
      sourceVerified: editForm.value.sourceVerified,
      // 没动过就不发：后端对 remark 只判 null，发一个与原值不同的串就会真的改掉
      remark: editForm.value.remark === editOriginalRemark.value ? undefined : editForm.value.remark,
      reason: editForm.value.reason.trim(),
    })
    ElMessage.success('已保存，变更已留痕')
    editDialog.value = false
    await loadCatalog()
  } finally {
    editSaving.value = false
  }
}

/* ---------------- 五、锚点体检 ---------------- */
const anBody = ref<AnchorsBody | null>(null)
const anLoading = ref(false)

async function loadAnchors() {
  if (anLoading.value) return
  anLoading.value = true
  try {
    anBody.value = (await client.get<{ data: AnchorsBody }>('/emr-timeliness/anchors')).data.data
  } finally {
    anLoading.value = false
  }
}

function anchorAsRule(r: AnchorRule): RuleView {
  // 锚点体检返回体是另一套键名（declared* 前缀），转成 RuleTags 认识的形状再复用标签。
  // sourceVerified 填 true 是为了**不渲染**「阈值待病案科确认」徽标：/anchors 根本不返回该字段，
  // 这里无论标 true 还是 false 都是编的。核对状态以 catalog / indicators / 规则详情三处为准。
  return {
    code: r.ruleCode, name: r.ruleName, scope: r.scope,
    available: r.available, enabled: r.enabled, executable: r.executable,
    limitMinutes: 0, warnRatioPct: 0,
    startAnchor: r.declaredStartAnchor, startAnchorCn: r.declaredStartAnchorCn,
    endAnchor: r.declaredEndAnchor, endAnchorCn: r.declaredEndAnchorCn,
    sourceDoc: null, sourceArticle: null, sourceArticleRaw: null,
    sourceEffectiveFrom: null, sourceVerified: true,
    unavailableReason: r.unavailableReason,
  }
}

function mismatch(declared: string | null, executed: string | null): boolean {
  if (executed === null) return false   // 不可执行的规则本就没有执行锚点，不是「对不上」
  return declared !== executed
}

/* ---------------- 通用渲染 ---------------- */

/** 去掉后端文案里的强调标记（<b>/**），只去标记不改字——文案本身仍是原文。 */
function plain(s: unknown): string {
  if (s === null || s === undefined) return ''
  return String(s).replace(/<\/?b>/g, '').replace(/\*\*/g, '')
}

function fmt(v: unknown): string {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'boolean') return v ? '是' : '否'
  const s = String(v)
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(s) ? fmtDateTime(s) : s
}

/**
 * 比率一律显示成「分子 / 分母（百分比）」。
 * 只给一个「100%」看不出是 2/2 还是 200/200，而这两者能不能拿去考核完全不同。
 * 分母为 0 时后端返 null，这里显示「无可判定病例」而**不是 0%**。
 */
function rateFrac(num: number | null | undefined, den: number | null | undefined,
                  rate: number | null | undefined): string {
  const n = num ?? 0
  const d = den ?? 0
  if (d === 0) return '—（无可判定病例，分母为 0）'
  return `${n} / ${d}（${rate === null || rate === undefined ? '—' : rate + '%'}）`
}

function minutesCn(m: number | null | undefined): string {
  if (m === null || m === undefined) return '—'
  const abs = Math.abs(m)
  const h = Math.floor(abs / 60)
  const mm = abs % 60
  const sign = m < 0 ? '-' : ''
  return h > 0 ? `${sign}${h} 小时 ${mm} 分` : `${sign}${mm} 分`
}

function statusTag(s: unknown): 'success' | 'danger' | 'warning' | 'info' {
  switch (s) {
    case 'ON_TIME': return 'success'
    case 'LATE': return 'danger'
    case 'MISSING_OVERDUE': return 'danger'
    case 'ANOMALY_NEGATIVE': return 'warning'
    default: return 'info'
  }
}

function groupLabel(by: string | undefined): string {
  switch (by) {
    case 'doctor': return '责任医师'
    case 'ward': return '病区'
    case 'dept': return '科室'
    default: return '分组'
  }
}

/** 全窗口覆盖段列名中文化。未登记的键原样显示英文——比显示成空白或猜错要好。 */
const ZH: Record<string, string> = {
  admissions: '窗口内入院数',
  with_admit_at: '有入院时刻',
  with_duty_doctor: '有主管医师',
  discharged_in_window: '窗口内出院数',
  dischargedStatusWithoutTimestamp: '状态已出院但无出院时刻',
  records: '窗口内病历数',
  admission_records: '入院记录数',
  first_progress_typed: '有首程类型的记录数',
  progress_records: '普通病程记录数',
  discharge_records: '出院记录数',
  round_records: '查房记录数',
  round_level_typed: '已标查房级别数',
  with_writer: '有书写人的病历数',
  consults: '窗口内会诊数',
  consults_with_done_at: '有会诊完成时刻',
}
function zh(col: string): string {
  return ZH[col] ?? col
}

/* ---------------- 局部小组件 ---------------- */

/** 规则状态标签：算不出来 / 锚点对不上 / 已停用 / 阈值待病案科确认，一个都不许省 */
const RuleTags = defineComponent({
  name: 'RuleTags',
  props: { rule: { type: Object as PropType<RuleView>, required: true } },
  setup(props) {
    return () => {
      const r = props.rule
      const tags = []
      if (!r.available) {
        tags.push(h(ElTag, { type: 'danger', size: 'small' }, () => '缺数据源｜算不出来'))
      } else if (!r.executable) {
        tags.push(h(ElTag, { type: 'danger', size: 'small' }, () => '锚点口径对不上'))
      } else if (!r.enabled) {
        tags.push(h(ElTag, { type: 'info', size: 'small' }, () => '已停用'))
      } else {
        tags.push(h(ElTag, { type: 'success', size: 'small' }, () => '在用'))
      }
      if (!r.sourceVerified) {
        tags.push(h(ElTag, { type: 'warning', size: 'small' }, () => '阈值待病案科确认'))
      }
      return h('span', { class: 'tags' }, tags)
    }
  },
})

/** 出处一行：条文号一律显示后端给的 sourceArticle（自带未核对后缀），不显示去掉后缀的 raw */
const SourceLine = defineComponent({
  name: 'SourceLine',
  props: { rule: { type: Object as PropType<RuleView>, required: true } },
  setup(props) {
    return () => {
      const r = props.rule
      if (!r.sourceDoc && !r.sourceArticle) return null
      return h('div', { class: 'source' }, [
        h('span', { class: 'dim' }, `${r.sourceDoc ?? ''}　`),
        h('span', { class: r.sourceVerified ? '' : 'hot' }, r.sourceArticle ?? ''),
      ])
    }
  },
})

/** 后端 warnings 数组原样上屏，一条不省 */
const WarnList = defineComponent({
  name: 'WarnList',
  props: { warnings: { type: Array as PropType<string[]>, default: () => [] } },
  setup(props) {
    return () => {
      const ws = props.warnings ?? []
      if (ws.length === 0) return null
      return h('div', ws.map((w, i) => h(ElAlert, {
        key: i, type: 'warning', showIcon: true, closable: false, class: 'caveat',
      }, () => h('div', { class: 'pre' }, plain(w)))))
    }
  },
})

/** 被跳过的规则必须点名，不静默丢弃——否则「全院没有超时」可能只是规则被悄悄跳过了 */
const SkippedRules = defineComponent({
  name: 'SkippedRules',
  props: { skipped: { type: Array as PropType<SkipEntry[]>, default: () => [] } },
  setup(props) {
    return () => {
      const sk = props.skipped ?? []
      if (sk.length === 0) return null
      return h(ElAlert, {
        type: 'info', showIcon: true, closable: false, class: 'caveat',
        title: `本次有 ${sk.length} 条规则未参与统计，逐条原因如下（不静默跳过）`,
      }, () => h('ul', sk.map((s) => h('li', { key: s.code, class: 'pre' },
        `${s.code}　${s.name}：${plain(s.reason)}`))))
    }
  },
})

/** 锚点列级事实 */
const ColumnFacts = defineComponent({
  name: 'ColumnFacts',
  props: { facts: { type: Object as PropType<ColumnFactsData | null>, default: null } },
  setup(props) {
    return () => {
      const f = props.facts
      if (!f) return h('span', { class: 'dim' }, '—')
      if (!f.declared) {
        return h('div', [
          h('div', { class: 'dim' }, '本规则未声明该锚点'),
          f.note ? h('div', { class: 'pre dim' }, plain(f.note)) : null,
        ])
      }
      const lines = [h('div', { class: 'pre dim' }, f.expression ?? '')]
      if (f.resolved === false || f.exists === false) {
        lines.push(h(ElTag, { type: 'danger', size: 'small' }, () => '列不存在或无法体检'))
      } else if (f.rowCount !== undefined) {
        lines.push(h('div', {},
          `${f.table}.${f.column}　填充 ${f.filled ?? 0} / ${f.rowCount}`
          + `（${f.fillRatePct === null || f.fillRatePct === undefined ? '—' : f.fillRatePct + '%'}）`))
      }
      if (f.note) lines.push(h('div', { class: 'pre hot' }, plain(f.note)))
      return h('div', lines)
    }
  },
})

/* ---------------- 生命周期 ---------------- */
function onTabChange(name: string | number) {
  const n = String(name)
  if (n === 'overdue' && !odBody.value) void loadOverdue(0)
  if (n === 'upcoming' && !upBody.value) void loadUpcoming()
  if (n === 'anchors' && !anBody.value) void loadAnchors()
}

onMounted(async () => {
  await Promise.all([loadConfig(), loadCatalog()])
  await loadIndicators()
})
</script>

<style scoped>
.caveat {
  margin-bottom: 8px;
}
.bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
.indicator {
  margin-top: 18px;
  padding-top: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.ind-head {
  margin-bottom: 6px;
  line-height: 24px;
}
.ind-code {
  font-weight: 600;
  color: var(--el-color-primary);
  margin-right: 6px;
}
.ind-name {
  font-weight: 600;
  margin-right: 6px;
}
.source {
  font-size: 12px;
  margin-bottom: 6px;
}
.pre {
  white-space: pre-wrap;
  line-height: 1.6;
  word-break: break-word;
}
.dim {
  color: var(--el-text-color-secondary);
}
.hot {
  color: var(--el-color-danger);
}
.missing {
  margin-top: 6px;
}
.missing-t {
  font-weight: 600;
}
.notelist {
  margin: 0;
  padding-left: 20px;
}
.expand {
  padding: 8px 16px;
}
</style>
