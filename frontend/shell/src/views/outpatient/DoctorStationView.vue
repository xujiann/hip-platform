<template>
  <div class="doctor-page">
    <el-card class="worklist">
      <template #header>
        接诊队列（{{ today }}）
        <el-button link type="primary" style="float: right" @click="loadWorklist">刷新</el-button>
      </template>
      <el-table :data="worklist" highlight-current-row height="calc(100vh - 220px)" @current-change="openPatient">
        <el-table-column prop="regNo" label="号" width="50" />
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
                   @click="startVisit">
          接 诊
        </el-button>
        <!-- v37 门诊病历连续调阅：历次就诊/既往诊断抽屉 -->
        <el-button size="small" style="float: right; margin-right: 8px" @click="openHistory">历史就诊</el-button>
      </template>

      <el-tabs v-model="tab">
        <el-tab-pane label="病历" name="emr">
          <!-- v43：签名冻结态——原文只读，页首写明签名人与签名时间 -->
          <el-alert v-if="emrSigned" type="success" :closable="false" show-icon style="margin-bottom: 10px"
                    :title="`本次病历已签名冻结 · 签名人：${emrSignerName || '—'} · 签名时间：${emrSignedAtText || '—'}`" />
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
                  <el-table-column label="前缀" width="96">
                    <template #default="{ row }">
                      <el-input v-model="row.prefix" size="small" :disabled="emrSigned" placeholder="如 疑似" />
                    </template>
                  </el-table-column>
                  <el-table-column label="诊断名称" min-width="180">
                    <template #default="{ row }">
                      {{ row.icdName }}<span v-if="row.icdCode" class="dim"> ({{ row.icdCode }})</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="后缀" width="96">
                    <template #default="{ row }">
                      <el-input v-model="row.suffix" size="small" :disabled="emrSigned" placeholder="如 术后" />
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
                      <el-input v-model="row.customName" size="small" :disabled="emrSigned"
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
            <el-button type="primary" :loading="savingEmr" :disabled="emrSigned" @click="saveEmr">保存病历</el-button>
            <!-- v43：门诊病历签名入口（此前端点齐备但界面无按钮，签名与补正在正常路径上都走不到） -->
            <el-button v-if="!emrSigned" type="warning" :loading="signing" @click="signEmr">签 名</el-button>
            <span v-if="!emrSigned" class="sign-tip">签名后原文冻结，如需更正只能追加补正记录</span>
          </el-form>

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
                                :timestamp="`${String(a.amended_at).slice(0, 16).replace('T', ' ')} · ${a.amended_by_name ?? ('用户' + a.amended_by)}`">
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
            <el-table-column label="" width="60">
              <template #default="{ row, $index }">
                <!-- v44：协定处方由药事委员会固定，要么整组用要么整组撤，不许单行删 -->
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
            <el-table-column prop="itemName" label="项目" />
            <el-table-column label="类别" width="80">
              <template #default="{ row }">{{ categoryNames[row.category as string] }}</template>
            </el-table-column>
            <el-table-column label="" width="60">
              <template #default="{ $index }">
                <el-button link type="danger" @click="labLines.splice($index, 1)">移除</el-button>
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
                <el-button v-if="row.status === 'CREATED'" link type="danger" @click="cancelOrder(row)">作废</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
    <el-empty v-else class="workspace" description="从左侧队列选择患者" />

    <!-- v37 历史就诊抽屉：近 50 次就诊（剔退号）+ 各次诊断/主诉/处理意见 -->
    <el-drawer v-model="historyVisible" title="历史就诊" size="480px">
      <el-empty v-if="!history.length" description="无历史就诊" :image-size="60" />
      <el-timeline v-else>
        <el-timeline-item v-for="h in history" :key="h.registrationId as number"
                          :timestamp="`${h.visitDate}${h.signed ? ' · 已签名' : ''}`">
          <div v-if="(h.diagnoses as Record<string, unknown>[])?.length" style="margin-bottom:4px">
            <el-tag v-for="(d, i) in (h.diagnoses as Record<string, unknown>[])" :key="i"
                    :type="d.primaryDiag ? 'danger' : 'info'" size="small" style="margin-right:4px">
              {{ d.icdName || d.icdCode }}
            </el-tag>
          </div>
          <p v-if="h.chiefComplaint" class="hist-line">主诉：{{ h.chiefComplaint }}</p>
          <p v-if="h.advice" class="hist-line">处理：{{ h.advice }}</p>
        </el-timeline-item>
      </el-timeline>
    </el-drawer>

    <!-- v44 诊断助手（979）：历史 / 常用 / 高频 三源，点一下带入诊断表 -->
    <el-drawer v-model="assistVisible" title="诊断助手" size="460px">
      <el-input v-model="assistKeyword" placeholder="按名称或编码筛选三段结果" clearable
                style="margin-bottom: 10px" @keyup.enter="loadAssist" @clear="loadAssist">
        <template #append><el-button @click="loadAssist">筛选</el-button></template>
      </el-input>
      <el-tabs v-model="assistTab">
        <el-tab-pane :label="`历史诊断(${assist.history.length})`" name="history">
          <el-empty v-if="!assist.history.length" description="该患者无历史诊断" :image-size="60" />
          <div v-for="(e, i) in assist.history" :key="'h' + i" class="assist-row">
            <span class="assist-name" @click="addDiagFrom(e)">{{ e.icdName }}
              <span v-if="e.icdCode" class="dim">({{ e.icdCode }})</span></span>
            <span class="dim">{{ e.lastVisitDate }}</span>
            <el-button link type="warning" size="small" @click="starDiag(e)">加星</el-button>
          </div>
        </el-tab-pane>
        <el-tab-pane :label="`常用诊断(${assist.favorite.length})`" name="favorite">
          <el-empty v-if="!assist.favorite.length" description="尚无常用诊断（保存病历时自动累积）" :image-size="60" />
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
    <el-dialog v-model="specialVisible" title="医保特殊病种（慢特病）· 院内登记" width="640px">
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
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { todayLocal } from '../../utils/date'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

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
const historyVisible = ref(false)
const history = ref<Record<string, unknown>[]>([])
async function openHistory() {
  if (!current.value) return
  history.value = (await client.get(`/outpatient/doctor/patient/${current.value.patientId}/history`)).data.data
  historyVisible.value = true
}
const tab = ref('emr')
const savingEmr = ref(false)
const submitting = ref(false)

