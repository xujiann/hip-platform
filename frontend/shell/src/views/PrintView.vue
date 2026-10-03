<template>
  <div class="print-page" :class="{ 'paper-a5': isA5 }">
    <div v-if="data" class="ticket" :class="{ sheet: isSheet, wide: isTempSheet }">
      <!-- 五种日常单据每张纸自带页眉（一次就诊可能出好几张，页眉必须跟着纸走），故这里不出全局页眉 -->
      <h2 v-if="!isClinicalDoc">{{ hospitalName }}</h2>
      <h3 v-if="!isClinicalDoc">{{ titles[type] }}</h3>
      <hr v-if="!isClinicalDoc" />
      <template v-if="type === 'registration'">
        <p>患者：{{ data.patient_name }}（{{ data.patient_no }}）</p>
        <p>科室：{{ data.dept_name }} · 第 <b class="big">{{ data.reg_no }}</b> 号</p>
        <p>就诊日期：{{ data.visit_date }}　挂号费：¥{{ data.fee }}</p>
      </template>
      <template v-else-if="type === 'charge'">
        <p>患者：{{ data.patient_name }}（{{ data.patient_no }}）</p>
        <p>结算单号：{{ data.charge_no }}</p>
        <table class="items">
          <tr><th>项目</th><th>数量</th><th>金额</th></tr>
          <tr v-for="(i, idx) in (data.items as Record<string, unknown>[])" :key="idx">
            <td>{{ i.item_name }}</td><td>{{ i.qty }}</td><td>¥{{ i.amount }}</td>
          </tr>
        </table>
        <p class="big">合计：¥{{ data.total_amount }}（{{ payNames[String(data.pay_method)] }}）</p>
      </template>
      <template v-else-if="type === 'lab-report'">
        <p>患者：{{ data.patient_name }}（{{ data.patient_no }}）　项目：{{ data.item_name }}</p>
        <p>申请单号：{{ data.group_no }}</p>
        <table class="items">
          <tr><th>项目</th><th>结果</th><th>单位</th><th>参考范围</th><th>标志</th></tr>
          <tr v-for="(r, idx) in (data.results as Record<string, unknown>[])" :key="idx">
            <td>{{ r.item_name }}</td>
            <td :class="{ abnormal: r.abnormal_flag && r.abnormal_flag !== 'N' }">{{ r.result_value }}</td>
            <td>{{ r.unit }}</td><td>{{ r.ref_range }}</td><td>{{ r.abnormal_flag }}</td>
          </tr>
        </table>
      </template>
      <!-- 收尾环·打印1：住院费用一日清单（患者可拿走的凭据） -->
      <template v-else-if="type === 'inp-daily-fee'">
        <p>患者：{{ data.patient_name }}（{{ data.patient_no }}）　住院号：{{ data.admission_no }}</p>
        <p>科室：{{ data.dept_name }} · {{ data.ward_name }} {{ data.bed_no }}床　日期：{{ data.date }}</p>
        <table class="items">
          <tr><th>项目</th><th>规格</th><th>数量</th><th>单价</th><th>金额</th></tr>
          <tr v-for="(r, idx) in (data.rows as Record<string, unknown>[])" :key="idx">
            <td>{{ r.item_name }}</td><td>{{ r.spec }}</td><td>{{ r.qty }}</td>
            <td>¥{{ r.unit_price }}</td><td>¥{{ r.amount }}</td>
          </tr>
          <tr v-if="!(data.rows as unknown[]).length"><td colspan="5" style="text-align:center">当日无已执行费用</td></tr>
        </table>
        <p class="big">当日合计：¥{{ data.dayTotal }}</p>
        <p>已交押金：¥{{ data.depositTotal }}　　已发生费用：¥{{ data.executedTotal }}</p>
        <p :class="{ owed: data.owed }">
          押金余额：¥{{ data.balance }}
          <span v-if="data.owed">（已欠费，请及时续交押金）</span>
        </p>
      </template>
      <!-- 收尾环·打印2：出院小结 -->
      <template v-else-if="type === 'inp-discharge-summary'">
        <p>患者：{{ data.patient_name }}（{{ data.patient_no }}）　性别：{{ sexName }}</p>
        <p>住院号：{{ data.admission_no }}　科室：{{ data.dept_name }}　主管医生：{{ data.doctor_name }}</p>
        <p>入院日期：{{ fmtDate(data.admit_at) }}　　出院日期：{{ fmtDate(data.discharged_at) }}</p>
        <p>入院诊断：{{ data.admit_diag_name }} {{ data.admit_diag_icd ? '(' + data.admit_diag_icd + ')' : '' }}</p>
        <p>出院诊断：{{ data.discharge_diag_name || data.admit_diag_name }}
          {{ data.discharge_diag_icd ? '(' + data.discharge_diag_icd + ')' : '' }}</p>
        <p v-if="otherDiag.length">其他诊断：{{ otherDiag.map((d) => `${d.name}(${d.icd})`).join('；') }}</p>
        <hr />
        <div class="section">
          <b>诊疗经过</b>
          <el-empty v-if="!records.length" description="无病历记录" :image-size="60" />
          <div v-for="(r, idx) in records" :key="idx" class="rec">
            <p class="rec-h">{{ recordTypeNames[String(r.record_type)] ?? r.record_type }} · {{ fmtDate(r.created_at) }}
              <b>{{ r.title }}</b></p>
            <p class="rec-c">{{ r.content }}</p>
          </div>
        </div>
        <div v-if="meds.length" class="section">
          <b>出院医嘱（带药）</b>
          <table class="items">
            <tr><th>药品</th><th>规格</th><th>用法</th><th>数量</th></tr>
            <tr v-for="(mm, idx) in meds" :key="idx">
              <td>{{ mm.item_name }}</td><td>{{ mm.spec }}</td>
              <td>{{ mm.usage_route }} {{ mm.dose_per_time }} {{ mm.frequency }}</td><td>{{ mm.qty }}</td>
            </tr>
          </table>
        </div>
      </template>
      <!-- v42 车道1：体温单（三测单）——独立 SVG 坐标格点版式，不复用票据 isSheet 文字流版式 -->
      <template v-else-if="type === 'temp-sheet'">
        <div class="sheet-head">
          <p>姓名：{{ hdr.patient_name }}　性别：{{ sexNameOf(hdr.sex) }}　科室：{{ hdr.dept_name }}
            　病区：{{ hdr.ward_name || '—' }}　床号：{{ hdr.bed_no || '—' }}</p>
          <p>住院号：{{ hdr.admission_no }}　入院日期：{{ fmtDate(hdr.admit_at) }}
            　护理级别：{{ hdr.care_level || '—' }}　过敏史：{{ hdr.allergy_history || '无' }}</p>
        </div>
        <div class="sheet-bar no-print">
          <el-button-group>
            <el-button size="small" :disabled="week <= 1 || loading" @click="gotoWeek(week - 1)">← 上一周</el-button>
            <el-button size="small" :disabled="week >= totalWeeks || loading" @click="gotoWeek(week + 1)">下一周 →</el-button>
          </el-button-group>
          <span class="bar-txt">第 {{ data.week }} / {{ totalWeeks }} 住院周　{{ data.weekStart }} ~ {{ data.weekEnd }}</span>
          <span class="zoom-box">
            <el-button size="small" :disabled="zoom <= ZOOM_MIN" @click="setZoom(zoom - 0.2)">缩小 −</el-button>
            <el-slider v-model="zoom" :min="ZOOM_MIN" :max="ZOOM_MAX" :step="0.1" :show-tooltip="false"
                       style="width: 130px" />
            <el-button size="small" :disabled="zoom >= ZOOM_MAX" @click="setZoom(zoom + 0.2)">放大 +</el-button>
            <el-button size="small" @click="setZoom(1)">100%</el-button>
            <b class="bar-txt">{{ Math.round(zoom * 100) }}%</b>
          </span>
        </div>
        <p class="sheet-week">第 {{ data.week }} / {{ totalWeeks }} 住院周　{{ data.weekStart }} ~ {{ data.weekEnd }}</p>
        <TempSheetSvg :sheet="data" :scale="zoom" />
      </template>
      <!-- v42 车道2：护理记录单（合版时代加——该文件由车道1 独占，车道2 只交付端点与返回体） -->
      <template v-else-if="type === 'nur-record'">
        <div class="sheet-head">
          <p>姓名：{{ hdr.patient_name }}　性别：{{ sexNameOf(hdr.sex) }}　科室：{{ hdr.dept_name }}
            　病区：{{ hdr.ward_name || '—' }}　床号：{{ hdr.bed_no || '—' }}</p>
          <p>住院号：{{ hdr.admission_no }}　患者号：{{ hdr.patient_no }}　入院日期：{{ fmtDate(hdr.admit_at) }}
            　护理级别：{{ hdr.care_level || '—' }}</p>
          <p v-if="nurFrom || nurTo">记录区间：{{ nurFrom || '起始' }} ~ {{ nurTo || '至今' }}</p>
        </div>
        <table class="tbl">
          <tr><th style="width:14%">时间</th><th style="width:10%">类型</th><th>病情观察</th>
            <th>护理措施</th><th style="width:14%">效果评价</th><th style="width:10%">护士签名</th></tr>
          <tr v-for="(r, i) in nurRows" :key="i">
            <td>{{ fmtDateTime(r.record_time, '') }}</td>
            <td>{{ r.kind_name }}</td>
            <td>{{ r.observation || '—' }}</td>
            <td>{{ r.measure || '—' }}</td>
            <td>{{ r.effect || '—' }}</td>
            <td>{{ r.nurse_name || '—' }}{{ r.signed ? '（已签）' : '' }}</td>
          </tr>
          <tr v-if="nurRows.length === 0"><td colspan="6">（该区间无护理记录）</td></tr>
        </table>
      </template>
      <!--
        v43 车道B：五种日常单据（处方笺 / 检验申请单 / 检查申请单 / 治疗单 / 导诊单，偏离表 1026★）。
        一个单据号 = 一张纸：一次就诊可能开两张处方、三张检查申请单，故按 groups 循环，
        每张纸自带医院名/单据名/条码位/患者页眉/签名栏，打印时逐张分页。
        条码位**只留位置与可读单号，不画条码图形**——真条码属实施期条码打印机对接。
      -->
      <template v-else-if="isClinicalDoc">
        <div v-for="(g, gi) in docSheets" :key="gi" class="doc-sheet">
          <div class="doc-head">
            <div class="doc-title">
              <h2>{{ hospitalName }}</h2>
              <h3>{{ titles[type] }}</h3>
            </div>
            <div class="barcode-slot">
              <span class="slot-tag">条码粘贴处</span>
              <span class="slot-no">{{ g.groupNo || String(data.patient_no ?? '') }}</span>
            </div>
          </div>
          <div class="doc-meta">
            <span>姓名：{{ data.patient_name }}</span>
            <span>性别：{{ sexNameOf(data.sex) }}</span>
            <span>年龄：{{ ageText }}</span>
            <span>门诊号：{{ data.patient_no }}</span>
          </div>
          <div class="doc-meta">
            <span>科室：{{ data.dept_name }}</span>
            <span>就诊日期：{{ fmtDate(data.visit_date) }}</span>
            <span>号序：第 {{ data.reg_no }} 号</span>
            <span v-if="docNoLabel[type]">{{ docNoLabel[type] }}：{{ g.groupNo }}</span>
          </div>
          <div v-if="type !== 'guide-sheet'" class="doc-line">临床诊断：{{ diagText || '—' }}</div>
          <hr />

          <!-- 处方笺：Rp. + 逐条用法用量 + 医师/药师/核对/发药 四签名栏 -->
          <template v-if="type === 'prescription'">
            <!-- v74 复核（1026★ 第三轮自查）：allergyText = 档案文本 + 结构化过敏记录，皆空才印「无」 -->
            <div class="doc-line">过敏史：{{ allergyLine(data.allergyText) }}</div>
            <div class="rp">Rp.</div>
            <ol class="rx">
              <li v-for="(r, i) in g.rows" :key="i">
                <div class="rx-name">
                  {{ r.item_name }}<span v-if="r.spec">　{{ r.spec }}</span><span v-if="r.urgent" class="urgent">【加急】</span><span v-if="r.remark" class="remark">（{{ r.remark }}）</span>
                  <span class="rx-qty">× {{ r.qty }} {{ r.unit }}</span>
                  <span v-if="r.antibiotic" class="tag-abx">抗菌药</span>
                </div>
                <div class="rx-usage">
                  用法：{{ r.usage_route || '—' }}　每次 {{ r.dose_per_time || '—' }}　{{ r.frequency || '—' }}
                  <span v-if="r.days">　共 {{ r.days }} 日</span>
                </div>
              </li>
            </ol>
            <p class="rx-end">—— 以下空白 ——</p>
            <div class="doc-line">药品金额：¥{{ money2(g.total) }}</div>
            <div class="sign-bar">
              <span>医师：{{ docDoctor(g) || '' }}</span>
              <span>药师（审核）：</span>
              <span>调配核对：</span>
              <span>发药：</span>
            </div>
            <div class="doc-line small">开具日期：{{ fmtDate(data.visit_date) }}</div>
          </template>

          <!-- 检验申请单：申请科室/医师 + 病史摘要 + 标本状态 + 标本要求手填栏 -->
          <template v-else-if="type === 'lab-request'">
            <div class="doc-line">
              申请科室：{{ data.dept_name }}　申请医师：{{ docDoctor(g) || '—' }}{{ docDoctorSuffix(g) }}
              　申请日期：{{ fmtDate(data.visit_date) }}
            </div>
            <div class="doc-line">病史摘要：{{ briefHistory }}</div>
            <!-- v74 复核修：v44 七个医嘱字段后端一直在返回（临床摘要/标本类型/采样部位/注意事项/加急/备注），
                 打印页此前一个都没印——CHANGELOG 写的「有值印值」并不成立，医生填了等于白填。
                 口径照 CHANGELOG 原意：有值印值，无值仍保留手填栏（历史单据必然为空）。 -->
            <div v-if="firstOf(g, 'clinical_summary')" class="doc-line">临床摘要：{{ firstOf(g, 'clinical_summary') }}</div>
            <div v-else class="fill-line">临床摘要：</div>
            <table class="items">
              <tr><th>检验项目</th><th>数量</th><th>标本类型 / 采样部位</th><th>执行科室</th><th>标本条码</th><th>标本状态</th></tr>
              <tr v-for="(r, i) in g.rows" :key="i">
                <td>{{ r.item_name }}<span v-if="r.urgent" class="urgent">【加急】</span><span v-if="r.remark" class="remark">（{{ r.remark }}）</span></td>
                <td>{{ r.qty }} {{ r.unit }}</td>
                <td>{{ [r.specimen_type, r.sampling_site].filter(Boolean).join(' / ') || '—' }}</td>
                <td>{{ r.exec_dept_name || '—' }}</td>
                <td>{{ r.sample_barcode || '（未采样）' }}</td>
                <td>{{ sampleStatusNames[String(r.sample_status)] ?? '待采集' }}</td>
              </tr>
            </table>
            <div v-if="firstOf(g, 'notice')" class="doc-line">注意事项：{{ firstOf(g, 'notice') }}</div>
            <div v-else class="fill-line">注意事项：</div>
            <div class="fill-line">标本要求（采集容器 / 采集时间 / 送检要求）：</div>
            <div class="sign-bar">
              <span>申请医师：{{ docDoctor(g) || '' }}</span>
              <span>采样人 / 时间：</span>
              <span>接收人 / 时间：</span>
              <span>检验者：</span>
            </div>
          </template>

          <!-- 检查申请单：项目名本身即含部位，另留「部位补充」「检查目的」两条手填栏 -->
          <template v-else-if="type === 'exam-request'">
            <div class="doc-line">
              申请科室：{{ data.dept_name }}　申请医师：{{ docDoctor(g) || '—' }}{{ docDoctorSuffix(g) }}
              　申请日期：{{ fmtDate(data.visit_date) }}
            </div>
            <table class="items">
              <tr><th>检查项目（含部位）</th><th>数量</th><th>执行科室</th><th>状态</th></tr>
              <tr v-for="(r, i) in g.rows" :key="i">
                <td>{{ r.item_name }}<span v-if="r.urgent" class="urgent">【加急】</span><span v-if="r.remark" class="remark">（{{ r.remark }}）</span></td>
                <td>{{ r.qty }} {{ r.unit }}</td>
                <td>{{ r.exec_dept_name || '—' }}</td>
                <td>{{ orderStatusNames[String(r.status)] ?? r.status }}</td>
              </tr>
            </table>
            <!-- v74 复核修：同检验申请单——v44 字段有值印值，无值仍保留手填栏 -->
            <div v-if="firstOf(g, 'sampling_site')" class="doc-line">检查部位（补充说明）：{{ firstOf(g, 'sampling_site') }}</div>
            <div v-else class="fill-line">检查部位（补充说明）：</div>
            <div v-if="firstOf(g, 'exam_purpose')" class="doc-line">检查目的 / 临床要求：{{ firstOf(g, 'exam_purpose') }}</div>
            <div v-else class="fill-line">检查目的 / 临床要求：</div>
            <div v-if="firstOf(g, 'clinical_summary')" class="doc-line">临床摘要：{{ firstOf(g, 'clinical_summary') }}</div>
            <div v-else class="fill-line">临床摘要：</div>
            <div v-if="firstOf(g, 'notice')" class="doc-line">注意事项：{{ firstOf(g, 'notice') }}</div>
            <div v-else class="fill-line">注意事项：</div>
            <div class="doc-line">病史摘要：{{ briefHistory }}</div>
            <div class="doc-line">体格检查：{{ emrInfo.physical_exam || '—' }}</div>
            <div class="sign-bar">
              <span>申请医师：{{ docDoctor(g) || '' }}</span>
              <span>登记：</span>
              <span>检查技师：</span>
              <span>报告医师：</span>
            </div>
          </template>

          <!-- 治疗单：执行科室 + 执行记录手填栏（执行时间/患者反应由执行护士现场填） -->
          <template v-else-if="type === 'treat-sheet'">
            <div class="doc-line">
              开单科室：{{ data.dept_name }}　开单医师：{{ docDoctor(g) || '—' }}{{ docDoctorSuffix(g) }}
              　开单日期：{{ fmtDate(data.visit_date) }}
            </div>
            <table class="items">
              <tr><th>治疗项目</th><th>数量</th><th>单位</th><th>执行科室</th><th>状态</th></tr>
              <tr v-for="(r, i) in g.rows" :key="i">
                <!-- v74：治疗单同样印 v44 的加急与备注（复核指出此前只接了两张申请单） -->
                <td>{{ r.item_name }}<span v-if="r.urgent" class="urgent">【加急】</span><span v-if="r.remark" class="remark">（{{ r.remark }}）</span></td>
                <td>{{ r.qty }}</td>
                <td>{{ r.unit }}</td>
                <td>{{ r.exec_dept_name || '—' }}</td>
                <td>{{ orderStatusNames[String(r.status)] ?? r.status }}</td>
              </tr>
            </table>
            <div class="fill-line">医嘱要求（部位 / 剂量 / 疗程）：</div>
            <div class="fill-line">执行记录（执行时间 / 患者反应 / 备注）：</div>
            <div class="sign-bar">
              <span>开单医师：{{ docDoctor(g) || '' }}</span>
              <span>核对：</span>
              <span>执行人：</span>
              <span>执行时间：</span>
            </div>
          </template>

          <!-- 导诊单：患者拿着跑流程用——每一项去哪个科室、缴费了没有 -->
          <template v-else>
            <!-- 诊室：本版无诊室主数据（sys_dept 无房间号列、无诊室表），纸面留「—」而不是编一个 -->
            <div class="doc-line">
              <!-- v74 复核（1026★ 第二轮审计者 #10）：页眉 doctor_name 是挂号时的排班医生，代班/转接时不是接诊的人——
                   与四种单据署名同一类口径错。挂号医师照印排班；接诊医师取行上的开单医生（后端随行返回），未开单印「—」。 -->
              就诊科室：<b>{{ data.dept_name }}</b>　诊室：—　挂号医师：{{ data.doctor_name || '—' }}　开单医师：{{ firstOf(g, 'order_doctor_name') || '—' }}
            </div>
            <div class="doc-line">请到上述科室候诊，叫号序号：<b class="big">{{ data.reg_no }}</b></div>
            <table class="items">
              <tr><th style="width:8%">序</th><th style="width:14%">环节</th><th>项目</th>
                <th style="width:12%">数量</th><th style="width:18%">前往科室</th><th style="width:14%">状态</th></tr>
              <tr v-for="(r, i) in g.rows" :key="i">
                <td>{{ i + 1 }}</td>
                <td>{{ guideStageNames[String(r.order_type)] ?? '其他' }}</td>
                <td>{{ r.item_name }}</td>
                <td>{{ r.qty }} {{ r.unit }}</td>
                <td>{{ r.exec_dept_name || (r.order_type === 'DRUG' ? '药房' : '—') }}</td>
                <td>{{ guideStatusOf(r) }}</td>
              </tr>
              <tr v-if="!g.rows.length">
                <td colspan="6" style="text-align:center">本次就诊暂无待办项目</td>
              </tr>
            </table>
            <p class="tip">
              温馨提示：请先到收费处缴费，再持本单按上表顺序到对应科室完成各项目；
              检查检验结果可凭患者号查询。
            </p>
          </template>

          <p class="foot">打印时间：{{ now }}　{{ footNote }}</p>
        </div>
      </template>
      <hr v-if="!isClinicalDoc" />
      <p v-if="!isClinicalDoc" class="foot">打印时间：{{ now }}　{{ footNote }}</p>
      <el-button class="no-print" type="primary" @click="doPrint">打 印</el-button>
    </div>
    <el-empty v-else description="加载中或单据不存在" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import client from '../api/client'
