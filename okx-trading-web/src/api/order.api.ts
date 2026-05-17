import request from './request'

export const orderApi = {
  list(params?: any) {
    return request.get('/orders', { params })
  },
  getById(id: number) {
    return request.get(`/orders/${id}`)
  },
  sync(id: number) {
    return request.post(`/orders/${id}/sync`)
  }
}