const emr = reactive({ chiefComplaint: '', presentIllness: '', pastHistory: '', physicalExam: '', advice: '' })
const emrSigned = ref(false)
const emrSignerName = ref('')
const emrSignedAtText = ref('')
const signing = ref(false)
const amendForm = reactive({ amendText: '', reason: '' })
const amending = ref(false)
const amendments = ref<Record<string, unknown>[]>([])
const stockWarnings = ref<string[]>([])
const cdssTips = ref<string[]>([])
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
const assist = ref<{ history: AssistEntry[]; favorite: AssistEntry[]; frequent: AssistEntry[] }>(
  { history: [], favorite: [], frequent: [] })

type SpecialDisease = { id: number; diseaseCode?: string; diseaseName: string; insuranceType?: string
                        startDate?: string; endDate?: string; remark?: string }
const specialDiseases = ref<SpecialDisease[]>([])
const specialVisible = ref(false)
const savingSpecial = ref(false)
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
const scopeNames: Record<string, string> = { PERSONAL: '个人', DEPT: '科室', HOSPITAL: '全院' }

async function loadRxTemplates() {
  const resp = await client.get('/outpatient/rx-templates', { params: { includeDisabled: false } })
  rxTemplates.value = resp.data.data ?? []
}

/** 套用：按 orderType 分流、**追加而非覆盖**（医生常先手工加一行再套模板）。 */
async function applyTemplate(id: number | undefined) {
  if (!id) return
  const resp = await client.get(`/outpatient/rx-templates/${id}/lines`)
  if (resp.data.code !== 0) {
    ElMessage.error(resp.data.message)
    await loadRxTemplates()   // 4060：多半是被停用了，刷新列表
    tplPicked.value = undefined
    return
  }
  const lines = (resp.data.data ?? []) as Record<string, unknown>[]
  const warn: string[] = []
  for (const ln of lines) {
    // 落地提示，不拦截——真正的判定仍在开单端点
    if (ln.itemExists === false) warn.push(`${ln.itemName ?? ln.itemId}：项目已不存在，请移除该行`)
    else if (ln.itemEnabled === false) warn.push(`${ln.itemName}：该药已停用，开单时会被拒`)
    else if (typeof ln.stock === 'number' && typeof ln.qty === 'number' && ln.stock < ln.qty) {
      warn.push(`${ln.itemName}：库存不足（余 ${ln.stock}）`)
    }
    if (ln.orderType === 'DRUG') rxLines.value.push({ ...ln })
    else labLines.value.push({ ...ln })
  }
  tplHint.value = `已套用 ${lines.length} 行` + (warn.length ? `；${warn.length} 行需注意` : '')
  if (warn.length) ElMessage.warning(warn.join('；'))
  tplPicked.value = undefined
}

