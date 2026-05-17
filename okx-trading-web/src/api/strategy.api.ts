import request from './request'

export const strategyApi = {
  list() {
    return request.get('/strategies')
  },
  getById(id: number) {
    return request.get(`/strategies/${id}`)
  },
  create(data: any) {
    return request.post('/strategies', data)
  },
  update(id: number, data: any) {
    return request.put(`/strategies/${id}`, data)
  },
  enable(id: number) {
    return request.post(`/strategies/${id}/enable`)
  },
  disable(id: number) {
    return request.post(`/strategies/${id}/disable`)
  },
  remove(id: number) {
    return request.delete(`/strategies/${id}`)
  }
}
