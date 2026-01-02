<template>
  <section class="hero-root relative isolate overflow-hidden flex flex-col justify-center">
    <div class="pointer-events-none absolute inset-0 -z-10">
      <div class="absolute left-1/2 top-[-10%] h-64 w-[36rem] -translate-x-1/2 rounded-full bg-gradient-to-r from-rose-400/30 to-red-500/30 blur-3xl"></div>
      <div class="absolute right-[-10%] bottom-[-10%] h-72 w-[32rem] rounded-full bg-gradient-to-tr from-sky-400/25 to-indigo-500/25 blur-3xl"></div>
    </div>

    <div class="mx-auto max-w-6xl px-6 lg:px-8 py-12">
      <div class="mx-auto max-w-3xl text-center">
        <h1 class="hero-title text-4xl font-extrabold tracking-tight text-gray-900 sm:text-6xl">
          k‑File 一站式文件管理
        </h1>

        <p class="mt-6 text-lg leading-8 text-gray-600">
          专为作业与资料收集场景设计。
          支持<span class="font-semibold text-gray-800">自动重命名</span>、<span class="font-semibold text-gray-800">一键设置截止时间</span>与<span class="font-semibold text-gray-800">未提交名单导出</span>。
          告别繁琐的人工统计与催收，让文件归档井井有条。
        </p>

        <div class="mt-8 flex items-center justify-center gap-x-4">
          <button
              class="inline-flex items-center rounded-xl bg-gray-900 px-6 py-3 text-base font-semibold text-white shadow-sm transition hover:bg-gray-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gray-900"
              @click="showBetaModal = true"
          >
            立即开始收集
            <svg class="ml-2 h-5 w-5" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path fill-rule="evenodd" d="M3 10a.75.75 0 0 1 .75-.75h9.638L10.23 6.293a.75.75 0 1 1 1.04-1.086l4.999 4.75a.75.75 0 0 1 0 1.086l-4.999 4.75a.75.75 0 1 1-1.04-1.086l3.158-2.957H3.75A.75.75 0 0 1 3 10Z" clip-rule="evenodd" />
            </svg>
          </button>