/** 协定处方整组撤回（locked 行不可单行删） */
function dropAgreedGroup(row: Record<string, unknown>) {
  const before = rxLines.value.length
  rxLines.value = rxLines.value.filter((l) => !(l.locked && l.tplId === row.tplId))
  labLines.value = labLines.value.filter((l) => !(l.locked && l.tplId === row.tplId))
  tplHint.value = `已撤回协定处方（移除 ${before - rxLines.value.length} 行）`
}

const rxLines = ref<Record<string, unknown>[]>([])

const chargeItemOptions = ref<Record<string, unknown>[]>([])
const labItemId = ref<number | null>(null)
const labLines = ref<Record<string, unknown>[]>([])

const orders = ref<Record<string, unknown>[]>([])

async function loadWorklist() {
  today.value = todayLocal()
  const resp = await client.get('/outpatient/doctor/worklist', { params: { date: today.value } })
  worklist.value = resp.data.data
}

async function openPatient(row: Record<string, unknown> | null) {
  warnIfLeavingUnsigned(row)
  current.value = row
  if (!row) return
  const resp = await client.get(`/outpatient/doctor/${row.registrationId}/workspace`)
  const ws = resp.data.data
  Object.assign(emr, {
    chiefComplaint: ws.emr?.chiefComplaint ?? '', presentIllness: ws.emr?.presentIllness ?? '',
    pastHistory: ws.emr?.pastHistory ?? '', physicalExam: ws.emr?.physicalExam ?? '', advice: ws.emr?.advice ?? '',
  })
  diagList.value = (ws.diagnoses ?? []).map((d: Partial<DiagRow>) => {
    if (d.icdCode) knownIcd.set(d.icdCode, d.icdName ?? d.icdCode)
    return {
      icdCode: d.icdCode ?? '', icdName: d.icdName ?? '',
      // 历史行 diagSystem 为 null：读侧按西医显示，但不回写数据库（保存时原样回传空值）
      diagSystem: d.diagSystem ?? '',
      prefix: d.prefix ?? '', suffix: d.suffix ?? '',
      certainty: d.certainty ?? '', customName: d.customName ?? '',
    }
  })
  icdOptions.value = (ws.diagnoses ?? [])
    .filter((d: { icdCode?: string }) => !!d.icdCode)
    .map((d: { icdCode: string; icdName: string }) => ({ code: d.icdCode, name: d.icdName }))
  diagSystem.value = 'ICD10'
  icdPick.value = null
  tcmName.value = ''
  assistVisible.value = false
  await loadSpecialDiseases()
  orders.value = ws.orders ?? []
  emrSigned.value = !!ws.emr?.signature
  emrSignerName.value = (ws.emrSignerName as string) ?? ''
  emrSignedAtText.value = ws.emr?.signedAt ? new Date(ws.emr.signedAt as string).toLocaleString('zh-CN') : ''
  stockWarnings.value = []
  amendForm.amendText = ''
  amendForm.reason = ''
  amendments.value = emrSigned.value ? await loadAmendments(row.registrationId as number) : []
  skinTests.value = []   // v43：切患者清空，避免上一位的皮试结果串到本位
  tplHint.value = ''     // v44：切患者清空模板提示
  rxLines.value = []
  labLines.value = []
}

