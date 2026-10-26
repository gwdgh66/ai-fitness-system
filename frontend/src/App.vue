<script setup lang="ts">
import { onMounted } from 'vue'
import AppSidebar from '@/components/AppSidebar.vue'
import AppTopBar from '@/components/AppTopBar.vue'
import AppToasts from '@/components/AppToasts.vue'
import CopilotDock from '@/components/CopilotDock.vue'
import { appStore } from '@/stores/appStore'

onMounted(() => {
  void appStore.loadTwin()
})
</script>

<template>
  <div class="flex h-screen overflow-hidden bg-obsidian">
    <AppSidebar />

    <div class="flex min-w-0 flex-1 flex-col">
      <AppTopBar />
      <main class="min-h-0 flex-1 overflow-y-auto px-4 py-4 sm:px-5">
        <RouterView />
      </main>
    </div>

    <CopilotDock />
    <AppToasts />
  </div>
</template>