<!--          <a-->
<!--              href="https://ksite.xin/"-->
<!--              target="_blank"-->
<!--              rel="noopener noreferrer"-->
<!--              class="inline-flex items-center rounded-xl px-6 py-3 text-base font-semibold text-gray-700 ring-1 ring-inset ring-gray-300 transition hover:bg-white hover:text-gray-900"-->
<!--          >-->
<!--            了解更多-->
<!--          </a>-->
        </div>

        <div class="mt-8 flex flex-wrap justify-center gap-2 text-sm text-gray-500">
          <span class="px-3 py-1 rounded-full bg-gray-100/80 border border-gray-200">📚 课程作业</span>
          <span class="px-3 py-1 rounded-full bg-gray-100/80 border border-gray-200">🗂️ 培训材料</span>
          <span class="px-3 py-1 rounded-full bg-gray-100/80 border border-gray-200">📝 活动报名</span>
          <span class="px-3 py-1 rounded-full bg-gray-100/80 border border-gray-200">💾 团队归档</span>
        </div>
      </div>

      <div class="mx-auto mt-16 grid max-w-5xl grid-cols-1 gap-6 text-gray-700 sm:grid-cols-2 lg:gap-8">
        <div class="relative rounded-2xl bg-white/60 p-6 ring-1 ring-gray-200/50 backdrop-blur-md transition hover:bg-white/80">
          <div class="mb-3 flex h-10 w-10 items-center justify-center rounded-lg bg-rose-500/10 text-rose-600">
            <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
          </div>
          <h3 class="text-base font-semibold text-gray-900">智能截止控制</h3>
          <p class="mt-2 text-sm leading-6 text-gray-600">
            一键设置截止时间，到期自动停止收集。支持单次提交多个文件，满足多样化需求。
          </p>
        </div>
        <div class="relative rounded-2xl bg-white/60 p-6 ring-1 ring-gray-200/50 backdrop-blur-md transition hover:bg-white/80">
          <div class="mb-3 flex h-10 w-10 items-center justify-center rounded-lg bg-indigo-500/10 text-indigo-600">
            <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
          </div>
          <h3 class="text-base font-semibold text-gray-900">自动命名与校验</h3>
          <p class="mt-2 text-sm leading-6 text-gray-600">
            导入名单自动校验提交资格。按预设规则（如学号+姓名）<span class="text-indigo-600 font-medium">自动重命名</span>文件，统一规范。
          </p>
        </div>
        <div class="relative rounded-2xl bg-white/60 p-6 ring-1 ring-gray-200/50 backdrop-blur-md transition hover:bg-white/80">
          <div class="mb-3 flex h-10 w-10 items-center justify-center rounded-lg bg-amber-500/10 text-amber-600">
            <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><polyline points="10 9 9 9 8 9"/></svg>
          </div>
          <h3 class="text-base font-semibold text-gray-900">实时进度催收</h3>
          <p class="mt-2 text-sm leading-6 text-gray-600">
            实时统计提交进度，支持<span class="text-amber-600 font-medium">一键导出未提交人员名单</span>，精准提醒。
          </p>
        </div>
        <div class="relative rounded-2xl bg-white/60 p-6 ring-1 ring-gray-200/50 backdrop-blur-md transition hover:bg-white/80">
          <div class="mb-3 flex h-10 w-10 items-center justify-center rounded-lg bg-emerald-500/10 text-emerald-600">
            <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          </div>
          <h3 class="text-base font-semibold text-gray-900">一键打包下载</h3>
          <p class="mt-2 text-sm leading-6 text-gray-600">
            按任务批量打包，省时省力。生成下载直链，支持定时/限时分享，集中分发更安全。
          </p>
        </div>
      </div>

      <div class="mt-16 text-center">
        <p class="text-xs text-gray-400">Powered by kk</p>
      </div>
    </div>

    <Teleport to="body">
      <Transition
          enter-active-class="ease-out duration-300"
          enter-from-class="opacity-0"
          enter-to-class="opacity-100"
          leave-active-class="ease-in duration-200"
          leave-from-class="opacity-100"
          leave-to-class="opacity-0"
      >
        <div v-if="showBetaModal" class="relative z-[999]" role="dialog" aria-modal="true">
          <!-- 遮罩 -->
          <div
              class="fixed inset-0 bg-black/40"
              @click="showBetaModal = false"
          ></div>

          <!-- 弹窗主体 -->
          <div class="fixed inset-0 flex items-center justify-center p-4">
            <Transition
                enter-active-class="ease-out duration-300"
                enter-from-class="opacity-0 translate-y-6 scale-95"
                enter-to-class="opacity-100 translate-y-0 scale-100"
                leave-active-class="ease-in duration-200"
                leave-from-class="opacity-100 translate-y-0 scale-100"
                leave-to-class="opacity-0 translate-y-6 scale-95"
            >
              <div
                  class="w-full max-w-[360px] rounded-2xl bg-white p-6 shadow-xl ring-1 ring-black/5"
              >
                <!-- Beta 标签 -->
                <div class="mb-4 flex justify-center">
                  <div class="inline-flex items-center gap-2 rounded-full bg-indigo-50 px-3 py-1 text-xs font-semibold text-indigo-600">
                    <span class="h-2 w-2 rounded-full bg-indigo-500"></span>
                    Beta 内测
                  </div>
                </div>

                <h3 class="text-center text-lg font-semibold text-gray-900">
                  k-File 正在内测
                </h3>

                <p class="mt-2 text-center text-sm leading-relaxed text-gray-500">
                  当前版本仅对受邀用户开放。<br />
                  如需体验，请联系管理员添加测试权限。
                </p>

                <!-- 联系方式卡片 -->
                <div class="mt-6 rounded-xl border border-gray-200 bg-gray-50 px-4 py-3">
                  <div class="flex items-center justify-between">
                    <div>
                      <div class="text-xs text-gray-400">联系管理员（QQ）</div>
                      <div class="mt-0.5 font-mono text-base font-semibold text-gray-900">
                        {{ contactQQ }}
                      </div>
                    </div>

                    <button
                        @click="copyToClipboard"
                        class="ml-4 rounded-lg px-3 py-1.5 text-xs font-medium
                           bg-blue-100 text-gray-700
                           hover:bg-blue-200
                           transition
                           border-0 outline-none
                           appearance-none
                           focus:outline-none"
                    >
                      复制
                    </button>



                  </div>
                </div>

                <button
                    class="mt-6 w-full rounded-xl border border-gray-300 py-2.5 text-sm font-medium text-gray-700 hover:bg-gray-100"
                    @click="showBetaModal = false"
                >
                  关闭
                </button>
              </div>
            </Transition>
          </div>
        </div>
      </Transition>
    </Teleport>

  </section>
</template>

<script setup>
import { ref } from 'vue'

const showBetaModal = ref(false)
const contactQQ = '2604159440'
const copied = ref(false)

const copyToClipboard = async () => {
  try {
    await navigator.clipboard.writeText(contactQQ)
    copied.value = true
    // 2秒后重置复制状态
    setTimeout(() => {
      copied.value = false
    }, 2000)
  } catch (err) {
    console.error('Failed to copy!', err)
  }
}
</script>

<style scoped>
:host { display: block; }
.hero-root {
  min-height: calc(100vh - 64px);
  padding-bottom: 2rem;
}
</style>