/** v43：五种日常单据打印（车道B 端点 /api/print/doc/{docType}/{registrationId}） */
function printDoc(docType: string) {
  if (!current.value) return
  window.open(`/print?type=${docType}&id=${current.value.registrationId}`, '_blank')
}

/** v43：皮试结果只读查看（车道D 端点，仅放开读；登记与出结果仍限护士） */
const skinTests = ref<Record<string, unknown>[]>([])
async function loadSkinTests() {
  if (!current.value) return
  const resp = await client.get('/outpatient/nurse/skin-tests/for-doctor',
    { params: { registrationId: current.value.registrationId } })
  skinTests.value = resp.data.data ?? []
  if (skinTests.value.length === 0) ElMessage.info('本次就诊暂无皮试记录')
}

async function loadAmendments(registrationId: number) {
  const resp = await client.get(`/outpatient/doctor/${registrationId}/emr/amendments`)
  return (resp.data.data ?? []) as Record<string, unknown>[]
}

// 阻塞4：签名冻结病历追加补正记录（原文保留，法定留痕）
async function submitAmend() {
  if (!current.value) return
  if (!amendForm.amendText.trim() || !amendForm.reason.trim()) {
    ElMessage.warning('补正内容与补正原因均须填写')
    return
  }
  amending.value = true
  try {
    const resp = await client.post(`/outpatient/doctor/${current.value.registrationId}/emr/amend`, {
      amendText: amendForm.amendText,
      reason: amendForm.reason,
    })
    if (resp.data.code !== 0) {
      ElMessage.error(resp.data.message)
      return
    }
    ElMessage.success('补正已留痕')
    amendForm.amendText = ''
    amendForm.reason = ''
    amendments.value = await loadAmendments(current.value.registrationId as number)
  } finally {
    amending.value = false
  }
}

async function startVisit() {
  if (!current.value) return
  const resp = await client.post(`/outpatient/doctor/${current.value.registrationId}/start`)
  current.value.status = resp.data.data.status
  ElMessage.success('已接诊')
  await loadWorklist()
}

async function searchIcd(kw: string) {
  if (!kw) return
  const resp = await client.get('/masterdata/icd10', { params: { keyword: kw } })
  icdOptions.value = resp.data.data
  resp.data.data.forEach((i: { code: string; name: string }) => knownIcd.set(i.code, i.name))
}

async function saveEmr() {
  if (!current.value) return
  savingEmr.value = true
  try {
    const resp = await client.put(`/outpatient/doctor/${current.value.registrationId}/emr`, {
      emr,
      // v44：既有三键（icdCode/icdName + 顺序定主诊断）原样保留，新增五键留空即传空
      // （后端 blank 视同 null，历史数据与旧调用方不受影响）
      diagnoses: diagList.value.map((d) => ({
        icdCode: d.icdCode, icdName: d.icdName,
        prefix: d.prefix || null, suffix: d.suffix || null,
        certainty: d.certainty || null, diagSystem: d.diagSystem || null,
        customName: d.customName || null,
      })),
    })
    if (resp.data.code !== 0) {
      ElMessage.error(resp.data.message)
      return
    }
    ElMessage.success('病历已保存')
    await loadCdssTips()
  } finally {
    savingEmr.value = false
  }
}

// ===================== v44 车道E：诊断录入 / 诊断助手 / 常用诊断 / 特殊病种 =====================

/** 同一诊断（编码相同，或都无编码而名称相同）不重复加入 */
function alreadyPicked(code: string, name: string) {
  return diagList.value.some((d) => (code ? d.icdCode === code : !d.icdCode && d.icdName === name))
}

function pushDiag(code: string, name: string, system: string) {
  if (!name || alreadyPicked(code, name)) return
  diagList.value.push({ icdCode: code, icdName: name, diagSystem: system,
    prefix: '', suffix: '', certainty: '', customName: '' })
}

