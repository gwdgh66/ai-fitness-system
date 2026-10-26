import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题（顶栏与文档标题使用） */
    title: string
    /** 若为占位页，标注交付步骤 */
    step?: string
    /** 占位页的一句话说明 */
    summary?: string
  }
}

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/dashboard' },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { title: '核心看板' },
  },
  {
    path: '/twin',
    name: 'twin',
    component: () => import('@/views/ProfileTwinView.vue'),
    meta: { title: '数字孪生' },
  },
  {
    path: '/workout',
    name: 'workout',
    component: () => import('@/views/WorkoutPlanView.vue'),
    meta: { title: '周课表与打卡' },
  },
  {
    path: '/nutrition',
    name: 'nutrition',
    component: () => import('@/views/NutritionView.vue'),
    meta: { title: '饮食与套餐' },
  },
  {
    path: '/engineering',
    name: 'engineering',
    component: () => import('@/views/ObservabilityRagView.vue'),
    meta: { title: 'AI 工程与 RAG 调试台' },
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · Kinetic` : 'Kinetic · 个人 AI 健身系统'
})

export default router