import { fmtDate, fmtDateTime } from '../utils/date'
import TempSheetSvg from '../components/TempSheetSvg.vue'
// v75 车道C：纯拼装逻辑抽到 utils/print-format.ts（vitest 锁定）；此处同名变量/函数的用别名导入，模板用法不变
import {
  ageText as ageTextOf, allergyLine, briefHistory as briefHistoryOf, docDoctor as docDoctorOf,
  docDoctorSuffix as docDoctorSuffixOf, firstOf, formatDiagnoses, guideStatusOf, money2, printedAt } from '../utils/print-format'

const route = useRoute()
const type = String(route.query.type ?? 'registration')
const id = String(route.query.id ?? '')
const date = String(route.query.date ?? '')
// v42 护理记录单：区间与类型过滤（PrintView 原先只读 type/id/date 三个 query）
const nurFrom = String(route.query.from ?? '')
const nurTo = String(route.query.to ?? '')
const nurKind = String(route.query.kind ?? '')
// v43 五种日常单据：只打其中一张（一次就诊可能有多个处方号/申请单号）时传 groupNo
const groupNo = String(route.query.groupNo ?? '')
const data = ref<Record<string, unknown> | null>(null)
const hospitalName = ref('')
// v75 合并后补齐：日期取后端业务日期 printedOn（演示库可冻结），时刻取本机钟；无 printedOn 的凭条票据照旧本地时间
const now = computed(() => printedAt((data.value as Record<string, unknown> | null)?.printedOn))