function addDiagFromIcd(code: string) {
  if (!code) return
  pushDiag(code, knownIcd.get(code) ?? code, 'ICD10')
  icdPick.value = null
}

/**
 * 中医/自定义诊断加入。**本平台不预置中医诊断码表**（无权威码表授权，也不编造编码），
 * 故中医诊断只录名称，icdCode 传空串——后端 icd_code 列非空约束不变，存空串不存假码。
 */
function addDiagCustom() {
  const name = tcmName.value.trim()
  if (!name) return
  pushDiag('', name, 'TCM')
  tcmName.value = ''
}

function addDiagFrom(e: AssistEntry) {
  pushDiag(e.icdCode ?? '', e.icdName, e.diagSystem || (e.icdCode ? 'ICD10' : 'TCM'))
}

async function openAssist() {
  assistVisible.value = true
  await loadAssist()
}

async function loadAssist() {
  if (!current.value) return
  // patientId 可空：没有患者上下文时 history 段自然为空，常用与高频仍可用
  const resp = await client.get('/outpatient/doctor/diagnosis-assist', {
    params: { patientId: current.value.patientId, keyword: assistKeyword.value || undefined },
  })
  assist.value = resp.data.data
}

/** 加星到个人常用诊断（保存病历时本就会自动累加，这里是医生主动收藏） */
async function starDiag(e: { icdCode?: string | null; icdName: string; diagSystem?: string | null }) {
  await client.post('/outpatient/doctor/diagnosis-favorite', {
    icdCode: e.icdCode || null, icdName: e.icdName, diagSystem: e.diagSystem || null,
  })
  ElMessage.success('已加入常用诊断')
  if (assistVisible.value) await loadAssist()
}

async function removeFavorite(id?: number) {
  if (!id) return
  await client.delete(`/outpatient/doctor/diagnosis-favorite/${id}`)
  ElMessage.success('已移出常用诊断')
  await loadAssist()
}

async function loadSpecialDiseases() {
  // patientId 缺失（队列行对应的患者档案已被清理）时不发请求：端点 patientId 必填，发了只会 400
  if (!current.value?.patientId) { specialDiseases.value = []; return }
  const resp = await client.get('/outpatient/doctor/special-disease', {
    params: { patientId: current.value.patientId, activeOnly: true },
  })
  specialDiseases.value = resp.data.data ?? []
}

/**
 * 特殊病种「院内登记」（984）。**只登记，不报送**——向医保经办机构备案需走当地医保接口，
 * 属外部对接条件，本平台不提供，界面上也已写明，避免让院方误以为登记完就等于备案完成。
 */
async function addSpecialDisease() {
  if (!current.value) return
  savingSpecial.value = true
  try {
    const resp = await client.post('/outpatient/doctor/special-disease', {
      patientId: current.value.patientId,
      diseaseName: specialForm.diseaseName, diseaseCode: specialForm.diseaseCode,
      insuranceType: specialForm.insuranceType, startDate: specialForm.startDate,
      endDate: specialForm.endDate || null, remark: specialForm.remark,
    })
    if (resp.data.code !== 0) {
      ElMessage.error(resp.data.message)
      return
    }
    ElMessage.success('已登记（院内）')
    specialForm.diseaseName = ''
    specialForm.diseaseCode = ''
    specialForm.remark = ''
    await loadSpecialDiseases()
  } finally {
    savingSpecial.value = false
  }
}

async function removeSpecialDisease(id: number) {
  await client.delete(`/outpatient/doctor/special-disease/${id}`)
  await loadSpecialDiseases()
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
  if (!current.value) return
  if (!emrHasContent()) {
    ElMessage.warning('病历内容为空，请先书写并保存病历后再签名')
    return
  }
  try {
    await ElMessageBox.confirm(
      '签名后本次病历原文即冻结，不能再修改，只能追加补正记录。确认签名？', '病历签名',
      { type: 'warning', confirmButtonText: '确认签名', cancelButtonText: '再改改' })
  } catch {
    return   // 医生取消，不做任何事
  }
  signing.value = true
  try {
    const resp = await client.post(`/outpatient/doctor/${current.value.registrationId}/emr/sign`)
    if (resp.data.code !== 0) {
      ElMessage.error(resp.data.message)
      return
    }
    ElMessage.success('病历已签名')
    await openPatient(current.value)
    await loadWorklist()
  } finally {
    signing.value = false
  }
}

