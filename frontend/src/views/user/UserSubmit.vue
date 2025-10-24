<template>
  <div class="min-h-full bg-gray-50/30">
    <div class="container mx-auto px-4 py-4 max-w-4xl">

      <!-- 页面头部 - 使用高亮样式 -->
      <div class="bg-white rounded-2xl shadow-sm border border-gray-100 mb-6">
        <div class="px-6 py-4">
          <div class="flex items-center justify-between gap-4">
            <h1 class="text-xl font-medium text-gray-800">
              <span class="project-name-highlight">文件上传</span> - {{ (project && (project.name || project.title)) || '加载中...' }}
            </h1>
            <div class="shrink-0">
              <button v-if="mode === 'submit'"
                      @click="switchToStatus"
                      class="px-3 py-2 text-blue-600 hover:text-blue-700 border border-blue-200 hover:border-blue-300 rounded-lg bg-white">
                查询状态
              </button>
              <button v-else
                      @click="switchToSubmit"
                      class="px-3 py-2 text-blue-600 hover:text-blue-700 border border-blue-200 hover:border-blue-300 rounded-lg bg-white">
                返回提交
              </button>
            </div>
          </div>
          <!-- 项目信息紧凑显示 -->
          <div v-if="project" class="mt-3 flex flex-wrap items-center gap-4 text-sm text-gray-600">
            <span class="flex items-center gap-1">
              <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                <path fill-rule="evenodd" d="M6 2a1 1 0 00-1 1v1H4a2 2 0 00-2 2v10a2 2 0 002 2h12a2 2 0 002-2V6a2 2 0 00-2-2h-1V3a1 1 0 10-2 0v1H7V3a1 1 0 00-1-1zm0 5a1 1 0 000 2h8a1 1 0 100-2H6z" clip-rule="evenodd" />
              </svg>
              截止时间：{{ endAtText }}
            </span>
            <span v-if="latest.exists && submitCountDisplay > 0" class="flex items-center gap-1">
              <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" />
              </svg>
              已提交 {{ submitCountDisplay }} 次
            </span>
          </div>
        </div>
      </div>

      <!-- 主要内容区域 -->
      <div v-if="project" class="space-y-6">

        <!-- 状态查询模式 -->
        <div v-if="mode === 'status'" class="bg-white rounded-2xl shadow-sm border border-gray-100 p-6">
          <div class="max-w-2xl">
            <div class="grid grid-cols-1 md:grid-cols-2 gap-4 mb-6">
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-2">{{ queryLabel }}</label>
                <div class="flex gap-3">
                  <input
                      v-model="queryValue"
                      :placeholder="`请输入${queryLabel}`"
                      class="flex-1 px-4 py-3 border border-gray-200 rounded-xl focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-colors"
                  />
                  <button
                      :disabled="!queryValue || querying"
                      @click="queryStatusByField"
                      class="px-6 py-3 bg-blue-600 text-white rounded-xl hover:bg-blue-700 disabled:bg-gray-300 disabled:cursor-not-allowed transition-colors font-medium"
                  >
                    {{ querying ? '查询中…' : '查询' }}
                  </button>
                </div>
              </div>
            </div>

            <!-- 用户状态标签（与提交页一致） -->
            <div v-if="project.userSubmitStatusText" class="mb-4">
              <span :class="[
                'inline-flex items-center px-3 py-1 rounded-full text-sm font-medium',
                project.userSubmitStatusType === 'success' ? 'bg-green-100 text-green-800' :
                project.userSubmitStatusType === 'warning' ? 'bg-amber-100 text-amber-800' :
                project.userSubmitStatusType === 'danger' ? 'bg-red-100 text-red-800' :
                'bg-blue-100 text-blue-800'
              ]">
                {{ project.userSubmitStatusText }}
              </span>
            </div>

            <!-- 查询结果 -->
            <div class="border-t border-gray-100 pt-6">
              <h3 class="text-sm font-medium text-gray-700 mb-4">查询结果</h3>

              <!-- 查询中提示 -->
              <div v-if="querying" class="flex items-center gap-2 text-gray-500 mb-3">
                <svg class="w-4 h-4 animate-spin" viewBox="0 0 24 24" fill="none">
                  <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                  <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
                </svg>
                正在查询…
              </div>

              <div v-if="latest.exists" class="space-y-4">
                <!-- 状态标签 -->
                <div class="flex items-center gap-3">
                  <span :class="[
                    'inline-flex items-center px-3 py-1 rounded-full text-sm font-medium',
                    latest.expired
                      ? 'bg-amber-100 text-amber-800'
                      : 'bg-green-100 text-green-800'
                  ]">
                    {{ latest.expired ? '逾期' : '正常' }}
                  </span>
                  <span class="text-sm text-gray-500">
                    时间：{{ formatTimestamp(latest.createdAt) }}（第 {{ submitCountDisplay }} 次）
                  </span>
                </div>

                <!-- 版本时间线 -->
                <div v-if="Array.isArray(versions) && versions.length" class="relative">
                  <div class="space-y-4">
                    <div v-for="(ver, idx) in versions" :key="ver.id" class="relative flex">
                      <!-- 时间线图标 -->
                      <div class="flex-shrink-0 w-10 h-10 rounded-full flex items-center justify-center relative z-10"
                           :class="[
                             idx === 0 ? 'bg-green-100 text-green-600' :
                             idx < versions.length - 1 ? 'bg-blue-100 text-blue-600' :
                             'bg-gray-100 text-gray-500'
                           ]">
                        <svg v-if="idx === 0" class="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
                          <path fill-rule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clip-rule="evenodd" />
                        </svg>
                        <div v-else class="w-2 h-2 bg-current rounded-full"></div>
                      </div>

                      <!-- 连接线 -->
                      <div v-if="idx < versions.length - 1"
                           class="absolute left-5 top-10 w-px h-6 bg-gray-200"></div>

                      <!-- 内容区域 -->
                      <div class="ml-4 flex-1 min-w-0">
                        <div class="flex items-center justify-between mb-2">
                          <h4 class="text-sm font-medium text-gray-800">
                            {{ idx === 0 ? '最新提交' : `历史提交 ${versions.length - idx}` }}
                          </h4>
                          <time class="text-sm text-gray-500">{{ formatTimestamp(ver.createdAt) }}</time>
                        </div>

                        <!-- 文件列表 -->
                        <div v-if="Array.isArray(ver.fileNames) && ver.fileNames.length"
                             class="grid gap-2">
                          <div v-for="(name, i2) in ver.fileNames" :key="i2"
                               class="flex items-center gap-3 p-3 bg-gray-50 rounded-xl">
                            <div class="w-5 h-5 text-gray-400">
                              <svg fill="currentColor" viewBox="0 0 20 20">
                                <path fill-rule="evenodd" d="M4 4a2 2 0 012-2h4.586A2 2 0 0112 2.586L15.414 6A2 2 0 0116 7.414V16a2 2 0 01-2 2H6a2 2 0 01-2-2V4zm2 6a1 1 0 011-1h6a1 1 0 110 2H7a1 1 0 01-1-1zm1 3a1 1 0 100 2h6a1 1 0 100-2H7z" clip-rule="evenodd" />
                              </svg>
                            </div>
                            <span class="text-sm text-gray-700 truncate">{{ name }}</span>
                          </div>
                        </div>
                        <div v-else class="text-sm text-gray-500 italic">暂无文件</div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <div v-else class="text-center py-8">
                <div class="w-16 h-16 mx-auto mb-4 rounded-full border-2 border-dashed border-gray-300 flex items-center justify-center">
                  <svg class="w-8 h-8 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                  </svg>
                </div>
                <p class="text-gray-500">
                  <template v-if="queryTried">
                    {{ `${queryLabel}「${queryValue}」暂无提交` }}
                  </template>
                </p>
              </div>
            </div>
          </div>
        </div>

        <!-- 提交模式 -->
        <template v-if="mode === 'submit'">
          <!-- 警告信息（紧凑显示） -->
          <div v-if="hasWarnings" class="space-y-3">
            <div v-if="project.offline" class="bg-amber-50 border border-amber-200 rounded-xl p-4 flex items-start gap-3">
              <svg class="w-5 h-5 text-amber-600 mt-0.5 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                <path fill-rule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clip-rule="evenodd" />
              </svg>
              <div>
                <h4 class="font-medium text-amber-800">项目已下线</h4>
                <p class="text-amber-700 mt-1">无法提交</p>
              </div>
            </div>

            <div v-else-if="isPastDeadline && !project.allowOverdue" class="bg-amber-50 border border-amber-200 rounded-xl p-4 flex items-start gap-3">
              <svg class="w-5 h-5 text-amber-600 mt-0.5 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                <path fill-rule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clip-rule="evenodd" />
              </svg>
              <div>
                <h4 class="font-medium text-amber-800">项目已过期</h4>
                <p class="text-amber-700 mt-1">无法提交</p>
              </div>
            </div>

            <div v-else-if="isPastDeadline && project.allowOverdue" class="bg-amber-50 border border-amber-200 rounded-xl p-4 flex items-start gap-3">
              <svg class="w-5 h-5 text-amber-600 mt-0.5 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                <path fill-rule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clip-rule="evenodd" />
              </svg>
              <div>
                <h4 class="font-medium text-amber-800">当前为逾期提交</h4>
                <p class="text-amber-700 mt-1">该提交将标记为逾期</p>
              </div>
            </div>

            <div v-if="project && project.allowResubmit === false" class="bg-blue-50 border border-blue-200 rounded-xl p-4 flex items-start gap-3">
              <svg class="w-5 h-5 text-blue-600 mt-0.5 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clip-rule="evenodd" />
              </svg>
              <div>
                <h4 class="font-medium text-blue-800">提交限制</h4>
                <p class="text-blue-700 mt-1">本项目每个提交者仅可提交一次，如需修改请联系管理员</p>
              </div>
            </div>

            <div v-if="!project.allowResubmit && latest.exists" class="bg-amber-50 border border-amber-200 rounded-xl p-4 flex items-start gap-3">
              <svg class="w-5 h-5 text-amber-600 mt-0.5 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                <path fill-rule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clip-rule="evenodd" />
              </svg>
              <div>
                <h4 class="font-medium text-amber-800">重复提交禁止</h4>
                <p class="text-amber-700 mt-1">已存在您的提交记录</p>
              </div>
            </div>
          </div>

          <!-- 提交表单 -->
          <div class="bg-white rounded-2xl shadow-sm border border-gray-100 p-6">
            <!-- 用户状态标签 -->
            <div v-if="project.userSubmitStatusText" class="mb-6">
              <span :class="[
                'inline-flex items-center px-3 py-1 rounded-full text-sm font-medium',
                project.userSubmitStatusType === 'success' ? 'bg-green-100 text-green-800' :
                project.userSubmitStatusType === 'warning' ? 'bg-amber-100 text-amber-800' :
                project.userSubmitStatusType === 'danger' ? 'bg-red-100 text-red-800' :
                'bg-blue-100 text-blue-800'
              ]">
                {{ project.userSubmitStatusText }}
              </span>
            </div>

            <!-- 动态字段 -->
            <div v-if="expectedFields.length" class="grid gap-4 mb-6">
              <div v-for="field in expectedFields" :key="field.key" class="space-y-2">
                <label class="block text-sm font-medium text-gray-700">
                  {{ field.label || field.key }}
                  <span v-if="field.required" class="text-red-500 ml-1">*</span>
                </label>

                <!-- 美化的下拉框 -->
                <div v-if="(field.type||'text') === 'select' && Array.isArray(field.options)" class="relative">
                  <select v-model="submitter[field.key]"
                          class="custom-select w-full px-4 py-3 border border-gray-200 rounded-xl focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-colors appearance-none bg-white text-gray-800">
                    <option value="" class="text-gray-500">请选择</option>
                    <option v-for="opt in field.options" :key="opt" :value="opt" class="text-gray-800">{{ opt }}</option>
                  </select>
                  <div class="absolute inset-y-0 right-0 flex items-center pr-3 pointer-events-none">
                    <svg class="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
                    </svg>
                  </div>
                </div>

                <input v-else
                       v-model="submitter[field.key]"
                       :placeholder="field.placeholder || ''"
                       class="w-full px-4 py-3 border border-gray-200 rounded-xl focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-colors" />
              </div>
            </div>

            <!-- 文件上传区域 -->
            <div class="space-y-4">
              <label class="block text-sm font-medium text-gray-700">选择文件</label>

              <!-- 可点击的上传区域 -->
              <div class="upload-click-area border-2 border-dashed border-gray-200 rounded-2xl p-8 text-center transition-colors hover:border-blue-400 hover:bg-blue-50/30 cursor-pointer"
                   :class="{
                     'opacity-50 cursor-not-allowed': !project.allowResubmit && latest.exists,
                     'border-blue-400 bg-blue-50/30': isDragging
                   }"
                   @click="triggerFileSelect"
                   @dragenter.prevent="handleDragEnter"
                   @dragover.prevent
                   @dragleave.prevent="handleDragLeave"
                   @drop.prevent="handleDrop">

                <!-- 上传图标 -->
                <div class="w-16 h-16 mx-auto mb-4 rounded-2xl border-2 border-dashed border-gray-300 flex items-center justify-center bg-white"
                     :class="{ 'border-blue-400 bg-blue-50': isDragging }">
                  <svg class="w-8 h-8 text-gray-400" :class="{ 'text-blue-500': isDragging }" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
                  </svg>
                </div>

                <!-- 上传文本 -->
                <div class="space-y-2">
                  <h3 class="text-lg font-medium text-gray-800">
                    {{ fileList.length ? '继续添加文件' : '上传文件' }}
                  </h3>
                  <p class="text-gray-500">
                    将文件拖拽到此处，或 <span class="text-blue-600 font-medium">点击选择文件</span>
                  </p>

                  <!-- 文件限制信息 -->
                  <div class="text-sm text-gray-400 space-y-1">
                    <div>允许类型：{{ (project.allowedFileTypes||[]).join(', ') || '不限' }}</div>
                    <div>大小上限：{{ sizeLimitText }}</div>
                  </div>
                </div>

                <!-- 隐藏的文件输入 -->
                <input ref="fileInput"
                       type="file"
                       :multiple="!!project.allowMultiFiles"
                       :accept="acceptAttr"
                       :disabled="!project.allowResubmit && latest.exists"
                       @change="onFileInputChange"
                       class="hidden" />
              </div>

              <!-- 文件列表 -->
              <div v-if="fileList.length" class="space-y-3">
                <h4 class="text-sm font-medium text-gray-700 flex items-center gap-2">
                  <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                    <path fill-rule="evenodd" d="M3 4a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm0 4a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm0 4a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1z" clip-rule="evenodd" />
                  </svg>
                  已选择文件 ({{ fileList.length }})
                </h4>

                <div class="grid gap-2">
                  <div v-for="(file, index) in fileList" :key="index"
                       class="flex items-center justify-between p-3 bg-gray-50 rounded-xl border border-gray-100 hover:bg-gray-100 transition-colors">
                    <div class="flex items-center gap-3 min-w-0 flex-1">
                      <div class="w-5 h-5 text-blue-500 flex-shrink-0">
                        <svg fill="currentColor" viewBox="0 0 20 20">
                          <path fill-rule="evenodd" d="M4 4a2 2 0 012-2h4.586A2 2 0 0112 2.586L15.414 6A2 2 0 0116 7.414V16a2 2 0 01-2 2H6a2 2 0 01-2-2V4zm2 6a1 1 0 011-1h6a1 1 0 110 2H7a1 1 0 01-1-1zm1 3a1 1 0 100 2h6a1 1 0 100-2H7z" clip-rule="evenodd" />
                        </svg>
                      </div>
                      <div class="min-w-0 flex-1">
                        <p class="text-sm font-medium text-gray-800 truncate">{{ file.name }}</p>
                        <p class="text-xs text-gray-500">{{ formatBytes(file.size) }}</p>
                      </div>
                    </div>
                    <button @click.stop="removeFile(index)"
                            class="w-6 h-6 text-gray-400 hover:text-red-500 transition-colors flex-shrink-0 ml-2 rounded-full hover:bg-red-50 flex items-center justify-center">
                      <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                      </svg>
                    </button>
                  </div>
                </div>
              </div>

              <!-- 操作按钮 -->
              <div class="flex gap-3 pt-4">
                <button :disabled="disableSubmit"
                        @click="submit"
                        class="px-6 py-3 bg-blue-600 text-white rounded-xl hover:bg-blue-700 disabled:bg-gray-300 disabled:cursor-not-allowed transition-colors font-medium flex items-center gap-2">
                  <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
                  </svg>
                  开始上传
                </button>
                <button :disabled="!fileList.length"
                        @click="clearFiles"
                        class="px-6 py-3 border border-gray-200 text-gray-700 rounded-xl hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed transition-colors font-medium">
                  清空文件
                </button>
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- 上传进度对话框 -->
    <div v-if="showUploadDialog" class="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
      <div class="bg-white rounded-2xl p-6 w-full max-w-md shadow-2xl">
        <div class="text-center space-y-4">
          <div class="w-16 h-16 mx-auto bg-blue-100 rounded-full flex items-center justify-center">
            <svg class="w-8 h-8 text-blue-600 animate-spin" fill="none" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
            </svg>
          </div>

          <div>
            <h3 class="text-lg font-semibold text-gray-800 mb-2">正在上传</h3>
            <p class="text-sm text-gray-600">
              {{ currentFileName ? `正在上传第 ${currentFileIndex}/${totalFilesCount} 个：${currentFileName}` : '准备上传...' }}
            </p>
          </div>

          <div class="space-y-2">
            <div class="flex justify-between text-sm text-gray-500">
              <span>速度：{{ speedBps ? (formatBytes(speedBps) + '/s') : '—' }}</span>
              <span>{{ formatBytes(uploadedBytes) }} / {{ formatBytes(totalBytes) }}</span>
            </div>

            <div class="w-full bg-gray-200 rounded-full h-3 overflow-hidden">
              <div class="bg-blue-600 h-full rounded-full transition-all duration-300"
                   :style="{ width: uploadProgress + '%' }"></div>
            </div>

            <p class="text-xs text-gray-500">请勿关闭页面，上传完成后将自动关闭本窗口</p>
          </div>
        </div>
      </div>
    </div>

    <!-- Loading 遮罩 -->
    <div v-if="loading || submitting" class="fixed inset-0 bg-white bg-opacity-75 flex items-center justify-center z-40">
      <div class="text-center">
        <div class="w-12 h-12 mx-auto mb-4 border-4 border-blue-600 border-t-transparent rounded-full animate-spin"></div>
        <p class="text-gray-600">{{ submitting ? '正在提交，请稍候…' : '正在加载…' }}</p>
      </div>
    </div>

    <!-- ElementUI Upload (隐藏，用于功能支持) -->
    <el-upload
        ref="uploadRef"
        class="hidden"
        :multiple="!!(project && project.allowMultiFiles)"
        :auto-upload="false"
        :on-change="onFileChange"
        :on-remove="onFileRemove"
        :file-list="fileList"
        :limit="uploadLimit"
        :disabled="(!project || !project.allowResubmit) && latest.exists"
        :accept="acceptAttr"
        :show-file-list="false"
    />
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import axios from 'axios'
import { watch } from 'vue'
import api from '../../api'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../../stores/auth'

