<template>
  <div class="doctor-page" :class="{ 'range-mode': rangeMode }">
    <el-card class="worklist">
      <template #header>
        接诊队列
        <el-button link type="primary" style="float: right" @click="loadWorklist">刷新</el-button>
        <!-- v70 包 B（965★ 跨日期检索 / 2033★「我的患者」）。
             不勾「跨日期」时只发 date 一个参数——后端「零条件 = 旧行为」那条路径原样保留。 -->
        <div class="queue-filter no-print">
          <el-date-picker v-if="!rangeMode" v-model="today" type="date" size="small"
                          value-format="YYYY-MM-DD" :clearable="false" style="width: 132px"
                          @change="loadWorklist" />
          <el-date-picker v-else v-model="dateRange" type="daterange" size="small" unlink-panels
                          value-format="YYYY-MM-DD" start-placeholder="起" end-placeholder="止"
                          style="width: 212px" @change="loadWorklist" />
          <el-checkbox v-model="rangeMode" size="small" @change="onRangeModeChange">跨日期</el-checkbox>
          <el-checkbox v-model="mineOnly" size="small" @change="loadWorklist">我的患者</el-checkbox>
        </div>
      </template>
      <el-table :data="worklist" highlight-current-row height="calc(100vh - 220px)" @current-change="openPatient">
        <el-table-column prop="regNo" label="号" width="50" />
        <!-- v79 审阅修补（三）（甲组反驳 A2-U3）：跨日期时同一患者不同日的就诊要分得清（后端 worklist 追加 visitDate 键） -->
        <el-table-column v-if="rangeMode" prop="visitDate" label="就诊日期" width="96" />
        <el-table-column prop="patientName" label="姓名" width="80" />
        <el-table-column label="性别/年龄" width="76">
          <template #default="{ row }">
            {{ { M: '男', F: '女', U: '?' }[row.sex as string] }}/{{ row.age ?? '?' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="66">
          <template #default="{ row }">
            <el-tag :type="row.status === 'REGISTERED' ? 'warning' : 'success'" size="small">
              {{ row.status === 'REGISTERED' ? '待诊' : '已诊' }}
            </el-tag>
          </template>
        </el-table-column>
        <!-- v43：诊毕未签一目了然（病历已写但未签名即标红） -->
        <el-table-column label="病历" width="62">
          <template #default="{ row }">
            <el-tag v-if="row.emrSigned" type="success" size="small">已签</el-tag>
            <el-tag v-else-if="row.emrWritten" type="danger" size="small">未签</el-tag>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="current" class="workspace">
      <template #header>
        <b>{{ current.patientName }}</b>
        （{{ current.patientNo }} · {{ { M: '男', F: '女', U: '未知' }[current.sex as string] }} · {{ current.age }} 岁）
        <el-tag v-if="current.allergyHistory" type="danger" style="margin-left: 8px">
          过敏：{{ current.allergyHistory }}
        </el-tag>
        <el-button v-if="current.status === 'REGISTERED'" type="primary" size="small" style="float: right"
                   :disabled="!workspaceReady" @click="startVisit">
          接 诊
        </el-button>
        <!-- v37 门诊病历连续调阅：历次就诊/既往诊断抽屉 -->
        <el-button size="small" style="float: right; margin-right: 8px" :disabled="!workspaceReady" @click="openHistory">历史就诊</el-button>
      </template>

      <el-alert v-if="!workspaceReady" :title="workspaceLoading ? '正在加载本次就诊，请稍候' : '本次就诊未加载，请重新选择患者'"
                type="info" :closable="false" show-icon />
      <template v-else>
      <!-- ============ v51 车道F4：CDSS 合理用药审查提示（warn 档） ============
           后端把审查警告挂在开单返回体的**订单元素**上（字段 cdssWarnings，@JsonInclude(NON_EMPTY)）。
           warn 档的全部意义就是「不拦截，但医生当场看得见」——前端不显示，
           这一版的过敏/重复用药/特殊人群/相互作用提示对医生就等于不存在。
           刻意放在页签**之外**：开单成功后页签会切到「已开医嘱」，放在处方页签里医生就看不见了。
           无警告时（字段不出现）这一整块不渲染，页面表现与 v50 逐像素相同。 -->
      <el-alert v-if="rxCdssWarnings.length && !cdssWarnBarClosed" type="error" show-icon :closable="true"
                class="cdss-warn-bar" @close="cdssWarnBarClosed = true"
                :title="`本次开单有 ${rxCdssWarnings.length} 条合理用药审查提示（不拦截开单，请逐条确认）`">
        <template #default>
          <div v-for="(w, i) in rxCdssWarnings" :key="i" class="cdss-warn-item">
            <el-tag v-if="warnTag(w)" type="danger" size="small" effect="plain" class="cdss-warn-tag">
              {{ warnTag(w) }}
            </el-tag>
            <span class="cdss-warn-text">{{ warnBody(w) }}</span>
          </div>
          <div class="cdss-warn-src">
            以上为后端合理用药审查原文照录，前端未做任何改写、补全或推断。
            <el-button link type="danger" @click="cdssWarnDialog = true">展开查看</el-button>
          </div>
        </template>
      </el-alert>
      <!-- 横幅关掉之后仍留一个入口：警告要能被回看，关掉一次就再也找不到等于没提示过 -->
      <div v-else-if="rxCdssWarnings.length" class="cdss-warn-reopen">
        <el-button link type="danger" @click="cdssWarnDialog = true">
          本次开单有 {{ rxCdssWarnings.length }} 条合理用药审查提示，点此重看
        </el-button>
      </div>

      <el-tabs v-model="tab">
        <el-tab-pane label="病历" name="emr">
          <!-- v43：签名冻结态——原文只读，页首写明签名人与签名时间 -->
          <el-alert v-if="emrSigned" type="success" :closable="false" show-icon style="margin-bottom: 10px"
                    :title="`本次病历已签名冻结 · 签名人：${emrSignerName || '—'} · 签名时间：${emrSignedAtText || '—'}`" />

          <!-- v79 车道B（2457★ 门诊侧）：暂存/提交状态。不新增状态列——未签名即「暂存」、已签名即「已提交」；
               判据只有 workspace 的 emr.signature（emrSigned）与 emr.id（currentEmrId），签名人/时间取既有
               emrSignerName / emr.signedAt。病历还没存过（无 id）时不说「暂存」：那时库里一个字都没有。 -->
          <div class="emr-status no-print">
            <el-tag v-if="emrSigned" type="success" size="small">{{ emrStatusText }}</el-tag>
            <el-tag v-else-if="currentEmrId" type="warning" size="small">暂存（未签名）</el-tag>
            <el-tag v-else type="info" size="small">新病历（尚未暂存）</el-tag>
          </div>

          <!-- v45 车道J：1092★ 新建病历自动带出 + 992★ 引用抽屉入口 + 1082★ 跨患者粘贴管控档位 -->
          <div class="ref-bar no-print">
            <span v-if="basicBrought" class="ref-auto" :title="basicBrought">已自动带出：{{ basicBrought }}</span>
            <span v-else class="dim">—</span>
            <span class="ref-spacer" />
            <span class="dim">插入到</span>
            <el-select v-model="insertTarget" size="small" style="width: 112px" :disabled="emrSigned">
              <el-option v-for="s in EMR_SECTIONS" :key="s.key" :label="s.label" :value="s.key" />
            </el-select>
            <el-button size="small" @click="refVisible = true">引用资料</el-button>
            <!-- v69 包 A（1079★/2455）：按既往病历新建。端点此前齐备而界面无入口，医生进不去。
                 **只取正文**：挑一份 → 拿到正文 → 插进选定的正文段 → 仍走既有「保存病历」写入，
                 本轮不新增任何写病历路径（端点 javadoc 里的硬约束）。 -->
            <el-button size="small" :disabled="emrSigned" @click="openPriorRecords">按既往病历新建</el-button>
            <!-- 档位常驻可见：医生该知道院里此刻是"需确认"还是"禁止"，而不是粘贴时才被弹一脸 -->
            <!-- v79 复核（D5）：放行档也显示，评委切到 off 时能看到档位确实变了 -->
            <el-tag size="small" :type="copyMode === 'block' ? 'danger' : copyMode === 'warn' ? 'warning' : 'info'">
              跨患者粘贴：{{ copyMode === 'block' ? '禁止' : copyMode === 'warn' ? '需确认' : '放行' }}
            </el-tag>
          </div>
          <el-alert v-if="prefilledNote" type="info" show-icon style="margin-bottom: 10px"
                    :title="prefilledNote" @close="prefilledNote = ''" />
          <!-- v79 车道B（988★）：新病历自动套用科室默认模板后的黄条。只改本页表单，点「暂存」才落库；撤销恢复为套用前 -->
          <el-alert v-if="autoTpl" type="warning" :closable="false" show-icon style="margin-bottom: 10px"
                    :title="`已自动套用科室默认模板《${autoTpl.name}》，点「暂存」后生效；不需要可点「撤销」恢复为套用前。`">
            <template #default>
              <div v-if="autoTpl.notes.length" class="dim">{{ autoTpl.notes.join('；') }}</div>
              <el-button size="small" style="margin-top: 4px" @click="undoAutoTemplate">撤销</el-button>
            </template>
          </el-alert>

          <!-- 989★/1075★ 结构化录入：只有当院里真的维护了病历模板时才出现这一行，不留死入口 -->
          <div v-if="emrTemplates.length" class="ref-bar no-print">
            <el-select v-model="structTemplateId" clearable filterable :disabled="emrSigned"
                       placeholder="结构化模板（按模板定义的元素录入）" style="width: 300px"
                       @change="loadStructFields">
              <el-option v-for="t in emrTemplates" :key="t.id as number"
                         :label="`${t.name}（${t.scopeName ?? (t.dept_id ? '科室' : '通用')}）`" :value="t.id as number" />
            </el-select>
            <!-- v76（993★ ③）：整段套用模板正文。与结构化录入互不替代：结构化只读模板的元素定义，这里读模板的 content -->
            <el-button size="small" :disabled="emrSigned || !structTemplateId" @click="applyTemplateBody">套用正文</el-button>
            <span v-if="structHint" class="dim">{{ structHint }}</span>
            <span v-if="structFields.length" class="dim">
              已选结构化元素时，保存会把结构化内容追加到现病史末尾；套用正文若也写了现病史，两部分会并存。
            </span>
          </div>
          <StructuredFieldForm :key="workspaceRenderKey + ':' + structTemplateId" v-if="structFields.length" v-model="structValues" :fields="structFields"
                               :disabled="emrSigned" preview-label="保存后写入现病史" />

          <!-- 1082★ 复制粘贴管控挂在正文输入区的外层容器上：copy/cut/paste 从内部 textarea 冒泡上来，
               一处绑定覆盖全部五段正文。**纯前端行为，保存端点一行未改。** -->
          <div @copy="onCopy" @cut="onCopy" @paste="onPaste">
          <el-form :model="emr" label-width="80px">
            <el-form-item label="主诉"><el-input v-model="emr.chiefComplaint" :disabled="emrSigned" /></el-form-item>
            <el-form-item label="现病史"><el-input v-model="emr.presentIllness" type="textarea" :rows="3" :disabled="emrSigned" /></el-form-item>
            <el-form-item label="既往史"><el-input v-model="emr.pastHistory" type="textarea" :rows="2" :disabled="emrSigned" /></el-form-item>
            <el-form-item label="体格检查"><el-input v-model="emr.physicalExam" type="textarea" :rows="2" :disabled="emrSigned" /></el-form-item>
            <!-- v44 车道E：诊断录入区（偏离表 977 自定义名称 / 982 前后缀 / 983 确诊疑诊 /
                 1084 中西医 + 常用诊断 / 979 诊断助手三源）。第一条仍是主诊断，口径不变。 -->
            <el-form-item label="诊断">
              <div class="diag-block">
                <!-- 特殊病种院内登记提示（984）：只提示，不参与任何拦截 -->
                <el-alert v-if="specialDiseases.length" type="warning" :closable="false" show-icon
                          style="margin-bottom: 6px"
                          :title="`该患者已登记特殊病种（院内）：${specialDiseases.map((s) => s.diseaseName).join('、')}`" />

                <div class="diag-add">
                  <el-radio-group v-model="diagSystem" size="small" :disabled="emrSigned">
                    <el-radio-button value="ICD10">西医</el-radio-button>
                    <el-radio-button value="TCM">中医</el-radio-button>
                  </el-radio-group>
                  <el-select v-if="diagSystem === 'ICD10'" v-model="icdPick" filterable remote reserve-keyword
                             :disabled="emrSigned" :remote-method="searchIcd" placeholder="搜索 ICD（名称/编码/拼音）"
                             style="width: 300px" @change="addDiagFromIcd">
                    <el-option v-for="i in icdOptions" :key="i.code" :label="`${i.name} (${i.code})`" :value="i.code" />
                  </el-select>
                  <el-input v-else v-model="tcmName" :disabled="emrSigned" style="width: 300px"
                            placeholder="中医诊断名称（本平台不预置中医码表，按名称录入）"
                            @keyup.enter="addDiagCustom" />
                  <el-button v-if="diagSystem === 'TCM'" :disabled="emrSigned || !tcmName.trim()"
                             type="primary" size="small" @click="addDiagCustom">加入</el-button>
                  <el-button size="small" :disabled="emrSigned" @click="openAssist">诊断助手</el-button>
                  <el-button size="small" :disabled="emrSigned" @click="specialVisible = true">特殊病种</el-button>
                </div>

                <el-table v-if="diagList.length" :data="diagList" size="small" style="margin-top: 6px">
                  <el-table-column label="主/次" width="58">
                    <template #default="{ $index }">
                      <el-tag :type="$index === 0 ? 'danger' : 'info'" size="small">
                        {{ $index === 0 ? '主' : '次' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="体系" width="58">
                    <template #default="{ row }">
                      <el-tag size="small" :type="row.diagSystem === 'TCM' ? 'success' : 'primary'">
                        {{ row.diagSystem === 'TCM' ? '中医' : '西医' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <!-- v80 审阅修补二（N-4）：前缀 / 后缀 / 自定义描述 maxlength 与库列宽一致（V135：varchar(32) / (32) / (128)），
                       后端 saveEmr 另有同口径预检（超长 4000 点名第几条哪一栏） -->
                  <el-table-column label="前缀" width="96">
                    <template #default="{ row }">
                      <el-input v-model="row.prefix" size="small" maxlength="32" :disabled="emrSigned" placeholder="如 疑似" />
                    </template>
                  </el-table-column>
                  <el-table-column label="诊断名称" min-width="180">
                    <template #default="{ row }">
                      {{ row.icdName }}<span v-if="row.icdCode" class="dim"> ({{ row.icdCode }})</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="后缀" width="96">
                    <template #default="{ row }">
                      <el-input v-model="row.suffix" size="small" maxlength="32" :disabled="emrSigned" placeholder="如 术后" />
                    </template>
                  </el-table-column>
                  <el-table-column label="确诊/疑诊" width="128">
                    <template #default="{ row }">
                      <el-select v-model="row.certainty" size="small" clearable :disabled="emrSigned"
                                 placeholder="未标">
                        <el-option label="确诊" value="CONFIRMED" />
                        <el-option label="疑诊" value="SUSPECTED" />
                      </el-select>
                    </template>
                  </el-table-column>
                  <el-table-column label="自定义描述" min-width="150">
                    <template #default="{ row }">
                      <el-input v-model="row.customName" size="small" maxlength="128" :disabled="emrSigned"
                                placeholder="临床诊断名称描述（与标准名并存）" />
                    </template>
                  </el-table-column>
                  <el-table-column label="" width="104">
                    <template #default="{ row, $index }">
                      <el-button link type="warning" :disabled="emrSigned" @click="starDiag(row)">加星</el-button>
                      <el-button link type="danger" :disabled="emrSigned" @click="diagList.splice($index, 1)">
                        移除
                      </el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </el-form-item>
            <el-form-item v-if="cdssTips.length" label="CDSS">
              <el-alert v-for="(tip, i) in cdssTips" :key="i" :title="tip" type="warning" show-icon
                        :closable="false" style="margin-bottom: 4px" />
            </el-form-item>
            <el-form-item label="处理意见"><el-input v-model="emr.advice" type="textarea" :rows="2" :disabled="emrSigned" /></el-form-item>
            <!-- v79 车道B（2457★）：文案改「暂存」「提交（签名）」，端点不变（PUT /emr 与 POST /emr/sign） -->
            <el-button type="primary" :loading="savingEmr" :disabled="emrSigned" @click="saveEmr">暂存</el-button>
            <!-- v43：门诊病历签名入口（此前端点齐备但界面无按钮，签名与补正在正常路径上都走不到） -->
            <el-button v-if="!emrSigned" type="warning" :loading="signing" @click="signEmr">提交（签名）</el-button>
            <!-- v55 车道R2（994）：版本留痕直达。此前版本页唯一入口是手填 outp_emr.id，而全系统没有任何页面显示这个 id
                 ——功能在、医生进不去。workspace 与保存返回体本就带 id，这里只是把它接到按钮上。
                 新标签打开：不丢医生手上尚未保存的正文；病历未保存（无 id）或本人不持有该菜单时不出现，不摆死按钮。 -->
            <el-button v-if="currentEmrId && canOpenEmrVersion" plain @click="openEmrVersions">版本留痕</el-button>
            <!-- v69 包 A（1095★）：一键存为模板。同样是端点齐备而界面无入口。
                 需要已保存的病历才有 id 可存，故与「版本留痕」同条件出现，不摆死按钮。 -->
            <el-button v-if="currentEmrId" plain :loading="savingTpl" @click="saveAsTemplate">存为模板</el-button>
            <span v-if="!emrSigned" class="sign-tip">签名即提交：提交后原文冻结，如需更正只能追加补正记录</span>
          </el-form>
          </div>

          <!-- 阻塞4：签名冻结病历的合规补正入口 -->
          <div v-if="emrSigned" class="amend-block">
            <el-alert type="info" :closable="false" show-icon
                      title="病历已签名冻结，原文不可修改。如发现错字/表述有误，请追加补正记录（原文保留，留痕可追溯）。" />
            <el-form :model="amendForm" label-width="80px" style="margin-top: 10px">
              <el-form-item label="补正内容">
                <el-input v-model="amendForm.amendText" type="textarea" :rows="2" placeholder="正确的表述/更正说明" />
              </el-form-item>
              <el-form-item label="补正原因">
                <el-input v-model="amendForm.reason" placeholder="如：录入笔误、诊断补充" />
              </el-form-item>
              <el-button type="warning" :loading="amending" @click="submitAmend">提交补正</el-button>
            </el-form>
            <el-timeline v-if="amendments.length" style="margin-top: 12px">
              <el-timeline-item v-for="a in amendments" :key="a.id as number"
                                :timestamp="`${fmtDateTime(a.amended_at)} · ${a.amended_by_name ?? ('用户' + a.amended_by)}`">
                <b>补正：</b>{{ a.amend_text }}
                <div class="amend-reason">原因：{{ a.reason }}</div>
              </el-timeline-item>
            </el-timeline>
          </div>
        </el-tab-pane>

        <el-tab-pane label="处方" name="rx">
          <!-- v44/984：开单时提示该患者的特殊病种院内登记（只提示，不参与任何开单拦截） -->
          <el-alert v-if="specialDiseases.length" type="warning" :closable="false" show-icon
                    style="margin-bottom: 8px"
                    :title="`特殊病种（院内登记）：${specialDiseases.map((s) => s.diseaseName).join('、')}
                             —— 开药请留意是否在该病种用药范围内`" />
          <div class="add-row">
            <el-select v-model="rxDrugId" filterable remote :remote-method="searchDrugs"
                       placeholder="搜索药品" style="width: 260px">
              <el-option v-for="d in drugOptions" :key="d.id"
                         :label="`${d.name} ${d.spec}（¥${d.price}/​${d.unit}，存${d.stock}）`" :value="d.id" />
            </el-select>
            <el-input v-model="rxDose" placeholder="单次量" style="width: 90px" />
            <el-select v-model="rxFreq" style="width: 90px">
              <el-option v-for="f in ['qd', 'bid', 'tid', 'qid', 'q8h', 'prn']" :key="f" :label="f" :value="f" />
            </el-select>
            <el-select v-model="rxRoute" style="width: 100px">
              <el-option v-for="u in ['口服', '静滴', '肌注', '外用', '雾化']" :key="u" :label="u" :value="u" />
            </el-select>
            <el-input-number v-model="rxDays" :min="1" :max="30" style="width: 100px" /><span>天</span>
            <el-input-number v-model="rxQty" :min="1" :max="999" style="width: 100px" /><span>盒/瓶</span>
            <el-button type="primary" @click="addRxLine">加入</el-button>
          </div>
          <!-- v44 合版补：车道F 的处方模板/协定处方套用入口（跨车道，按纪律由合版统一加）。
               纪律：套用**只是填充下方开单表单**，医生仍点「开立处方」走原 createOrders——
               过敏/重复用药/抗菌药分级/CDSS/停用药预检全部照常执行。绝不另开批量提交路径。 -->
          <div class="tpl-bar">
            <el-select v-model="tplPicked" placeholder="套用处方模板 / 协定处方" clearable filterable
                       style="width: 300px" @change="applyTemplate">
              <el-option v-for="tp in rxTemplates" :key="tp.id as number"
                         :label="String(tp.name)" :value="tp.id as number">
                <span>{{ tp.name }}</span>
                <el-tag v-if="tp.linesLocked" size="small" type="danger" style="margin-left: 6px">协定</el-tag>
                <span style="color: #999; margin-left: 6px">{{ scopeNames[tp.scope as string] }} · {{ tp.line_count }} 行</span>
              </el-option>
            </el-select>
            <span v-if="tplHint" class="tpl-hint">{{ tplHint }}</span>
          </div>
          <el-table :data="rxLines" size="small">
            <el-table-column prop="itemName" label="药品" />
            <el-table-column prop="dosePerTime" label="单次量" width="80" />
            <el-table-column prop="frequency" label="频次" width="70" />
            <el-table-column prop="usageRoute" label="途径" width="70" />
            <el-table-column prop="days" label="天数" width="60" />
            <el-table-column prop="qty" label="数量" width="60" />
            <!-- v74（1006★「所有医嘱均拥有备注功能」）：药品行同样开放加急与备注，键名与后端 OrderLine 一致 -->
            <el-table-column label="加急" width="56" align="center">
              <template #default="{ row }"><el-checkbox v-model="row.urgent" /></template>
            </el-table-column>
            <el-table-column label="备注" min-width="110">
              <template #default="{ row }"><el-input v-model="row.remark" size="small" placeholder="备注" maxlength="200" /></template>
            </el-table-column>
            <el-table-column label="" width="60">
              <template #default="{ row, $index }">
                <!-- v44：协定处方建档后明细固定，要么整组用要么整组撤，不许单行删 -->
                <el-button v-if="!row.locked" link type="danger" @click="rxLines.splice($index, 1)">移除</el-button>
                <el-button v-else link type="warning" @click="dropAgreedGroup(row)">撤组</el-button>
              </template>
            </el-table-column>
          </el-table>
          <!-- 阻塞6：断货医生开单感知——开单不拦，但当场提示库存不足，让医生换药或知情 -->
          <el-alert v-if="stockWarnings.length" type="warning" show-icon :closable="true" style="margin-top: 8px"
                    @close="stockWarnings = []"
                    title="以下药品库存不足，患者缴费后可能无法发药，请换药或安排进药：">
            <template #default>
              <div v-for="(w, i) in stockWarnings" :key="i">· {{ w }}</div>
            </template>
          </el-alert>
          <el-button type="success" :disabled="!rxLines.length" :loading="submitting" style="margin-top: 8px"
                     @click="submitOrders('rx')">
            开立处方
          </el-button>
        </el-tab-pane>

        <el-tab-pane label="检查检验" name="lab">
          <div class="add-row">
            <el-select v-model="labItemId" filterable remote :remote-method="searchChargeItems"
                       placeholder="搜索检验/检查/治疗项目" style="width: 300px">
              <el-option v-for="c in chargeItemOptions" :key="c.id"
                         :label="`[${categoryNames[c.category as string]}] ${c.name}（¥${c.price}）`" :value="c.id" />
            </el-select>
            <el-button type="primary" @click="addLabLine">加入</el-button>
          </div>
          <el-table :data="labLines" size="small">
            <el-table-column prop="itemName" label="项目" min-width="140" />
            <el-table-column label="类别" width="70">
              <template #default="{ row }">{{ categoryNames[row.category as string] }}</template>
            </el-table-column>
            <!-- v74 复核补录入口（1006★/1013★/1014★/1016★）：V137 七个申请单字段后端一直能收、打印页也会印，
                 但医生站此前没有任何控件能填——真实业务下恒为空。按类别只露相关字段：检验行填标本类型/采样部位，
                 检查行填检查目的/临床摘要/注意事项；加急与备注各行都有。键名与后端 OrderLine 一致，随 lines 原样上送。 -->
            <el-table-column label="申请信息" min-width="300">
              <template #default="{ row }">
                <!-- v77 车道B（1016★ 第二句「开单界面即时展示自动带出的摘要与诊断」）：检验/检查行只读列出本次就诊
                     当前诊断（取 diagList，与病历区同一份）；临床摘要在加入行时由主诉+现病史预填，见 prefillClinicalSummary -->
                <div v-if="row.category === 'LAB' || row.category === 'EXAM'" class="dim" style="margin-bottom: 4px">
                  诊断：
                  <template v-if="diagList.length">
                    <el-tag v-for="(d, i) in diagList" :key="i" size="small" type="info" style="margin-right: 4px">
                      <!-- v80 审阅修补二（N-6）：按 1026★ 打印口径拼前缀 / 名称［自定义］/ 后缀 /（疑诊）/ [中医]——
                           diagList 行与历史就诊接口同为 camelCase 键，复用 formatHistoryDiagnosis；此前只印标准名，疑诊在这里读作确诊 -->
                      {{ formatHistoryDiagnosis(d) }}
                    </el-tag>
                  </template>
                  <span v-else>尚未录入诊断</span>
                </div>
                <template v-if="row.category === 'LAB'">
                  <!-- v78 车道B（1016★ 第二轮复核：标本类型字典化）：从字典选、允许手填字典外值（allow-create）；值仍是名称字符串，
                       row.specimenType 键名与后端 OrderLine 不变。字典外值后端照收，只是不参与流向匹配、按收费项目字典带出执行科室 -->
                  <el-select v-model="row.specimenType" size="small" filterable allow-create default-first-option clearable
                             placeholder="标本类型（可手填）" style="width: 46%; margin-right: 4px; margin-bottom: 4px">
                    <el-option v-for="s in specimenTypeOptions" :key="s.id" :label="s.name" :value="s.name" />
                  </el-select>
                  <el-input v-model="row.samplingSite" size="small" placeholder="采样部位" maxlength="32" style="width: 46%; margin-bottom: 4px" />
                  <!-- v74 复核（1026★ 第三轮反驳者二）：检验申请单印"临床摘要/注意事项"两栏，此前检验行没有录入框，纸上永远是空线 -->
                  <el-input v-model="row.clinicalSummary" size="small" placeholder="临床摘要" maxlength="500" style="width: 100%; margin-bottom: 4px" />
                  <el-input v-model="row.notice" size="small" placeholder="注意事项" maxlength="200" style="width: 100%" />
                </template>
                <template v-else-if="row.category === 'EXAM'">
                  <el-input v-model="row.examPurpose" size="small" placeholder="检查目的 / 临床要求" maxlength="200" style="width: 100%; margin-bottom: 4px" />
                  <el-input v-model="row.clinicalSummary" size="small" placeholder="临床摘要" maxlength="500" style="width: 100%; margin-bottom: 4px" />
                  <el-input v-model="row.notice" size="small" placeholder="注意事项" maxlength="200" style="width: 100%" />
                </template>
              </template>
            </el-table-column>
            <el-table-column label="加急" width="56" align="center">
              <template #default="{ row }"><el-checkbox v-model="row.urgent" /></template>
            </el-table-column>
            <el-table-column label="备注" min-width="120">
              <template #default="{ row }"><el-input v-model="row.remark" size="small" placeholder="备注" maxlength="200" /></template>
            </el-table-column>
            <el-table-column label="" width="60">
              <template #default="{ row, $index }">
                <!-- v80 审阅修补（D5）：协定处方来源的检验 / 检查 / 治疗行与药品行同口径——不单行移除，只能整组撤回 -->
                <el-button v-if="!row.locked" link type="danger" @click="labLines.splice($index, 1)">移除</el-button>
                <el-button v-else link type="warning" @click="dropAgreedGroup(row)">撤组</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button type="success" :disabled="!labLines.length" :loading="submitting" style="margin-top: 8px"
                     @click="submitOrders('lab')">
            提交申请
          </el-button>
        </el-tab-pane>

        <el-tab-pane :label="`已开医嘱(${orders.length})`" name="orders">
          <!-- v43 合版补：车道B 的五种日常单据打印入口（PrintView 分支由车道B 落地，非死链）
               与车道D 的皮试结果只读入口（端点已放开 DOCTOR_OUTP，前端此前无入口）。
               两项均跨车道，按车道纪律由合版统一加。 -->
          <div class="doc-bar no-print">
            <el-dropdown @command="printDoc">
              <el-button size="small" type="primary">打印单据<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="prescription">处方笺</el-dropdown-item>
                  <el-dropdown-item command="lab-request">检验申请单</el-dropdown-item>
                  <el-dropdown-item command="exam-request">检查申请单</el-dropdown-item>
                  <el-dropdown-item command="treat-sheet">治疗单</el-dropdown-item>
                  <el-dropdown-item command="guide-sheet">导诊单</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button size="small" @click="loadSkinTests">皮试结果</el-button>
            <span v-if="skinTests.length" class="skin-inline">
              <el-tag v-for="st in skinTests" :key="st.id as number" size="small"
                      :type="st.positive ? 'danger' : (st.result === 'PENDING' ? 'info' : 'success')"
                      style="margin-right: 6px">
                {{ st.drug_name }}{{ st.category ? '（' + st.category + '）' : '' }}：{{ st.result_text }}
              </el-tag>
            </span>
          </div>
          <el-table :data="orders" size="small">
            <el-table-column prop="groupNo" label="单号" width="140" />
            <el-table-column label="类型" width="70">
              <template #default="{ row }">{{ typeNames[row.orderType as string] }}</template>
            </el-table-column>
            <el-table-column prop="itemName" label="项目" />
            <!-- v77 第二轮复核（1016★）：执行科室此前只在提交成功的提示里闪一次；工作区现回填 execDeptName，逐行可见 -->
            <el-table-column label="执行科室" width="90">
              <template #default="{ row }">{{ row.execDeptName || '—' }}</template>
            </el-table-column>
            <el-table-column prop="qty" label="量" width="50" />
            <el-table-column prop="amount" label="金额" width="80" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="orderStatusTag[row.status as string]">
                  {{ orderStatusNames[row.status as string] }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="" width="70">
              <template #default="{ row }">
                <el-button v-if="row.status === 'CREATED'" link type="danger" :disabled="cancellingOrder !== null" @click="cancelOrder(row)">作废</el-button>
                <!-- v74 复核补入口：检验发布后医嘱置 EXECUTED，此时报告可打。此前 lab-report 打印页全前端零入口。 -->
                <el-button v-if="row.orderType === 'LAB' && row.status === 'EXECUTED'" link type="primary"
                           @click="printLabReport(row)">报告</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
      </template>
    </el-card>
    <el-empty v-else class="workspace"
              :description="visitDenied ? '无权查阅该次就诊：不是当日就诊，且不是本人接诊（挂号不在本人名下、病历也不是本人书写）。请从左侧选择其他就诊' : '从左侧队列选择患者'" />

    <!-- v37 历史就诊抽屉：近 50 次就诊（剔退号）+ 各次诊断/主诉/处理意见 -->
    <el-drawer v-if="workspaceReady" :key="'history-' + workspaceRenderKey" v-model="historyVisible" title="历史就诊" size="480px">
      <el-empty v-if="!history.length" :image-size="60"
                :description="historyDenied ? '该患者非当日就诊，且本人未接诊过其往次就诊，不显示其历次就诊' : '无历史就诊'" />
      <!-- v79 审阅修补（三）（乙组反驳 B1-E2/B2-4c，1082★）：抽屉不在编辑区复制容器里，copy 冒不到那一层；
           这里列的是当前所看患者的历次就诊，复制时登记来源患者即当前患者（onCopy 按 current 取） -->
      <div v-else @copy="onCopy" @cut="onCopy">
      <el-timeline>
        <el-timeline-item v-for="h in history" :key="h.registrationId as number"
                          :timestamp="`${h.visitDate}${h.signed ? ' · 已签名' : ''}`">
          <div v-if="(h.diagnoses as Record<string, unknown>[])?.length" style="margin-bottom:4px">
            <el-tag v-for="(d, i) in (h.diagnoses as Record<string, unknown>[])" :key="i"
                    :type="d.primaryDiag ? 'danger' : 'info'" size="small" style="margin-right:4px">
              <!-- v80 审阅修补（D8）：按 1026★ 打印口径拼前缀 / 名称［自定义］/ 后缀 /（疑诊）/ [中医]，此前疑诊在这里读作确诊 -->
              {{ formatHistoryDiagnosis(d) }}
            </el-tag>
          </div>
          <p v-if="h.chiefComplaint" class="hist-line">主诉：{{ h.chiefComplaint }}</p>
          <p v-if="h.advice" class="hist-line">处理：{{ h.advice }}</p>
        </el-timeline-item>
      </el-timeline>
      </div>
    </el-drawer>

    <!-- v44 诊断助手（979）：历史 / 常用 / 高频 三源，点一下带入诊断表 -->
    <el-drawer v-if="workspaceReady" :key="'assist-' + workspaceRenderKey" v-model="assistVisible" title="诊断助手" size="460px">
      <el-input v-model="assistKeyword" placeholder="按名称或编码筛选三段结果" clearable
                style="margin-bottom: 10px" @keyup.enter="loadAssist()" @clear="loadAssist()">
        <template #append><el-button @click="loadAssist()">筛选</el-button></template>
      </el-input>
      <el-tabs v-model="assistTab">
        <el-tab-pane :label="`历史诊断(${assist.history.length})`" name="history">
          <el-empty v-if="!assist.history.length" :image-size="60"
                    :description="assistHistoryDenied ? '该患者非当日就诊，且本人未接诊过其往次就诊，不显示其历史诊断' : '该患者无历史诊断'" />
          <div v-for="(e, i) in assist.history" :key="'h' + i" class="assist-row">
            <span class="assist-name" @click="addDiagFrom(e)">{{ e.icdName }}
              <span v-if="e.icdCode" class="dim">({{ e.icdCode }})</span></span>
            <span class="dim">{{ e.lastVisitDate }}</span>
            <el-button link type="warning" size="small" @click="starDiag(e)">加星</el-button>
          </div>
        </el-tab-pane>
        <el-tab-pane :label="`常用诊断(${assist.favorite.length})`" name="favorite">
          <el-empty v-if="!assist.favorite.length" description="尚无常用诊断（暂存病历时自动累积）" :image-size="60" />
          <div v-for="e in assist.favorite" :key="'f' + e.id" class="assist-row">
            <span class="assist-name" @click="addDiagFrom(e)">{{ e.icdName }}
              <span v-if="e.icdCode" class="dim">({{ e.icdCode }})</span></span>
            <span class="dim">用过 {{ e.useCount }} 次</span>
            <el-button link type="danger" size="small" @click="removeFavorite(e.id)">删除</el-button>
          </div>
        </el-tab-pane>
        <el-tab-pane :label="`高频诊断(${assist.frequent.length})`" name="frequent">
          <el-empty v-if="!assist.frequent.length" description="近半年暂无可聚合的诊断数据" :image-size="60" />
          <div v-for="(e, i) in assist.frequent" :key="'q' + i" class="assist-row">
            <span class="assist-name" @click="addDiagFrom(e)">{{ e.icdName }}
              <span v-if="e.icdCode" class="dim">({{ e.icdCode }})</span></span>
            <span class="dim">全院 {{ e.useCount }} 例</span>
            <el-button link type="warning" size="small" @click="starDiag(e)">加星</el-button>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>

    <!-- v44 医保特殊病种「院内登记」（984）：只登记，不报送 -->
    <el-dialog v-if="workspaceReady" :key="'special-' + workspaceRenderKey" v-model="specialVisible" title="医保特殊病种（慢特病）· 院内登记" width="640px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px"
                title="本功能为院内登记：记录患者已认定的特殊病种与院内认定有效期，供诊断与开单时提示。
                       向医保经办机构备案报送需走当地医保接口，属外部对接条件，本平台不提供。" />
      <el-table :data="specialDiseases" size="small" empty-text="暂无登记">
        <el-table-column prop="diseaseName" label="病种" min-width="140" />
        <el-table-column prop="diseaseCode" label="病种编码" width="110" />
        <el-table-column prop="insuranceType" label="医保类别" width="100" />
        <el-table-column label="有效期" min-width="170">
          <template #default="{ row }">{{ row.startDate }} ~ {{ row.endDate || '长期' }}</template>
        </el-table-column>
        <el-table-column label="" width="64">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeSpecialDisease(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-form :model="specialForm" label-width="86px" style="margin-top: 12px">
        <el-form-item label="病种名称">
          <el-input v-model="specialForm.diseaseName" placeholder="如 原发性高血压（Ⅱ级以上）" />
        </el-form-item>
        <el-form-item label="病种编码">
          <el-input v-model="specialForm.diseaseCode" placeholder="院内自定义或当地医保病种码（平台不预置码表）" />
        </el-form-item>
        <el-form-item label="医保类别">
          <el-input v-model="specialForm.insuranceType" placeholder="如 职工医保 / 居民医保" />
        </el-form-item>
        <el-form-item label="有效期">
          <el-date-picker v-model="specialForm.startDate" type="date" value-format="YYYY-MM-DD" placeholder="起始" />
          <span style="margin: 0 6px">~</span>
          <el-date-picker v-model="specialForm.endDate" type="date" value-format="YYYY-MM-DD" placeholder="截止（可空=长期）" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="specialForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="specialVisible = false">关 闭</el-button>
        <el-button type="primary" :loading="savingSpecial" @click="addSpecialDisease">登 记</el-button>
      </template>
    </el-dialog>

    <!-- ============ v51 车道F4：CDSS 审查提示全文（开单成功后自动弹出，可随时重看） ============
         刻意用对话框而不是 ElMessage：toast 几秒就没了，医生正在看下一位患者时警告已经消失，
         那与「只写进 cdss_alert 表」没有区别。这里必须能被看完、能被回看。 -->
    <el-dialog v-if="workspaceReady" :key="'cdss-' + workspaceRenderKey" v-model="cdssWarnDialog" width="760px" :close-on-click-modal="false"
               title="合理用药审查提示（不拦截开单，请逐条确认）">
      <el-alert type="warning" show-icon :closable="false" class="cdss-dlg-note"
                title="本次开单已成立。以下提示由后端合理用药审查（过敏 / 交叉过敏 / 重复用药 / 特殊人群 / 相互作用 / 疗程）随开单返回体下发，原文照录。">
        <div>这些是 warn 档提示，<b>不拦截开单</b>，是否调整医嘱由医生判断。</div>
        <div>
          其中标注「过敏审查覆盖不全」的条目说明<b>审查本身有缺口</b>——
          此时「未发现过敏禁忌」这句话不成立，请人工阅读原始过敏史原文后判断。
        </div>
      </el-alert>
      <div v-for="(w, i) in rxCdssWarnings" :key="i" class="cdss-dlg-item">
        <el-tag v-if="warnTag(w)" type="danger" size="small" effect="plain" class="cdss-warn-tag">
          {{ warnTag(w) }}
        </el-tag>
        <span class="cdss-warn-text">{{ warnBody(w) }}</span>
      </div>
      <el-empty v-if="!rxCdssWarnings.length" description="本次开单无审查提示" :image-size="60" />
      <template #footer>
        <el-button type="primary" @click="cdssWarnDialog = false">我已逐条阅读</el-button>
      </template>
    </el-dialog>

    <!-- v45 车道J：临床资料引用抽屉（992★ 基本资料/检验/检查/历史病历，点条目插入正文） -->
    <!-- v69 包 A：既往病历清单。只读挑选，选中即取正文插入当前病历的选定段。 -->
  <el-dialog v-if="workspaceReady" :key="'prior-' + workspaceRenderKey" v-model="priorVisible" title="按既往病历新建（取正文插入当前病历）" width="720px">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 8px"
              :title="`选中一条即把它的正文插入「${sectionLabel}」；原病历只读不改，改完仍点「暂存」写入本次就诊。`" />
    <el-table :data="priorRecords" height="360" v-loading="priorLoading">
      <el-table-column label="来源" width="80">
        <template #default="{ row }">{{ row.source === 'INP' ? '住院' : '门诊' }}</template>
      </el-table-column>
      <el-table-column prop="title" label="文书" min-width="150" show-overflow-tooltip />
      <el-table-column prop="dept_name" label="科室" width="110" show-overflow-tooltip />
      <el-table-column prop="doctor_name" label="医师" width="90" />
      <el-table-column label="时间" width="150">
        <template #default="{ row }">{{ fmtDateTime(row.created_at) }}</template>
      </el-table-column>
      <el-table-column prop="content_length" label="字数" width="70" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" size="small" :disabled="emrSigned"
                     @click="usePriorRecord(row)">取正文</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!priorLoading && !priorRecords.length" description="该患者暂无既往病历" />
  </el-dialog>

  <!-- v79 审阅修补（三）（乙组反驳 B1-E2①）：引用抽屉「历史病历」页签正文的复制上抛到这里登记来源（当前患者） -->
  <EmrRefDrawer v-if="workspaceReady" :key="workspaceRenderKey" v-model="refVisible" :registration-id="(current?.registrationId as number) ?? null"
                  :disabled="emrSigned" @insert="insertText" @copy="onCopy" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { fmtDateTime, todayLocal } from '../../utils/date'
import { ElMessage, ElMessageBox } from 'element-plus'
import client, { type BizError } from '../../api/client'
import { useAuthStore } from '../../stores/auth'
import EmrRefDrawer, { useEmrPasteGuard } from '../../components/EmrRefDrawer.vue'
import StructuredFieldForm, { type EmrTemplateField } from '../../components/StructuredFieldForm.vue'
import { applyTemplate as applyTemplateToEmr, outpTemplatesOnly, splitTemplateContent,
  type EmrFields, type EmrParts } from '../../utils/emr-template'
import { editedSinceApplied, eligibleForDefaultTemplate, pickDefaultTemplate, planDefaultTemplate,
  revertDefaultTemplate, untouchedDefaultTemplate, type DefaultTemplate } from '../../utils/default-template'
import { presaveBeforeSign } from '../../utils/emr-submit'
import { formatHistoryDiagnosis, stripBlockMarks } from '../../utils/print-format'
import { dropTemplateBatch, stampTemplateLines } from '../../utils/rx-template-apply'

const categoryNames: Record<string, string> = { LAB: '检验', EXAM: '检查', TREAT: '治疗', MATERIAL: '材料' }
const typeNames: Record<string, string> = { DRUG: '药品', LAB: '检验', EXAM: '检查', TREAT: '治疗' }
const orderStatusNames: Record<string, string> = {
  CREATED: '已开立', CHARGED: '已收费', DISPENSED: '已发药', EXECUTED: '已执行', CANCELLED: '已作废',
}
const orderStatusTag: Record<string, string> = {
  CREATED: 'warning', CHARGED: 'primary', DISPENSED: 'success', EXECUTED: 'success', CANCELLED: 'info',
}

// 每次取值（1.1.8）：常量固化在页面加载时，急诊夜班跨 0 点后刷新仍查昨天的队列
const today = ref(todayLocal())
const worklist = ref<Record<string, unknown>[]>([])
const current = ref<Record<string, unknown> | null>(null)
let workspaceGeneration = 0
let workspaceAlive = true
const cancellingOrder = ref<number | null>(null)
const workspaceLoading = ref(false)
const workspaceReady = ref(false)
const workspaceRenderKey = ref(0)
type WorkspaceContext = {
  visit: Record<string, unknown>; registrationId: number; patientId: number | undefined; generation: number; valid: () => boolean
}
const workspaceActions = reactive(new Set<string>())
function captureWorkspace(requireReady = true): WorkspaceContext | null {
  const visit = current.value
  if (!workspaceAlive || !visit || (requireReady && (!workspaceReady.value || workspaceLoading.value))) return null
  const generation = workspaceGeneration
  const registrationId = visit.registrationId as number
  const patientId = visit.patientId as number | undefined
  return { visit, registrationId, patientId, generation, valid: () => workspaceAlive && generation === workspaceGeneration
    && current.value === visit && visit.registrationId === registrationId && visit.patientId === patientId }
}
function workspaceActionPending(action: string) {
  return !!current.value && workspaceActions.has(action + ':' + current.value.registrationId)
}
function beginWorkspaceAction(context: WorkspaceContext, action: string) {
  if (!context.valid() || !workspaceReady.value || workspaceLoading.value) return null
  const key = action + ':' + context.registrationId
  if (workspaceActions.has(key)) return null
  workspaceActions.add(key)
  return () => { workspaceActions.delete(key) }
}
async function inWorkspace<T>(context: WorkspaceContext, request: () => Promise<T>): Promise<T | undefined> {
  if (!context.valid()) return
  try {
    const result = await request()
    return context.valid() ? result : undefined
  } catch (error) { if (context.valid()) throw error }
}
function clearWorkspace() {
  workspaceReady.value = false
  Object.assign(emr, { chiefComplaint: '', presentIllness: '', pastHistory: '', physicalExam: '', advice: '' })
  currentEmrId.value = null; emrAuthorId.value = null
  emrSigned.value = false; emrSignerName.value = ''; emrSignedAtText.value = ''
  autoTpl.value = null; basicBrought.value = ''; prefilledNote.value = ''
  diagList.value = []; icdOptions.value = []; diagSystem.value = 'ICD10'; icdPick.value = null; tcmName.value = ''
  orders.value = []; rxLines.value = []; labLines.value = []; skinTests.value = []; amendments.value = []
  specialDiseases.value = []; specialVisible.value = false
  Object.assign(specialForm, { diseaseName: '', diseaseCode: '', insuranceType: '', startDate: todayLocal(), endDate: '', remark: '' })
  amendForm.amendText = ''; amendForm.reason = ''
  stockWarnings.value = []; cdssTips.value = []; rxCdssWarnings.value = []
  cdssWarnBarClosed.value = false; cdssWarnDialog.value = false
  refVisible.value = false; insertTarget.value = 'presentIllness'
  structTemplateId.value = null; structFields.value = []; structValues.value = {}; structHint.value = ''
  priorVisible.value = false; priorRecords.value = []; historyVisible.value = false; history.value = []; historyDenied.value = false
  assistVisible.value = false; assistHistoryDenied.value = false
  assist.value = { history: [], favorite: [], frequent: [] }; assistKeyword.value = ''; assistTab.value = 'history'
  tplHint.value = ''; tplPicked.value = undefined; summaryTruncWarned = false
  rxDrugId.value = null; labItemId.value = null
}
onUnmounted(() => { workspaceAlive = false; workspaceGeneration++ })
const historyVisible = ref(false)
const history = ref<Record<string, unknown>[]>([])
/** v80 审阅修补二（R2-2）：打开的这次就诊被 4036 拒（非当日且非本人接诊）——工作区空态换一句说明 */
const visitDenied = ref(false)
/** v80 审阅修补二（R2-2）：历次就诊被 4036 拒时为真——抽屉照开，空态换一句说明，不说成「无历史就诊」 */
const historyDenied = ref(false)
async function openHistory() {
  const context = captureWorkspace(); if (!context) return
  historyDenied.value = false
  try {
    const response = await inWorkspace(context, () => client.get(`/outpatient/doctor/patient/${context.patientId}/history`, { __silentCodes: [4036] }))
    if (!response || !context.valid()) return
    history.value = response.data.data
  } catch (error) {
    if (!context.valid()) return
    if ((error as BizError).bizCode !== 4036) throw error
    history.value = []; historyDenied.value = true
  }
  if (context.valid()) historyVisible.value = true
}
const tab = ref('emr')
const savingEmr = computed(() => workspaceActionPending('save'))
const submitting = computed(() => workspaceActionPending('orders'))

const emr = reactive({ chiefComplaint: '', presentIllness: '', pastHistory: '', physicalExam: '', advice: '' })

// ===================== v45 车道J：引用带出 / 结构化录入 / 跨患者粘贴管控 =====================

/** 引用片段与结构化内容的落点（门诊病历是五段定长列，不像住院那样只有一个正文框） */
const EMR_SECTIONS = [
  { key: 'chiefComplaint', label: '主诉' },
  { key: 'presentIllness', label: '现病史' },
  { key: 'pastHistory', label: '既往史' },
  { key: 'physicalExam', label: '体格检查' },
  { key: 'advice', label: '处理意见' },
] as const
type EmrSectionKey = (typeof EMR_SECTIONS)[number]['key']

const refVisible = ref(false)
const insertTarget = ref<EmrSectionKey>('presentIllness')
/** 1092★：进患者时自动带出的基础信息摘要（来源是只读引用端点，不是队列行里那份） */
const basicBrought = ref('')
const prefilledNote = ref('')

/** 结构化录入（车道 I 的字段定义 + 保存端点的可空 fields 参数） */
const emrTemplates = ref<Record<string, unknown>[]>([])
const structTemplateId = ref<number | null>(null)
const structFields = ref<EmrTemplateField[]>([])
const structValues = ref<Record<string, unknown>>({})
const structHint = ref('')

/**
 * 1082★ 跨患者复制粘贴管控。**只在前端**：block 档也只是拒绝这一次粘贴动作，
 * 病历保存端点一行未改、服务端不做任何拦截（诚实边界见 useEmrPasteGuard 的注释）。
 */
const { mode: copyMode, loadPolicy: loadCopyPolicy, onCopy, onPaste } = useEmrPasteGuard(() => ({
  patientId: (current.value?.patientId as number | undefined) ?? null,
  patientName: String(current.value?.patientName ?? ''),
}), () => {
  const context = captureWorkspace()
  return () => !!context?.valid() && workspaceReady.value && !emrSigned.value
})

/** 把引用片段/资料写进选定的正文段（已签名冻结时不写） */
function insertText(text: string) {
  if (!captureWorkspace()) return
  if (!text.trim()) return
  if (emrSigned.value) {
    ElMessage.warning('病历已签名冻结，不能再插入内容；如需更正请追加补正记录')
    return
  }
  const k = insertTarget.value
  emr[k] = emr[k] ? `${emr[k]}\n${text}` : text
  ElMessage.success(`已插入「${EMR_SECTIONS.find((s) => s.key === k)?.label}」`)
}

/**
 * 1092★ 新建病历自动带出：进患者时取一次只读的 BASIC 引用段。
 * 摘要常驻显示；**只有本次尚未写过病历**且既往史为空、患者又有过敏记录时，
 * 才把过敏史预填进既往史——门诊病历五段里只有它有对应落点，姓名/门诊号没有列可填，
 * 故以"已自动带出"摘要 + 一键插入呈现，而不是硬塞进某段临床叙述里。
 */
async function loadBasicRef(isNewEmr: boolean, context = captureWorkspace()) {
  if (!context) return
  basicBrought.value = ''; prefilledNote.value = ''
  try {
    const response = await inWorkspace(context, () => client.get('/outpatient/emr-ref', {
      params: { registrationId: context.registrationId, kind: 'BASIC' },
    }))
    if (!response || !context.valid()) return
    const p = (response.data.data as { prefill?: Record<string, unknown> }).prefill ?? {}
    basicBrought.value = [p.patientName, p.sexText, p.age == null ? '' : `${p.age} 岁`,
      p.patientNo ? `门诊号 ${p.patientNo}` : '', p.allergyHistory ? `过敏 ${p.allergyHistory}` : ''].filter(Boolean).join(' · ')
    const allergy = String(p.allergyHistory ?? '').trim()
    if (isNewEmr && allergy && !emr.pastHistory.trim()) {
      emr.pastHistory = `过敏史：${allergy}`
      prefilledNote.value = '已按引用数据源自动带出患者基础信息；其中过敏史已预填进「既往史」，医生可修改或删除后再保存。'
    }
  } catch { /* Optional reference data never interrupts the live workspace. */ }
}

/** 本科室可用的病历模板（沿用住院侧同一个冻结契约端点；本页只用来挑结构化模板） */
async function loadEmrTemplates() {
  try {
    // v74 补 993★ 硬缺口（v68 三方复核）：此前走不认使用范围的旧通道 /emr-templates，下拉里是全院全部启用模板、
    // 含所有人的个人模板；改走按登录人可见范围与授权过滤的 /emr-templates/visible（GLOBAL/HOSPITAL 人人可见，
    // DEPT 本科室 + 被授权科室，PERSONAL 本人 + 被授权个人），默认模板置顶。
    // v76：门诊下拉只列 OUTP 或空 record_type 的模板（住院长正文套进门诊会超 2000 字；旧 JSON 模板 record_type 为 null，
    // 传 recordType=OUTP 给后端会漏掉它们，故在前端过滤）
    emrTemplates.value = outpTemplatesOnly(
      ((await client.get('/emr-templates/visible', { params: { type: 'EMR' } })).data.data ?? []) as Record<string, unknown>[])
  } catch {
    emrTemplates.value = []
  }
}

/**
 * v76（993★ ③）套用模板正文到门诊五段：旧 JSON / 「主诉：…」标签行 / 纯文本三种形态由 splitTemplateContent 拆，
 * 纯文本整段进「插入到」选定的段。目标段非空先确认；超出库列宽只提示、不截断。
 * 只改本页表单，仍须点「保存病历」才落库；已签名在这里先拦，后端 4008 兜底。
 */
async function applyTemplateBody() {
  const context = captureWorkspace(); if (!context) return
  if (emrSigned.value) { ElMessage.warning('病历已签名冻结，不能套用模板；如需更正请追加补正记录'); return }
  const template = emrTemplates.value.find(x => x.id === structTemplateId.value)
  if (!template) return
  let content = template.content as string | null | undefined
  if (content === undefined) {
    const response = await inWorkspace(context, () => client.get(`/emr-templates/${template.id}`))
    if (!response || !context.valid()) return
    content = response.data.data?.content as string | null | undefined
  }
  const split = splitTemplateContent(content, insertTarget.value)
  if (split.format === 'empty') { ElMessage.warning(`模板「${template.name}」没有正文可套用`); return }
  const draft = { ...emr }
  const result = await inWorkspace(context, () => applyTemplateToEmr(draft, split.parts, {
    confirmOverwrite: labels => ElMessageBox.confirm(`「${labels.join('、')}」已有内容，套用模板将覆盖它，是否继续？`, '套用模板正文', { type: 'warning' }).then(() => true, () => false),
  }))
  if (!result || !context.valid() || result.cancelled || emrSigned.value) return
  Object.assign(emr, draft)
  ElMessage.success(`已套用模板「${template.name}」到：${result.applied.map(k => EMR_SECTIONS.find(x => x.key === k)?.label).join('、')}；点「暂存」后生效`)
  if (split.skipped.length) {
    const labels = split.skipped.map(x => x.label)
    ElMessage.info(`模板里的「${labels.join('、')}」不属于病历正文五段，未套用${labels.some(l => l.includes('诊断')) ? '；诊断请在下方诊断区录入' : ''}`)
  }
  if (result.overLimit.length) ElMessage.warning(`${result.overLimit.map(x => `${x.label}${x.length}字（上限 ${x.limit}）`).join('、')}，超出部分保存时可能失败，请先精简`)
}

/* ===================== v79 车道B（988★）：新病历自动套用科室默认模板 =====================
 * 口径（v79 规划节，主控定）：只在「工作区无病历、未签名、五段正文全空、本科室有启用的默认模板」时套；
 * 已有正文 / 已签名 / 无默认模板 / 接口失败一律什么都不做、不提示。判定在 utils/default-template.ts（有 vitest）。
 * 取数：GET /api/emr-templates/default?deptId=<挂号科室>&recordType=OUTP——无默认返 data=null；
 * 默认模板对本人不可见返 4066，按「无默认」静默处理。只改本页表单，仍须点「暂存」落库；不新增写路径。
 */
interface AutoTplState {
  regId: number
  name: string
  /** 套用前的五段（新病历为空；系统若已预填过敏史则含那一句）——撤销即恢复到它 */
  before: EmrFields
  /** 套用时写入的段值 */
  written: EmrParts
  /** 套用刚完成时的五段：医生一字未动就离开，不提示「已书写未签名」 */
  after: EmrFields
  /** 黄条附注（未套用的非正文段、超列宽） */
  notes: string[]
}
const autoTpl = ref<AutoTplState | null>(null)
/** 本页会话里医生对哪些就诊点过「撤销」：开单/作废后同一就诊重新进页，不再自动套回去 */
const declinedAutoTpl = new Set<number>()

async function fetchDefaultTemplate(row: Record<string, unknown>, context = captureWorkspace(false)): Promise<DefaultTemplate | null> {
  if (!context || typeof row.deptId !== 'number') return null
  try {
    const response = await inWorkspace(context, () => client.get('/emr-templates/default', {
      params: { deptId: row.deptId, recordType: 'OUTP' }, __silentCodes: [4066, 4067],
    }))
    if (!response || !context.valid()) return null
    const template = pickDefaultTemplate(response.data.data)
    if (template && template.content === undefined && template.id !== null) {
      const detail = await inWorkspace(context, () => client.get(`/emr-templates/${template.id}`, { __silentCodes: [4066] }))
      if (!detail || !context.valid()) return null
      template.content = detail.data.data?.content as string | null | undefined
    }
    return template
  } catch { return null }
}

async function autoApplyDefaultTemplate(row: Record<string, unknown>, template: DefaultTemplate, context = captureWorkspace(false)) {
  if (!context?.valid() || context.registrationId !== row.registrationId || emrSigned.value) return
  const before: EmrFields = { ...emr }
  const plan = planDefaultTemplate(template.content, before)
  if (!plan) return
  const draft = { ...emr }
  const result = await inWorkspace(context, () => applyTemplateToEmr(draft, plan.parts, { confirmOverwrite: () => Promise.resolve(true) }))
  if (!result || !context.valid() || result.cancelled || emrSigned.value) return
  Object.assign(emr, draft)
  const notes: string[] = []
  if (plan.skipped.length) notes.push(`模板里的「${plan.skipped.map(x => x.label).join('、')}」不属于病历正文五段，未套用`)
  if (result.overLimit.length) notes.push(`${result.overLimit.map(x => `${x.label}${x.length}字（上限 ${x.limit}）`).join('、')}，超出部分暂存时可能失败，请先精简`)
  autoTpl.value = { regId: context.registrationId, name: template.name, before, written: plan.parts, after: { ...emr }, notes }
}

async function undoAutoTemplate() {
  const context = captureWorkspace(), state = autoTpl.value
  if (!context || !state || state.regId !== context.registrationId) return
  const edited = editedSinceApplied(emr, state.written)
  if (edited.length) {
    const labels = EMR_SECTIONS.filter(x => edited.includes(x.key)).map(x => x.label)
    const accepted = await ElMessageBox.confirm(`「${labels.join('、')}」在套用后已被修改，撤销会连同这些修改一起恢复为套用前的内容，是否继续？`, '撤销默认模板', { type: 'warning' }).then(() => true, () => false)
    if (!accepted || !context.valid()) return
  }
  if (!context.valid() || emrSigned.value) return
  revertDefaultTemplate(emr, state.before, state.written)
  declinedAutoTpl.add(state.regId); autoTpl.value = null
  ElMessage.success('已撤销自动套用的科室默认模板')
}

/** 取模板的结构化字段定义（车道 I：GET /api/emr/templates/{id}/fields，只回启用中的） */
async function loadStructFields() {
  const context = captureWorkspace(); if (!context) return
  const templateId = structTemplateId.value
  structFields.value = []; structValues.value = {}; structHint.value = ''
  if (!templateId) return
  try {
    const response = await inWorkspace(context, () => client.get(`/emr/templates/${templateId}/fields`))
    if (!response || !context.valid() || structTemplateId.value !== templateId) return
    structFields.value = (response.data.data ?? []) as EmrTemplateField[]
    if (!structFields.value.length) structHint.value = '该模板尚未配置结构化元素，本次按自由文本书写即可（元素定义须由管理员配置）'
  } catch {
    if (context.valid() && structTemplateId.value === templateId) structHint.value = '结构化元素定义暂不可读，本次仍可按自由文本书写病历'
  }
}

const emrSigned = ref(false)
const emrSignerName = ref('')
const emrSignedAtText = ref('')
/** v79（2457★）：已签名即「已提交」，签名人与时间与上方冻结条同源 */
const emrStatusText = computed(
  () => `已提交（已签名 · ${emrSignerName.value || '—'} · ${emrSignedAtText.value || '—'}）`)

/**
 * v55 车道R2（994）：当前病历的 outp_emr.id。
 * 来源只有两处——workspace 返回体的 emr.id 与保存病历返回体的 id，两者都是既有契约（OutpEmr 实体直出，一字未改）。
 * 病历尚未保存时为 null：此时一版都没有，按钮不出现。
 */
const currentEmrId = ref<number | null>(null)
/**
 * v79 审阅修补（三）（乙组反驳 B3-2e）：病历行上的书写医师（outp_emr.doctor_id，workspace 的 emr 实体直出）。
 * 提交前要不要先暂存看它是不是本人——先暂存会把书写医师改成提交人，4023 就被架空（utils/emr-submit.ts）。
 */
const emrAuthorId = ref<number | null>(null)
const auth = useAuthStore()
/** 与 router 守卫同一判据：菜单未知不拦；已知则须持有 /emr-version（V162 已授 DOCTOR_OUTP），否则点进去会被踢回首页 */
const canOpenEmrVersion = computed(() => {
  const menus = auth.user?.menus ?? []
  return menus.length === 0 || menus.some((m) => m.path === '/emr-version')
})
/* ===================== v69 包 A：按既往病历新建 / 存为模板 =====================
 * 两块都是「端点齐备、界面无入口」的接线，**零后端改动**。
 * 既往病历一侧只读：取正文 → 插入当前正文段 → 仍走既有「保存病历」写入。
 */
const priorVisible = ref(false)
const priorLoading = computed(() => workspaceActionPending('prior'))
const priorRecords = ref<Record<string, unknown>[]>([])
const savingTpl = computed(() => workspaceActionPending('save-template'))
const sectionLabel = computed(
  () => EMR_SECTIONS.find((x) => x.key === insertTarget.value)?.label ?? '现病史')

async function openPriorRecords() {
  const context = captureWorkspace(); if (!context?.patientId) return
  const finish = beginWorkspaceAction(context, 'prior'); if (!finish) return
  priorVisible.value = true
  try {
    const response = await inWorkspace(context, () => client.get('/emr-templates/prior-records', { params: { patientId: context.patientId } }))
    if (response && context.valid()) priorRecords.value = response.data.data ?? []
  } catch (error) {
    if (context.valid()) throw error
  } finally { finish() }
}

async function usePriorRecord(row: Record<string, unknown>) {
  const context = captureWorkspace(); if (!context?.patientId) return
  const response = await inWorkspace(context, () => client.get('/emr-templates/prior-records/content', {
    params: { patientId: context.patientId, source: row.source, recordId: row.record_id },
  }))
  if (!response || !context.valid()) return
  const text = String(response.data.data?.content ?? '')
  if (!text.trim()) { ElMessage.warning('这份既往病历没有正文可取'); return }
  insertText(text)
  priorVisible.value = false
}

async function saveAsTemplate() {
  const context = captureWorkspace(), recordId = currentEmrId.value
  if (!context?.patientId || !recordId) return
  const finish = beginWorkspaceAction(context, 'save-template'); if (!finish) return
  try {
    const name = await ElMessageBox.prompt('模板名称', '把当前病历存为个人模板', {
      inputValue: `${String(context.visit.patientName ?? '')}门诊病历模板`.trim(),
      inputValidator: (value: string) => (value && value.trim().length > 0) || '请填写模板名称',
    }).then(result => (result as { value: string }).value).catch(() => null)
    if (name == null || !context.valid()) return
    const response = await inWorkspace(context, () => client.post('/emr-templates/from-record', {
      source: 'OUTP', recordId, patientId: context.patientId, name: name.trim(), scope: 'PERSONAL',
    }))
    if (!response || !context.valid()) return
    const out = response.data.data
    ElMessage.success(out?.truncated ? `已存为个人模板，但来源正文 ${out.sourceLength} 字超过模板上限 ${out.maxContent} 字，已截断保存前 ${out.contentLength} 字` : '已存为个人模板（仅本人与被授权人可用）')
  } catch (error) {
    if (context.valid()) throw error
  } finally { finish() }
}

function openEmrVersions() {
  if (!captureWorkspace()) return
  if (!currentEmrId.value) return
  const q = new URLSearchParams({ emrType: 'OUTP', emrId: String(currentEmrId.value), from: 'doctor' })
  window.open(`/emr-version?${q.toString()}`, '_blank')
}
const signing = computed(() => workspaceActionPending('sign'))
const amendForm = reactive({ amendText: '', reason: '' })
const amending = computed(() => workspaceActionPending('amend'))
const amendments = ref<Record<string, unknown>[]>([])
const stockWarnings = ref<string[]>([])
const cdssTips = ref<string[]>([])

/**
 * v51 车道F4：**本次开单**返回体带回的 CDSS 合理用药审查警告（原文照录）。
 *
 * <p>与 {@link cdssTips} 是两回事，别混：cdssTips 是按主诊断查来的诊疗建议（GET /cdss/suggestions），
 * 这里是开单那一刻的处方审查结论（POST .../orders 的返回体，挂在订单元素的 cdssWarnings 字段上）。
 *
 * <p>后端 `@JsonInclude(NON_EMPTY)`：没有警告时字段根本不出现，本数组恒为空，页面与 v50 完全一致。
 */
const rxCdssWarnings = ref<string[]>([])
/** 常驻横幅是否已被医生手动关掉（只关横幅，不清空警告——关掉还能从入口重新打开） */
const cdssWarnBarClosed = ref(false)
const cdssWarnDialog = ref(false)

/**
 * 把后端自己写在句首的【类别】标记拆出来单独当标签显示。
 *
 * <p><b>只做这一件事</b>：`warnTag(w) + warnBody(w)` 与原文<b>逐字相同</b>，
 * 前端不做关键词匹配、不做相似度、不做严重度推断、不改一个字——
 * 提示文案是后端各审查服务连「凭什么」一起拼好的整句，改写就等于伪造依据。
 */
function warnTag(w: string): string {
  const m = /^【([^】]+)】/.exec(w)
  return m ? m[1] : ''
}

function warnBody(w: string): string {
  const m = /^【[^】]+】/.exec(w)
  return m ? w.slice(m[0].length) : w
}
const icdOptions = ref<{ code: string; name: string }[]>([])
const knownIcd = new Map<string, string>()

/**
 * v44 车道E 诊断行（偏离表 977/982/983/1084）。
 * icdCode/icdName/primaryDiag 三个既有字段口径不变——列表顺序第一条即主诊断，后端照旧按下标定主次；
 * 其余五项都是本版新加的可空字段，不填就不传值，后端存 null。
 */
type DiagRow = {
  icdCode: string
  icdName: string
  diagSystem: string
  prefix: string
  suffix: string
  certainty: string
  customName: string
}
const diagList = ref<DiagRow[]>([])
const diagSystem = ref<'ICD10' | 'TCM'>('ICD10')
const icdPick = ref<string | null>(null)
const tcmName = ref('')

type AssistEntry = { id?: number; icdCode?: string | null; icdName: string; diagSystem?: string | null
                     useCount?: number; lastVisitDate?: string }
const assistVisible = ref(false)
const assistTab = ref('history')
const assistKeyword = ref('')
/** v80 审阅修补（D1）：历史段被 4036 拒时为真——抽屉历史页签换一句说明，不说成「该患者无历史诊断」 */
const assistHistoryDenied = ref(false)
const assist = ref<{ history: AssistEntry[]; favorite: AssistEntry[]; frequent: AssistEntry[] }>(
  { history: [], favorite: [], frequent: [] })

type SpecialDisease = { id: number; diseaseCode?: string; diseaseName: string; insuranceType?: string
                        startDate?: string; endDate?: string; remark?: string }
const specialDiseases = ref<SpecialDisease[]>([])
const specialVisible = ref(false)
const savingSpecial = computed(() => workspaceActionPending('special'))
const specialForm = reactive({ diseaseName: '', diseaseCode: '', insuranceType: '',
  startDate: todayLocal(), endDate: '', remark: '' })

const drugOptions = ref<Record<string, unknown>[]>([])
const rxDrugId = ref<number | null>(null)
const rxDose = ref('1粒')
const rxFreq = ref('tid')
const rxRoute = ref('口服')
const rxDays = ref(3)
const rxQty = ref(1)
// ===== v44 合版：处方模板 / 协定处方套用（车道F 契约） =====
const rxTemplates = ref<Record<string, unknown>[]>([])
const tplPicked = ref<number | undefined>(undefined)
const tplHint = ref('')
let tplBatchSeq = 0   // v80 审阅修补（D2）：套用批次序号，本页内递增即可
const scopeNames: Record<string, string> = { PERSONAL: '个人', DEPT: '科室', HOSPITAL: '全院' }

async function loadRxTemplates() {
  const resp = await client.get('/outpatient/rx-templates', { params: { includeDisabled: false } })
  rxTemplates.value = resp.data.data ?? []
}

/** 套用：按 orderType 分流、**追加而非覆盖**（医生常先手工加一行再套模板）。 */
async function applyTemplate(id: number | undefined) {
  const context = captureWorkspace(); if (!context || !id) return
  const response = await inWorkspace(context, () => client.get(`/outpatient/rx-templates/${id}/lines`))
  if (!response || !context.valid()) return
  if (response.data.code !== 0) {
    ElMessage.error(response.data.message)
    await loadRxTemplates()
    if (context.valid()) tplPicked.value = undefined
    return
  }
  const lines = stampTemplateLines((response.data.data ?? []) as Record<string, unknown>[], id, `${id}-${++tplBatchSeq}`)
  const warnings: string[] = []
  for (const line of lines) {
    if (line.itemExists === false) warnings.push(`${line.itemName ?? line.itemId}：项目已不存在，请移除该行`)
    else if (line.itemEnabled === false) warnings.push(`${line.itemName}：该药已停用，开单时会被拒`)
    else if (typeof line.stock === 'number' && typeof line.qty === 'number' && line.stock < line.qty) warnings.push(`${line.itemName}：库存不足（余 ${line.stock}）`)
    if (line.orderType === 'DRUG') rxLines.value.push({ ...line })
    else { const item = { ...line }; prefillClinicalSummary(item); labLines.value.push(item) }
  }
  tplHint.value = `已套用 ${lines.length} 行` + (warnings.length ? `；${warnings.length} 行需注意` : '')
  if (warnings.length) ElMessage.warning(warnings.join('；'))
  tplPicked.value = undefined
}

/** 协定处方整组撤回（locked 行不可单行删）；v80 审阅修补（D2）：只撤 row 所在那一批，药品与检查检验一并计数 */
function dropAgreedGroup(row: Record<string, unknown>) {
  if (!captureWorkspace()) return
  const r = dropTemplateBatch(rxLines.value, labLines.value, row)
  rxLines.value = r.rx
  labLines.value = r.lab
  tplHint.value = `已撤回协定处方（移除 ${r.removed} 行）`
}

const rxLines = ref<Record<string, unknown>[]>([])

const chargeItemOptions = ref<Record<string, unknown>[]>([])
const labItemId = ref<number | null>(null)
const labLines = ref<Record<string, unknown>[]>([])

const orders = ref<Record<string, unknown>[]>([])

/* v70 包 B：队列检索条件。**默认形态与本版之前逐字一致**——不跨日期、不勾「我的患者」时
 * 仍然只发 `date` 一个参数，后端据此走「零条件 = 旧行为」那条不换 SQL 的路径。 */
const rangeMode = ref(false)
const dateRange = ref<[string, string] | null>(null)
const mineOnly = ref(false)

function onRangeModeChange(on: boolean) {
  // 切进区间模式时默认给近 7 天，省得用户面对两个空框
  if (on && !dateRange.value) {
    const d = new Date(); d.setDate(d.getDate() - 6)
    const from = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    dateRange.value = [from, todayLocal()]
  }
  loadWorklist()
}

async function loadWorklist() {
  const params: Record<string, string | boolean> = {}
  if (rangeMode.value && dateRange.value) {
    params.from = dateRange.value[0]
    params.to = dateRange.value[1]
  } else {
    params.date = today.value
  }
  if (mineOnly.value) params.mine = true
  const resp = await client.get('/outpatient/doctor/worklist', { params })
  worklist.value = resp.data.data
}

async function openPatient(row: Record<string, unknown> | null): Promise<WorkspaceContext | null> {
  workspaceGeneration++
  workspaceRenderKey.value = workspaceGeneration
  warnIfLeavingUnsigned(row)
  current.value = row
  clearWorkspace()
  visitDenied.value = false
  workspaceLoading.value = !!row
  if (!row) return null
  const context = captureWorkspace(false)!
  try {
    let response
    try {
      response = await inWorkspace(context, () => client.get(`/outpatient/doctor/${context.registrationId}/workspace`, { __silentCodes: [4036] }))
    } catch (error) {
      if (!context.valid()) return null
    if ((error as BizError).bizCode !== 4036) throw error
      current.value = null
      visitDenied.value = true
      return null
    }
    if (!response || !context.valid()) return null
    const ws = response.data.data
    currentEmrId.value = typeof ws.emr?.id === 'number' ? ws.emr.id : null
    emrAuthorId.value = typeof ws.emr?.doctorId === 'number' ? ws.emr.doctorId : null
    Object.assign(emr, {
      chiefComplaint: ws.emr?.chiefComplaint ?? '', presentIllness: ws.emr?.presentIllness ?? '',
      pastHistory: ws.emr?.pastHistory ?? '', physicalExam: ws.emr?.physicalExam ?? '', advice: ws.emr?.advice ?? '',
    })
    diagList.value = (ws.diagnoses ?? []).map((d: Partial<DiagRow>) => {
      if (d.icdCode) knownIcd.set(d.icdCode, d.icdName ?? d.icdCode)
      return { icdCode: d.icdCode ?? '', icdName: d.icdName ?? '', diagSystem: d.diagSystem ?? '',
        prefix: d.prefix ?? '', suffix: d.suffix ?? '', certainty: d.certainty ?? '', customName: d.customName ?? '' }
    })
    icdOptions.value = (ws.diagnoses ?? []).filter((d: { icdCode?: string }) => !!d.icdCode)
      .map((d: { icdCode: string; icdName: string }) => ({ code: d.icdCode, name: d.icdName }))
    orders.value = ws.orders ?? []
    emrSigned.value = !!ws.emr?.signature
    emrSignerName.value = (ws.emrSignerName as string) ?? ''
    emrSignedAtText.value = ws.emr?.signedAt ? new Date(ws.emr.signedAt as string).toLocaleString('zh-CN') : ''
    await loadSpecialDiseases(context)
    if (!context.valid()) return null
    if (emrSigned.value) {
      const loaded = await inWorkspace(context, () => loadAmendments(context.registrationId))
      if (!loaded || !context.valid()) return null
      amendments.value = loaded
    }
    const eligible = eligibleForDefaultTemplate({ hasEmr: !!ws.emr, signed: emrSigned.value, emr,
      declined: declinedAutoTpl.has(context.registrationId) })
    const [, template] = await Promise.all([
      loadBasicRef(!ws.emr, context), eligible ? fetchDefaultTemplate(context.visit, context) : Promise.resolve(null),
    ])
    if (!context.valid()) return null
    if (template) await autoApplyDefaultTemplate(context.visit, template, context)
    if (!context.valid()) return null
    workspaceReady.value = true
    return context
  } finally {
    // A completion never releases the loading state owned by a newer B open.
    if (context.generation === workspaceGeneration) workspaceLoading.value = false
  }
}

/** v74 复核补入口：检验报告单（既有 PrintView 契约 ?type=lab-report&id=<orderId>，后端 /api/print/lab-report/{orderId}） */
function printLabReport(row: Record<string, unknown>) {
  if (!captureWorkspace()) return
  window.open(`/print?type=lab-report&id=${row.id}`, '_blank')
}

/** v43：五种日常单据打印（车道B 端点 /api/print/doc/{docType}/{registrationId}） */
function printDoc(docType: string) {
  if (!captureWorkspace()) return
  if (!current.value) return
  window.open(`/print?type=${docType}&id=${current.value.registrationId}`, '_blank')
}

/** v43：皮试结果只读查看（车道D 端点，仅放开读；登记与出结果仍限护士） */
const skinTests = ref<Record<string, unknown>[]>([])
async function loadSkinTests() {
  const context = captureWorkspace(); if (!context) return
  const response = await inWorkspace(context, () => client.get('/outpatient/nurse/skin-tests/for-doctor', { params: { registrationId: context.registrationId } }))
  if (!response || !context.valid()) return
  skinTests.value = response.data.data ?? []
  if (!skinTests.value.length) ElMessage.info('本次就诊暂无皮试记录')
}

async function loadAmendments(registrationId: number) {
  const resp = await client.get(`/outpatient/doctor/${registrationId}/emr/amendments`)
  return (resp.data.data ?? []) as Record<string, unknown>[]
}

// 阻塞4：签名冻结病历追加补正记录（原文保留，法定留痕）
async function submitAmend() {
  const context = captureWorkspace(); if (!context) return
  if (!amendForm.amendText.trim() || !amendForm.reason.trim()) { ElMessage.warning('补正内容与补正原因均须填写'); return }
  const finish = beginWorkspaceAction(context, 'amend'); if (!finish) return
  const body = { amendText: amendForm.amendText, reason: amendForm.reason }
  try {
    const response = await inWorkspace(context, () => client.post(`/outpatient/doctor/${context.registrationId}/emr/amend`, body))
    if (!response || !context.valid()) return
    if (response.data.code !== 0) { ElMessage.error(response.data.message); return }
    ElMessage.success('补正已留痕'); amendForm.amendText = ''; amendForm.reason = ''
    const loaded = await inWorkspace(context, () => loadAmendments(context.registrationId))
    if (loaded && context.valid()) amendments.value = loaded
  } catch (error) {
    if (context.valid()) throw error
  } finally { finish() }
}

async function startVisit() {
  const context = captureWorkspace(); if (!context) return
  const finish = beginWorkspaceAction(context, 'start'); if (!finish) return
  try {
    const response = await inWorkspace(context, () => client.post(`/outpatient/doctor/${context.registrationId}/start`))
    if (!response || !context.valid()) return
    context.visit.status = response.data.data.status
    ElMessage.success('已接诊')
    await loadWorklist()
  } catch (error) {
    if (context.valid()) throw error
  } finally { finish() }
}

async function searchIcd(kw: string) {
  if (!kw) return
  const resp = await client.get('/masterdata/icd10', { params: { keyword: kw } })
  icdOptions.value = resp.data.data
  resp.data.data.forEach((i: { code: string; name: string }) => knownIcd.set(i.code, i.name))
}

async function saveEmr(): Promise<boolean> {
  const context = captureWorkspace(); if (!context) return false
  const finish = beginWorkspaceAction(context, 'save'); if (!finish) return false
  try {
  const usingStruct = structFields.value.length > 0 && Object.values(structValues.value).some(
    value => value !== null && value !== undefined && value !== '' && !(Array.isArray(value) && value.length === 0))
  const body = { emr: { ...emr }, diagnoses: diagList.value.map(d => ({ icdCode: d.icdCode, icdName: d.icdName,
    prefix: d.prefix || null, suffix: d.suffix || null, certainty: d.certainty || null,
    diagSystem: d.diagSystem || null, customName: d.customName || null })),
    templateId: usingStruct ? structTemplateId.value : undefined,
    fields: usingStruct ? JSON.parse(JSON.stringify(structValues.value)) : undefined }
    const response = await inWorkspace(context, () => client.put(`/outpatient/doctor/${context.registrationId}/emr`, body))
    if (!response || !context.valid()) return false
    if (response.data.code !== 0) { ElMessage.error(response.data.message); return false }
    ElMessage.success('病历已暂存（未签名，仍可修改）')
    if (typeof response.data.data?.id === 'number') currentEmrId.value = response.data.data.id
    emrAuthorId.value = typeof response.data.data?.doctorId === 'number' ? response.data.data.doctorId : null
    autoTpl.value = null
    await loadCdssTips(context)
    return context.valid()
  } catch (error) {
    if (context.valid()) throw error
    return false
  } finally { finish() }
}

// ===================== v44 车道E：诊断录入 / 诊断助手 / 常用诊断 / 特殊病种 =====================

/** 同一诊断（编码相同，或都无编码而名称相同）不重复加入 */
function alreadyPicked(code: string, name: string) {
  return diagList.value.some((d) => (code ? d.icdCode === code : !d.icdCode && d.icdName === name))
}

function pushDiag(code: string, name: string, system: string) {
  if (!captureWorkspace()) return
  if (!name || alreadyPicked(code, name)) return
  diagList.value.push({ icdCode: code, icdName: name, diagSystem: system,
    prefix: '', suffix: '', certainty: '', customName: '' })
}

function addDiagFromIcd(code: string) {
  if (!captureWorkspace()) return
  if (!code) return
  pushDiag(code, knownIcd.get(code) ?? code, 'ICD10')
  icdPick.value = null
}

/**
 * 中医/自定义诊断加入。**本平台不预置中医诊断码表**（无权威码表授权，也不编造编码），
 * 故中医诊断只录名称，icdCode 传空串——后端 icd_code 列非空约束不变，存空串不存假码。
 */
function addDiagCustom() {
  if (!captureWorkspace()) return
  const name = tcmName.value.trim()
  if (!name) return
  pushDiag('', name, 'TCM')
  tcmName.value = ''
}

function addDiagFrom(e: AssistEntry) {
  if (!captureWorkspace()) return
  pushDiag(e.icdCode ?? '', e.icdName, e.diagSystem || (e.icdCode ? 'ICD10' : 'TCM'))
}

async function openAssist() {
  const context = captureWorkspace(); if (!context) return
  assistVisible.value = true
  await loadAssist(context)
}

async function loadAssist(context = captureWorkspace()) {
  if (!context) return
  const keyword = assistKeyword.value || undefined
  assistHistoryDenied.value = false
  try {
    const response = await inWorkspace(context, () => client.get('/outpatient/doctor/diagnosis-assist', { params: { patientId: context.patientId, keyword }, __silentCodes: [4036] }))
    if (response && context.valid()) assist.value = response.data.data
  } catch (error) {
    if (!context.valid()) return
    if ((error as BizError).bizCode !== 4036) throw error
    assistHistoryDenied.value = true
    const response = await inWorkspace(context, () => client.get('/outpatient/doctor/diagnosis-assist', { params: { keyword } }))
    if (response && context.valid()) assist.value = response.data.data
  }
}

/** 加星到个人常用诊断（保存病历时本就会自动累加，这里是医生主动收藏） */
async function starDiag(entry: { icdCode?: string | null; icdName: string; diagSystem?: string | null }) {
  const context = captureWorkspace()
  await client.post('/outpatient/doctor/diagnosis-favorite', { icdCode: entry.icdCode || null, icdName: entry.icdName, diagSystem: entry.diagSystem || null })
  if (context && !context.valid()) return
  ElMessage.success('已加入常用诊断')
  if (context && assistVisible.value) await loadAssist(context)
}

async function removeFavorite(id?: number) {
  if (!id) return
  const context = captureWorkspace()
  await client.delete(`/outpatient/doctor/diagnosis-favorite/${id}`)
  if (context && !context.valid()) return
  ElMessage.success('已移出常用诊断')
  if (context) await loadAssist(context)
}

async function loadSpecialDiseases(context = captureWorkspace()) {
  if (!context) return
  if (!context.patientId) { specialDiseases.value = []; return }
  const response = await inWorkspace(context, () => client.get('/outpatient/doctor/special-disease', {
    params: { patientId: context.patientId, activeOnly: true },
  }))
  if (response && context.valid()) specialDiseases.value = response.data.data ?? []
}

/**
 * 特殊病种「院内登记」（984）。**只登记，不报送**——向医保经办机构备案需走当地医保接口，
 * 属外部对接条件，本平台不提供，界面上也已写明，避免让院方误以为登记完就等于备案完成。
 */
async function addSpecialDisease() {
  const context = captureWorkspace(); if (!context) return
  const finish = beginWorkspaceAction(context, 'special'); if (!finish) return
  const body = { patientId: context.patientId, diseaseName: specialForm.diseaseName, diseaseCode: specialForm.diseaseCode,
    insuranceType: specialForm.insuranceType, startDate: specialForm.startDate, endDate: specialForm.endDate || null, remark: specialForm.remark }
  try {
    const response = await inWorkspace(context, () => client.post('/outpatient/doctor/special-disease', body))
    if (!response || !context.valid()) return
    if (response.data.code !== 0) { ElMessage.error(response.data.message); return }
    ElMessage.success('已登记（院内）'); specialForm.diseaseName = ''; specialForm.diseaseCode = ''; specialForm.remark = ''
    await loadSpecialDiseases(context)
  } catch (error) {
    if (context.valid()) throw error
  } finally { finish() }
}

async function removeSpecialDisease(id: number) {
  const context = captureWorkspace(); if (!context) return
  const finish = beginWorkspaceAction(context, 'special'); if (!finish) return
  try {
    const response = await inWorkspace(context, () => client.delete(`/outpatient/doctor/special-disease/${id}`))
    if (response && context.valid()) await loadSpecialDiseases(context)
  } catch (error) {
    if (context.valid()) throw error
  } finally { finish() }
}

function emrHasContent() {
  return [emr.chiefComplaint, emr.presentIllness, emr.pastHistory, emr.physicalExam, emr.advice]
    .some((v) => (v ?? '').trim().length > 0)
}

/**
 * v43 门诊病历签名（偏离表 991）。端点 POST /emr/sign 早已齐备，此前界面无按钮，
 * 医生签不了名；而「已签名」才显示的补正区块因此永远不可达。签名成功后本页转冻结态。
 */
async function signEmr() {
  const context = captureWorkspace(); if (!context) return
  if (!emrHasContent()) { ElMessage.warning('病历内容为空，请先书写并暂存后再提交（签名）'); return }
  if (untouchedDefaultTemplate(autoTpl.value, emr)) { ElMessage.warning('自动套用的模板尚未修改，请书写后再提交'); return }
  const finish = beginWorkspaceAction(context, 'sign'); if (!finish) return
  const presave = presaveBeforeSign({ hasEmr: currentEmrId.value != null, authorId: emrAuthorId.value, meId: auth.user?.id ?? null })
  try {
    try {
      await ElMessageBox.confirm(presave
        ? '签名即提交：将先暂存屏上当前内容，再以此签名；签名后本次病历原文即冻结，不能再修改，只能追加补正记录。确认提交？'
        : '签名即提交：这份病历的书写医师不是当前登录人，不会先暂存屏上内容，按已暂存的内容签名；签名后原文即冻结，只能追加补正记录。确认提交？',
        '提交病历（签名）', { type: 'warning', confirmButtonText: '确认提交（签名）', cancelButtonText: '再改改' })
    } catch { return }
    if (!context.valid()) return
    if (presave && !(await saveEmr())) return
    if (!context.valid()) return
    const response = await inWorkspace(context, () => client.post(`/outpatient/doctor/${context.registrationId}/emr/sign`))
    if (!response || !context.valid()) return
    if (response.data.code !== 0) { ElMessage.error(response.data.message); return }
    ElMessage.success('病历已提交（已签名）')
    const refreshed = await openPatient(context.visit)
    if (refreshed?.valid()) await loadWorklist()
  } catch (error) {
    if (context.valid()) throw error
  } finally { finish() }
}

/**
 * v43 诊毕未签提示：切换患者时若上一位的病历已书写却未签名，给一条提示。
 * 本版是零写路径阻断——只提示，不拦截切换，也不改任何保存/开单逻辑。
 */
function warnIfLeavingUnsigned(next: Record<string, unknown> | null) {
  const prev = current.value
  if (!prev || emrSigned.value || !emrHasContent()) return
  // v79：只有自动套用的默认模板、医生一字未动——那不是「已书写」，不提示
  if (untouchedDefaultTemplate(autoTpl.value, emr)) return
  if (next && (next.registrationId as number) === (prev.registrationId as number)) return
  ElMessage({
    type: 'warning',
    duration: 6000,
    message: `患者「${prev.patientName}」本次病历已书写但尚未签名，请回到该患者完成签名。`,
  })
}

// CDSS：按主诊断给出诊疗建议
async function loadCdssTips(context = captureWorkspace()) {
  if (!context) return
  cdssTips.value = []
  const primary = diagList.value[0]?.icdCode
  if (!primary) return
  try {
    const response = await inWorkspace(context, () => client.get('/cdss/suggestions', { params: { icd: primary } }))
    if (response && context.valid() && diagList.value[0]?.icdCode === primary) cdssTips.value = (response.data.data as { content: string }[]).map(s => s.content)
  } catch { /* Optional advice remains a silent fallback. */ }
}

async function searchDrugs(kw: string) {
  const resp = await client.get('/masterdata/drugs', { params: { keyword: kw } })
  drugOptions.value = resp.data.data
}

function addRxLine() {
  if (!captureWorkspace()) return
  const drug = drugOptions.value.find((d) => d.id === rxDrugId.value)
  if (!drug) return
  rxLines.value.push({
    orderType: 'DRUG', itemId: drug.id, itemName: drug.name, qty: rxQty.value,
    usageRoute: rxRoute.value, frequency: rxFreq.value, dosePerTime: rxDose.value, days: rxDays.value,
  })
  rxDrugId.value = null
}

async function searchChargeItems(kw: string) {
  const resp = await client.get('/masterdata/charge-items', { params: { keyword: kw } })
  chargeItemOptions.value = resp.data.data
}

/** v78 车道B：标本类型字典（契约 GET /masterdata/specimen-types，默认只返启用项）。进页拉一次缓存在 ref，检验行下拉共用 */
const specimenTypeOptions = ref<{ id: number; name: string }[]>([])
async function loadSpecimenTypes() {
  try {
    specimenTypeOptions.value = (await client.get('/masterdata/specimen-types')).data.data
  } catch {
    specimenTypeOptions.value = []   // 字典拉不到（拦截器已弹红字）不拖垮医生站：下拉为空、仍可手填
  }
}

function addLabLine() {
  if (!captureWorkspace()) return
  const item = chargeItemOptions.value.find((c) => c.id === labItemId.value)
  if (!item) return
  const line: Record<string, unknown> = { orderType: item.category, itemId: item.id, itemName: item.name, category: item.category, qty: 1 }
  prefillClinicalSummary(line)
  labLines.value.push(line)
  labItemId.value = null
}

/**
 * v77 车道B（1016★ 第二句）：检验/检查行加入时，临床摘要为空则由当前病历的主诉+现病史预填成
 * 「主诉：…；现病史：…」。医生可改；已有值（含协定处方模板自带的）不覆盖；超 500 字（列宽）截断并提示一次。
 * 只读本页 emr 表单（医生正在写的那份，未必已保存）——开单时病历通常就在同屏，拿已保存版会把刚写的漏掉。
 */
let summaryTruncWarned = false
function prefillClinicalSummary(line: Record<string, unknown>) {
  if (line.category !== 'LAB' && line.category !== 'EXAM') return
  if (typeof line.clinicalSummary === 'string' && line.clinicalSummary.trim()) return
  const parts: string[] = []
  // 第二轮复核（反驳者三）：结构化录入会把「【结构化记录】…【结构化记录结束】」块追加进现病史，
  // 预填若原样带走，申请单「临床摘要」会印出内部标记，而同纸「病史摘要」是剥过的——同一处理函数剥一遍。
  const cc = stripBlockMarks(emr.chiefComplaint)
  const pi = stripBlockMarks(emr.presentIllness)
  if (cc) parts.push(`主诉：${cc}`)
  if (pi) parts.push(`现病史：${pi}`)
  if (!parts.length) return
  let text = parts.join('；')
  if (text.length > 500) {
    text = text.slice(0, 500)
    if (!summaryTruncWarned) {
      summaryTruncWarned = true
      ElMessage.warning('临床摘要预填超过 500 字，已截断到 500 字，请酌情精简')
    }
  }
  line.clinicalSummary = text
}

async function submitOrders(kind: 'rx' | 'lab') {
  const context = captureWorkspace(); if (!context) return
  const finish = beginWorkspaceAction(context, 'orders'); if (!finish) return
  try {
    const body = { lines: JSON.parse(JSON.stringify(kind === 'rx' ? rxLines.value : labLines.value)) }
    const response = await inWorkspace(context, () => client.post(`/outpatient/doctor/${context.registrationId}/orders`, body))
    if (!response || !context.valid()) return
    const created = (response.data.data ?? []) as Record<string, unknown>[]
    const departments = kind === 'lab' ? [...new Set(created.map(o => o.execDeptName).filter((name): name is string => typeof name === 'string' && name !== ''))] : []
    ElMessage.success(kind === 'rx' ? '处方已开立' : `申请已提交${departments.length ? `；检验执行科室：${departments.join('、')}` : ''}`)
    const warnings = created.filter(o => o.stockWarnAvailable !== null && o.stockWarnAvailable !== undefined)
    const seen = new Set<string>(), cdssWarnings: string[] = []
    for (const order of created) if (Array.isArray(order.cdssWarnings)) for (const item of order.cdssWarnings) {
      const text = String(item)
      if (!seen.has(text)) { seen.add(text); cdssWarnings.push(text) }
    }
    if (kind === 'rx') rxLines.value = []; else labLines.value = []
    const refreshed = await openPatient(context.visit)
    if (!refreshed?.valid()) return
    if (warnings.length) {
      stockWarnings.value = warnings.map(o => `${o.itemName}（余 ${o.stockWarnAvailable}）`)
      tab.value = 'rx'
      ElMessage({ type: 'warning', duration: 6000, message: '部分药品库存不足，缴费后可能无法发药，请查看处方页提示' })
    } else tab.value = 'orders'
    if (cdssWarnings.length) { rxCdssWarnings.value = cdssWarnings; cdssWarnBarClosed.value = false; cdssWarnDialog.value = true }
  } catch (error) {
    if (context.valid()) throw error
  } finally { finish() }
}

async function cancelOrder(row: Record<string, unknown>) {
  const visit = current.value
  if (!workspaceAlive || !visit || cancellingOrder.value !== null || row.status !== 'CREATED'
      || row.registrationId !== visit.registrationId || !orders.value.includes(row)) return
  const generation = workspaceGeneration
  const stillCurrent = () => workspaceAlive && generation === workspaceGeneration && current.value === visit
  cancellingOrder.value = row.id as number
  try {
    try {
      await client.put(`/outpatient/doctor/orders/${row.id}/cancel`)
      if (stillCurrent()) ElMessage.success('已作废')
    } catch (error) {
      // The API client displays the business error. Refresh charged state after the old CREATED row is rejected.
      if ((error as BizError).bizCode !== 4007) throw error
    }
    if (!stillCurrent()) return
    orders.value = []
    const resp = await client.get(`/outpatient/doctor/${visit.registrationId}/workspace`)
    if (stillCurrent()) orders.value = resp.data.data.orders ?? []
    // Refreshing orders must not overwrite unsaved EMR/diagnosis edits or reopen another patient.
  } finally {
    cancellingOrder.value = null
  }
}

onMounted(async () => {
  // v79 复核（乙组审计者 D3）：粘贴管控档位最先加载、单独兜底——排在后面时，前面任一加载抛错它就不会执行，档位停在默认
  try { await loadCopyPolicy() } catch { /* 读不到即保持组合式函数的默认档，不影响进页 */ }
  await loadWorklist()
  await loadRxTemplates()   // v44：进页时拉一次可见模板（后端已按登录人算好可见范围，前端不再过滤）
  // v74 复核（993★）：loadEmrTemplates 自 v45 起定义了却从未被调用——结构化模板那一栏（989★/1075★）在门诊
  // 医生站从来渲染不出来。进页拉一次可见范围内的病历模板，与处方模板同口径。
  await loadEmrTemplates()
  await loadSpecimenTypes()   // v78：检验行标本类型下拉的字典项，进页一次
})
</script>

<style scoped>
/* v51 车道F4：CDSS 审查提示。字号不缩、行距放开——这是要被逐条读完的东西，不是脚注。 */
.cdss-warn-bar { margin-bottom: 10px; }
.cdss-warn-item { line-height: 1.7; margin-top: 4px; }
.cdss-dlg-item { line-height: 1.8; padding: 8px 0; border-bottom: 1px solid #f0f0f0; }
.cdss-dlg-item:last-child { border-bottom: none; }
.cdss-dlg-note { margin-bottom: 10px; }
.cdss-warn-tag { margin-right: 6px; vertical-align: 1px; }
.cdss-warn-text { white-space: pre-wrap; word-break: break-word; }
.cdss-warn-src { margin-top: 8px; font-size: 12px; color: #909399; }
.cdss-warn-reopen { margin-bottom: 8px; }

.tpl-bar { display: flex; align-items: center; gap: 8px; margin: 8px 0; }
.tpl-hint { color: #909399; font-size: 12px; }
/* v43 合版：已开医嘱页签的单据打印与皮试结果工具条 */
.doc-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.skin-inline { display: inline-flex; flex-wrap: wrap; align-items: center; }

.doctor-page {
  display: grid;
  /* v43：队列多一列「病历（已签/未签）」，360px 才放得下不横向滚 */
  grid-template-columns: 360px 1fr;
  gap: 12px;
}
/* v79 审阅修补（三）：跨日期时队列多一列「就诊日期」，加宽同量，仍不横向滚 */
.doctor-page.range-mode { grid-template-columns: 456px 1fr; }
.queue-filter { display: flex; align-items: center; gap: 8px; margin-top: 6px; flex-wrap: wrap; }
.dim { color: var(--el-text-color-placeholder); }
.emr-status {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 12px;
}
.sign-tip {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.hist-line { margin: 2px 0; font-size: 13px; color: var(--el-text-color-regular); }
.add-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
/* v44 车道E：诊断录入区与诊断助手 */
.diag-block { width: 100%; }
.diag-add { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.assist-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.assist-name { flex: 1; cursor: pointer; color: var(--el-color-primary); }
.assist-name:hover { text-decoration: underline; }

.amend-block {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px dashed #dcdfe6;
}
.amend-reason {
  color: #888;
  font-size: 12px;
  margin-top: 2px;
}
</style>
