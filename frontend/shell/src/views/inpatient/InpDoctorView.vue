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
      <!-- v74（1006★ 唯一缺口）：备注/注意事项/加急对开药与开申请同时生效，随本次开立一并上送；
           长度与 V171 列宽一致（200）。开立后清空，避免上一条的备注串到下一条。 -->
      <div class="add-row">
        <el-input v-model="remark" placeholder="备注（随医嘱保存，护士站可见）" maxlength="200" clearable style="width: 300px" />
        <el-input v-model="notice" placeholder="注意事项" maxlength="200" clearable style="width: 300px" />
        <el-checkbox v-model="urgent">加急</el-checkbox>
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
        <el-table-column label="备注 / 注意事项" min-width="160">
          <template #default="{ row }">
            <el-tag v-if="row.urgent" type="danger" size="small" style="margin-right: 4px">加急</el-tag>
            <span v-if="row.remark">{{ row.remark }}</span>
            <span v-if="row.notice" style="color: #c00; margin-left: 6px">注意：{{ row.notice }}</span>
          </template>
        </el-table-column>
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
          <!-- v71 包 C（1089★/2465★）：书写过程中展示病历完整性结果。
               该预检端点此前只在出院办理页被消费一次，医生写的时候看不到还缺什么。
               **只读、不拦**：出院与归档处的既有判定一个字节没动；
               档位值不上屏（v64 已把「gate 档位以配置值上屏」清掉一次，不开倒车）。 -->
          <el-alert v-if="integrity" :closable="false" show-icon style="margin-bottom: 8px"
                    :type="integrity.complete ? 'success' : 'warning'"
                    :title="integrity.complete
                      ? '病历完整性预检：当前无缺项'
                      : `病历完整性预检：还缺 ${(integrity.missing || []).length} 项 —— ${(integrity.missing || []).join('、')}`" />
          <el-form inline>
            <el-form-item>
              <el-select v-model="recordType" style="width: 130px" :disabled="!!editingRecord">
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
              <el-select v-model="roundLevel" style="width: 120px" :disabled="!!editingRecord">
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
                           :label="`${t.name}（${t.scopeName ?? (t.dept_id ? '科室' : '通用')}）`" :value="t.id as number" />
              </el-select>
            </el-form-item>
          </el-form>
          <!-- v79（1082★/2458）：跨患者复制粘贴管控，口径与门诊同（off 放行 / warn 确认 / block 拒绝，
               只识别本系统内复制的片段）。挂在编辑区与时间线的外层容器上：copy/cut/paste 从内部冒泡上来，
               时间线里复制的既往记录也会记下来源患者。**纯前端行为，住院病历保存端点一行未改。** -->
          <div @copy="onCopy" @cut="onCopy" @paste="onPaste">
          <!-- v79 审阅修补（三）（乙组反驳 B1-E1/B2-1b）：放行档也显示，与门诊 DoctorStationView 三档同写法 -->
          <el-tag size="small" :type="copyMode === 'block' ? 'danger' : copyMode === 'warn' ? 'warning' : 'info'"
                  style="margin-bottom: 6px">
            跨患者粘贴：{{ copyMode === 'block' ? '禁止' : copyMode === 'warn' ? '需确认' : '放行' }}
          </el-tag>
          <el-input v-model="recordContent" type="textarea" :rows="4"
                    :placeholder="recordType === 'ROUND' ? '查房意见' : '病历内容'" />
          <el-input v-if="recordType === 'ROUND'" v-model="superiorCorrection" type="textarea" :rows="2"
                    placeholder="上级修正意见（可空）" style="margin-top: 6px" />
          <!-- v79（2457★ 住院侧）：不新增状态列——未签名即「暂存」、已签名即「已提交」。
               v79 审阅修补（乙组 D2/N10）：暂存记录此前写好就改不了；现时间线未签名记录有「修改」，
               回填到本编辑区，保存走 PUT /records/{id}（每次保存多一版留痕）。 -->
          <template v-if="editingRecord">
            <el-button type="primary" style="margin-top: 8px" :loading="savingEdit" @click="saveEdit">保存修改</el-button>
            <el-button style="margin-top: 8px" @click="cancelEdit">取消修改</el-button>
            <span class="sign-tip">正在修改暂存记录《{{ editingRecord.title }}》；保存后仍为暂存（未签名），并多一版留痕</span>
          </template>
          <template v-else>
            <el-button type="primary" style="margin-top: 8px" @click="addRecord">暂存记录</el-button>
            <span class="sign-tip">暂存后可在下方记录旁点「修改」续写，写完点「提交（签名）」；签名后原文冻结，如需更正只能追加补正</span>
          </template>
          <el-timeline style="margin-top: 16px">
            <el-timeline-item v-for="r in records" :key="r.id as number"
                              :timestamp="`${fmtDateTime(r.createdAt)} · ${recordTypeNames[r.recordType as string]}`">
              <b>{{ r.title }}</b>
              <el-tag v-if="r.signature" size="small" type="success" style="margin-left: 6px">已提交（已签名）</el-tag>
              <el-tag v-else size="small" type="info" style="margin-left: 6px">暂存（未签名）</el-tag>
              <el-button v-if="canEditInpRecord(r)" size="small" link type="primary" style="margin-left: 6px"
                         @click="startEdit(r)">修改</el-button>
              <el-button v-if="!r.signature" size="small" link type="primary" style="margin-left: 6px"
                         @click="signRecord(r)">提交（签名）</el-button>
              <!-- 阻塞4：签名冻结病历只能追加补正，不能改原文 -->
              <el-button v-if="r.signature" size="small" link type="warning" style="margin-left: 6px"
                         @click="openAmend(r)">补正</el-button>
              <!-- v79（994★③）：版本留痕直达，带 INP 与本条记录 id。新标签打开：不丢编辑区里尚未暂存的正文；
                   本人不持有 /emr-version 菜单时不出现，不摆死按钮（与门诊医生站同一判据）。 -->
              <el-button v-if="canOpenEmrVersion" size="small" link style="margin-left: 6px"
                         @click="openEmrVersions(r)">版本留痕</el-button>
              <p class="record-content">{{ r.content }}</p>
            </el-timeline-item>
          </el-timeline>
          </div>
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
              <template #default="{ row }">{{ fmtDateTime(row.measuredAt) }}</template>
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
          <template #default="{ row }">{{ fmtDateTime(row.created_at) }}</template>
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
                          :timestamp="`${fmtDateTime(a.amended_at)} · ${a.amended_by_name ?? ('用户' + a.amended_by)}`">
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
import { fmtDateTime } from '../../utils/date'
import VitalsChart from '../../components/VitalsChart.vue'
import { useEmrPasteGuard } from '../../components/EmrRefDrawer.vue'
import { useAuthStore } from '../../stores/auth'
import { canEditInpRecord, inpEditFormOf, inpUpdatePayload } from '../../utils/inp-record-edit'