const route = useRoute()
const id = route.params.id
const loading = ref(false)
const project = ref(null)
const expectedFields = ref([])
const submitter = ref({})
const files = ref([])
const fileList = ref([])
const latest = ref({ exists: false })
const querying = ref(false)
const queryTried = ref(false)
const submitting = ref(false)
const uploadProgress = ref(0)
const showUploadDialog = ref(false)
const currentFileName = ref('')
const currentFileIndex = ref(0)
const totalFilesCount = ref(0)
const uploadedBytes = ref(0)
const totalBytes = ref(0)
const speedBps = ref(0)
const versions = ref([])
const mode = ref('submit')
const queryValue = ref('')
const isDragging = ref(false)
const uploadRef = ref(null)
const fileInput = ref(null)

// 计算属性
const headerTitle = computed(() => {
  if (!project.value) return ''
  const projName = project.value?.name || project.value?.title || ''
  return mode.value === 'status' ? `${projName} - 查询状态` : projName
})

// 提交次数展示：优先用 versions 数量，回退 latest.submitCount
const submitCountDisplay = computed(() => {
  const vlen = Array.isArray(versions.value) ? versions.value.length : 0
  if (vlen > 0) return vlen
  return Number(latest.value?.submitCount || 0)
})

const queryLabel = computed(() => {
  const key = project.value?.queryFieldKey
  if (project.value?.queryFieldLabel) return project.value.queryFieldLabel
  if (!key) return '查询字段'
  const f = (expectedFields.value || []).find(x => x.key === key)
  return f?.label || key
})

