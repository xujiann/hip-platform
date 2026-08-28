import axios, { type AxiosInstance } from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

export interface R<T = unknown> {
  code: number
  message: string
  data: T
}

declare module 'axios' {
  interface AxiosRequestConfig {
    /**
     * 调用方自行处理的业务码白名单：命中时拦截器**不弹**默认红字（但仍 reject，
     * 并在 rejected error 的 `bizCode` 上暴露该码，供调用方分流处理，如大额退费的 5011→引导审批）。
     * 其余业务码维持「集中红字 + reject」旧行为不变。
     */
    __silentCodes?: number[]
  }
}

/** 拦截器 reject 时附带的业务码（对应后端 R.code），调用方可据此分流处理 */
export interface BizError extends Error {
  bizCode?: number
}

/**
 * 写操作在途去重：同一 method+url+params+body 在前一次未返回前直接拒绝第二次。
 *
 * 双击「挂号 / 补交押金 / 开医嘱 / 出码」这类**纯新增**接口时，后端的条件更新救不了
 * （没有状态可判），结果是两条挂号、两笔押金、两组医嘱。前端去重是这类接口的第一道防线。
 */
const inFlight = new Set<string>()
const DEDUP_METHODS = ['post', 'put', 'delete', 'patch']

function keyOf(config: { method?: string; url?: string; params?: unknown; data?: unknown }) {
  const body = typeof config.data === 'string' ? config.data : JSON.stringify(config.data ?? '')
  // params 必须进 key（1.1.8）：`/queue/pass?registrationId=1` 与 `=2` 是不同操作——
  // 不拼会把"给两个患者连续过号"误拦，而真正的同参数重复提交反而漏防
  const params = JSON.stringify(config.params ?? '')
  return `${config.method}:${config.url}:${params}:${body}`
}

function releaseDedup(config?: { __dedupKey?: string }) {
  const key = config?.__dedupKey
  if (key) inFlight.delete(key)
}

export interface HipClientOptions {
  baseURL: string
  tokenKey: string
  loginPath: string
}

/**
 * 统一客户端工厂（1.1.8 B-10）：院内端与患者门户共用在途去重/超时/错误提示三道防线。
 * 门户此前自建裸 axios——双击可重复预约/重复支付确认，弱网失败零提示。
 */
export function createHipClient(opts: HipClientOptions): AxiosInstance {
  const instance = axios.create({ baseURL: opts.baseURL, timeout: 15000 })

  instance.interceptors.request.use((config) => {
    const token = localStorage.getItem(opts.tokenKey)
    if (token) config.headers.Authorization = `Bearer ${token}`

    if (DEDUP_METHODS.includes((config.method ?? '').toLowerCase())) {
      const key = keyOf(config)
      if (inFlight.has(key)) {
        ElMessage.warning('操作处理中，请勿重复提交')
        return Promise.reject(new axios.Cancel('duplicate-submit'))
      }
      inFlight.add(key)
      ;(config as { __dedupKey?: string }).__dedupKey = key
    }
    return config
  })

  instance.interceptors.response.use(
    async (resp) => {
      releaseDedup(resp.config as { __dedupKey?: string })
      // blob/文本下载没有 {code,message} 信封：无条件读 resp.data.code 会让
      // undefined !== 0 恒成立，把成功的下载判成失败（1.0.9 A-8：日结 CSV 导出曾 100% 失效）
      if (resp.config.responseType === 'blob') {
        // 反向兜底（1.1.8）：后端返回业务错误（HTTP 200 + JSON 信封）时 axios 也包成 Blob，
        // 直接放行会让用户把错误 JSON 下载成 .csv——财务对账场景极具迷惑性
        const blob = resp.data as Blob
        if (blob instanceof Blob && blob.type.includes('application/json')) {
          const r = JSON.parse(await blob.text()) as R
          ElMessage.error(r.message || '导出失败')
          return Promise.reject(new Error(r.message))
        }
        return resp
      }
      const isEnvelope = resp.data && typeof resp.data === 'object' && 'code' in resp.data
      if (!isEnvelope) return resp
      const r = resp.data as R
      if (r.code !== 0) {
        // 调用方声明自处理的业务码（如 5011 大额退费引导审批）不弹默认红字，避免「红字 + 引导框」双重打扰；
        // 仍 reject 并在 error.bizCode 暴露码，调用方据此分流。其余码维持集中红字。
        const silent = resp.config.__silentCodes?.includes(r.code) ?? false
        if (!silent) ElMessage.error(r.message || '操作失败')
        const err = new Error(r.message) as BizError
        err.bizCode = r.code
        return Promise.reject(err)
      }
      return resp
    },
    (error) => {
      releaseDedup(error.config as { __dedupKey?: string })
      if (axios.isCancel(error)) return Promise.reject(error)   // 去重拦截已提示，不再弹网络错误
      const status = error.response?.status
      if (status === 401) {
        localStorage.removeItem(opts.tokenKey)
        router.push(opts.loginPath)
        ElMessage.warning('登录已过期，请重新登录')
      } else if (status === 403) {
        // 后端方法级权限拒绝：给出可理解的原因，而不是笼统的"网络请求失败"
        ElMessage.error('无该功能权限，请联系管理员分配角色')
      } else if (status === 404 && error.response?.data?.message === '该功能模块未启用') {
        // 模块开关的 404（ModuleGateFilter）不全局弹错：调用方要么已自行降级
        // （医生站 CDSS 提示、专科页麻醉 tab），要么该页面本就该随菜单一起消失——
        // 第六轮审阅 P2-B：此前组件 catch 了、全局红 toast 却照弹，"静默降级"不静默
      } else {
        ElMessage.error(error.response?.data?.message || '网络请求失败')
      }
      return Promise.reject(error)
    },
  )
  return instance
}

/** 院内端客户端 */
const client = createHipClient({ baseURL: '/api', tokenKey: 'hip_token', loginPath: '/login' })

/** 患者门户客户端：独立令牌、独立登录页，同一套防线 */
export const portalClient = createHipClient({
  baseURL: '/api/portal',
  tokenKey: 'hip_portal_token',
  loginPath: '/portal',
})

export default client