/**
 * v43 诊毕未签提示：切换患者时若上一位的病历已书写却未签名，给一条提示。
 * 本版是零写路径阻断——只提示，不拦截切换，也不改任何保存/开单逻辑。
 */
function warnIfLeavingUnsigned(next: Record<string, unknown> | null) {
  const prev = current.value
  if (!prev || emrSigned.value || !emrHasContent()) return
  if (next && (next.registrationId as number) === (prev.registrationId as number)) return
  ElMessage({
    type: 'warning',
    duration: 6000,
    message: `患者「${prev.patientName}」本次病历已书写但尚未签名，请回到该患者完成签名。`,
  })
}

// CDSS：按主诊断给出诊疗建议
async function loadCdssTips() {
  cdssTips.value = []
  // 主诊断口径不变：列表第一条即主诊断。中医/自定义诊断无 ICD 编码，CDSS 按编码检索，跳过
  const primary = diagList.value[0]?.icdCode
  if (!primary) return
  // CDSS 可按院整体关闭（模块开关返回 404）——辅助提示拿不到就不显示，
  // 不能让开关状态打断接诊主流程（v27-B：此前无 catch，关闭 cdss 后接诊必弹全局错误）
  try {
    const resp = await client.get('/cdss/suggestions', { params: { icd: primary } })
    cdssTips.value = (resp.data.data as { content: string }[]).map((s) => s.content)
  } catch {
    /* 模块未启用或临时不可用：静默降级 */
  }
}

async function searchDrugs(kw: string) {
  const resp = await client.get('/masterdata/drugs', { params: { keyword: kw } })
  drugOptions.value = resp.data.data
}

function addRxLine() {
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

function addLabLine() {
  const item = chargeItemOptions.value.find((c) => c.id === labItemId.value)
  if (!item) return
  labLines.value.push({ orderType: item.category, itemId: item.id, itemName: item.name, category: item.category, qty: 1 })
  labItemId.value = null
}

async function submitOrders(kind: 'rx' | 'lab') {
  if (!current.value) return
  const lines = kind === 'rx' ? rxLines.value : labLines.value
  submitting.value = true
  try {
    const resp = await client.post(`/outpatient/doctor/${current.value.registrationId}/orders`, { lines })
    ElMessage.success(kind === 'rx' ? '处方已开立' : '申请已提交')
    // 阻塞6：开单返回值带库存预警（stockWarnAvailable 非空即库存低于开量）
    const created = (resp.data.data ?? []) as Record<string, unknown>[]
    const warns = created.filter((o) => o.stockWarnAvailable !== null && o.stockWarnAvailable !== undefined)
    if (kind === 'rx') rxLines.value = []
    else labLines.value = []
    await openPatient(current.value)
    if (warns.length) {
      stockWarnings.value = warns.map((o) => `${o.itemName}（余 ${o.stockWarnAvailable}）`)
      tab.value = 'rx'
      ElMessage({ type: 'warning', duration: 6000,
        message: '部分药品库存不足，缴费后可能无法发药，请查看处方页提示' })
    } else {
      tab.value = 'orders'
    }
  } finally {
    submitting.value = false
  }
}

async function cancelOrder(row: Record<string, unknown>) {
  await client.put(`/outpatient/doctor/orders/${row.id}/cancel`)
  ElMessage.success('已作废')
  await openPatient(current.value)
}

onMounted(async () => {
  await loadWorklist()
  await loadRxTemplates()   // v44：进页时拉一次可见模板（后端已按登录人算好可见范围，前端不再过滤）
})
</script>

<style scoped>
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
.dim { color: var(--el-text-color-placeholder); }
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
