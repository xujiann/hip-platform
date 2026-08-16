import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

export interface R<T = unknown> {
  code: number
  message: string
  data: T
}

const client = axios.create({ baseURL: '/api', timeout: 15000 })

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('hip_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

client.interceptors.response.use(
  (resp) => {
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
