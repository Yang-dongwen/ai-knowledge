import request from './request'

export const logApi = {
  list(params?: any) {
    return request.get('/strategy-run-logs', { params })
  },
  getLatest() {
    return request.get('/strategy-run-logs/latest')
  }
}