const titles: Record<string, string> = {
  registration: '挂号凭条', charge: '收费票据', 'lab-report': '检验报告单',
  'inp-daily-fee': '住院费用一日清单', 'inp-discharge-summary': '出院小结',
  'temp-sheet': '体温单（三测单）', 'nur-record': '护理记录单',
  // v43 车道B 五种日常单据（与后端 PrintReportController.DOC_TITLE 逐字对应）
  prescription: '处方笺', 'lab-request': '检验申请单', 'exam-request': '检查申请单',
  'treat-sheet': '治疗单', 'guide-sheet': '导诊单',
}
const payNames: Record<string, string> = { CASH: '现金', WECHAT: '微信', ALIPAY: '支付宝', YB: '医保' }
// PREOP：EmrIntegrityService 对手术病例硬要 record_type='PREOP'，此前全仓无中文名（v42 车道5 代加）
const recordTypeNames: Record<string, string> = { ADMISSION: '入院记录', FIRST_PROGRESS: '首次病程', PROGRESS: '病程记录', ROUND: '三级查房', PREOP: '术前小结', DISCHARGE: '出院小结' }

// ===== v43 车道B：五种日常单据 =====
const CLINICAL_DOCS = ['prescription', 'lab-request', 'exam-request', 'treat-sheet', 'guide-sheet']
const isClinicalDoc = computed(() => CLINICAL_DOCS.includes(type))
/** 单据号在纸面上的叫法；导诊单没有单据号（它对应整次就诊，不对应某张医嘱单） */
const docNoLabel: Record<string, string> = {
  prescription: '处方编号', 'lab-request': '申请单号',
  'exam-request': '申请单号', 'treat-sheet': '治疗单号',
}
const sampleStatusNames: Record<string, string> = {
  COLLECTED: '已采集', RECEIVED: '已接收', PUBLISHED: '已发布',
}
const orderStatusNames: Record<string, string> = {
  CREATED: '待缴费', CHARGED: '已缴费', DISPENSED: '已发药', EXECUTED: '已完成', CANCELLED: '已作废',
}
const guideStageNames: Record<string, string> = {
  DRUG: '取药', LAB: '检验', EXAM: '检查', TREAT: '治疗',
}
interface DocSheet { groupNo: string; rows: Record<string, unknown>[]; total?: unknown }
/** 一个单据号一张纸；导诊单整次就诊只出一张，用空单据号的伪分组占位 */
const docSheets = computed<DocSheet[]>(() => {
  if (!data.value) return []
  if (type === 'guide-sheet') {
    return [{ groupNo: '', rows: (data.value.rows as Record<string, unknown>[]) ?? [] }]
  }
  return (data.value.groups as DocSheet[]) ?? []
})

