<template>
  <section class="hero-root relative isolate overflow-hidden">
    <!-- Soft gradient background shapes -->
    <div class="pointer-events-none absolute inset-0 -z-10">
      <div class="absolute left-1/2 top-[-10%] h-64 w-[36rem] -translate-x-1/2 rounded-full bg-gradient-to-r from-rose-400/30 to-red-500/30 blur-3xl"></div>
      <div class="absolute right-[-10%] bottom-[-10%] h-72 w-[32rem] rounded-full bg-gradient-to-tr from-sky-400/25 to-indigo-500/25 blur-3xl"></div>
    </div>

    <div class="mx-auto max-w-6xl py-0">
      <div class="mx-auto max-w-3xl text-center">
        <h1 class="hero-title text-4xl font-extrabold tracking-tight text-gray-900 sm:text-6xl">
          k‑File · 高效文件收集与管理
        </h1>
        <p class="mt-5 text-lg leading-8 text-gray-600">
          极速创建项目、分发提交入口、在线预览与批注、状态流转、打包归档、统计导出。
          面向团队的极简工作流，让文件提交井井有条。
        </p>
        <div class="mt-10 flex items-center justify-center gap-x-4">
          <button
            class="inline-flex items-center rounded-xl bg-gray-900 px-6 py-3 text-base font-semibold text-white shadow-sm transition hover:bg-gray-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gray-900"
            @click="$router.push('/admin/login')"
          >
            Get started
            <svg class="ml-2 h-5 w-5" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path fill-rule="evenodd" d="M3 10a.75.75 0 0 1 .75-.75h9.638L10.23 6.293a.75.75 0 1 1 1.04-1.086l4.999 4.75a.75.75 0 0 1 0 1.086l-4.999 4.75a.75.75 0 1 1-1.04-1.086l3.158-2.957H3.75A.75.75 0 0 1 3 10Z" clip-rule="evenodd" />
            </svg>
          </button>
          <a
            href="https://ksite.xin/"
            target="_blank"
            rel="noopener noreferrer"
            class="inline-flex items-center rounded-xl px-6 py-3 text-base font-semibold text-gray-700 ring-1 ring-inset ring-gray-300 transition hover:bg-white hover:text-gray-900"
          >
            了解更多
          </a>
        </div>
      </div>

      <!-- Feature highlights -->
      <div class="mx-auto mt-16 grid max-w-4xl grid-cols-1 gap-6 text-gray-700 sm:grid-cols-2">
        <div class="rounded-2xl bg-white/70 p-6 ring-1 ring-gray-200 backdrop-blur">
          <h3 class="text-base font-semibold text-gray-900">项目化管理</h3>
          <p class="mt-2 text-sm leading-6 text-gray-600">按项目创建提交入口，配置截止时间、说明与校验规则。</p>
        </div>
        <div class="rounded-2xl bg-white/70 p-6 ring-1 ring-gray-200 backdrop-blur">
          <h3 class="text-base font-semibold text-gray-900">便捷提交</h3>
          <p class="mt-2 text-sm leading-6 text-gray-600">拖拽上传、大文件/多文件支持，进度可视，断点重试。</p>
        </div>
        <div class="rounded-2xl bg-white/70 p-6 ring-1 ring-gray-200 backdrop-blur">
          <h3 class="text-base font-semibold text-gray-900">流程与审核</h3>
          <p class="mt-2 text-sm leading-6 text-gray-600">状态流转、批注与反馈，通知到位，协作更高效。</p>
        </div>
        <div class="rounded-2xl bg-white/70 p-6 ring-1 ring-gray-200 backdrop-blur">
          <h3 class="text-base font-semibold text-gray-900">归档与统计</h3>
          <p class="mt-2 text-sm leading-6 text-gray-600">一键打包、结构清晰，导出统计报表，沉淀可复用资产。</p>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
// 纯展示页，无需逻辑
</script>

<style scoped>
/* 让主容器在 App 的浅色背景上更通透、简洁 */
:host { display: block; }
/* 背景延伸至页面底部（扣除 64px 头部高度） */
.hero-root { min-height: calc(100vh - 64px); }
</style>
