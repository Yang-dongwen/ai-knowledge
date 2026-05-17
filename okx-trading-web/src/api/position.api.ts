import request from './request'

export const positionApi = {
  list() {
    return request.get('/positions')
  }
}