// 诊断拼接 / 年龄文案 / 署名与职称 / 病史摘要 / 导诊单状态的口径与注释见 utils/print-format.ts
const diagText = computed(() => formatDiagnoses(data.value?.diagnoses as Record<string, unknown>[] | undefined))
const emrInfo = computed(() => (data.value?.emr as Record<string, unknown>) ?? {})
const briefHistory = computed(() => briefHistoryOf(emrInfo.value))
const docDoctor = (g: { rows?: Array<Record<string, unknown>> }) => docDoctorOf(g, data.value)
const docDoctorSuffix = (g: { rows?: Array<Record<string, unknown>> }) => docDoctorSuffixOf(g, data.value)
const ageText = computed(() => ageTextOf(data.value?.ageText))

// 住院单据用较宽版式（A5 清单/小结），门诊凭条保持窄条
const isSheet = computed(() => type === 'inp-daily-fee' || type === 'inp-discharge-summary'
  || type === 'nur-record'     // v42：护理记录单同为 A5 宽版文字流
  || isClinicalDoc.value)      // v43：处方笺与三种申请单/导诊单均为 A5 纸面
// 体温单是坐标格点版式（横向 7 天 × 6 时点），既不是窄条也不是 A5 文字流：自成一档宽版
const isTempSheet = computed(() => type === 'temp-sheet')
// v75 车道C：?paper=A5 → 根容器加 paper-a5 类（窄版 + 命名 @page）；缺省 A4 逐字不变。
// 只对文字流单据（isSheet）生效：挂号凭条/收费票据是窄条小票，体温单是坐标格点，都不是 A5 纸面。
const isA5 = computed(() => String(route.query.paper ?? '').toUpperCase() === 'A5' && isSheet.value)
const sexName = computed(() => ({ M: '男', F: '女' } as Record<string, string>)[String(data.value?.sex)] ?? '')

