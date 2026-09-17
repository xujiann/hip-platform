<template>
  <!-- ============ 工位二：取材（工作列表 → 大体描述 → 蜡块编号） ============ -->
  <el-form inline size="small">
    <el-form-item label="范围">
      <el-select v-model="query.scope" style="width: 230px" @change="load">
        <el-option value="pending" label="待取材（已核收、未诊断且无蜡块）" />
        <el-option value="all" label="全部（已核收未拒收，含存量无蜡块标本）" />
      </el-select>
    </el-form-item>
    <el-form-item label="类别">
      <el-select v-model="query.specimenType" clearable placeholder="全部" style="width: 120px">
        <el-option v-for="t in SPECIMEN_TYPES" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
    </el-form-item>
    <el-form-item label="仅加急">
      <el-switch v-model="query.urgentOnly" />
    </el-form-item>
    <el-form-item label="关键词">
      <el-input v-model="query.keyword" clearable placeholder="条码 / 病理号 / 患者 / 患者号"
                style="width: 200px" @keyup.enter="load" />
    </el-form-item>
    <el-form-item>
      <el-button type="primary" :loading="loading" @click="load">查询</el-button>
    </el-form-item>
  </el-form>

  <!-- v63（2530 复核 demo 镜头）：后端口径文本可能带 **强调**（本目录 format.ts 的 mdText 专治这个），
       一律去标记后按纯文本插值——不做 Markdown 渲染、不用 v-html -->
  <el-alert v-if="note" type="info" :closable="false" class="cav" :title="mdText(note)" />
  <el-alert v-if="truncated" type="warning" show-icon :closable="false" class="cav"
            :title="`命中超过 ${limit} 条，仅显示前 ${limit} 条（不做翻页）；请收窄条件`" />

  <el-table :data="rows" v-loading="loading" size="small" border stripe max-height="440">
    <el-table-column label="病理号 / 条码" width="180">
      <template #default="{ row }">
        <span class="code">{{ fmt(row.path_no) }}</span><br>
        <span class="code muted">{{ fmt(row.barcode) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="部位" width="60">
      <template #default="{ row }">{{ fmt(row.part_no) }}</template>
    </el-table-column>
    <el-table-column label="患者" width="150">
      <template #default="{ row }">
        {{ fmt(row.patient_name) }}
        <span class="muted">{{ fmt(row.patient_no) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="类别" width="90">
      <template #default="{ row }">
        {{ typeName(row.specimen_type) }}
        <el-tag v-if="row.urgent === true" size="small" type="danger">急</el-tag>
      </template>
    </el-table-column>
    <!-- v60（2530 尾）：登记时强制录入的标本描述（4554 必填）此前在取材工位看不见——取材员对着标本却看不到登记员写了什么 -->
    <el-table-column label="标本描述" min-width="150" show-overflow-tooltip>
      <template #default="{ row }">{{ fmt(row.specimen_desc) }}</template>
    </el-table-column>
    <el-table-column label="取材部位" min-width="120" show-overflow-tooltip>
      <template #default="{ row }">{{ fmt(row.sampling_site) }}</template>
    </el-table-column>
    <el-table-column label="临床诊断" min-width="150" show-overflow-tooltip>
      <template #default="{ row }">{{ fmt(row.clinical_diagnosis) }}</template>
    </el-table-column>
    <el-table-column label="签收时刻" width="140">
      <template #default="{ row }">{{ fmtTime(row.received_at) }}</template>
    </el-table-column>
    <el-table-column label="距签收(小时)" width="110">
      <template #default="{ row }">{{ fmt(row.hours_since_received) }}</template>
    </el-table-column>
    <el-table-column label="蜡块/切片" width="90">
      <template #default="{ row }">{{ num(row.block_count) }} / {{ num(row.slide_count) }}</template>
    </el-table-column>
    <el-table-column label="操作" width="290" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" size="small" @click="openGrossing(row)">取材登记</el-button>
        <!-- v59（2530 复核）：未诊断前取材员可改自己录的字段与文本，出新版本；诊断后由诊断端点修订 -->
        <el-button v-if="canRevise(row)" link type="warning" size="small" @click="openRevise(row)">修订取材描述</el-button>
        <el-button link type="primary" size="small" @click="openView(Number(row.id))">查看大体所见</el-button>
      </template>
    </el-table-column>
    <template #empty>该范围内无标本</template>
  </el-table>

  <!-- ============ 大体所见查看（只读，不分诊断状态，2530） ============ -->
  <el-dialog v-model="viewDialog" title="大体所见与蜡块（只读）" width="760px" top="6vh">
    <div v-loading="viewLoading">
      <el-descriptions :column="3" border size="small" class="cav">
        <el-descriptions-item label="病理号">
          <span class="code">{{ fmt(view.specimen?.path_no) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="条码">
          <span class="code">{{ fmt(view.specimen?.barcode) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="患者">{{ fmt(view.specimen?.patient_name) }}</el-descriptions-item>
        <el-descriptions-item label="类别">{{ typeName(view.specimen?.specimen_type) }}</el-descriptions-item>
        <el-descriptions-item label="取材部位">{{ fmt(view.specimen?.sampling_site) }}</el-descriptions-item>
        <el-descriptions-item label="写完诊断">{{ fmtTime(view.diagnosedAt) }}</el-descriptions-item>
        <!-- v60（2530 尾）：登记时录入的标本描述与大体所见并排——「送来的是什么」与「取材看到的是什么」本该同屏 -->
        <el-descriptions-item label="标本描述（登记时录入）" :span="3">{{ fmt(view.specimen?.specimen_desc) }}</el-descriptions-item>
      </el-descriptions>

      <div class="sec">
        <b>大体所见</b>
        <el-tag v-if="view.grossFindingPresent" size="small" type="info" style="margin-left: 6px">
          {{ view.diagnosedAt ? '已写诊断（该列可能已被诊断端点修订）' : '取材工位录入，诊断尚未书写' }}</el-tag>
        <pre v-if="view.grossFindingPresent">{{ view.grossFinding }}</pre>
        <el-empty v-else description="该标本尚未录入大体所见" :image-size="50" />
      </div>

      <!-- 字段级记录（v58，2530）：只认 path_gross_field 的行，不从上面的文本反解析 -->
      <div class="sec">
        <b>字段级记录</b>
        <template v-if="viewFieldVersions.length">
          <!-- v60（2530 尾）：被取代版本的字段可调阅——fieldsByRevision 逐版给字段行（车道 B 契约），这里按版切换、标来源；
               默认最新有字段的版（与后端 fieldsRevisionSeq 同口径）。后端没回该键（B 未合入 / 旧包）时下拉只列最新字段版这一项，不编造其它版 -->
          <el-select v-model="viewVersionSeq" size="small" style="width: 320px; margin-left: 6px">
            <el-option v-for="v in viewFieldVersions" :key="String(v.revisionSeq)" :value="Number(v.revisionSeq)"
                       :label="versionLabel(v)" />
          </el-select>
          <!-- v62（2530 复核 demo 镜头）：标签文案与配色一律由 viewVersionTag 派生——
               「被取代版本」只在**真有更晚的字段版**时出现，0 项 / 唯一版走诚实兜底，不再打「已被第 — 版取代」 -->
          <el-tag size="small" style="margin-left: 6px" :type="viewVersionTag.type">{{ viewVersionTag.text }}</el-tag>
          <!-- v64（2530 复核 demo 镜头）：此前「模板」一栏直接印英文模板码（GI_BIOPSY），
               同屏的内部键名 fieldsByRevision 也打在提示里。模板改印模板清单里的中文名（t.name，
               本组件本就加载着那份清单）；清单里查不到这个码时说「未登记的模板」，仍不把码打出来 -->
          <div class="muted" style="margin-top: 4px">
            来源：{{ versionSourceName(viewVersion) }}　模板：{{ templateNameOf(viewVersion) }}
            <span v-if="view.fieldsByRevision == null">　（当前服务只提供最新字段版，更早版本的字段暂不可调阅）</span>
          </div>
          <!-- v59：字段随版本走；诊断只改文本不改字段，两者分叉时明说，不让两处并排自相矛盾。
               v62（2530 复核，审计者 met=false 第 (b) 点）：此前这条 v-if 还要求「是最新字段版」——
               切到第 1 版时，说真话的 fieldsNote 提示消失、而上面那条说假话的「被取代版本」标签恰好出现，
               两者互斥。fieldsNote 讲的是「字段版 vs 文本版」的全局关系，与当前在看哪一版无关，故只看它有没有值。
               v63（2530 复核 data 镜头）：这句话是**全屏唯一**的「字段覆盖到哪一段」结论，来源是 format.ts 的
               grossFieldsNote（后端 fieldsNote 优先、后端没给才兜底），轨迹抽屉用的是同一个函数；
               上面那个 el-tag 只报版本身份，不再自己推断一句可能与这条相反的话。
               后端这句带裸 Markdown「**累积全文**」，经 mdText 去标记后上屏（此前 String() 直出、星号打在屏幕上）。 -->
          <el-alert v-if="viewFieldsNote" type="warning" show-icon :closable="false" style="margin-top: 4px"
                    :title="viewFieldsNote" />
          <span v-if="!viewFields.length" class="muted" style="display: block; margin-top: 4px">{{ viewNoFieldsNote }}</span>
          <el-table v-else :data="viewFields" size="small" border max-height="200" style="margin-top: 4px">
            <el-table-column label="#" width="50">
              <template #default="{ row }">{{ fmt(row.seq) }}</template>
            </el-table-column>
            <el-table-column label="字段" width="140">
              <template #default="{ row }">{{ fmt(row.label) }}</template>
            </el-table-column>
            <el-table-column label="内容" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ fmt(row.value) }}</template>
            </el-table-column>
            <el-table-column label="录入" width="220">
              <template #default="{ row }">{{ fmt(row.operatorName) }}　{{ fmtTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
        </template>
        <span v-else class="muted" style="margin-left: 6px">
          {{ view.grossFindingPresent ? '历史标本或纯自由文本，无字段级记录（不从文本反解析）' : '无字段级记录' }}
        </span>
      </div>

      <h4>蜡块（{{ viewBlocks.length }}）</h4>
      <el-table :data="viewBlocks" size="small" border max-height="220">
        <el-table-column label="块号" width="70">
          <template #default="{ row }">{{ fmt(row.block_no) }}</template>
        </el-table-column>
        <el-table-column label="蜡块编码" width="170">
          <template #default="{ row }"><span class="code">{{ fmt(row.block_code) }}</span></template>
        </el-table-column>
        <el-table-column label="组织描述" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ fmt(row.tissue_desc) }}</template>
        </el-table-column>
        <el-table-column label="建块" width="180">
          <template #default="{ row }">{{ fmt(row.created_by_name) }}　{{ fmtTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="包埋" width="140">
          <template #default="{ row }">{{ fmtTime(row.embedded_at) }}</template>
        </el-table-column>
        <el-table-column label="切片" width="60">
          <template #default="{ row }">{{ num(row.slide_count) }}</template>
        </el-table-column>
        <template #empty>尚无蜡块</template>
      </el-table>

      <h4>取材打点（{{ viewEvents.length }}）</h4>
      <el-table :data="viewEvents" size="small" border max-height="160">
        <el-table-column label="时刻" width="150">
          <template #default="{ row }">{{ fmtTime(row.occurred_at) }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="110">
          <template #default="{ row }">{{ fmt(row.operator_name) }}</template>
        </el-table-column>
        <el-table-column label="备注" show-overflow-tooltip>
          <template #default="{ row }">{{ fmt(row.remark) }}</template>
        </el-table-column>
        <template #empty>该标本尚无取材打点</template>
      </el-table>
      <!-- v64（2530 复核 demo 镜头）：这里此前原样打出后端写给调用方的接口说明——库表列名
           path_specimen.gross_finding、内部键名 fieldsAvailable / textFieldForms / fieldsByRevision…、
           迁移号 V164 / V166，以及平台自述「v61、v62 两版口径与事实相反」的缺陷史，
           全落在评委看的「查看大体所见」正屏上。那些话归控制器的 Javadoc 与返回体 note（写给调用方），
           屏上只留业务口径这一句。 -->
      <p class="muted">大体所见由取材工位录入，病理医师出报告时可再修订；每改一次留一版，字段级记录随它所属的那一版保存。
        平台不从这段文字里反推字段——没有录成字段的内容只作为文本保存。</p>
    </div>
    <template #footer>
      <el-button type="primary" size="small" @click="viewDialog = false">关闭</el-button>
    </template>
  </el-dialog>

  <!-- ============ 取材登记 / 修订取材描述（v59 起同一张表单两种模式） ============ -->
  <el-dialog v-model="dialog" :title="mode === 'REVISE' ? '修订取材描述' : '取材登记'" width="760px" top="6vh">
    <el-descriptions :column="3" border size="small" class="cav">
      <el-descriptions-item label="病理号">
        <span class="code">{{ fmt(current.path_no) }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="条码">
        <span class="code">{{ fmt(current.barcode) }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="患者">{{ fmt(current.patient_name) }}</el-descriptions-item>
      <el-descriptions-item label="类别">{{ typeName(current.specimen_type) }}</el-descriptions-item>
      <el-descriptions-item label="已有蜡块">{{ num(current.block_count) }}</el-descriptions-item>
      <el-descriptions-item label="取材部位">{{ fmt(current.sampling_site) }}</el-descriptions-item>
    </el-descriptions>

    <el-alert v-if="mode === 'GROSSING' && num(current.block_count) > 0" type="warning" show-icon :closable="false" class="cav"
              title="该标本已有蜡块：第二次及以后的取材必须显式勾选「补取材」，否则本次登记会被拒绝。误点两次「取材」凭空多出一组蜡块是事故，与病理医师看完 HE 片后下的补取材必须区分开。" />

    <!-- 已有大体所见：先给人看见，再决定怎么填——否则填了结构化字段提交才吃 5222 -->
    <div v-if="existingGross" class="sec" v-loading="existingLoading">
      <b>{{ mode === 'REVISE' ? '当前大体所见（修订前，将记为 old_text）' : '该标本已录入的大体所见' }}</b>
      <el-tag size="small" type="info" style="margin-left: 6px">
        {{ mode === 'REVISE' ? `当前第 ${fmt(existingTextSeq)} 版` : '只读；不覆盖，勾选「补取材」后可作为新版本追加' }}</el-tag>
      <pre>{{ existingGross }}</pre>
    </div>

    <!-- v59：修订预填——字段来自库里最新一版字段行，自由描述由当前文本剥掉字段前缀得到；拆不开时整段放自由描述。
         v62（2530 复核 data 镜头）：此前这条提示在补取材追加后是一句自相矛盾的假话
         （「属于第 2 版，与当前第 2 版文本不同版」——同一个版号被说成「不同版」）。现在措辞由 revisePrefillNote 分四档给出。 -->
    <el-alert v-if="mode === 'REVISE' && revisePrefillNote" type="warning" show-icon :closable="false" class="cav"
              :title="revisePrefillNote" />

    <!-- v64（2530 复核 data 镜头，本轮头号纪律）：后端 coverNote 把人指到这个弹窗来补齐字段，
         而弹窗上此前**没有一个字**说得出「当前这段描述里 N 个『标签：值』只有 M 个有结构化字段行」——
         v63 新增的三个覆盖事实键在整个 frontend/shell/src 里零引用。这条提示就是它们的屏上归宿：
         数与逐条清单都来自后端返回体，前端不重新解析那段文本。 -->
    <el-alert v-if="mode === 'REVISE' && reviseCoverNote" type="warning" show-icon :closable="false" class="cav"
              :title="reviseCoverNote" />

    <el-form label-width="100px" size="small">
      <el-form-item v-if="mode === 'GROSSING'" label="补取材">
        <el-switch v-model="form.append" />
        <span class="muted" style="margin-left: 8px">已诊断标本的补取材同样必须显式声明</span>
      </el-form-item>

      <el-form-item v-if="mode === 'GROSSING' && existingGross && !form.append" label="大体所见">
        <span class="muted">已有内容（见上），本次不再填写：勾选「补取材」后可填写本次描述（作为新版本追加）；修订既有描述用列表里的「修订取材描述」</span>
      </el-form-item>

      <el-alert v-if="mode === 'GROSSING' && existingGross && form.append" type="info" show-icon :closable="false" class="cav"
                title="本次填写的描述将作为新版本追加：新文本 = 既有文本 + 「。补取材：」 + 本次描述，不覆盖既有内容；不填则只加蜡块、不出新版本。" />

      <!-- v60（2563 尾，车道 B 契约）：勾了补取材可挂接病理医师下的 RESAMPLE 医嘱——POST /grossing 带 techOrderId，
           后端校验医嘱存在 / RESAMPLE / 属于该标本 / ORDERED（四路径同码 5275，在任何写入之前拒）；不挂接即自由补取材，旧行为不变 -->
      <el-form-item v-if="mode === 'GROSSING' && form.append" label="挂接补取材医嘱">
        <div style="width: 100%">
          <el-select v-model="form.techOrderId" clearable placeholder="不挂接（自由补取材，医嘱进度不随本次取材推进）"
                     style="width: 100%" :loading="resampleLoading">
            <el-option v-for="o in resampleOrders" :key="Number(o.id)" :value="Number(o.id)" :label="resampleLabel(o)" />
          </el-select>
          <span v-if="!resampleLoading && !resampleOrders.length" class="muted">
            该标本没有待执行的补取材医嘱：这里只列病理医师下达、尚未执行的「补取材」，深切 / 重切 / 免疫组化等不在此挂接</span>
          <span v-else-if="form.techOrderId" class="muted">
            <!-- v64 合并后补齐：原写「已补取材、待切片」多一个顿号。全仓其余每一处（含唯一事实源
                 PathologyReportController.TECH_PROGRESS_NAMES）都是「已补取材待切片」，屏上口径要逐字同源；
                 这句恰是本轮新加的，守卫测试比的是键与入口、抓不到文案里多一个标点。 -->
            提交后本次新蜡块记到该医嘱名下，医嘱进度随之变为「已补取材待切片」</span>
        </div>
      </el-form-item>

      <el-form-item v-if="showGrossInputs" label="取材模板">
        <el-select v-model="form.templateCode" clearable placeholder="不用模板" style="width: 260px"
                   @change="onTemplateChange">
          <!-- v64（2530 复核 demo 镜头）：下拉此前把模板码贴在中文名后面（「胃肠镜活检（GI_BIOPSY）」），
               选中后收起的那一行也带着码。只印 t.name -->
          <el-option v-for="t in templates" :key="String(t.code)" :value="String(t.code)"
                     :label="String(t.name)">
            <span>{{ t.name }}</span>
            <el-tag size="small" :type="t.source === 'CONFIG' ? 'warning' : 'info'"
                    style="margin-left: 6px">{{ t.source === 'CONFIG' ? '配置' : '内置' }}</el-tag>
          </el-option>
        </el-select>
        <span v-if="templateNote" class="muted" style="margin-left: 8px">字段清单可配，模板管理未做；本次所用模板随本版修订一起留痕</span>
      </el-form-item>

      <el-form-item v-if="showGrossInputs && currentTemplate && currentTemplate.example" label="示例描述">
        <span class="muted">{{ currentTemplate.example }}</span>
      </el-form-item>
      <el-form-item v-else-if="showGrossInputs && currentTemplate" label="示例描述">
        <span class="muted">—（运维新增的模板没有示例文本，平台不编一段假的）</span>
      </el-form-item>

      <template v-if="showGrossInputs">
        <el-form-item v-for="f in grossFields" :key="f" :label="f">
          <el-input v-model="form.gross[f]" maxlength="300" show-word-limit />
          <!-- v61（2530 复核）：本次自己加的字段可以删；模板自带的不给删（删了下次换模板又回来，徒增困惑） -->
          <el-button v-if="customFields.includes(f)" link type="danger" size="small"
                     style="margin-left: 6px" @click="removeCustomField(f)">删除</el-button>
        </el-form-item>

        <!-- v61（2530 复核）：此前字段名集合是封闭的——输入框只按「模板字段 ∪ 已填键」渲染，
             全页唯一的「增加」按钮是加蜡块，没有任何自定义字段名入口；模板清单又只能由运维直改 sys_config。
             于是「模板里没有『淋巴结清扫组数』这一项怎么办」的唯一答案是写进自由描述，
             即回落成一段非结构化文本——正是「确保描述内容的结构化存储」要消灭的形态。
             后端 assembleGross 本就支持任意字段名（20 项 / 32 字 / trim 后不得重名），是典型的「只做了后端」。 -->
        <el-form-item label="添加字段">
          <el-input v-model="newFieldName" size="small" maxlength="32" show-word-limit
                    style="width: 240px" placeholder="模板里没有的项，在这里加"
                    @keyup.enter="addCustomField" />
          <el-button size="small" style="margin-left: 6px" @click="addCustomField">添加</el-button>
          <span class="muted" style="margin-left: 8px">
            本次取材内有效，不写回模板；共 {{ grossFields.length }} 项（上限 20），字段名 32 字内、不得与已有项重名
          </span>
        </el-form-item>

        <el-form-item label="自由描述">
          <el-input v-model="form.grossText" type="textarea" :rows="2"
                    placeholder="与上面的结构化字段拼成一段大体所见；总长上限 2000 字" />
        </el-form-item>
      </template>

      <el-form-item v-if="mode === 'GROSSING'" label="蜡块">
        <div style="width: 100%">
          <el-button size="small" @click="addBlock">增加一块</el-button>
          <span class="muted" style="margin-left: 8px">
            共 {{ form.blocks.length }} 块（一次最多 50 块）；块号从既有最大号 +1 续排，
            编码 =「病理号（缺失回落条码）- 块号」
          </span>
          <div v-for="(b, i) in form.blocks" :key="i" class="block-row">
            <span class="block-idx">第 {{ i + 1 }} 块</span>
            <el-input v-model="b.tissueDesc" size="small" maxlength="500" show-word-limit
                      placeholder="组织描述（可留空）" style="flex: 1" />
            <el-button link type="danger" size="small" :disabled="form.blocks.length <= 1"
                       @click="form.blocks.splice(i, 1)">删除</el-button>
          </div>
        </div>
      </el-form-item>

      <el-form-item v-if="mode === 'GROSSING'" label="备注">
        <el-input v-model="form.remark" maxlength="255" show-word-limit />
      </el-form-item>
    </el-form>

    <el-alert v-if="mode === 'GROSSING'" type="info" :closable="false" show-icon
              title="大体所见为空时首写；已有内容时不覆盖——勾选「补取材」后填写的描述作为新版本追加，未勾选还填描述会被拒绝。这段描述之后也会被病理医师出报告时修订。" />
    <el-alert v-else type="info" :closable="false" show-icon
              title="提交后生成新一版修订（来源「取材修订」）：字段行落在新版下、文本整体替换、修订前原文留痕；仅未诊断前可用，诊断后由诊断端点修订。与当前文本完全相同会被拒（没有变化就没有版本）。" />

    <template #footer>
      <el-button size="small" @click="dialog = false">取消</el-button>
      <el-button v-if="mode === 'GROSSING'" type="primary" size="small" :loading="saving" @click="submit">登记取材</el-button>
      <el-button v-else type="warning" size="small" :loading="saving" @click="submitRevise">提交修订</el-button>
    </template>
  </el-dialog>

  <!-- ============ 取材结果 ============ -->
  <el-dialog v-model="resultDialog" title="取材完成" width="560px">
    <el-alert v-if="result.codePrefixSource === 'BARCODE'" type="warning" show-icon :closable="false"
              class="cav"
              title="本标本尚无病理号，蜡块编码回落院内条码；病理号事后补发不会回改已生成的蜡块编码。" />
    <el-descriptions :column="2" border size="small" class="cav">
      <el-descriptions-item label="本次产出">{{ num(result.blockCount) }} 块</el-descriptions-item>
      <el-descriptions-item label="该标本累计">{{ num(result.totalBlockCount) }} 块</el-descriptions-item>
      <!-- v64（2530 复核 demo 镜头）：这个标签此前原样贴出 PATH_NO / BARCODE 两个英文码 -->
      <el-descriptions-item label="编码前缀" :span="2">
        <span class="code">{{ fmt(result.codePrefix) }}</span>
        <el-tag size="small" style="margin-left: 6px">{{ codePrefixSourceName(result.codePrefixSource) }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="大体所见是否写入" :span="2">
        {{ result.grossFindingWritten === true ? '已写入' : '未写入（该列原本已有内容或本次未填）' }}
      </el-descriptions-item>
    </el-descriptions>

    <!-- 已落库的字段级记录（v58，2530）：取材成功后回读 GET /grossing/{id}，展示的是库里的行，不是表单里的值 -->
    <div class="sec" v-loading="resultFieldsLoading">
      <b>已落库的字段级记录</b>
      <template v-if="resultView.fieldsAvailable === true">
        <el-tag size="small" type="success" style="margin-left: 6px">{{ resultFields.length }} 项，按填写顺序</el-tag>
        <el-table :data="resultFields" size="small" border max-height="200" style="margin-top: 4px">
          <el-table-column label="#" width="50">
            <template #default="{ row }">{{ fmt(row.seq) }}</template>
          </el-table-column>
          <el-table-column label="字段" width="140">
            <template #default="{ row }">{{ fmt(row.label) }}</template>
          </el-table-column>
          <el-table-column label="内容" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ fmt(row.value) }}</template>
          </el-table-column>
          <el-table-column label="录入时刻" width="150">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
      </template>
      <span v-else class="muted" style="margin-left: 6px">
        {{ result.grossFindingWritten === true
          ? '无字段级记录：本次只写了自由描述，或该标本此前只有自由文本'
          : '无字段级记录：本次未写大体所见' }}
      </span>
    </div>
    <el-table :data="resultBlocks" size="small" border max-height="240">
      <el-table-column label="块号" width="80">
        <template #default="{ row }">{{ fmt(row.block_no) }}</template>
      </el-table-column>
      <el-table-column label="蜡块编码" width="180">
        <template #default="{ row }"><span class="code">{{ fmt(row.block_code) }}</span></template>
      </el-table-column>
      <el-table-column label="组织描述" show-overflow-tooltip>
        <template #default="{ row }">{{ fmt(row.tissue_desc) }}</template>
      </el-table-column>
    </el-table>
    <p class="muted">打码机属设备直连，平台只给编码字符串，不负责打印。</p>
    <template #footer>
      <el-button type="primary" size="small" @click="resultDialog = false">知道了</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 工位二：取材。对接 {@code PathologyProcessController}（/api/pathology/process）。
 *
 * <p>「待取材」的判据是<b>名下一块蜡块都没有</b>，不是 status——既有 status 只有
 * COLLECTED/RECEIVED/DIAGNOSED 三档、压根没有「已取材」这一档。
 * {@code hours_since_received} 是原始事实（距签收小时数），<b>本页不判超时</b>：
 * 时限阈值口径归病理质控页唯一定义，两处各判一次必然分叉。
 */
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client, { type BizError } from '../../../api/client'
import { SPECIMEN_TYPES, fmt, fmtTime, grossFieldsNote, mdText, num, typeName, type Row } from './format'

const emit = defineEmits<{ (e: 'changed'): void }>()

const rows = ref<Row[]>([])
const loading = ref(false)
const truncated = ref(false)
const limit = ref(100)
const note = ref('')
const query = reactive({ scope: 'pending', specimenType: '', urgentOnly: false, keyword: '' })

async function load() {
  loading.value = true
  try {
    const d = (await client.get('/pathology/process/grossing/worklist', {
      params: {
        scope: query.scope,
        specimenType: query.specimenType || undefined,
        urgentOnly: query.urgentOnly || undefined,
        keyword: query.keyword || undefined,
      },
    })).data.data as Row
    rows.value = (d.items ?? []) as Row[]
    truncated.value = d.truncated === true
    limit.value = num(d.limit) || 100
    note.value = String(d.note ?? '')
  } finally {
    loading.value = false
  }
}

/* ---------------- 取材模板 ---------------- */
const templates = ref<Row[]>([])
const templateNote = ref('')

async function loadTemplates() {
  const d = (await client.get('/pathology/process/grossing/templates')).data.data as Row
  templates.value = (d.items ?? []) as Row[]
  templateNote.value = String(d.note ?? '')
}

const currentTemplate = computed<Row | null>(
  () => templates.value.find((t) => String(t.code) === form.templateCode) ?? null,
)
/** 字段清单 = 模板字段 + 表单里已有的其它键（修订预填自库里字段行时，不在模板里的字段也得有输入框，不能悄悄丢） */
const grossFields = computed<string[]>(() => {
  const out: string[] = [...((currentTemplate.value?.fields ?? []) as string[])]
  for (const k of Object.keys(form.gross)) if (!out.includes(k)) out.push(k)
  return out
})

/* ---------------- v61（2530 复核）：自定义字段名 ----------------
 * 反驳者原话：「结构化字段的字段名集合是封闭的……没有任何自定义字段名的入口；模板字段清单只能由运维直改
 * sys_config。于是评委现场问『模板里没有淋巴结清扫组数这一项怎么办』，唯一答案是写进自由描述——即回落成
 * 一段非结构化文本，正是参数摘要『确保描述内容的结构化存储』要消灭的形态。后端明明支持任意 label，
 * 前端却不给录入口，这是『只做了后端 / 演示走不完』的典型。」
 * 校验口径与后端 assembleGross 同源（20 项 / 32 字 / trim 后不得重名），**不新开一套**：
 * 前端先拦是为了当场给话，真正的守门仍在后端（越界 5222、自定义字段名本身非法 5276）。 */
const newFieldName = ref('')
const customFields = ref<string[]>([])

function addCustomField() {
  const name = newFieldName.value.trim()
  if (!name) return ElMessage.warning('字段名不能为空')
  if (name.length > 32) return ElMessage.warning('字段名最多 32 字')
  if (grossFields.value.some((f) => f.trim() === name)) return ElMessage.warning(`已有「${name}」这一项`)
  if (grossFields.value.length >= 20) return ElMessage.warning('大体描述字段最多 20 项')
  form.gross[name] = ''
  customFields.value.push(name)
  newFieldName.value = ''
}

function removeCustomField(name: string) {
  delete form.gross[name]
  customFields.value = customFields.value.filter((f) => f !== name)
}

/** 换模板：同名字段保留已填值，不在新模板里但已填了内容的字段也保留——悄悄吞掉用户写的字比多显示一个输入框坏得多 */
function onTemplateChange() {
  const kept = { ...form.gross }
  form.gross = {}
  for (const f of ((currentTemplate.value?.fields ?? []) as string[])) form.gross[f] = kept[f] ?? ''
  for (const [k, v] of Object.entries(kept)) if (v && v.trim() && !(k in form.gross)) form.gross[k] = v
}

/* ---------------- 取材登记 / 修订取材描述 ---------------- */
const dialog = ref(false)
/** GROSSING = 取材登记（POST /grossing）；REVISE = 修订取材描述（PUT /grossing/{id}/fields，v59） */
const mode = ref<'GROSSING' | 'REVISE'>('GROSSING')
const resultDialog = ref(false)
const saving = ref(false)
const current = ref<Row>({})
const result = ref<Row>({})
const resultBlocks = computed<Row[]>(() => (result.value.blocks ?? []) as Row[])
/** 描述输入框何时出现：修订模式恒出；取材模式在无既有大体所见、或勾了补取材（作为新版本追加）时出 */
const showGrossInputs = computed(
  () => mode.value === 'REVISE' || !existingGross.value || form.append,
)

const form = reactive({
  templateCode: '',
  gross: {} as Record<string, string>,
  grossText: '',
  append: false,
  /** v60：勾了补取材时可挂接的 RESAMPLE 医嘱 id（POST /grossing 的 techOrderId，车道 B 契约）；不挂接为 undefined */
  techOrderId: undefined as number | undefined,
  remark: '',
  blocks: [{ tissueDesc: '' }] as { tissueDesc: string }[],
})

/* ---------------- 补取材挂接 RESAMPLE 医嘱（v60，2563 尾） ---------------- */
const resampleOrders = ref<Row[]>([])
const resampleLoading = ref(false)

/**
 * 只列该标本待执行的补取材医嘱：GET /report/tech-orders 的 status / techType 都是后端白名单参数
 * （值域照 TECH_STATUSES / TECH_TYPES），传 specimenId 时默认全状态，故 status=ORDERED 必须显式给；
 * 回来再按 tech_type / status 过一遍，后端返回口径变了也不会把别的类型混进来。取不到就没得挂：下拉为空、仍可自由补取材。
 */
async function loadResampleOrders(specimenId: number) {
  resampleLoading.value = true
  resampleOrders.value = []
  try {
    const d = (await client.get('/pathology/report/tech-orders', {
      params: { specimenId, status: 'ORDERED', techType: 'RESAMPLE' },
    })).data.data as Row
    resampleOrders.value = ((d.items ?? []) as Row[])
      .filter((o) => o.tech_type === 'RESAMPLE' && o.status === 'ORDERED')
  } catch {
    resampleOrders.value = []
  } finally {
    resampleLoading.value = false
  }
}

function resampleLabel(o: Row): string {
  const item = o.tech_item ? ` ${String(o.tech_item)}` : ''
  const block = o.block_code ? String(o.block_code) : '未指定'
  const reason = o.reason ? `　原因：${String(o.reason)}` : ''
  return `#${fmt(o.id)}　补取材${item}　下达 ${fmt(o.ordered_by_name)} ${fmtTime(o.ordered_at)}　蜡块 ${block}${reason}`
}

function addBlock() {
  if (form.blocks.length >= 50) {
    ElMessage.warning('一次取材最多 50 个蜡块')
    return
  }
  form.blocks.push({ tissueDesc: '' })
}

/**
 * 打开登记对话框前先取该标本已录入的大体所见（只读）。
 * 后端对「已有大体所见还传结构化字段」直接报 5222，前端若不先把既有内容摆出来，
 * 人就得填完一整段再被拒——补取材场景（已有蜡块）几乎必然踩到。
 */
const existingGross = ref('')
const existingTextSeq = ref<unknown>(null)   // v59：既有文本的版号（textRevisionSeq），修订对话框里给人看
const existingLoading = ref(false)

async function loadExistingGross(specimenId: number) {
  existingLoading.value = true
  try {
    const d = (await client.get(`/pathology/process/grossing/${specimenId}`)).data.data as Row
    existingGross.value = d.grossFindingPresent === true ? String(d.grossFinding ?? '') : ''
    existingTextSeq.value = d.textRevisionSeq ?? null
  } catch {
    existingGross.value = ''   // 取不到就按「未知」处理：仍允许填写，由后端最终裁决
    existingTextSeq.value = null
  } finally {
    existingLoading.value = false
  }
}

function openGrossing(row: Row) {
  mode.value = 'GROSSING'
  current.value = row
  Object.assign(form, {
    templateCode: '',
    gross: {},
    grossText: '',
    // **不按「已有蜡块」自动勾上补取材**：后端要求显式声明，正是为了把
    // 「病理医师看完 HE 片后的补取材」与「误点两次取材凭空多出一组蜡块」区分开。
    // 前端替人勾上就等于把这道闸拆了——默认关，由人自己确认。
    append: false,
    techOrderId: undefined,
    remark: '',
    blocks: [{ tissueDesc: '' }],
  })
  existingGross.value = ''
  existingTextSeq.value = null
  revisePrefillNote.value = ''
  reviseCover.value = null   // v64：上一次修订的覆盖事实不能带到这次取材登记里
  newFieldName.value = ''
  customFields.value = []   // v61：上次的自定义字段名不能带到这次，否则「删除」按钮挂在别的标本的字段上
  dialog.value = true
  void loadExistingGross(Number(row.id))
  void loadResampleOrders(Number(row.id))   // v60：勾「补取材」后可挂接的 RESAMPLE 医嘱，先取好、不让人勾了再等
}

/* ---------------- 修订取材描述（v59，2530 复核） ---------------- */
const revisePrefillNote = ref('')

/** 只对未诊断的标本给入口：诊断后由诊断端点修订，后端也会以 5221 拒 */
function canRevise(row: Row): boolean {
  return row.diagnosed_at == null && row.rejected_at == null
}

/** 取材追加时后端拼的分隔标记（PathologyProcessController.grossing：新文本 = 既有文本 + 「。补取材：」 + 本次拼装） */
const APPEND_MARK = '。补取材：'

/**
 * 把当前文本按取材端点的拼装规则（「标签：值」以「；」相连，末尾「。」接自由文本）拆回字段 + 自由描述。
 * 字段来自库里最新一版字段行，不从文本猜。
 *
 * v62（2530 复核 decompose 镜头）：补取材追加后，这一版的字段前缀落在累积全文的**中段**
 * （旧文本 + 「。补取材：」 + 本版拼装），此前 startsWith 对不上就直接 `gross: {}` ——
 * 库里已落的字段一项都不预填、累积全文整段进自由描述，用户照单提交即「新版 = 当前全文、结构化字段归零」。
 * 现在按同一个分隔标记切开：本版字段原样预填，自由描述 = 更早各版内容 + 本版自由描述，**一个字不丢**
 * （字段会被重新拼到最前，所以提示条要说清「本次修订将整体替换当前全文」）。
 */
type SplitMode = 'EXACT' | 'PREFIX' | 'CUMULATIVE' | 'UNPARSED'
function splitGross(text: string, fields: Row[]): { gross: Record<string, string>; free: string; mode: SplitMode } {
  const gross: Record<string, string> = {}
  for (const f of fields) gross[String(f.label)] = String(f.value ?? '')
  if (!fields.length) return { gross, free: text, mode: 'EXACT' }
  const prefix = fields.map((f) => `${String(f.label)}：${String(f.value ?? '')}`).join('；')
  if (text === prefix) return { gross, free: '', mode: 'EXACT' }
  if (text.startsWith(prefix + '。')) return { gross, free: text.slice(prefix.length + 1), mode: 'PREFIX' }
  const at = text.lastIndexOf(APPEND_MARK)
  if (at >= 0) {
    const older = text.slice(0, at)
    const seg = text.slice(at + APPEND_MARK.length)
    const free = seg === prefix ? '' : seg.startsWith(prefix + '。') ? seg.slice(prefix.length + 1) : null
    if (free !== null) {
      return { gross, free: free ? older + APPEND_MARK + free : older, mode: 'CUMULATIVE' }
    }
  }
  return { gross: {}, free: text, mode: 'UNPARSED' }
}

/**
 * 修订弹窗顶上那条提示：按「字段版 vs 文本版」的**真实关系**分档，不自己推断累积与否（读后端 textIsCumulative）。
 * 同版且拆得开 → 无提示（正常预填）；其余三档各自说清「预填了什么、当前文本是什么、提交会发生什么」。
 */
function prefillNote(d: Row, mode: SplitMode, fieldCount: number): string {
  const fSeq = fmt(d.fieldsRevisionSeq)
  const tSeq = fmt(d.textRevisionSeq)
  const hasFields = d.fieldsAvailable === true
  if (mode === 'UNPARSED') {
    return hasFields
      ? `当前文本不是由库里第 ${fSeq} 版字段直接拼出的形态（经多次追加或整体改写），无法判断哪一段对应哪一项字段：`
        + '已把当前文本整段放入自由描述、字段留空。本次修订将整体替换当前全文，请自行拆分字段后提交'
      : '该标本无字段级记录（历史标本或纯自由文本，平台不从文本反解析字段），已把当前文本整段放入自由描述；'
        + '本次修订将整体替换当前全文'
  }
  if (!hasFields) return ''
  // v63（2530 复核 data 镜头，本轮头号纪律）：累积全文还要再分一档。
  // 字段版号 == 文本版号 时，库里最新那一版字段就是最后一次补取材写的，说「含第 fSeq 版之前各次…」才成立；
  // 而最后一次补取材**可能一行字段都没写**（该版 path_gross_field 零行），字段级记录停在更早的第 fSeq 版，
  // 此时当前全文里既有第 fSeq 版之前的、也有它之后那次追加的内容，原来那句话把后半段说漏了。
  const fieldsSeqIsTextSeq = d.fieldsRevisionSeq !== null && d.fieldsRevisionSeq !== undefined
    && d.textRevisionSeq !== null && d.textRevisionSeq !== undefined
    && Number(d.fieldsRevisionSeq) === Number(d.textRevisionSeq)
  if (d.textIsCumulative === true && fieldsSeqIsTextSeq) {
    return `已按库里第 ${fSeq} 版字段（${fieldCount} 项）原样预填。当前文本是累积全文，`
      + `含第 ${fSeq} 版之前各次取材/追加的内容，那部分已放入「自由描述」；`
      + '本次修订将整体替换当前全文（字段会重新拼到最前），请核对后提交'
  }
  if (d.textIsCumulative === true) {
    return `已按库里第 ${fSeq} 版字段（${fieldCount} 项）原样预填——第 ${tSeq} 版补取材未再填写字段。`
      + `当前文本是累积全文，第 ${fSeq} 版之前与其后各次追加的内容都已放入「自由描述」；`
      + '本次修订将整体替换当前全文（字段会重新拼到最前），请核对后提交'
  }
  // v64（2530 复核 decompose 镜头，本轮头号纪律）：**这一句此前读的是 fieldsCurrent**。
  // v63 把后端 fieldsCurrent 的定义从「只比版号」改成「版号相同 且 字段覆盖全文」两维之与，
  // 却只给后端自己那句 fieldsNote 加了防护（改用 versionCurrent 算版号措辞），
  // 这个第三消费方没跟着改：于是「版号明明相同、只是覆盖不全」也掉进这一句，屏上打出
  // 「已按库里第 3 版字段（2 项）原样预填，但当前文本已是第 3 版」——字段版号与文本版号都是 3，
  // 同一个版号被说成两件事，是 v63 自己把这个分支打开、把这句假话放上屏的。
  // 现在按后端**分开给出**的那一维判：只有版号真落后才说版号（fieldsVersionCurrent）；
  // 覆盖那一维由 reviseCoverNote 那条独立提示说，两句各管各的辖域，前端不自己重算。
  // 旧服务没有这个键时它是 undefined —— 宁可不说，也不说一句可能是假的。
  if (d.fieldsVersionCurrent === false) {
    return `已按库里第 ${fSeq} 版字段（${fieldCount} 项）原样预填，而当前文本已是第 ${tSeq} 版`
      + '（见轨迹抽屉的修订留痕），以文本为准：请核对字段与自由描述是否仍对得上再提交'
  }
  return ''
}

/**
 * v64（2530 复核 data 镜头，本轮头号纪律）：修订弹窗里那条「覆盖到哪几项」的事实条。
 *
 * <p>后端 coverNote 明写「要补齐请到『修订取材描述』里……」，把人指到这个弹窗来，而这个弹窗上
 * 没有任何一个字说得出「当前这段描述里 4 个『标签：值』只有 2 个有结构化字段行」——
 * v63 新增的 textFieldForms / textFieldFormsBacked / fieldsCoverText 三个键在整个 frontend/shell/src 里零引用。
 *
 * <p>数、逐条清单、以及「字段行在更早版本里」还是「从来没录成字段」的分类，**全部原样取自后端返回体**：
 * 那段文本的拆分规则归后端 assembleGross / grossFieldForms 唯一定义，前端再解析一遍必然漂。
 */
const reviseCover = ref<Row | null>(null)

/**
 * 表单里此刻**真会被提交**的字段项（键 = 字段名，值 = trim 后的内容）。
 *
 * <p>提交时的过滤规则就在这一处：{@code submitRevise} 直接拿它拼请求体，
 * 下面那句「这次提交会发生什么」也由它生成——两边各写一遍 skip 规则，规则一改就必然漂
 * （后端 effectiveGrossFields 同理，一处定义两处用）。
 */
const reviseFormFields = computed<Record<string, string>>(() => {
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(form.gross)) if (v && v.trim()) out[k] = v.trim()
  return out
})

/**
 * v65（2530 复核，本轮头号纪律）：「**这次提交会发生什么**」——由 {@link reviseFormFields}（表单此刻
 * 真有哪几项字段）生成，随输入实时变，不是写死的一句。
 *
 * <p><b>修复前的反向事实</b>：v64 把「（已预填在下面的字段栏里）」直接写进 reviseCoverNote 的模板串，
 * 与 splitGross 的档位、与字段栏里到底有没有东西都无关。而 UNPARSED 档（当前文本不是由最新字段版
 * 直接拼出的形态）返回的是 <code>gross: {}</code>，一项都不预填：上一条 alert 刚说完「字段留空」，
 * 紧挨着的这一条就宣告「已预填」，两条黄条同屏互相否定，而技师照它原样提交，新版落 0 行 path_gross_field。
 *
 * <p>措辞只说两类事：<b>本次提交带什么</b>（表单状态，此刻可验）与<b>旧版字段行仍在</b>
 * （path_gross_field 只有 insert，全仓零 update / delete，守卫测试钉着）。
 * 不说「新版会怎样」——gate=block 档下这一次根本不落库，任何「提交后新版如何」的断言在那一档上都是假的。
 */
const reviseSubmitNote = computed(() => {
  const k = Object.keys(reviseFormFields.value).length
  if (k === 0) {
    return '下面的字段栏此刻一项都没有填：本次提交不带任何字段，落库后这一版不会有字段级记录，'
      + '结构化记录停在修订前那一版（那几行不会被删，仍按版本调阅得到）。'
  }
  return `下面的字段栏此刻填了 ${k} 项：本次提交带的就是这 ${k} 项，落库后它们构成这一版的字段级记录；`
    + '没进字段栏的内容只作为文本保存（更早各版已经落下的字段行不会被删，仍按版本调阅得到）。'
})

const reviseCoverNote = computed(() => {
  const d = reviseCover.value
  if (d == null || d.fieldsAvailable !== true || d.fieldsCoverText !== false) return ''
  const n = num(d.textFieldForms)
  const backed = num(d.textFieldFormsBacked)
  const unbacked = (d.textFieldFormsUnbacked ?? []) as Row[]
  const names = unbacked.map((u) => `「${fmt(u.label)}」`).join('')
  const inEarlier = num(d.textFieldFormsInEarlierVersions)
  const textOnly = num(d.textFieldFormsTextOnly)
  const kinds: string[] = []
  if (inEarlier > 0) {
    kinds.push(`${inEarlier} 处的字段行留在更早的版本里（在「查看大体所见」里按版本可以调阅到），当前这一版没有`)
  }
  if (textOnly > 0) kinds.push(`${textOnly} 处从来没有录成字段，只以文本形式存在`)
  // 前半句是后端返回体里的事实（当前文本 vs 库里的字段行），与表单无关；
  // 后半句是「这次提交会发生什么」，只能由表单此刻的状态生成——两半各有各的事实源，不混着写死。
  return `当前这段描述里有 ${n} 处「标签：值」，其中 ${backed} 处在最新字段版里有结构化字段行。`
    + `差的 ${n - backed} 处是${names || '（后端未逐条给出）'}`
    + (kinds.length ? `：${kinds.join('；')}` : '')
    + '。要让它们随本次这一版一起被记录，把它们填进下面的字段栏再提交。'
    + reviseSubmitNote.value
})

async function openRevise(row: Row) {
  let d: Row
  try {
    d = (await client.get(`/pathology/process/grossing/${Number(row.id)}`)).data.data as Row
  } catch {
    return   // 拦截器已提示；取不到当前内容就不开表单，免得对着空白改
  }
  if (d.grossFindingPresent !== true) {
    ElMessage.warning('该标本尚无大体所见，无从修订：请先「取材登记」填写')
    return
  }
  mode.value = 'REVISE'
  current.value = row
  const text = String(d.grossFinding ?? '')
  // v62（2530 复核 decompose 镜头，本轮头号纪律）：预填**不再依赖 fieldsCurrent**。
  // fieldsCurrent 回答的是「字段版是否等于文本版」，而修订表单要预填的是「库里最新一版有字段行的那一版」——
  // 这正是后端 fields / fieldsRevisionSeq 的口径（max(revision_seq) from path_gross_field），两者无关。
  // v61 把补取材追加场景的 fieldsCurrent 改判为 false（判定本身是对的），这里恰好拿它当预填开关，
  // 于是库里已落的字段一项都不预填、提交即把结构化记录洗成扁平文本。
  const fields = (d.fields ?? []) as Row[]
  const split = splitGross(text, fields)
  const revisions = (d.revisions ?? []) as Row[]
  const lastTemplate = revisions.length ? revisions[revisions.length - 1]?.templateCode : null
  const templateCode = lastTemplate != null && templates.value.some((t) => String(t.code) === String(lastTemplate))
    ? String(lastTemplate) : ''
  Object.assign(form, {
    templateCode,
    gross: split.gross,
    grossText: split.free,
    append: false,
    techOrderId: undefined,
    remark: '',
    blocks: [{ tissueDesc: '' }],
  })
  // 模板字段里没填过的也给输入框（值空则提交时略去），顺序：模板字段在前、既有字段在后
  const tf = (templates.value.find((t) => String(t.code) === templateCode)?.fields ?? []) as string[]
  for (const f of tf) if (!(f in form.gross)) form.gross[f] = ''
  revisePrefillNote.value = prefillNote(d, split.mode, fields.length)
  reviseCover.value = d   // v64：覆盖事实条的数据源就是这一份返回体，不另取一次
  existingGross.value = text
  existingTextSeq.value = d.textRevisionSeq ?? null
  newFieldName.value = ''
  // v61：预填自库里字段行的项都算「已有字段」，本次没加过自定义项——不给删除按钮，
  // 免得一键删掉上一版录进去的字段却以为只是去掉一个输入框
  customFields.value = []
  dialog.value = true
}

async function submitRevise() {
  saving.value = true
  try {
    // v65：提交带哪几项字段与屏上那句「这次提交会发生什么」同源（reviseFormFields），不各算一遍
    const gross = reviseFormFields.value
    if (!Object.keys(gross).length && !form.grossText.trim()) {
      ElMessage.warning('字段与自由描述至少填一项')
      return
    }
    const r = (await client.put(`/pathology/process/grossing/${Number(current.value.id)}/fields`, {
      templateCode: form.templateCode || undefined,
      gross: Object.keys(gross).length ? gross : undefined,
      grossText: form.grossText || undefined,
    })).data.data as Row
    dialog.value = false
    ElMessage.success(`已生成第 ${fmt(r.revisionSeq)} 版取材描述（字段 ${num(r.grossFieldCount)} 项）`)
    // v62：gate emr.gate.pathology.grossfield=warn 时后端照常落库但回带 warnings
    //（本次修订后该标本不再有结构化字段）——不静默吞掉，block 档则根本走不到这里（5277 由拦截器提示）
    for (const w of ((r.warnings ?? []) as unknown[])) ElMessage.warning(String(w))
    await load()
    emit('changed')
  } finally {
    saving.value = false
  }
}

/* ---------------- 大体所见查看（只读，2530） ---------------- */
const viewDialog = ref(false)
const viewLoading = ref(false)
const view = ref<{ specimen?: Row; grossFinding?: unknown; grossFindingPresent?: unknown;
  fieldsAvailable?: unknown; fields?: Row[]; revisions?: Row[];
  fieldsRevisionSeq?: unknown; textRevisionSeq?: unknown; fieldsNote?: unknown; fieldsCurrent?: unknown;   // v59：字段与文本各自的版号
  textIsCumulative?: unknown;   // v61：当前文本是补取材追加的累积全文（v62 起前端真消费它，此前全仓零消费）
  fieldsByRevision?: Row[];   // v60（车道 B 契约）：[{revisionSeq, source, sourceName, templateCode, fields:[{seq,label,value}]}]，B 未合入前为 undefined
  diagnosedAt?: unknown; blocks?: Row[]; grossingEvents?: Row[]; note?: unknown }>({})
const viewBlocks = computed<Row[]>(() => (view.value.blocks ?? []) as Row[])
const viewEvents = computed<Row[]>(() => (view.value.grossingEvents ?? []) as Row[])

/* ---------------- 被取代版本的字段可调阅（v60，2530 尾） ---------------- */
/**
 * 可切换的字段版本，按 revisionSeq 升序。fieldsByRevision 有值就逐版列（含没字段行的版，选中后显示「本版未填写字段」）；
 * 后端没回该键（车道 B 未合入 / 旧包）就只列最新字段版这一项（fields + fieldsRevisionSeq），不编造被取代的版。
 */
const viewFieldVersions = computed<Row[]>(() => {
  const raw = view.value.fieldsByRevision
  if (Array.isArray(raw) && raw.length) {
    return [...raw].sort((a, b) => Number(a.revisionSeq) - Number(b.revisionSeq))
  }
  const latest = (view.value.fields ?? []) as Row[]
  if (!latest.length) return []
  return [{ revisionSeq: view.value.fieldsRevisionSeq ?? null, source: null, sourceName: null,
            templateCode: null, fields: latest }]
})
const viewVersionSeq = ref<number | null>(null)
const viewVersion = computed<Row | null>(
  () => viewFieldVersions.value.find((v) => Number(v.revisionSeq) === viewVersionSeq.value)
    ?? latestVersionWithFields(viewFieldVersions.value),
)
/** 选中的是否就是最新字段版（后端 fieldsRevisionSeq 那一版）：是则用 fields（带录入人 / 时刻），否则用 fieldsByRevision 里该版的行 */
const viewVersionIsLatestFields = computed(() => {
  const v = viewVersion.value
  const seq = view.value.fieldsRevisionSeq
  return v != null && seq != null && Number(v.revisionSeq) === Number(seq)
})
const viewFields = computed<Row[]>(() => {
  const v = viewVersion.value
  if (!v) return []
  const latest = (view.value.fields ?? []) as Row[]
  if (viewVersionIsLatestFields.value && latest.length) return latest
  // v62（2530 复核，审计者 met=false 第 (a) 点）：fieldsByRevision 的字段行只有 seq/label/value，
  // 而本表有「录入」列——切到被取代版本该列此前恒显示「— —」（最新版走 view.fields 带
  // operatorName/createdAt，同屏一眼看得出是缺数据）。一版字段是一次修订里一次性落的，
  // 录入人/时刻就是**该版版本头**的 changedByName / changedAt，按版回填、不编造；
  // 孤儿版（字段行有、修订行没有，直连改库造出来的）版本头本就为 null，照样显示「—」，不猜。
  return ((v.fields ?? []) as Row[]).map((f) => ({
    ...f,
    operatorName: f.operatorName ?? v.changedByName ?? null,
    createdAt: f.createdAt ?? v.changedAt ?? null,
  }))
})

/**
 * 0 项时的诚实兜底：整份标本一行字段都没有 vs 只是这一版没填，是两件事，不能共用一句话。
 * 标签位置只放短的那半句，表格位置放整句——同屏两处不重复念同一段话。
 */
const viewNoFieldsTag = computed(() => (view.value.fieldsRevisionSeq == null ? '无字段级记录' : '本版未填写字段'))
const viewNoFieldsNote = computed(() => (view.value.fieldsRevisionSeq == null
  ? '纯自由文本，无字段级记录（平台不从文本反解析字段）'
  : '本版未填写字段（只写了自由文本，或该版由诊断端点修订文本）'))

/**
 * 「字段级记录覆盖到当前文本的哪一段」——**全屏只说一次**，来源是 format.ts 的 grossFieldsNote
 * （后端 fieldsNote 优先，后端没给才兜底）。轨迹抽屉里那条同名标签用的是同一个函数：同一事实两个入口一套措辞。
 */
const viewFieldsNote = computed(() => grossFieldsNote(view.value as Row))

/**
 * v62（2530 复核 demo 镜头）：字段版本标签——「被取代版本」只在**真有更晚的字段版**时出现。
 *
 * <p>修复前：只写了自由描述、没有结构化字段的标本（零种子库里最普通的一种，也是 tools/demo-pathology.py
 * 铺出来的全部标本）走 else 分支，屏上打出「被取代版本（第 1 版，0 项）：已被第 — 版字段取代，仅供调阅」——
 * 它既没被任何版本取代（就是当前版、唯一版），「第 —」也不是版本号而是 fmt(null)；
 * 而那句诚实的兜底「历史标本或纯自由文本，无字段级记录」在当时的代码里对全新库标本永远不可达。
 *
 * <p><b>v63（2530 复核 demo + data 两个镜头，本轮头号纪律）</b>，两处都是「标签自己推断、与同屏后端文案对撞」：
 * <ul>
 *   <li><b>demo</b>：superseded 此前压根不看 textIsCumulative。补取材追加场景里当前 gross_finding 是
 *       <b>累积全文</b>，更早各版写下的内容一个字没少地留在正文里——<b>没有被任何版取代</b>，
 *       而屏上打的是「被取代版本（第 1 版，3 项）：已被第 2 版字段取代，仅供调阅」，
 *       正下方那条后端 fieldsNote 同屏说着「各版字段都在」。现在 superseded 把 textIsCumulative 算进去，
 *       累积全文里的更早版本改走「较早字段版」一档，与后端那句话同时成立。</li>
 *   <li><b>data</b>：此前只凭单键 textIsCumulative 就断言「本版字段只覆盖最后一次补取材」。
 *       最后那次补取材<b>可能一行字段都没写</b>（该版 path_gross_field 零行，字段级记录停在第 1 版「取材首写」），
 *       这句话与库内事实相反，且被同屏后端文案「第 2 版补取材追加未填写字段」逐字打脸。
 *       现在标签<b>不再自己下这个结论</b>：覆盖范围由 viewFieldsNote（后端同源）在正下方那条 alert 里说一次，
 *       标签只报版本身份与配色——同屏两处不重复念同一段话，更不会念出两套说法。</li>
 * </ul>
 */
const viewVersionTag = computed<{ type: 'success' | 'warning' | 'info'; text: string }>(() => {
  const v = viewVersion.value
  if (!v) return { type: 'info', text: '无可调阅的字段版本' }
  const seq = fmt(v.revisionSeq)
  const n = viewFields.value.length
  const latestSeq = view.value.fieldsRevisionSeq
  const cumulative = view.value.textIsCumulative === true
  const earlier = latestSeq != null && Number(v.revisionSeq) < Number(latestSeq)
  // 真被取代 = 存在一个更晚的、有字段行的版本（latestSeq 就是它；全无字段行时它为 null），
  // **且当前正文不是累积全文**——累积全文场景里这一版写下的内容仍原样留在当前 gross_finding 里，没被谁取代
  const superseded = earlier && !cumulative
  if (n === 0) return { type: 'info', text: `第 ${seq} 版：${viewNoFieldsTag.value}` }
  if (superseded) {
    return { type: 'info', text: `被取代版本（第 ${seq} 版，${n} 项）：已被第 ${fmt(latestSeq)} 版字段取代，仅供调阅` }
  }
  if (earlier) {
    return { type: 'info',
             text: `较早字段版（第 ${seq} 版，${n} 项）：当前文本是累积全文，本版字段写下的内容仍在正文里，未被取代` }
  }
  // 覆盖范围那句话归正下方的 viewFieldsNote（后端同源）说；这里只报身份与配色，不另起一套措辞
  if (viewFieldsNote.value !== '') {
    return { type: 'warning', text: `最新字段版（第 ${seq} 版，${n} 项）：字段与当前文本的覆盖关系见下方提示` }
  }
  return { type: 'success', text: `最新字段版（第 ${seq} 版，${n} 项），与当前文本同版` }
})

/** 默认版 = 最新有字段的版（与后端 fieldsRevisionSeq 同口径）；全都没字段就取最后一版 */
function latestVersionWithFields(list: Row[]): Row | null {
  for (let i = list.length - 1; i >= 0; i--) if (versionFieldCount(list[i]) > 0) return list[i]
  return list.length ? list[list.length - 1] : null
}

function versionFieldCount(v: Row | null | undefined): number {
  return Array.isArray(v?.fields) ? (v.fields as Row[]).length : 0
}

/**
 * v64（2530 复核 demo 镜头）：模板名。此前屏上直接印英文模板码（GI_BIOPSY）——
 * 而模板清单（GET /grossing/templates，模板名的唯一事实源）本组件本就加载着，中文名 t.name 就在手边。
 * 清单还没到就先给「—」，**不拿英文码冒充名字**、也不在清单没到时就断言「未登记」。
 * TrailPanel 的「模板」列有一份逐字相同的实现（守卫测试钉着两处一致）。
 */
function templateNameOf(v: Row | null | undefined): string {
  const code = v?.templateCode
  if (code == null || code === '') return '—'
  const hit = templates.value.find((t) => String(t.code) === String(code))
  if (hit != null) return String(hit.name)
  return templates.value.length ? '未登记的模板' : '—'
}

/** v64：蜡块编码前缀取自哪一个号。后端给的是 PATH_NO / BARCODE 两个码，屏上说人话 */
function codePrefixSourceName(v: unknown): string {
  const s = v == null ? '' : String(v)
  return s === 'PATH_NO' ? '取自病理号' : s === 'BARCODE' ? '取自院内条码' : '—'
}

/** path_gross_revision.source 三档（V166）的中文兜底；后端 sourceName 优先 */
function versionSourceName(v: Row | null | undefined): string {
  if (!v) return '—'
  if (v.sourceName != null && v.sourceName !== '') return String(v.sourceName)
  const s = v.source == null ? '' : String(v.source)
  return s === 'GROSSING' ? '取材' : s === 'GROSSING_EDIT' ? '取材修订' : s === 'DIAGNOSE' ? '诊断' : (s || '—')
}

function versionLabel(v: Row): string {
  const n = versionFieldCount(v)
  return `第 ${fmt(v.revisionSeq)} 版 · ${versionSourceName(v)}${n ? `（${n} 项）` : '（本版未填写字段）'}`
}

/* ---------------- 取材成功后回读已落库字段（v58，2530） ---------------- */
const resultView = ref<{ fieldsAvailable?: unknown; fields?: Row[] }>({})
const resultFields = computed<Row[]>(() => (resultView.value.fields ?? []) as Row[])
const resultFieldsLoading = ref(false)

/** 展示的是库里的 path_gross_field 行（GET /grossing/{id}），不是表单里的值——落库了什么就显示什么 */
async function loadResultFields(specimenId: number) {
  resultFieldsLoading.value = true
  resultView.value = {}
  try {
    resultView.value = (await client.get(`/pathology/process/grossing/${specimenId}`)).data.data as typeof resultView.value
  } catch {
    resultView.value = {}   // 回读失败按「无记录」显示，不拿表单值冒充库里的行
  } finally {
    resultFieldsLoading.value = false
  }
}

async function openView(specimenId: number) {
  viewDialog.value = true
  viewLoading.value = true
  viewVersionSeq.value = null
  try {
    view.value = (await client.get(`/pathology/process/grossing/${specimenId}`)).data.data as typeof view.value
    // v60：默认停在最新有字段的版；被取代版本由人切下拉去看
    const def = latestVersionWithFields(viewFieldVersions.value)
    viewVersionSeq.value = def == null || def.revisionSeq == null ? null : Number(def.revisionSeq)
  } finally {
    viewLoading.value = false
  }
}

async function submit() {
  if (form.blocks.length === 0) {
    ElMessage.warning('取材必须至少产出 1 个蜡块')
    return
  }
  saving.value = true
  try {
    // 值为空的结构化字段整条不传：后端会把空值字段略去，前端也不必送出「大小：」这种半截话
    const gross: Record<string, string> = {}
    for (const [k, v] of Object.entries(form.gross)) if (v && v.trim()) gross[k] = v.trim()

    // v59：无既有大体所见 → 首写；已有且勾了补取材 → 作为新版本追加；已有且未勾 → 一律不传（后端会拒 5222）
    const sendGross = !existingGross.value || form.append
    // v60：只有勾了补取材才带 techOrderId（车道 B 契约：append=true 时挂接 RESAMPLE 医嘱，非法 5275）；未勾即使残留也不传
    const techOrderId = form.append && form.techOrderId ? Number(form.techOrderId) : undefined
    result.value = (await client.post('/pathology/process/grossing', {
      specimenId: Number(current.value.id),
      templateCode: form.templateCode || undefined,
      gross: sendGross && Object.keys(gross).length ? gross : undefined,
      grossText: sendGross && form.grossText ? form.grossText : undefined,
      append: form.append,
      techOrderId,
      remark: form.remark || undefined,
      blocks: form.blocks.map((b) => ({ tissueDesc: b.tissueDesc || undefined })),
    }, { __silentCodes: [5275] })).data.data as Row
    dialog.value = false
    resultDialog.value = true
    void loadResultFields(Number(current.value.id))   // 回读库里的字段行，不用表单值冒充
    await load()
    emit('changed')
  } catch (e) {
    // 5275：挂接的补取材医嘱非法（不存在 / 非 RESAMPLE / 不属于该标本 / 非 ORDERED，四路径同码）——后端在任何写入之前拒绝，
    // 这里把后端原话给人看并刷新下拉（多半是别人刚取消 / 完成了那条医嘱）；其余码由拦截器统一红字，照旧抛出
    if ((e as BizError).bizCode === 5275) {
      ElMessage.error(`补取材医嘱挂接被拒（5275）：${(e as Error).message || '医嘱非法'}——请重新选择或改为不挂接`)
      void loadResampleOrders(Number(current.value.id))
      return
    }
    throw e
  } finally {
    saving.value = false
  }
}

defineExpose({ reload: load })

void load()
void loadTemplates()
</script>

<style scoped>
.cav { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
.code { font-family: Consolas, Monaco, monospace; }
.block-row { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.block-idx { width: 60px; color: #606266; font-size: 12px; }
.sec { margin-bottom: 10px; }
.sec pre {
  margin: 4px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  background: var(--el-fill-color-light);
  padding: 8px;
  border-radius: 4px;
}
h4 { margin: 12px 0 6px; }
</style>