/** v42：体温单打印（周次由打印页自行翻页，此处固定从第 1 住院周进） */
function printTempSheet() {
  if (!current.value) return
  window.open(`/print?type=temp-sheet&id=${current.value.id}&week=1`, '_blank')
}

const admissions = ref<Record<string, unknown>[]>([])
const current = ref<Record<string, unknown> | null>(null)
/** v71 包 C：病历完整性预检结果（只读展示，不参与任何拦截）。 */
const integrity = ref<{ complete: boolean; missing: string[] } | null>(null)

async function loadIntegrity(admissionId: number) {
  try {
    integrity.value = (await client.get(
      `/inpatient/admissions/${admissionId}/emr-integrity`)).data.data
  } catch {
    // 预检取不到不影响书写——宁可不显示，也不摆一条误导的「无缺项」
    integrity.value = null
  }
}
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
const remark = ref('')            // v74（1006★）：备注 / 注意事项 / 加急
const notice = ref('')
const urgent = ref(false)
function orderExtras() {
  return { remark: remark.value || null, notice: notice.value || null, urgent: urgent.value }
}
function resetExtras() {
  remark.value = ''
  notice.value = ''
  urgent.value = false
}
const tab = ref('orders')
const records = ref<Record<string, unknown>[]>([])
const vitals = ref<Record<string, unknown>[]>([])
const recordType = ref('PROGRESS')
const recordTitle = ref('')
const recordContent = ref('')
/** v79 审阅修补（乙组 D2/N10）：正在编辑区修改的暂存记录；null = 编辑区用于新建 */
const editingRecord = ref<Record<string, unknown> | null>(null)
const savingEdit = ref(false)
// v42：PREOP 补入中文名——此前 EmrIntegrityService 判「缺术前小结」，而列表里 PREOP 行显示为 undefined
const recordTypeNames: Record<string, string> = { ADMISSION: '入院记录', FIRST_PROGRESS: '首次病程', PROGRESS: '病程记录', ROUND: '三级查房', PREOP: '术前小结', DISCHARGE: '出院小结' }
const roundLevel = ref('ATTENDING')
const superiorCorrection = ref('')