/** 页脚声明：法定文书写"病历组成部分"，日常单据各按用途声明，其余沿用原文案不变 */
const CLINICAL_FOOTS: Record<string, string> = {
  prescription: '本处方为诊疗记录，请遵医嘱用药',
  'lab-request': '本单为检验申请凭据，请按标本要求配合采集',
  'exam-request': '本单为检查申请凭据，请按指引到执行科室',
  'treat-sheet': '本单为治疗执行凭据，执行后由执行人签名留存',
  'guide-sheet': '本单为就诊指引，不作为收费凭证',
}
const footNote = computed(() => {
  if (isTempSheet.value) return '本页为住院病历组成部分（三测单）'
  if (type === 'nur-record') return '本页为住院病历组成部分（护理记录单）'
  return CLINICAL_FOOTS[type] ?? '本单据仅作就诊凭证'
})

// ===== 体温单：住院周翻页 + 缩放（投标应答明文承诺项 2025★/2026★/2073） =====
const ZOOM_MIN = 0.5
const ZOOM_MAX = 2
const week = ref(Math.max(1, Number(route.query.week ?? 1) || 1))
const zoom = ref(1)
const loading = ref(false)
const hdr = computed(() => (data.value?.header as Record<string, unknown>) ?? {})
const totalWeeks = computed(() => Number(data.value?.totalWeeks ?? 1) || 1)

