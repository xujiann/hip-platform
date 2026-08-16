import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

export interface R<T = unknown> {
  code: number
  message: string
  data: T
}

const client = axios.create({ baseURL: '/api', timeout: 15000 })

/**
 * 写操作在途去重：同一 method+url+body 在前一次未返回前直接拒绝第二次。
 *
 * 双击「挂号 / 补交押金 / 开医嘱 / 出码」这类**纯新增**接口时，后端的条件更新救不了
 * （没有状态可判），结果是两条挂号、两笔押金、两组医嘱。前端去重是这类接口的第一道防线。
 */
const inFlight = new Set<string>()
const DEDUP_METHODS = ['post', 'put', 'delete', 'patch']

function keyOf(config: { method?: string; url?: string; data?: unknown }) {
  const body = typeof config.data === 'string' ? config.data : JSON.stringify(config.data ?? '')
  return `${config.method}:${config.url}:${body}`
}

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('hip_token')
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

/** 无论成功失败都要释放在途标记，否则该操作会被永久锁死 */
function releaseDedup(config?: { __dedupKey?: string }) {
  const key = config?.__dedupKey
  if (key) inFlight.delete(key)
}

client.interceptors.response.use(
  (resp) => {
    releaseDedup(resp.config as { __dedupKey?: string })
    // blob/文本下载没有 {code,message} 信封：无条件读 resp.data.code 会让
    // undefined !== 0 恒成立，把成功的下载判成失败（1.0.9 A-8：日结 CSV 导出曾 100% 失效）
    const isEnvelope = resp.data && typeof resp.data === 'object' && 'code' in resp.data
    if (!isEnvelope || resp.config.responseType === 'blob') return resp
    const r = resp.data as R
    if (r.code !== 0) {
      ElMessage.error(r.message || '操作失败')
      return Promise.reject(new Error(r.message))
    }
    return resp
  },
  (error) => {
    releaseDedup(error.config as { __dedupKey?: string })
    if (axios.isCancel(error)) return Promise.reject(error)   // 去重拦截已提示，不再弹网络错误
    const status = error.response?.status
    if (status === 401) {
      localStorage.removeItem('hip_token')
      router.push('/login')
      ElMessage.warning('登录已过期，请重新登录')
    } else if (status === 403) {
      // 后端方法级权限拒绝：给出可理解的原因，而不是笼统的"网络请求失败"
      ElMessage.error('无该功能权限，请联系管理员分配角色')
    } else {
      ElMessage.error(error.response?.data?.message || '网络请求失败')
    }
    return Promise.reject(error)
  },
)

export default client
