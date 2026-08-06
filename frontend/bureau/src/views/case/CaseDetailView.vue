<template>
  <div v-loading="loading">
    <el-card class="mb">
      <div class="toolbar">
        <div>
          <h3 style="margin: 0">{{ c.caseNo }}　{{ c.name }}</h3>
          <div class="hint" style="margin-top: 6px">
            <el-tag :type="(CASE_STATUS_TAG[c.status] as any)" size="small">{{ CASE_STATUS[c.status] }}</el-tag>
            <span style="margin-left: 12px">{{ c.procedureType === 'SUMMARY' ? '简易程序' : '普通程序' }}</span>
            <span style="margin-left: 12px">立案 {{ c.filedAt }}</span>
            <span style="margin-left: 12px">办案期限 {{ detail.effectiveDeadline }}（含延长 {{ c.extensionDays }} 日与扣除期间）</span>
          </div>
        </div>
        <el-button @click="$router.push('/case/list')">返回列表</el-button>
      </div>
      <el-descriptions :column="3" border size="small" class="mb">
        <el-descriptions-item label="当事人">{{ c.partyName }}（{{ PARTY_TYPE[c.partyType] }}）</el-descriptions-item>
        <el-descriptions-item label="证照号">{{ c.partyCreditNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="法定代表人">{{ c.partyLegalRep || '-' }}</el-descriptions-item>
        <el-descriptions-item label="案由" :span="2">{{ cause.itemNo }}. {{ cause.category }}——{{ cause.description }}</el-descriptions-item>
        <el-descriptions-item label="涉案金额">{{ c.amountInvolved }}</el-descriptions-item>
        <el-descriptions-item label="案情摘要" :span="3">{{ c.summary || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="c.status === 'SUSPENDED'" label="中止原因" :span="3">{{ c.suspendReason }}（{{ c.suspendedAt }}）</el-descriptions-item>
        <el-descriptions-item v-if="c.status === 'TERMINATED'" label="终止原因" :span="3">{{ c.terminateReason }}（{{ c.terminatedAt }}）</el-descriptions-item>
        <el-descriptions-item v-if="c.status === 'CLOSED'" label="结案" :span="3">
          {{ CLOSE_REASON[c.closeReason] || c.closeReason }}（{{ c.closedAt }}）　案卷号：{{ c.archiveNo }}
        </el-descriptions-item>
      </el-descriptions>
      <!-- 流程操作：按状态提供合法动作 -->
      <div class="actions">
        <template v-if="c.status === 'INVESTIGATING'">
          <el-button v-if="c.procedureType === 'NORMAL'" type="primary" @click="dlg.report = true">调查终结报告</el-button>
          <el-button v-if="c.procedureType === 'SUMMARY'" type="primary" @click="openDecide">当场处罚决定</el-button>
          <el-button @click="onSuspend">中止调查</el-button>
          <el-button @click="onExtendCase">延长期限</el-button>
          <el-button type="warning" @click="onTerminate">终止调查</el-button>
        </template>
        <template v-if="c.status === 'SUSPENDED'">
          <el-button type="primary" @click="onResume">恢复调查</el-button>
        </template>
        <template v-if="c.status === 'REPORTED'">
          <el-button type="primary" @click="dlg.notice = true">处罚告知</el-button>
          <el-button @click="onSubmitReview">提交法制审核</el-button>
          <el-button @click="onExtendCase">延长期限</el-button>
        </template>
        <template v-if="c.status === 'NOTIFIED'">
          <el-button type="primary" @click="openDecide">作出决定</el-button>
          <el-button @click="dlg.statement = true">陈述申辩/听证</el-button>
          <el-button @click="onSubmitReview">提交法制审核</el-button>
          <el-button @click="onExtendCase">延长期限</el-button>
        </template>
        <template v-if="c.status === 'DECIDED'">
          <el-button type="primary" @click="dlg.deliver = true">登记送达</el-button>
          <el-button @click="dlg.execution = true">登记执行</el-button>
          <el-button @click="onCloseCase">结案</el-button>
        </template>
        <template v-if="c.status === 'DELIVERED'">
          <el-button type="primary" @click="dlg.execution = true">登记执行</el-button>
          <el-button @click="onLateFee">加处罚款测算</el-button>
          <el-button @click="onApproveDefer">批准暂缓/分期</el-button>
          <el-button @click="onCourtEnforce">申请法院强制执行</el-button>
          <el-button type="success" @click="onCloseCase">结案</el-button>
        </template>
        <el-button v-if="!['CLOSED', 'TERMINATED'].includes(c.status)" @click="dlg.meeting = true">集体讨论记录</el-button>
      </div>
    </el-card>

    <el-card>
      <el-tabs>
        <el-tab-pane :label="`办案人员(${detail.officers?.length || 0})`">
          <el-button size="small" class="mb" @click="dlg.officer = true" :disabled="['CLOSED','TERMINATED'].includes(c.status)">添加人员</el-button>
          <el-table :data="detail.officers" border size="small">
            <el-table-column prop="name" label="姓名" width="120" />
            <el-table-column prop="cert_no" label="执法证号" width="160" />
            <el-table-column label="分工" width="80">
              <template #default="{ row }">{{ row.duty === 'LEAD' ? '主办' : '协办' }}</template>
            </el-table-column>
            <el-table-column label="回避" width="200">
              <template #default="{ row }">
                <span v-if="row.avoided" class="danger">已回避：{{ row.avoid_reason }}</span>
                <el-button v-else size="small" text type="warning" @click="onAvoid(row)">申请回避</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`证据(${detail.evidences?.length || 0})`">
          <el-button size="small" class="mb" @click="dlg.evidence = true" :disabled="['CLOSED','TERMINATED'].includes(c.status)">添加证据</el-button>
          <el-table :data="detail.evidences" border size="small">
            <el-table-column label="种类" width="110">
              <template #default="{ row }">{{ EVIDENCE_TYPE[row.type] }}</template>
            </el-table-column>
            <el-table-column prop="name" label="名称" show-overflow-tooltip />
            <el-table-column prop="source" label="来源" width="140" show-overflow-tooltip />
            <el-table-column prop="obtained_at" label="取得日期" width="105" />
            <el-table-column label="先行登记保存" width="200">
              <template #default="{ row }">
                <template v-if="row.register_hold">
                  <span class="danger">保存中(限{{ row.hold_expire_at }})</span>
                  <el-button size="small" text type="primary" @click="onHoldDispose(row)">处理</el-button>
                </template>
                <span v-else>{{ row.hold_disposal ? '已处理:' + row.hold_disposal : '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="封存" width="220">
              <template #default="{ row }">
                <template v-if="row.sealed">
                  <span class="danger">封存至{{ row.seal_expire_at }}</span>
                  <el-button v-if="!row.seal_extended" size="small" text @click="onSeal(row, true)">延长</el-button>
                  <el-button size="small" text type="success" @click="onSeal(row, false)">解除</el-button>
                </template>
                <span v-else>-</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`文书(${detail.documents?.length || 0})`">
          <el-button size="small" class="mb" @click="dlg.document = true" :disabled="c.status === 'CLOSED'">制作文书</el-button>
          <el-table :data="detail.documents" border size="small">
            <el-table-column label="类型" width="130">
              <template #default="{ row }">{{ DOC_TYPE[row.doc_type] || row.doc_type }}</template>
            </el-table-column>
            <el-table-column prop="title" label="标题" show-overflow-tooltip />
            <el-table-column prop="made_at" label="日期" width="105" />
            <el-table-column prop="maker" label="制作人" width="100" />
            <el-table-column label="签名确认" width="90">
              <template #default="{ row }">{{ row.signed ? '已签' : '未签' }}</template>
            </el-table-column>
            <el-table-column label="查看" width="80">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="viewDoc(row)">查看</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`期限扣除(${detail.exclusions?.length || 0})`">
          <el-button size="small" class="mb" @click="dlg.exclusion = true" :disabled="['CLOSED','TERMINATED'].includes(c.status)">登记扣除期间</el-button>
          <p class="hint">检测检验、鉴定、听证、公告、专家评审时间不计入办案期限（第45条）</p>
          <el-table :data="detail.exclusions" border size="small">
            <el-table-column label="事由" width="110">
              <template #default="{ row }">{{ EXCLUSION_REASON[row.reason] }}</template>
            </el-table-column>
            <el-table-column prop="start_at" label="开始" width="110" />
            <el-table-column prop="end_at" label="结束" width="110" />
            <el-table-column prop="note" label="说明" />
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`审核·告知·讨论(${(detail.reviews?.length || 0) + (detail.notices?.length || 0)})`">
          <h4>法制审核（第37-40条）</h4>
          <el-table :data="detail.reviews" border size="small" class="mb">
            <el-table-column prop="requiredReason" label="触发情形" show-overflow-tooltip />
            <el-table-column prop="submittedAt" label="提交" width="105" />
            <el-table-column prop="deadlineAt" label="审核期限" width="105" />
            <el-table-column label="意见" width="200">
              <template #default="{ row }">
                <span v-if="row.reviewedAt">{{ REVIEW_OPINION[row.opinionType] }}：{{ row.opinion }}（{{ row.reviewer }}）</span>
                <el-button v-else size="small" type="primary" @click="onDoReview(row)">办理审核</el-button>
              </template>
            </el-table-column>
          </el-table>
          <h4>处罚告知与陈述申辩（第41条）</h4>
          <el-table :data="detail.notices" border size="small" class="mb">
            <el-table-column prop="notifiedAt" label="告知日期" width="105" />
            <el-table-column prop="proposedFine" label="拟罚款" width="110" align="right" />
            <el-table-column prop="proposedRecoup" label="拟追回基金" width="110" align="right" />
            <el-table-column label="听证" width="180">
              <template #default="{ row }">
                {{ row.hearingEntitled ? (row.hearingHeldAt ? `已听证 ${row.hearingHeldAt}` : row.hearingRequested ? '已申请' : '有听证权利') : '未达听证标准' }}
              </template>
            </el-table-column>
            <el-table-column prop="statement" label="陈述申辩" show-overflow-tooltip />
            <el-table-column prop="statementReview" label="复核意见" show-overflow-tooltip />
          </el-table>
          <h4>集体讨论（第44条）</h4>
          <el-table :data="detail.meetings" border size="small">
            <el-table-column prop="held_at" label="日期" width="105" />
            <el-table-column prop="attendees" label="参加人员" width="200" show-overflow-tooltip />
            <el-table-column prop="conclusion" label="结论" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="决定·送达·执行">
          <template v-if="detail.decision?.decisionType">
            <el-descriptions :column="3" border size="small" class="mb">
              <el-descriptions-item label="决定类型">{{ DECISION_TYPE[detail.decision.decisionType] }}</el-descriptions-item>
              <el-descriptions-item label="决定书文号">{{ detail.decision.decisionNo || '-' }}</el-descriptions-item>
              <el-descriptions-item label="决定日期">{{ detail.decision.decidedAt }}</el-descriptions-item>
              <el-descriptions-item label="罚款">{{ detail.decision.fineAmount }}</el-descriptions-item>
              <el-descriptions-item label="责令退回基金">{{ detail.decision.recoupAmount }}</el-descriptions-item>
              <el-descriptions-item label="没收违法所得">{{ detail.decision.confiscateAmount }}</el-descriptions-item>
              <el-descriptions-item label="决定内容" :span="3">{{ detail.decision.content }}</el-descriptions-item>
            </el-descriptions>
          </template>
          <p v-else class="hint">尚未作出处理决定</p>
          <h4>送达（第59条）</h4>
          <el-table :data="detail.deliveries" border size="small" class="mb">
            <el-table-column label="方式" width="110">
              <template #default="{ row }">{{ DELIVERY_METHOD[row.method] }}</template>
            </el-table-column>
            <el-table-column prop="delivered_at" label="送达日期" width="110" />
            <el-table-column prop="receiver" label="受送达人" width="140" />
            <el-table-column prop="note" label="备注" />
          </el-table>
          <h4>执行（第52-56条）　<span class="hint">暂缓/分期：{{ c.deferApproved ? '已批准' : '未申请' }}；法院强执：{{ c.courtEnforceApplied ? '已申请' : '未申请' }}</span></h4>
          <el-table :data="detail.executions" border size="small">
            <el-table-column label="类型" width="110">
              <template #default="{ row }">{{ EXEC_KIND[row.kind] }}</template>
            </el-table-column>
            <el-table-column prop="amount" label="金额" width="120" align="right" />
            <el-table-column prop="paid_at" label="缴付日期" width="110" />
            <el-table-column label="方式" width="110">
              <template #default="{ row }">{{ EXEC_METHOD[row.method] }}</template>
            </el-table-column>
            <el-table-column prop="note" label="备注" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 操作弹窗 -->
    <el-dialog v-model="dlg.officer" title="添加办案人员" width="440px">
      <el-form label-width="90px">
        <el-form-item label="姓名"><el-input v-model="officerForm.name" /></el-form-item>
        <el-form-item label="执法证号"><el-input v-model="officerForm.certNo" /></el-form-item>
        <el-form-item label="分工">
          <el-select v-model="officerForm.duty"><el-option label="主办" value="LEAD" /><el-option label="协办" value="MEMBER" /></el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.officer = false">取消</el-button>
        <el-button type="primary" @click="submit(`officers`, officerForm, 'officer')">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.evidence" title="添加证据（法定八类）" width="520px">
      <el-form label-width="110px">
        <el-form-item label="种类">
          <el-select v-model="evidenceForm.type" style="width: 100%">
            <el-option v-for="(v, k) in EVIDENCE_TYPE" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="evidenceForm.name" /></el-form-item>
        <el-form-item label="来源"><el-input v-model="evidenceForm.source" /></el-form-item>
        <el-form-item label="取得日期">
          <el-date-picker v-model="evidenceForm.obtainedAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="保管人"><el-input v-model="evidenceForm.keeper" /></el-form-item>
        <el-form-item label="先行登记保存">
          <el-switch v-model="evidenceForm.registerHold" /><span class="hint" style="margin-left: 8px">7个工作日内作出处理决定（第26条）</span>
        </el-form-item>
        <el-form-item label="封存">
          <el-switch v-model="evidenceForm.sealed" /><span class="hint" style="margin-left: 8px">不超过30日，可延长一次（第31条）</span>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="evidenceForm.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.evidence = false">取消</el-button>
        <el-button type="primary" @click="submit(`evidences`, evidenceForm, 'evidence')">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.document" title="制作文书" width="600px">
      <el-form label-width="90px">
        <el-form-item label="类型">
          <el-select v-model="documentForm.docType" style="width: 100%">
            <el-option v-for="(v, k) in DOC_TYPE" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题"><el-input v-model="documentForm.title" /></el-form-item>
        <el-form-item label="内容"><el-input v-model="documentForm.content" type="textarea" :rows="6" /></el-form-item>
        <el-form-item label="制作人"><el-input v-model="documentForm.maker" /></el-form-item>
        <el-form-item label="已签名确认"><el-switch v-model="documentForm.signed" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.document = false">取消</el-button>
        <el-button type="primary" @click="submit(`documents`, documentForm, 'document')">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.exclusion" title="登记不计入期限的期间" width="480px">
      <el-form label-width="90px">
        <el-form-item label="事由">
          <el-select v-model="exclusionForm.reason" style="width: 100%">
            <el-option v-for="(v, k) in EXCLUSION_REASON" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始日期">
          <el-date-picker v-model="exclusionForm.startAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-date-picker v-model="exclusionForm.endAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="exclusionForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.exclusion = false">取消</el-button>
        <el-button type="primary" @click="submit(`exclusions`, exclusionForm, 'exclusion')">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.report" title="案件调查终结报告（第36条）" width="640px">
      <p class="hint">应包括：当事人基本情况；案件来源、调查经过及行政强制措施情况；调查认定的事实及主要证据；违法行为性质；处理意见及依据。</p>
      <el-input v-model="reportContent" type="textarea" :rows="10" />
      <template #footer>
        <el-button @click="dlg.report = false">取消</el-button>
        <el-button type="primary" @click="onReport">提交（调查终结）</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.notice" title="行政处罚告知（第41条）" width="600px">
      <el-form label-width="110px">
        <el-form-item label="拟罚款金额"><el-input-number v-model="noticeForm.proposedFine" :min="0" :precision="2" style="width: 200px" /></el-form-item>
        <el-form-item label="拟追回基金"><el-input-number v-model="noticeForm.proposedRecoup" :min="0" :precision="2" style="width: 200px" /></el-form-item>
        <el-form-item label="事实理由依据"><el-input v-model="noticeForm.content" type="textarea" :rows="5" /></el-form-item>
      </el-form>
      <p class="hint">拟罚款达到听证标准将自动告知听证权利；当事人享有陈述权、申辩权。</p>
      <template #footer>
        <el-button @click="dlg.notice = false">取消</el-button>
        <el-button type="primary" @click="onNotify">送达告知书</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.statement" title="陈述申辩 / 听证" width="600px">
      <el-form label-width="110px">
        <el-form-item label="陈述申辩内容"><el-input v-model="statementForm.statement" type="textarea" :rows="4" /></el-form-item>
        <el-form-item label="复核意见"><el-input v-model="statementForm.statementReview" type="textarea" :rows="3" placeholder="成立的应当采纳；不得因陈述申辩加重处罚" /></el-form-item>
        <el-form-item label="申请听证"><el-switch v-model="statementForm.hearingRequested" /></el-form-item>
        <el-form-item label="听证日期">
          <el-date-picker v-model="statementForm.hearingHeldAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.statement = false">取消</el-button>
        <el-button type="primary" @click="onStatement">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.meeting" title="负责人集体讨论记录（第44条）" width="600px">
      <el-form label-width="90px">
        <el-form-item label="讨论日期">
          <el-date-picker v-model="meetingForm.heldAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="参加人员"><el-input v-model="meetingForm.attendees" /></el-form-item>
        <el-form-item label="讨论记录"><el-input v-model="meetingForm.record" type="textarea" :rows="4" placeholder="不同意见应当如实记录" /></el-form-item>
        <el-form-item label="结论"><el-input v-model="meetingForm.conclusion" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.meeting = false">取消</el-button>
        <el-button type="primary" @click="submit(`meetings`, meetingForm, 'meeting')">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.decide" title="作出处理决定（第43条）" width="640px">
      <el-form label-width="110px">
        <el-form-item label="决定类型">
          <el-select v-model="decisionForm.decisionType" style="width: 100%">
            <el-option v-for="(v, k) in DECISION_TYPE" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <template v-if="decisionForm.decisionType === 'PUNISH'">
          <el-form-item label="罚款金额"><el-input-number v-model="decisionForm.fineAmount" :min="0" :precision="2" style="width: 200px" /></el-form-item>
          <el-form-item label="责令退回基金"><el-input-number v-model="decisionForm.recoupAmount" :min="0" :precision="2" style="width: 200px" /></el-form-item>
          <el-form-item label="没收违法所得"><el-input-number v-model="decisionForm.confiscateAmount" :min="0" :precision="2" style="width: 200px" /></el-form-item>
          <el-form-item label="其他措施"><el-input v-model="decisionForm.otherMeasures" placeholder="约谈、建议解除服务协议等" /></el-form-item>
        </template>
        <el-form-item label="决定内容"><el-input v-model="decisionForm.content" type="textarea" :rows="5" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.decide = false">取消</el-button>
        <el-button type="primary" @click="onDecide">作出决定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.deliver" title="登记送达（第59条：7个工作日内）" width="480px">
      <el-form label-width="90px">
        <el-form-item label="送达方式">
          <el-select v-model="deliveryForm.method" style="width: 100%">
            <el-option v-for="(v, k) in DELIVERY_METHOD" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="送达日期">
          <el-date-picker v-model="deliveryForm.deliveredAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="受送达人"><el-input v-model="deliveryForm.receiver" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="deliveryForm.note" placeholder="电子送达须经当事人签订确认书" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.deliver = false">取消</el-button>
        <el-button type="primary" @click="onDeliver">登记</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.execution" title="登记执行（第52-55条）" width="480px">
      <el-form label-width="90px">
        <el-form-item label="类型">
          <el-select v-model="executionForm.kind" style="width: 100%">
            <el-option v-for="(v, k) in EXEC_KIND" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额"><el-input-number v-model="executionForm.amount" :min="0" :precision="2" style="width: 200px" /></el-form-item>
        <el-form-item label="缴付日期">
          <el-date-picker v-model="executionForm.paidAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="方式">
          <el-select v-model="executionForm.method" style="width: 100%">
            <el-option v-for="(v, k) in EXEC_METHOD" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="executionForm.note" /></el-form-item>
      </el-form>
      <p class="hint">当场收缴限 100 元以下；退回基金退原财政专户，罚款/没收上缴国库；加处罚款每日 3% 且不超过罚款本金。</p>
      <template #footer>
        <el-button @click="dlg.execution = false">取消</el-button>
        <el-button type="primary" @click="submit(`executions`, executionForm, 'execution')">登记</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.doc" :title="docView.title" width="640px">
      <p class="hint">{{ DOC_TYPE[docView.doc_type] }}　{{ docView.made_at }}　制作人：{{ docView.maker || '-' }}</p>
      <pre class="doc-content">{{ docView.content }}</pre>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'
import client from '../../api/client'
import {
  CASE_STATUS, CASE_STATUS_TAG, DECISION_TYPE, DELIVERY_METHOD, DOC_TYPE,
  EVIDENCE_TYPE, EXCLUSION_REASON, EXEC_KIND, EXEC_METHOD, PARTY_TYPE, REVIEW_OPINION,
} from './labels'

const CLOSE_REASON: Record<string, string> = {
  EXECUTED: '执行完毕', COURT: '法院受理强制执行', NO_NEED: '无须执行', OTHER: '其他',
}

const route = useRoute()
const id = route.params.id
const detail = ref<any>({})
const loading = ref(false)
const c = computed<any>(() => detail.value.caseFile || {})
const cause = computed<any>(() => detail.value.cause || {})

const dlg = reactive<Record<string, boolean>>({
  officer: false, evidence: false, document: false, exclusion: false, report: false,
  notice: false, statement: false, meeting: false, decide: false, deliver: false,
  execution: false, doc: false,
})

const today = new Date().toISOString().slice(0, 10)
const officerForm = reactive({ name: '', certNo: '', duty: 'MEMBER' })
const evidenceForm = reactive({ type: 'DOCUMENT', name: '', source: '', obtainedAt: today, keeper: '', note: '', registerHold: false, sealed: false })
const documentForm = reactive({ docType: 'INQUIRY_RECORD', title: '', content: '', maker: '', signed: false })
const exclusionForm = reactive({ reason: 'APPRAISE', startAt: today, endAt: today, note: '' })
const noticeForm = reactive({ content: '', proposedFine: 0, proposedRecoup: 0 })
const statementForm = reactive<any>({ statement: '', statementReview: '', hearingRequested: false, hearingHeldAt: null })
const meetingForm = reactive({ heldAt: today, attendees: '', record: '', conclusion: '' })
const decisionForm = reactive({ decisionType: 'PUNISH', fineAmount: 0, recoupAmount: 0, confiscateAmount: 0, otherMeasures: '', content: '' })
const deliveryForm = reactive({ method: 'DIRECT', deliveredAt: today, receiver: '', note: '' })
const executionForm = reactive({ kind: 'FINE', amount: 0, paidAt: today, method: 'BANK', note: '' })
const reportContent = ref('')
const docView = ref<any>({})

async function load() {
  loading.value = true
  try {
    const resp = await client.get(`/bureau/cases/${id}`)
    detail.value = resp.data.data
  } finally {
    loading.value = false
  }
}

/** 通用子表提交：POST /bureau/cases/{id}/{path} 后关弹窗刷新 */
async function submit(path: string, body: any, dlgKey: string) {
  await client.post(`/bureau/cases/${id}/${path}`, body)
  ElMessage.success('已保存')
  dlg[dlgKey] = false
  load()
}

async function onAvoid(row: any) {
  const { value } = await ElMessageBox.prompt('回避事由（第5条）', '申请回避', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id}/officers/${row.id}/avoid`, { reason: value })
  ElMessage.success('已回避')
  load()
}

async function onHoldDispose(row: any) {
  const disposal = await ElMessageBox.confirm('先行登记保存证据处理（第28条）', '处理决定', {
    distinguishCancelAndClose: true,
    confirmButtonText: '证据保全', cancelButtonText: '转封存',
  }).then(() => 'PRESERVE').catch((a) => (a === 'cancel' ? 'SEAL' : null))
  if (!disposal) return
  await client.post(`/bureau/cases/${id}/evidences/${row.id}/hold-disposal`, { disposal })
  ElMessage.success('已处理')
  load()
}

async function onSeal(row: any, extend: boolean) {
  await client.post(`/bureau/cases/${id}/evidences/${row.id}/seal?extend=${extend}`)
  ElMessage.success(extend ? '已延长30日' : '已解除封存')
  load()
}

function viewDoc(row: any) {
  client.get(`/bureau/cases/${id}/documents/${row.id}`).then((resp) => {
    docView.value = resp.data.data
    dlg.doc = true
  })
}

async function onSuspend() {
  const { value } = await ElMessageBox.prompt('中止情形（第42条：需以裁判结果为依据/送请解释确认/不可抗力/当事人下落不明等）', '中止调查', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id}/suspend`, { reason: value })
  load()
}

async function onResume() {
  await client.post(`/bureau/cases/${id}/resume`)
  ElMessage.success('已恢复调查，办案期限已顺延中止天数')
  load()
}

async function onTerminate() {
  const { value } = await ElMessageBox.prompt('终止情形（第47条：当事人死亡或组织终止无承受人/移送司法/其他）', '终止调查', { inputPattern: /\S+/, inputErrorMessage: '必填', type: 'warning' })
  await client.post(`/bureau/cases/${id}/terminate`, { reason: value })
  load()
}

async function onExtendCase() {
  const { value: days } = await ElMessageBox.prompt('延长天数（首次≤30日；继续延期须先有集体讨论记录，累计≤90日）', '延长办案期限', { inputPattern: /^\d+$/, inputErrorMessage: '请输入天数' })
  const { value: reason } = await ElMessageBox.prompt('延期理由', '延长办案期限', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id}/extend`, { days: Number(days), reason })
  ElMessage.success('已延长')
  load()
}

async function onReport() {
  if (!reportContent.value.trim()) {
    ElMessage.warning('请填写报告内容')
    return
  }
  await client.post(`/bureau/cases/${id}/report`, { content: reportContent.value })
  ElMessage.success('调查终结')
  dlg.report = false
  load()
}

async function onSubmitReview() {
  const { value } = await ElMessageBox.prompt('审核触发情形（第37条：罚款数额较大/听证案件/疑难复杂/重大公共利益等）', '提交法制审核', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id}/reviews`, { requiredReason: value })
  ElMessage.success('已提交法制审核（10个工作日内审核完毕）')
  load()
}

async function onDoReview(row: any) {
  const { value: reviewer } = await ElMessageBox.prompt('审核人（不得为本案办案人员）', '法制审核', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  const { value: opinionType } = await ElMessageBox.prompt('意见类型：AGREE同意/CONTINUE继续调查/CHANGE变更/CORRECT纠正/OTHER其他', '法制审核', { inputValue: 'AGREE', inputPattern: /^(AGREE|CONTINUE|CHANGE|CORRECT|OTHER)$/, inputErrorMessage: '类型无效' })
  const { value: opinion } = await ElMessageBox.prompt('审核意见', '法制审核', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/reviews/${row.id}`, { reviewer, opinionType, opinion })
  ElMessage.success('审核完成')
  load()
}

async function onNotify() {
  if (!noticeForm.content.trim()) {
    ElMessage.warning('请填写告知内容')
    return
  }
  await client.post(`/bureau/cases/${id}/notice`, noticeForm)
  ElMessage.success('已告知当事人')
  dlg.notice = false
  load()
}

async function onStatement() {
  await client.post(`/bureau/cases/${id}/statement`, statementForm)
  ElMessage.success('已保存')
  dlg.statement = false
  load()
}

function openDecide() {
  const n = detail.value.notices?.[0]
  if (n) {
    decisionForm.fineAmount = n.proposedFine
    decisionForm.recoupAmount = n.proposedRecoup
  }
  dlg.decide = true
}

async function onDecide() {
  if (!decisionForm.content.trim()) {
    ElMessage.warning('请填写决定内容')
    return
  }
  await client.post(`/bureau/cases/${id}/decide`, decisionForm)
  ElMessage.success('决定已作出')
  dlg.decide = false
  load()
}

async function onDeliver() {
  await client.post(`/bureau/cases/${id}/deliver`, deliveryForm)
  ElMessage.success('已送达')
  dlg.deliver = false
  load()
}

async function onLateFee() {
  const resp = await client.get(`/bureau/cases/${id}/late-fee-quote`)
  const q = resp.data.data
  ElMessageBox.alert(
    `缴款期限：${q.payDeadline}；逾期 ${q.overdueDays} 天；按日3%累计 ${q.accrued} 元；封顶后应加处 ${q.capped} 元（不超过罚款本金 ${q.fineAmount} 元）`,
    '加处罚款测算（第55条）',
  )
}

async function onApproveDefer() {
  await client.post(`/bureau/cases/${id}/approve-defer`)
  ElMessage.success('已批准暂缓/分期缴纳')
  load()
}

async function onCourtEnforce() {
  await client.post(`/bureau/cases/${id}/court-enforce`)
  ElMessage.success('已登记申请法院强制执行')
  load()
}

async function onCloseCase() {
  const { value } = await ElMessageBox.prompt('结案报告（经负责人批准后结案归档，一案一卷）', '结案', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id}/close`, { closeReport: value })
  ElMessage.success('已结案归档')
  load()
}

onMounted(load)
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.toolbar { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; }
.danger { color: #c0392b; font-size: 12px; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; }
.doc-content { white-space: pre-wrap; background: #f8f9fb; padding: 12px; border-radius: 4px; font-family: inherit; }
h4 { margin: 8px 0; }
</style>