const endAtText = computed(() => {
  if (!project.value?.endAt) return '未设置'
  return new Date(project.value.endAt).toLocaleString('zh-CN')
})

const isPastDeadline = computed(() => {
  if (!project.value?.endAt) return false
  return new Date() > new Date(project.value.endAt)
})

const uploadLimit = computed(() => {
  return project.value?.allowMultiFiles ? 10 : 1
})

const sizeLimitText = computed(() => {
  // 优先使用字节数字段，兼容旧的 MB 字段
  const bytes = project.value?.fileSizeLimitBytes
  if (bytes && Number.isFinite(bytes)) return formatBytes(bytes)
  const mb = project.value?.maxFileSize
  if (mb && Number.isFinite(mb)) return formatBytes(mb * 1024 * 1024)
  return '不限'
})

const acceptAttr = computed(() => {
  const types = project.value?.allowedFileTypes
  if (!types || !types.length) return ''
  return types.map(t => `.${t}`).join(',')
})

const hasWarnings = computed(() => {
  if (!project.value) return false
  return project.value.offline ||
      (isPastDeadline.value && !project.value.allowOverdue) ||
      (isPastDeadline.value && project.value.allowOverdue) ||
      (project.value.allowResubmit === false) ||
      (!project.value.allowResubmit && latest.value.exists)
})

