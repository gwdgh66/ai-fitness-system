import axios, { AxiosError, type AxiosInstance } from 'axios'
import type { ApiResult } from './types'

/**
 * 统一 API 错误。
 *
 * 后端约定：**业务异常返回 HTTP 200 + 业务错误码**（`code !== 200`），
 * 参数校验失败返回 HTTP 400，系统错误返回 HTTP 500 —— 三种情况都归一化成 `ApiError`，
 * 让视图层只处理一种错误类型，并能把后端的护栏文案原样展示给用户。
 */
export class ApiError extends Error {
  readonly code: number
  readonly httpStatus?: number

  constructor(code: number, message: string, httpStatus?: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.httpStatus = httpStatus
  }
}

const http: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.response.use(
  (response) => {
    const payload = response.data as ApiResult<unknown> | undefined
    if (payload == null || typeof payload.code !== 'number') {
      return response
    }
    if (payload.code !== 200) {
      throw new ApiError(payload.code, payload.message || '请求失败')
    }
    return response
  },
  (error: AxiosError<ApiResult<unknown>>) => {
    const status = error.response?.status
    const body = error.response?.data
    if (body && typeof body.code === 'number') {
      return Promise.reject(new ApiError(body.code, body.message || '请求失败', status))
    }
    if (error.code === 'ECONNABORTED') {
      return Promise.reject(new ApiError(-1, '请求超时：后端可能正在生成内容，请稍后重试', status))
    }
    if (!error.response) {
      return Promise.reject(new ApiError(-1, '无法连接后端服务（http://localhost:8080），请确认 Spring Boot 已启动', status))
    }
    return Promise.reject(new ApiError(status ?? -1, error.message, status))
  },
)

type QueryParams = Record<string, string | number | boolean | undefined | null>

export async function get<T>(url: string, params?: QueryParams): Promise<T> {
  const response = await http.get<ApiResult<T>>(url, { params })
  return response.data.data
}

export async function post<T>(url: string, body?: unknown, params?: QueryParams): Promise<T> {
  const response = await http.post<ApiResult<T>>(url, body, { params })
  return response.data.data
}

export async function put<T>(url: string, body?: unknown): Promise<T> {
  const response = await http.put<ApiResult<T>>(url, body)
  return response.data.data
}

export async function patch<T>(url: string, body?: unknown, params?: QueryParams): Promise<T> {
  const response = await http.patch<ApiResult<T>>(url, body, { params })
  return response.data.data
}

export async function del<T>(url: string, params?: QueryParams): Promise<T> {
  const response = await http.delete<ApiResult<T>>(url, { params })
  return response.data.data
}

/** 把任意异常转成可直接展示给用户的文案。 */
export function toErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message
  }
  if (error instanceof Error) {
    return error.message
  }
  return '未知错误'
}

export default http