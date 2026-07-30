import { createRouter, createWebHistory } from 'vue-router'
import { isLoggedIn } from './api'
import Login from './views/Login.vue'
import Notes from './views/Notes.vue'
import Edit from './views/Edit.vue'
import Detail from './views/Detail.vue'
import Me from './views/Me.vue'
import PublicShare from './views/PublicShare.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: Login, meta: { public: true, noTab: true } },
    { path: '/s/:token', name: 'publicShare', component: PublicShare, meta: { public: true, noTab: true } },
    { path: '/', redirect: '/notes' },
    { path: '/notes', name: 'notes', component: Notes },
    { path: '/edit', name: 'edit', component: Edit, meta: { noTab: true } },
    { path: '/detail/:id', name: 'detail', component: Detail, meta: { noTab: true } },
    { path: '/me', name: 'me', component: Me }
  ]
})

router.beforeEach((to) => {
  if (!to.meta.public && !isLoggedIn()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && isLoggedIn()) {
    return { path: '/notes' }
  }
  return true
})

export default router