function sexNameOf(v: unknown): string {
  return ({ M: '男', F: '女' } as Record<string, string>)[String(v)] ?? '—'
}
function setZoom(v: number) {
  zoom.value = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, Math.round(v * 10) / 10))
}
async function gotoWeek(n: number) {
  if (n < 1 || n > totalWeeks.value || loading.value) return
  week.value = n
  await reload()
}
const otherDiag = computed(() => (data.value?.otherDiagnoses as { icd: string; name: string }[]) ?? [])
const records = computed(() => (data.value?.records as Record<string, unknown>[]) ?? [])
const meds = computed(() => (data.value?.meds as Record<string, unknown>[]) ?? [])
const nurRows = computed(() => (data.value?.rows as Record<string, unknown>[]) ?? [])

// 日期列改走 utils/date 的 fmtDate（v57）：admit_at / discharged_at / created_at 都是 timestamptz，
// 裸切 slice(0,10) 在北京 0–8 点会显示前一天。

// 住院打印数据集在 inpatient 端点，门诊沿用 /print/{type}/{id}
function endpoint(): string {
  if (type === 'inp-daily-fee') return `/inpatient/admissions/${id}/print/daily-fee?date=${date}`
  if (type === 'inp-discharge-summary') return `/inpatient/admissions/${id}/print/discharge-summary`
  if (type === 'temp-sheet') return `/inpatient/admissions/${id}/print/temp-sheet?week=${week.value}`
  if (type === 'nur-record') {
    const qs = new URLSearchParams()
    if (nurFrom) qs.set('from', nurFrom)
    if (nurTo) qs.set('to', nurTo)
    if (nurKind) qs.set('kind', nurKind)
    const q = qs.toString()
    return `/inpatient/admissions/${id}/print/nursing-record` + (q ? `?${q}` : '')
  }
  // v43 五种日常单据：统一走 /print/doc/{docType}/{registrationId}（id 传的是挂号 id）
  if (isClinicalDoc.value) {
    return `/print/doc/${type}/${id}` + (groupNo ? `?groupNo=${encodeURIComponent(groupNo)}` : '')
  }
  return `/print/${type}/${id}`
}

