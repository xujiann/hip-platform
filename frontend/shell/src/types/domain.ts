/**
 * 核心域类型（1.2.1）：`Record<string, unknown>` 是全库 200+ 处的主流写法——
 * 后端字段改名时编译器完全帮不上忙，snake/camel 混用契约只靠肉眼。
 * 规约：**新页面必须用本文件的接口**（缺的先补进来再用）；存量页面改动时顺手迁移。
 *
 * 命名口径（与后端一致，勿"顺手统一"）：
 * - JPA 实体经 Jackson 序列化 → camelCase（Patient/Charge/Admission…）
 * - JdbcTemplate queryForList 直出 → snake_case（列表/报表行），除非 SQL 里 as "camelCase" 别名
 */

/** EMPI 患者（JPA，camelCase）；phone/idNo 非 ADMIN 已脱敏（含 * 的值禁止回填提交） */
export interface Patient {
  id: number
  patientNo: string
  name: string
  sex: 'M' | 'F' | 'U'
  birthDate?: string
  idType?: string
  idNo?: string
  phone?: string
  address?: string
  allergyHistory?: string
  bloodType?: string
  insuranceType?: string
}

/** 门诊挂号（JPA，camelCase） */
export interface Registration {
  id: number
  regNo: string
  patientId: number
  deptId: number
  visitDate: string
  fee: number | string
  status: 'REGISTERED' | 'VISITED' | 'CANCELLED' | string
  /** 列表端点聚合出的展示字段（后端 join 而来，详情端点可能没有） */
  patientName?: string
  deptName?: string
}

/** 门诊订单行（JPA，camelCase）——收费/发药/执行的共同数据源 */
export interface OutpOrder {
  id: number
  registrationId: number
  groupNo: string
  orderType: 'DRUG' | 'LAB' | 'EXAM' | 'TREAT' | 'REG' | string
  itemName: string
  spec?: string
  unit: string
  qty: number
  unitPrice: number | string
  amount: number | string
  status: 'CREATED' | 'CHARGED' | 'DISPENSED' | 'EXECUTED' | 'CANCELLED' | string
}

/** 结算单（JPA，camelCase） */
export interface Charge {
  id: number
  chargeNo: string
  registrationId: number
  totalAmount: number | string
  payMethod: 'CASH' | 'WECHAT' | 'ALIPAY' | 'YB' | string
  status: 'PAID' | 'REFUNDED' | string
  createdAt: string
  refundedAt?: string
}

/** 住院记录（JPA，camelCase） */
export interface Admission {
  id: number
  admissionNo: string
  patientId: number
  patientName?: string
  deptId: number
  wardId: number
  bedId: number
  wardName?: string
  bedNo?: string
  admitDiagIcd?: string
  admitDiagName?: string
  status: 'IN_HOSPITAL' | 'DISCHARGED' | string
}

/** 号源（JPA，camelCase） */
export interface Schedule {
  id: number
  deptId: number
  scheduleDate: string
  regType?: string
  shift?: string
  fee: number | string
  capacity: number
  booked: number
  enabled?: boolean
}

/** JdbcTemplate 直出的报表/列表行：字段为 snake_case，页面按需局部声明。
 *  不要为每张报表建全量接口——列随 SQL 变，收益低于维护成本；
 *  只把「多页面复用」或「金额/状态等易错字段」提到这里。 */
export type SnakeRow = Record<string, unknown>