// v42 起病历模板下拉；v76 改走 GET /emr-templates/visible?type=EMR（按登录人四级可见范围，含被授权），标签按 scopeName
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
  // v74 补 993★ 硬缺口（v68 三方复核）：此前走 /emr-templates?deptId=患者所在科室，不认登录人与使用范围，
  // 下拉里含所有人的个人模板。改走 /emr-templates/visible：按登录医生的科室与授权过滤（DEPT 本科室 + 被授权科室，
  // PERSONAL 本人 + 被授权个人，GLOBAL/HOSPITAL 人人可见），默认模板置顶。口径从"患者所在科室"改为"医生所在科室"。
  emrTemplates.value = (await client.get('/emr-templates/visible', { params: { type: 'EMR' } })).data.data ?? []
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
  // v74 复核（1006★ 第二轮审计者）：备注/注意事项/加急是页面级值，换患者必须清空，
  // 否则给 A 填了没开立、切到 B 再点开药，备注会落到 B 的医嘱上。同一患者刷新（开立/停嘱后）不清。
  if (row?.id !== current.value?.id) {
    resetExtras()
    // v79 审阅修补：换患者时撤掉进行中的修改，不把 A 的记录 id 带到 B 的路径上
    if (editingRecord.value) clearEditor()
  }
  current.value = row
  account.value = null
  emrTemplateId.value = null
  emrTemplates.value = []
  if (!row) return
  const [ws, rec, vit] = await Promise.all([
    client.get(`/inpatient/admissions/${row.id}/workspace`),
    client.get(`/inpatient/admissions/${row.id}/records`),
    client.get(`/inpatient/admissions/${row.id}/vitals`),
    loadIntegrity(row.id as number),
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
  ElMessage.success('已暂存（未签名）')
  recordContent.value = ''
  recordTitle.value = ''
  emrTemplateId.value = null
  await open(current.value)
}

/* ---------- v79 审阅修补（乙组 D2/N10，2457★）：修改未签名（暂存）记录 ---------- */
function clearEditor() {
  editingRecord.value = null
  recordContent.value = ''
  recordTitle.value = ''
  superiorCorrection.value = ''
  emrTemplateId.value = null
}

async function startEdit(r: Record<string, unknown>) {
  if (!canEditInpRecord(r)) return
  // 编辑区里有尚未暂存的新内容时先确认，别一声不响地覆盖掉
  if (!editingRecord.value && recordContent.value.trim()) {
    const ok = await ElMessageBox.confirm('编辑区里尚未暂存的内容将被该记录的正文替换，是否继续？', '修改暂存记录',
      { type: 'warning' }).then(() => true).catch(() => false)
    if (!ok) return
  }
  const f = inpEditFormOf(r)
  editingRecord.value = r
  recordType.value = f.recordType
  recordTitle.value = f.title
  recordContent.value = f.content
  superiorCorrection.value = f.superiorCorrection
  if (f.roundLevel) roundLevel.value = f.roundLevel
  emrTemplateId.value = null
}

function cancelEdit() {
  clearEditor()
}

async function saveEdit() {
  if (!current.value || !editingRecord.value) return
  if (!recordContent.value.trim()) {
    ElMessage.warning(recordType.value === 'ROUND' ? '请填写查房意见' : '请填写病历内容')
    return
  }
  savingEdit.value = true
  try {
    await client.put(`/inpatient/admissions/${current.value.id}/records/${editingRecord.value.id}`,
      inpUpdatePayload({
        recordType: recordType.value,
        title: recordTitle.value,
        content: recordContent.value,
        superiorCorrection: superiorCorrection.value,
      }))
    ElMessage.success('已保存修改（仍为暂存，未签名）')
    clearEditor()
    await open(current.value)
  } catch {
    /* 拦截器已弹错（如 9103 已被签名）：编辑区内容保留，医生可复制后另行处理 */
  } finally {
    savingEdit.value = false
  }
}

/**
 * v79（1082★/2458）：住院病历跨患者复制粘贴管控，与门诊共用 useEmrPasteGuard（导出签名未改）。
 * 档位取自 GET /outpatient/emr-ref/copy-policy（sys_config emr.copy.cross_patient，经系统配置接口设置、无独立界面），
 * 读不到时维持默认 warn。**只在前端**：block 档也只是拒绝这一次粘贴动作，服务端不做任何拦截。
 */
const { mode: copyMode, loadPolicy: loadCopyPolicy, onCopy, onPaste } = useEmrPasteGuard(() => ({
  patientId: (current.value?.patientId as number | undefined) ?? null,
  patientName: String(current.value?.patientName ?? ''),
}))

/* v79（994★③）：住院病历版本留痕入口。此前只能去 /emr-version 手选 INP、手填 inp_medical_record.id。 */
const auth = useAuthStore()
/** 与 router 守卫同一判据：菜单未知不拦；已知则须持有 /emr-version，否则点进去会被踢回首页 */
const canOpenEmrVersion = computed(() => {
  const menus = auth.user?.menus ?? []
  return menus.length === 0 || menus.some((m) => m.path === '/emr-version')
})
function openEmrVersions(r: Record<string, unknown>) {
  if (!r.id) return
  const q = new URLSearchParams({ emrType: 'INP', emrId: String(r.id), from: 'inp-doctor' })
  window.open(`/emr-version?${q.toString()}`, '_blank')
}

// 1.0.4：病历 CA 签名（签名后冻结标识）
async function signRecord(r: Record<string, unknown>) {
  if (!current.value) return
  // v79 审阅修补：正在编辑区修改的这条若直接签名，签的是库里的旧正文、屏上改动随之作废——先保存或取消
  if (editingRecord.value && editingRecord.value.id === r.id) {
    ElMessage.warning('这条记录正在修改中：请先「保存修改」或「取消修改」，再提交（签名）')
    return
  }
  await client.post(`/inpatient/admissions/${current.value.id}/records/${r.id}/sign`)
  ElMessage.success('已提交（已签名）')
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
    lines: [{ orderType: 'DRUG', itemId: drugId.value, qty: qty.value, usageRoute: route.value, frequency: freq.value, dosePerTime: dose.value, orderNature: orderNature.value, ...orderExtras() }],
  })
  ElMessage.success(orderNature.value === 'LONG' ? '长期医嘱已开立（按执行行逐日计费）' : '医嘱已开立')
  drugId.value = null
  resetExtras()
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
    lines: [{ orderType: item?.category ?? 'TREAT', itemId: itemId.value, qty: 1, ...orderExtras() }],
  })
  ElMessage.success('申请已开立')
  itemId.value = null
  resetExtras()
  await open(current.value)
}

onMounted(async () => {
  void loadCopyPolicy()
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
.sign-tip { margin-left: 10px; font-size: 12px; color: #909399; }
</style>
