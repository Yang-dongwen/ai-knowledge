import request from './request'

export const tradeApi = {
  list(params?: any) {
    return request.get('/trades', { params })
  },
  getRecent(limit = 5) {
    return request.get('/trades/recent', { params: { limit } })
  },
  getSummary() {
    return request.get('/trades/summary')
  }
}