/** 翻页重取（越界由后端返 4821，拦截器已弹红字，此处只保住已渲染的上一页不被清空） */
async function reload() {
  loading.value = true
  try {
    data.value = (await client.get(endpoint())).data.data
  } finally {
    loading.value = false
  }
}

function doPrint() {
  window.print()
}

onMounted(async () => {
  const [, cfg] = await Promise.all([
    reload(),
    client.get('/config/public'),
  ])
  hospitalName.value = cfg.data.data.hospital_name ?? ''
})
</script>

<style scoped>
.print-page { display: flex; justify-content: center; padding: 24px; background: #fff; min-height: 100%; }
.ticket { width: 420px; font-size: 13px; }
.ticket.sheet { width: 640px; }
/* 体温单：格点版式自带宽度与横向滚动，容器让位到整幅可用宽 */
.ticket.wide { width: 100%; max-width: 1280px; }
.sheet-head p { margin: 2px 0; }
.sheet-bar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin: 8px 0; }
.zoom-box { display: inline-flex; align-items: center; gap: 8px; }
.bar-txt { font-size: 12px; color: #606266; }
.sheet-week { text-align: center; font-size: 12px; color: #606266; margin: 4px 0; }
h2, h3 { text-align: center; margin: 4px 0; }
.items { width: 100%; border-collapse: collapse; margin: 8px 0; }
.items th, .items td { border: 1px solid #999; padding: 4px 6px; text-align: left; }
.big { font-size: 18px; }
.abnormal { color: #d03050; font-weight: 700; }
.owed { color: #d03050; font-weight: 700; }
.foot { color: #888; font-size: 11px; }
.section { margin: 10px 0; }
.rec { margin: 6px 0; }
.rec-h { color: #666; font-size: 12px; margin: 2px 0; }
.rec-c { white-space: pre-wrap; margin: 2px 0 0; }
/* ===== v43 车道B：五种日常单据版式（一张纸 = 一个 .doc-sheet） ===== */
.doc-sheet { border: 1px solid #333; padding: 12px 14px; margin-bottom: 14px; }
.doc-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.doc-title { flex: 1; }
.doc-title h2, .doc-title h3 { margin: 2px 0; }
/* 条码位：只留位置与可读单号，不画条码图形（真条码由实施期条码打印机套打） */
.barcode-slot {
  width: 156px; min-height: 48px; border: 1px dashed #999; display: flex;
  flex-direction: column; align-items: center; justify-content: center; gap: 2px; font-size: 11px;
}
.slot-tag { color: #aaa; }
.slot-no { font-family: Consolas, Monaco, monospace; color: #333; letter-spacing: 1px; }
.doc-meta { display: flex; flex-wrap: wrap; gap: 2px 18px; margin: 4px 0; }
.doc-line { margin: 4px 0; }
.doc-line.small { font-size: 12px; color: #666; }
.rp { font-size: 20px; font-weight: 700; font-style: italic; margin: 8px 0 2px; }
.rx { margin: 0 0 8px 22px; padding: 0; }
.rx li { margin: 5px 0; }
.rx-name { font-weight: 600; }
.rx-qty { margin-left: 10px; font-weight: 400; }
.tag-abx { margin-left: 8px; font-size: 11px; color: #d03050; border: 1px solid #d03050; padding: 0 3px; }
.rx-usage { color: #444; }
.rx-end { text-align: center; color: #aaa; font-size: 11px; margin: 8px 0; }
/* v74：加急与行备注——黑白打印也要看得出来，靠字重不靠颜色 */
.urgent { font-weight: 700; margin-left: 4px; }
.remark { color: #444; margin-left: 4px; }
/* 手填栏：纸面留白 + 下划线，供医生/护士/技师现场书写 */
.fill-line { border-bottom: 1px solid #999; padding: 14px 0 2px; margin: 8px 0 4px; }
.sign-bar { display: flex; flex-wrap: wrap; gap: 10px 12px; margin-top: 14px; }
.sign-bar span { flex: 1 1 42%; border-bottom: 1px solid #999; padding-bottom: 16px; font-size: 12px; }
.tip { color: #666; font-size: 12px; margin-top: 10px; }
/*
 * v75 车道C：A5 版式（?paper=A5）。@page 不能嵌套进选择器，故在顶层声明命名页 a5，
 * 再由 .paper-a5 下的元素用 page: a5 引用——只有 A5 请求才会引用，缺省 A4 的打印行为与此前逐字相同
 * （不声明无名 @page，A4 沿用浏览器默认纸张与页边距）。
 * Chrome 85+ 支持 page 属性与命名 @page 的 size；不支持的浏览器忽略 size，仍得到窄版字号/边距，纸张由打印对话框选。
 */
@page a5 { size: A5; margin: 8mm; }
.paper-a5 .ticket.sheet, .paper-a5 .doc-sheet { page: a5; }
/* 屏幕预览：宽度 = A5 148mm 减两侧 8mm 页边距，字号整体降一级 */
.paper-a5 .ticket { font-size: 12px; }
.paper-a5 .ticket.sheet { width: 132mm; }
.paper-a5 .doc-sheet { padding: 8px 10px; }
.paper-a5 .doc-line.small, .paper-a5 .sign-bar span, .paper-a5 .tip { font-size: 11px; }
.paper-a5 .foot, .paper-a5 .rx-end, .paper-a5 .barcode-slot { font-size: 10px; }
.paper-a5 .rp { font-size: 18px; }
.paper-a5 .big { font-size: 16px; }
.paper-a5 .barcode-slot { width: 130px; }
@media print {
  .no-print { display: none; }
  /* A5：页边距已由 @page a5 给定，容器自身的 24px 留白与固定宽度让位 */
  .print-page.paper-a5 { padding: 0; }
  .paper-a5 .ticket.sheet { width: auto; } .paper-a5 .doc-sheet { padding: 0; }
  /* 一张单据一页纸：边框交给真实单据纸，最后一张不再多空一页 */
  .doc-sheet { border: none; padding: 0; page-break-after: always; }
  .doc-sheet:last-child { page-break-after: auto; }
}
</style>