const disableSubmit = computed(() => {
  if (!project.value) return true
  if (project.value.offline) return true
  if (isPastDeadline.value && !project.value.allowOverdue) return true
  if (!project.value.allowResubmit && latest.value.exists) return true
  if (!fileList.value.length) return true

  // 检查必填字段
  for (const field of expectedFields.value) {
    if (field.required && !submitter.value[field.key]) {
      return true
    }
  }

  return false
})

// 方法
const formatTimestamp = (timestamp) => {
  if (!timestamp) return ''
  return new Date(timestamp).toLocaleString('zh-CN')
}

const formatBytes = (bytes) => {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

const triggerFileSelect = () => {
  if (!project.value?.allowResubmit && latest.value.exists) return
  fileInput.value?.click()
}

const onFileInputChange = (event) => {
  const filesArr = Array.from(event.target.files || [])
  filesArr.forEach(file => {
    // 创建 ElUpload 兼容的文件对象
    const fileObj = {
      name: file.name,
      size: file.size,
      raw: file,
      status: 'ready',
      uid: Date.now() + Math.random()
    }
    fileList.value.push(fileObj)
  })
  // 同步原生 File 列表
  files.value = fileList.value
    .map(f => f?.raw || f)
    .filter(f => f && typeof f.size === 'number')
  event.target.value = '' // 清空input
}

const onFileChange = (file, fileListParam) => {
  fileList.value = fileListParam
  files.value = (fileListParam || [])
    .map(f => f?.raw || f)
    .filter(f => f && typeof f.size === 'number')
}

const onFileRemove = (file, fileListParam) => {
  fileList.value = fileListParam
  files.value = (fileListParam || [])
    .map(f => f?.raw || f)
    .filter(f => f && typeof f.size === 'number')
}

const removeFile = (index) => {
  fileList.value.splice(index, 1)
  files.value = fileList.value.map(f => f.raw)
}

const clearFiles = () => {
  fileList.value = []
  files.value = []
}

// 校验文件类型/大小与必填项
const validateFiles = () => {
  // 必填字段
  for (const field of expectedFields.value || []) {
    if (field.required && !submitter.value?.[field.key]) {
      ElMessage.error(`请填写：${field.label || field.key}`)
      return false
    }
  }

  if (!files.value.length) {
    ElMessage.error('请先选择文件')
    return false
  }

  const whitelist = Array.isArray(project.value?.allowedFileTypes) ? project.value.allowedFileTypes : []
  const sizeLimit = Number(project.value?.fileSizeLimitBytes || 0)
  for (const f of files.value) {
    const ext = (f.name.split('.').pop() || '').toLowerCase()
    if (whitelist.length > 0 && !whitelist.some(t => String(t).toLowerCase() === ext)) {
      ElMessage.error(`文件类型不允许: ${f.name}`)
      return false
    }
    if (sizeLimit > 0 && f.size > sizeLimit) {
      ElMessage.error(`文件过大: ${f.name}`)
      return false
    }
  }
  return true
}

const submit = async () => {
  if (!validateFiles()) return
  // 若不允许重复提交，先检查是否已有记录，避免同页连续提交导致浪费带宽
  try {
    if (!project.value?.allowResubmit) {
      const { data } = await api.latestStatus(id, JSON.stringify(submitter.value||{}))
      if (data?.exists) {
        ElMessage.error('该项目不允许重复提交，已存在您的最新记录')
        return
      }
    }
  } catch (_) {
    // 忽略查询失败，由后端二次校验
  }

  submitting.value = true
  showUploadDialog.value = true

  try {
    // 1) 直传初始化：向后端申请每个文件的 PUT 签名与 key
    const metas = files.value.map(f => ({ name: f.name, type: f.type, size: f.size }))
    const { data } = await api.directInit(id, submitter.value, metas)
    const entries = Array.isArray(data?.entries) ? data.entries : []
    if (entries.length !== files.value.length) throw new Error('直传初始化失败')

    // 2) 顺序上传并更新总体进度
    const total = files.value.reduce((s, f) => s + (f.size || 0), 0) || 1
    let uploadedAll = 0
    totalFilesCount.value = files.value.length
    totalBytes.value = total
    uploadedBytes.value = 0
    speedBps.value = 0
    let lastTickBytes = 0
    let lastTickTime = Date.now()
    for (let i = 0; i < files.value.length; i++) {
      const f = files.value[i]
      const putUrl = entries[i].putUrl
      currentFileIndex.value = i + 1
      currentFileName.value = f.name
      let lastLoaded = 0
      await api.directPut(putUrl, f, (evt) => {
        const loaded = (evt?.loaded || 0)
        // 修正为增量
        const delta = Math.max(0, loaded - lastLoaded)
        lastLoaded = loaded
        const overall = uploadedAll + loaded
        uploadProgress.value = Math.floor((overall / total) * 100)
        uploadedBytes.value = overall
        const now = Date.now()
        const dt = now - lastTickTime
        if (dt >= 200) {
          const dBytes = overall - lastTickBytes
          const inst = dBytes / (dt / 1000)
          // 简单 EMA 平滑
          speedBps.value = speedBps.value > 0 ? (0.8 * speedBps.value + 0.2 * inst) : inst
          lastTickTime = now
          lastTickBytes = overall
        }
      })
      uploadedAll += f.size || 0
      uploadProgress.value = Math.floor((uploadedAll / total) * 100)
      uploadedBytes.value = uploadedAll
    }

    // 3) 通知后端完成并落库
    const keys = entries.map(e => e.key)
    // 上传结束后进入“保存中”阶段，避免 100% 时用户误解已完成
    currentFileName.value = '正在保存...'
    await api.directComplete(id, submitter.value, keys)

    ElMessage.success('提交成功')
    // 切换到状态页并自动查询
    if (project.value && project.value.queryFieldKey) {
      const key = project.value.queryFieldKey
      const val = submitter.value?.[key] || ''
      mode.value = 'status'
      if (val) { queryValue.value = val; await queryStatusByField() } else { await queryStatus() }
    } else {
      mode.value = 'status'
      await queryStatus()
    }
    // 清空选择
    fileList.value = []
    files.value = []

  } catch (error) {
    console.error('Upload failed:', error)
    const msg = error?.response?.data?.message || error?.message || '上传失败'
    ElMessage.error(msg)
  } finally {
    submitting.value = false
    showUploadDialog.value = false
    uploadProgress.value = 0
    currentFileName.value = ''
    currentFileIndex.value = 0
    totalFilesCount.value = 0
    uploadedBytes.value = 0
    totalBytes.value = 0
    speedBps.value = 0
  }
}

const queryStatus = async () => {
  if (!project.value) return
  querying.value = true
  try {
    const { data } = await api.latestStatus(id, JSON.stringify(submitter.value||{}))
    latest.value = {
      exists: !!data.exists,
      createdAt: data.createdAt,
      submitCount: data.submitCount,
      expired: !!data.expired,
      fileNames: Array.isArray(data.fileNames) ? data.fileNames : []
    }
    versions.value = Array.isArray(data.versions) ? data.versions : []
  } catch (e) {
    ElMessage.error('查询失败')
  } finally {
    querying.value = false
  }
}

const queryStatusByField = async () => {
  if (!project.value || !project.value.queryFieldKey || !queryValue.value) return
  querying.value = true
  try {
    const { data } = await api.latestStatus(id, { fieldValue: queryValue.value })
    latest.value = {
      exists: !!data.exists,
      createdAt: data.createdAt,
      submitCount: data.submitCount,
      expired: !!data.expired,
      fileNames: Array.isArray(data.fileNames) ? data.fileNames : []
    }
    versions.value = Array.isArray(data.versions) ? data.versions : []
    queryTried.value = true
  } catch (e) {
    ElMessage.error('查询失败')
  } finally {
    querying.value = false
  }
}

// 模式切换与拖拽相关事件
const switchToStatus = () => { mode.value = 'status'; latest.value = { exists: false }; queryValue.value = '' }
const switchToSubmit = () => { mode.value = 'submit' }

// 拖拽相关事件
const handleDragEnter = () => {
  isDragging.value = true
}

const handleDragLeave = (event) => {
  // 只有当离开整个拖拽区域时才设为false
  if (!event.currentTarget.contains(event.relatedTarget)) {
    isDragging.value = false
  }
}

const handleDrop = (event) => {
  event.preventDefault()
  isDragging.value = false

  if (!project.value?.allowResubmit && latest.value.exists) return

  const files = Array.from(event.dataTransfer.files)
  files.forEach(file => {
    const fileObj = {
      name: file.name,
      size: file.size,
      raw: file,
      status: 'ready',
      uid: Date.now() + Math.random()
    }
    fileList.value.push(fileObj)
  })
  // 同步原生 File 列表
  files.value = fileList.value
    .map(f => f?.raw || f)
    .filter(f => f && typeof f.size === 'number')
}

// 输入变化时重置查询结果展示状态
watch(() => queryValue.value, () => { queryTried.value = false; latest.value = { exists: false }; versions.value = [] })

// 组件挂载
onMounted(async () => {
  loading.value = true
  try {
    const { data } = await api.getProject(id)
    project.value = data
    const fields = Array.isArray(data.expectedUserFields) ? data.expectedUserFields : data.expectedFields
    expectedFields.value = Array.isArray(fields) ? fields : []

    // 设置模式
    const urlParams = new URLSearchParams(window.location.search)
    mode.value = urlParams.get('mode') || 'submit'

    // 如果是提交模式，查询当前状态
    if (mode.value === 'submit') {
      await queryStatus()
    }

  } catch (error) {
    console.error('Failed to load project:', error)
    ElMessage.error('加载项目失败')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
/* 项目名称高亮样式 */
.project-name-highlight {
  background: linear-gradient(180deg, transparent 60%, #fef08a 60%);
  color: #111827;
  font-weight: 700;
  font-size: 21px;
  padding: 0 4px;
}

/* 自定义下拉框样式 */
.custom-select {
  background-image: url("data:image/svg+xml,%3csvg xmlns='http://www.w3.org/2000/svg' fill='none' viewBox='0 0 20 20'%3e%3cpath stroke='%236b7280' stroke-linecap='round' stroke-linejoin='round' stroke-width='1.5' d='M6 8l4 4 4-4'/%3e%3c/svg%3e");
  background-position: right 0.75rem center;
  background-repeat: no-repeat;
  background-size: 1.5em 1.5em;
  padding-right: 3rem;
}

.custom-select:focus {
  background-image: url("data:image/svg+xml,%3csvg xmlns='http://www.w3.org/2000/svg' fill='none' viewBox='0 0 20 20'%3e%3cpath stroke='%233b82f6' stroke-linecap='round' stroke-linejoin='round' stroke-width='1.5' d='M6 8l4 4 4-4'/%3e%3c/svg%3e");
}

/* 可点击上传区域 */
.upload-click-area {
  user-select: none;
}

.upload-click-area:not(.opacity-50):hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
}

/* 确保与 ElementUI 样式兼容 */
:deep(.el-card) {
  border: none;
  box-shadow: none;
}

:deep(.el-form-item__label) {
  font-weight: 500;
}

:deep(.el-upload) {
  width: 100%;
}

:deep(.el-upload-dragger) {
  display: none;
}

/* 动画效果 */
.transition-colors {
  transition-property: color, background-color, border-color, transform, box-shadow;
  transition-timing-function: cubic-bezier(0.4, 0, 0.2, 1);
  transition-duration: 200ms;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .container {
    padding-left: 1rem;
    padding-right: 1rem;
  }

  .grid-cols-1.md\\:grid-cols-2 {
    grid-template-columns: 1fr;
  }

  .project-name-highlight {
    font-size: 18px;
  }
}
</style>
