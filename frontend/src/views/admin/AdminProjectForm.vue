<template>
  <div :class="['page-container', { 'project-form-dark': isDarkTheme }]">
    <div class="project-form-container">

      <div class="page-header">
        <div class="header-left">
          <div class="back-btn" @click="$router.back()">
            <el-icon><Back /></el-icon>
          </div>
          <div class="title-group">
            <h1 class="page-title">{{ isEdit ? '编辑项目：' + form.name : '新建项目' }}</h1>
            <span
                v-if="!isEdit && showUserQuota"
                class="quota-text"
            >
              剩余额度: {{ quotaDisplay.remaining }} / {{ quotaDisplay.limit }}
            </span>
          </div>
        </div>
        <div class="header-right">
          <el-button @click="$router.back()">取消</el-button>
          <el-button type="primary" @click="save" :loading="saving">保存项目</el-button>
        </div>
      </div>

      <div class="content-wrapper">
        <el-form :model="form" label-position="top" class="project-form">

          <!-- 使用模板（仅新建项目时显示） -->
          <div v-if="!isEdit" class="form-section card-style template-bar">
            <div class="form-content" style="display:flex; align-items:center; gap:16px; flex-wrap:wrap;">
              <span class="switch-label" style="white-space:nowrap;">使用模板</span>
              <el-select
                v-model="selectedTemplateId"
                placeholder="不使用模板"
                clearable
                style="width:320px;"
                @change="onTemplateChange"
              >
                <el-option :value="null" label="不使用模板" />
                <el-option v-for="t in usableTemplates" :key="t.id" :value="t.id" :label="t.name" />
              </el-select>
              <span class="form-hint">选择后自动回填可复用字段，名称/时间/大小/扩展名仍需填写</span>
              <el-button
                v-if="isSuperUser"
                type="primary"
                plain
                style="margin-left:auto;"
                @click="openSaveTemplate"
              >保存为模板</el-button>
            </div>
          </div>

          <div class="form-section card-style">
            <div class="section-header">
              <div class="header-text">
                <h3>基本设置</h3>
                <p class="section-desc">配置项目的核心信息、时间限制及文件规则。</p>
              </div>
            </div>

            <div class="form-content">
              <el-form-item label="项目名称" class="required-field full-width">
                <el-input v-model="form.name" placeholder="请输入项目名称" size="large" />
              </el-form-item>

              <div class="grid-row">
                <el-form-item label="开始时间">
                  <el-date-picker v-model="form.startAt" type="datetime" placeholder="选择开始时间" value-format="x" style="width: 100%"/>
                </el-form-item>
                <el-form-item label="截止时间">
                  <el-date-picker v-model="form.endAt" type="datetime" placeholder="选择截止时间" value-format="x" style="width: 100%"/>
                </el-form-item>
              </div>

              <div class="switch-grid">
                <div class="switch-card">
                  <span class="switch-label">可重复提交</span>
                  <el-switch v-model="form.allowResubmit" />
                </div>
                <div class="switch-card">
                  <span class="switch-label">允许多文件</span>
                  <el-switch v-model="form.allowMultiFiles" />
                </div>
                <div class="switch-card">
                  <span class="switch-label">允许逾期提交</span>
                  <el-switch v-model="form.allowOverdue" />
                </div>
                <div class="switch-card">
                  <span class="switch-label">项目状态</span>
                  <el-switch
                      v-model="form.offline"
                      :active-value="false"
                      :inactive-value="true"
                      active-text="在线"
                      inactive-text="已下线"
                      inline-prompt
                      class="status-switch"
                  />
                </div>
              </div>

              <div class="grid-row mt-4">
                <el-form-item label="文件大小上限 (MB)">
                  <el-input v-model.number="fileSizeLimitMB" type="number" placeholder="留空不限制">
                    <template #append>MB</template>
                  </el-input>
                </el-form-item>
                <el-form-item label="文件扩展名白名单">
                  <div style="width:100%;">
                    <el-select
                        v-model="allowedTypes"
                        multiple
                        filterable
                        allow-create
                        default-first-option
                        :disabled="!isSuperUser"
                        :placeholder="isSuperUser ? '留空允许所有类型，或输入 pdf, zip...' : '仅管理员可修改'"
                    >
                      <el-option v-for="t in typeSelectable" :key="t" :value="t" :label="`.${t}`" />
                    </el-select>
                    <div v-if="isSuperUser" class="form-hint form-hint-top">留空表示允许所有文件类型</div>
                  </div>
                </el-form-item>
              </div>
            </div>
          </div>

          <div class="form-section card-style">
            <div class="section-header with-action">
              <div class="header-text">
                <h3>智能识别</h3>
                <p class="section-desc">上传 Excel/CSV 表头自动生成字段配置。</p>
              </div>
              <el-button type="primary" @click="openAutoDetect">
                <el-icon class="el-icon--left"><Document /></el-icon>
                导入表单
              </el-button>
              <input ref="csvInputRef" type="file" accept=".csv,text/csv" class="hidden" @change="onCsvFileChange" />
            </div>

            <div class="form-content">
              <div v-if="autoPreview.headers.length" class="detect-status-bar">
                <el-icon><Check /></el-icon>
                <span>已识别 {{ autoPreview.count }} 行数据，包含字段：</span>
                <div class="tags-wrapper">
                  <el-tag v-for="h in autoPreview.headers" :key="h" size="small" type="info">{{ h }}</el-tag>
                </div>
              </div>

              <div class="advanced-setting-area">
                <el-checkbox v-model="autoRestrict" label="开启提交者限制（仅允许识别名单内的用户提交）" />
                <el-button type="primary" size="small" @click="showAdvanced = !showAdvanced">
                  {{ showAdvanced ? '隐藏高级设置' : '展开高级设置' }}
                </el-button>
              </div>
            </div>
          </div>

          <div class="form-section card-style" v-if="showAdvanced">
            <div class="section-header">
              <div class="header-text">
                <h3>提交限制 (高级)</h3>
                <p class="section-desc">仅在未使用“自动识别”时需手动配置。</p>
              </div>
            </div>

            <div class="form-content">
              <div class="advanced-box">
                <div class="grid-row">
                  <el-form-item label="限制依据字段">
                    <el-select v-model="form.allowedSubmitterKeys" multiple placeholder="选择字段">
                      <el-option v-for="f in expectedFields" :key="f.key" :value="f.key" :label="`${f.label} (${f.key})`" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="名单格式">
                    <el-radio-group v-model="listMode">
                      <el-radio-button label="csv" :disabled="(form.allowedSubmitterKeys||[]).length !== 1">单列逗号</el-radio-button>
                      <el-radio-button label="json">JSON 列表</el-radio-button>
                    </el-radio-group>
                  </el-form-item>
                </div>

                <el-form-item label="白名单数据">
                  <div class="list-editor-container">
                    <div class="editor-toolbar">
                      <el-button size="small" @click="triggerCsvSelect">导入 CSV 填充</el-button>
                      <input ref="csvInputRef2" type="file" accept=".csv,text/csv" class="hidden" @change="onCsvFileChange2" />
                      <span class="editor-hint">支持从 CSV 读取并转换为下方格式</span>
                    </div>
                    <el-input
                        type="textarea"
                        :rows="6"
                        v-model="listText"
                        :placeholder="listPlaceholder"
                        class="code-textarea"
                    />
                  </div>
                </el-form-item>
              </div>
            </div>
          </div>

          <div class="form-section card-style">
            <div class="section-header with-action">
              <div class="header-text">
                <h3>收集字段配置</h3>
                <p class="section-desc">定义用户需要填写或选择的信息。</p>
              </div>
              <el-button type="primary" :icon="Plus" @click="addField">新增字段</el-button>
            </div>

            <div class="form-content">
              <div class="custom-table-wrapper">
                <el-table :data="expectedFields" row-key="_rid" class="modern-table" ref="fieldsTable" empty-text="暂无配置，请点击右上方添加">
                  <el-table-column width="40" align="center">
                    <template #default>
                      <div class="drag-handle-icon">⋮⋮</div>
                    </template>
                  </el-table-column>

                  <el-table-column label="字段标识 (Key)" min-width="140">
                    <template #default="{ row }">
                      <el-input v-model="row.key" placeholder="如: studentNo" class="table-input" />
                    </template>
                  </el-table-column>

                  <el-table-column label="显示名称" min-width="140">
                    <template #default="{ row }">
                      <el-input v-model="row.label" placeholder="如: 学号" class="table-input" />
                    </template>
                  </el-table-column>

                  <el-table-column label="类型" width="110">
                    <template #default="{ row }">
                      <el-select v-model="row.type" class="table-input">
                        <el-option value="text" label="文本" />
                        <el-option value="select" label="下拉" />
                      </el-select>
                    </template>
                  </el-table-column>

                  <el-table-column label="选项配置 / 占位符" min-width="200">
                    <template #default="{ row }">
                      <el-select
                          v-if="row.type==='select'"
                          v-model="row._options"
                          multiple allow-create filterable default-first-option
                          placeholder="回车添加选项"
                          class="table-input"
                      />
                      <el-input v-else v-model="row.placeholder" placeholder="输入框提示文字" class="table-input" />
                    </template>
                  </el-table-column>

                  <el-table-column label="必填" width="70" align="center">
                    <template #default="{ row }">
                      <el-checkbox v-model="row.required" />
                    </template>
                  </el-table-column>

                  <el-table-column label="显示" width="70" align="center">
                    <template #default="{ row }">
                      <el-checkbox v-model="row.display" />
                    </template>
                  </el-table-column>

                  <el-table-column width="60" align="center">
                    <template #default="{ $index }">
                      <el-button link type="danger" :icon="Delete" @click="removeField($index)" />
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </div>
          </div>

          <div class="form-section card-style">
            <div class="section-header">
              <div class="header-text">
                <h3>文件自动命名</h3>
                <p class="section-desc">按字段自动生成存储文件名（保留原扩展名），例如：计 2306 20231234。</p>
              </div>
              <div class="header-right-inline">
                <span class="switch-label-inline">开启</span>
                <el-switch v-model="autoFileNamingEnabled" />
              </div>
            </div>

            <div class="form-content" v-if="autoFileNamingEnabled">
              <div class="grid-row">
                <el-form-item label="拼接符号（Separator）">
                  <el-select v-model="separatorChoice" placeholder="请选择">
                    <el-option :value="' '" label="空格 ( )" />
                    <el-option value="+" label="+ (加号)" />
                    <el-option value="-" label="- (横杠)" />
                    <el-option value="_" label="_ (下划线)" />
                    <el-option value="" label="不拼接 (直接连起来)" />
                    <el-option value="__custom__" label="自定义..." />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="separatorChoice === '__custom__'" label="自定义拼接符号">
                  <el-input v-model="separatorCustom" placeholder="例如：/" />
                </el-form-item>
              </div>

              <div class="section-subtitle">管理员自定义字段</div>
              <div class="custom-table-wrapper">
                <div class="table-actions">
                  <el-button type="primary" :icon="Plus" @click="addAutoNameCustomField">添加自定义字段</el-button>
                  <span class="text-gray">自定义字段不会出现在用户提交表单，可在下方“字段顺序”中引用其 Key。</span>
                </div>
                <el-table :data="autoNameCustomFields" class="modern-table" empty-text="暂无自定义字段">
                  <el-table-column label="Key" min-width="160">
                    <template #default="{ row }">
                      <el-input v-model="row.key" placeholder="如: course" class="table-input" />
                    </template>
                  </el-table-column>
                  <el-table-column label="显示名称" min-width="160">
                    <template #default="{ row }">
                      <el-input v-model="row.label" placeholder="如: 课程" class="table-input" />
                    </template>
                  </el-table-column>
                  <el-table-column label="值（固定）" min-width="200">
                    <template #default="{ row }">
                      <el-input v-model="row.value" placeholder="如: 数据结构" class="table-input" />
                    </template>
                  </el-table-column>
                  <el-table-column width="60" align="center">
                    <template #default="{ $index }">
                      <el-button link type="danger" :icon="Delete" @click="removeAutoNameCustomField($index)" />
                    </template>
                  </el-table-column>
                </el-table>
              </div>

              <div class="section-subtitle">字段顺序</div>
              <div class="custom-table-wrapper">
                <div class="table-actions">
                  <el-button type="primary" :icon="Plus" @click="addAutoNameField">添加字段</el-button>
                  <span class="text-gray">拖拽排序；多文件提交时会在末尾附加原文件名以避免同名覆盖。</span>
                </div>
                <el-table :data="autoNameFields" class="modern-table" ref="nameFieldsTable" empty-text="请添加至少一个字段">
                  <el-table-column width="40" align="center">
                    <template #default>
                      <div class="drag-handle-icon">⋮⋮</div>
                    </template>
                  </el-table-column>
                  <el-table-column label="字段" min-width="220">
                    <template #default="{ row }">
                      <el-select v-model="row.value" placeholder="选择字段" class="table-input">
                        <el-option
                            v-for="f in autoNameFieldOptions"
                            :key="f.key"
                            :value="f.key"
                            :label="`${f.label} (${f.key})${f.source === 'custom' ? ' [自定义]' : ''}`"
                        />
                      </el-select>
                    </template>
                  </el-table-column>
                  <el-table-column width="60" align="center">
                    <template #default="{ $index }">
                      <el-button link type="danger" :icon="Delete" @click="removeAutoNameField($index)" />
                    </template>
                  </el-table-column>
                </el-table>
              </div>

              <div v-if="autoNameSelectFields.length" class="mt-4">
                <div class="section-subtitle">选项别名（用于下拉字段）</div>
                <div v-for="sf in autoNameSelectFields" :key="sf.key" class="alias-block">
                  <div class="alias-title">{{ sf.label }} ({{ sf.key }})</div>
                  <el-table :data="sf._options.map(o => ({ option: o }))" size="small" class="modern-table">
                    <el-table-column prop="option" label="原始选项" />
                    <el-table-column label="别名（留空则使用原值）">
                      <template #default="{ row }">
                        <el-input
                            :model-value="(autoAliases?.[sf.key] && autoAliases[sf.key][row.option] != null) ? autoAliases[sf.key][row.option] : ''"
                            @update:model-value="(v) => { if (!autoAliases[sf.key]) autoAliases[sf.key] = {}; autoAliases[sf.key][row.option] = v }"
                            placeholder="例如：计"
                            size="small"
                        />
                      </template>
                    </el-table-column>
                  </el-table>
                </div>
              </div>

              <div class="preview-row">
                <span class="text-gray">预览：</span>
                <span class="preview-text">{{ autoNamePreview || '（未配置）' }}</span>
              </div>
            </div>
          </div>

          <div class="form-section card-style">
            <div class="section-header">
              <div class="header-text">
                <h3>状态反馈</h3>
                <p class="section-desc">配置用户在查询界面看到的信息。</p>
              </div>
            </div>
            <div class="form-content">
              <div class="form-grid">
                <div class="grid-row-sidebar">
                  <el-form-item label="提示类型">
                    <el-select v-model="form.userSubmitStatusType">
                      <el-option label="普通 (Info)" value="info" />
                      <el-option label="成功 (Success)" value="success" />
                      <el-option label="警告 (Warning)" value="warning" />
                      <el-option label="危险 (Danger)" value="danger" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="提示文案" class="flex-grow">
                    <el-input v-model="form.userSubmitStatusText" placeholder="例如：请务必核对学号..." />
                  </el-form-item>
                </div>
                <div class="grid-row-sidebar" id="main_key">
                  <el-form-item label="查询主键 (用户查询凭证)">
                    <el-select v-model="form.queryFieldKey" placeholder="请选择唯一标识字段 (如：学号)">
                      <el-option v-for="f in expectedFields" :key="f.key" :value="f.key" :label="`${f.label} (${f.key})`" />
                    </el-select>
                  </el-form-item>
                </div>
              </div>
            </div>
          </div>

          <div class="form-section card-style">
            <div class="section-header with-action">
              <div class="header-text">
                <h3>存储路径结构</h3>
                <p class="section-desc">定义文件在服务器上的存储目录层级。</p>
              </div>
              <el-button type="primary" :icon="Plus" @click="addSeg">添加层级</el-button>
            </div>

            <div class="form-content">
              <div class="custom-table-wrapper">
                <el-table :data="pathSegments" class="modern-table" ref="segmentsTable" empty-text="暂无层级">
                  <el-table-column width="40" align="center">
                    <template #default>
                      <div class="drag-handle-icon">⋮⋮</div>
                    </template>
                  </el-table-column>
                  <el-table-column label="层级序号" width="100">
                    <template #default="{ $index }">
                      <span class="step-badge">Level {{ $index + 1 }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="命名来源">
                    <template #default="{ row }">
                      <el-select v-model="row.value" placeholder="选择来源" class="table-input">
                        <el-option :value="'$project'" label="项目名称 (固定)" />
                        <el-option
                            v-for="f in expectedFields"
                            :key="f.key"
                            :value="f.key"
                            :label="`字段: ${f.label} (${f.key})`"
                        />
                      </el-select>
                    </template>
                  </el-table-column>
                  <el-table-column width="60" align="center">
                    <template #default="{ $index }">
                      <el-button link type="danger" :icon="Delete" @click="removeSeg($index)" />
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </div>
          </div>

        </el-form>
      </div>

      <el-dialog v-model="autoDialogVisible" title="确认自动配置" width="600px" class="modern-dialog">
        <div v-if="csvHeaders.length">
          <p class="dialog-desc">已分析 CSV 文件，即将应用以下配置：</p>
          <div class="preview-stats">
            <div class="stat-item">
              <strong>{{ csvPreviewCount }}</strong>
              <span>数据行数</span>
            </div>
            <div class="stat-item">
              <strong>{{ autoFieldPreview.length }}</strong>
              <span>检测字段</span>
            </div>
          </div>

          <el-table :data="autoFieldPreview" height="300" class="dialog-table">
            <el-table-column prop="key" label="字段名" width="140" />
            <el-table-column prop="type" label="类型" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.type==='select'?'warning':''">{{ row.type }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="预览">
              <template #default="{ row }">
                <span v-if="row.type==='select'" class="text-truncate">{{ row.options.join(', ') }}</span>
                <span v-else class="text-gray">{{ row.sample }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div v-else class="empty-state">未检测到有效数据</div>
        <template #footer>
          <el-button @click="autoDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="applyAutoDetect">应用配置</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="csvDialogVisible" title="导入名单映射" width="500px" class="modern-dialog">
        <div class="dialog-content">
          <p class="dialog-desc">请选择 CSV 列与限制字段的对应关系：</p>
          <el-form label-position="top">
            <el-form-item v-for="k in (form.allowedSubmitterKeys||[])" :key="k" :label="`字段: ${k}`">
              <el-select v-model="csvMapping[k]" placeholder="选择对应的 CSV 列">
                <el-option v-for="h in csvHeaders" :key="h" :label="h" :value="h" />
              </el-select>
            </el-form-item>
          </el-form>
        </div>
        <template #footer>
          <el-button @click="csvDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="confirmCsvImport">确认导入</el-button>
        </template>
      </el-dialog>

      <!-- 保存为模板（仅 SUPER，新建项目时） -->
      <el-dialog v-model="saveTemplateDialogVisible" title="保存为模板" width="500px" class="modern-dialog">
        <div class="dialog-content">
          <p class="dialog-desc">将当前填写的可复用字段保存为模板（名称、时间、文件大小、扩展名不会保存）。</p>
          <el-form label-position="top">
            <el-form-item label="模板名称">
              <el-input v-model="newTemplateName" placeholder="如：课程作业收集" />
            </el-form-item>
          </el-form>
        </div>
        <template #footer>
          <el-button @click="saveTemplateDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="confirmSaveTemplate">保存</el-button>
        </template>
      </el-dialog>

    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, nextTick, watch } from 'vue'
import api from '../../api'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../../stores/auth'
import { Document, Setting, User, FolderOpened, Plus, Delete, InfoFilled, Check, Back } from '@element-plus/icons-vue'
import { useThemeStore } from '../../stores/theme'

const route = useRoute();
const router = useRouter();
const theme = useThemeStore()
const isDarkTheme = computed(() => theme.effectiveDark)
const isEdit = computed(()=>!!route.params.id)
const saving = ref(false)

const form = ref({
  name: '',
  allowResubmit: true,
  allowMultiFiles: false,
  allowOverdue: false,
  userSubmitStatusType: 'info',
  userSubmitStatusText: '',
  queryFieldKey: '',
  fileSizeLimitBytes: null,
  startAt: null,
  endAt: null,
  offline: false,
  allowedSubmitterKeys: []
})
const allowedTypes = ref([])
const fileSizeLimitMB = ref(null)

// ===== 模板（仅新建项目）=====
const usableTemplates = ref([])      // 当前用户可用模板
const selectedTemplateId = ref(null) // 选中的模板 id，null=不使用
const saveTemplateDialogVisible = ref(false)
const newTemplateName = ref('')
const typeOptions = ['doc','docx','zip','rar','7z','pdf','txt']
const typeSelectable = computed(()=>{
  const set = new Set([ ...typeOptions, ...allowedTypes.value.filter(Boolean) ])
  return Array.from(set)
})
const expectedFields = ref([])
let _ridSeed = 1
const nextRid = () => `rid_${Date.now()}_${_ridSeed++}`
const pathSegments = ref([]) // 例如: ['$project','studentNo']
const fieldsTable = ref()
const segmentsTable = ref()
const listMode = ref('csv')
const listText = ref('')
// 自动识别/CSV 导入
const csvPurpose = ref('allow') // 'auto' | 'allow'
const csvInputRef = ref(null)
const csvInputRef2 = ref(null)
const csvDialogVisible = ref(false)
const csvFileName = ref('')
const csvHeaders = ref([])
const csvRows = ref([]) // array of objects by header
const csvMapping = ref({}) // key -> header
const csvPreviewCount = computed(() => Array.isArray(csvRows.value) ? csvRows.value.length : 0)
// 自动识别预览
const autoDialogVisible = ref(false)
const autoPreview = ref({ headers: [], count: 0 })
const autoFieldPreview = ref([])
const autoRestrict = ref(false)
const showAdvanced = ref(false)

// 自动命名文件（项目级）
const autoFileNamingEnabled = ref(false)
const autoNameFields = ref([]) // [{ value: 'major' }]
const nameFieldsTable = ref()
const separatorChoice = ref(' ')
const separatorCustom = ref('')
const autoAliases = ref({}) // { fieldKey: { option: alias } }
const autoNameCustomFields = ref([]) // [{ key, label, value }]
const separatorValue = computed(() => separatorChoice.value === '__custom__' ? (separatorCustom.value ?? '') : separatorChoice.value)
const autoNameFieldOptions = computed(() => {
  const out = []
  for (const f of (expectedFields.value || [])) {
    if (!f?.key) continue
    out.push({ key: f.key, label: f.label || f.key, source: 'user' })
  }
  for (const f of (autoNameCustomFields.value || [])) {
    if (!f?.key) continue
    out.push({ key: f.key, label: f.label || f.key, source: 'custom' })
  }
  return out
})
const autoNameSelectFields = computed(() => {
  const keys = new Set(autoNameFields.value.map(x => x.value).filter(Boolean))
  return expectedFields.value.filter(f => keys.has(f.key) && (f.type || 'text') === 'select')
})
const autoNamePreview = computed(() => {
  const sep = separatorValue.value ?? ''
  const parts = autoNameFields.value
      .map(x => x.value)
      .filter(Boolean)
      .map(k => {
        const u = expectedFields.value.find(e => e.key === k)
        if (u) return u.label || k
        const c = (autoNameCustomFields.value || []).find(e => e.key === k)
        return (c && (c.label || c.key)) ? (c.label || c.key) : k
      })
  if (!parts.length) return ''
  return parts.join(sep) + '.ext'
})

function openAutoDetect() { csvPurpose.value = 'auto'; csvInputRef.value?.click() }
function triggerCsvSelect() { csvPurpose.value = 'allow'; (csvInputRef2.value||csvInputRef.value)?.click() }
function onCsvFileChange(e) {
  const file = e?.target?.files?.[0]
  if (!file) return
  csvFileName.value = file.name || 'data.csv'
  const reader = new FileReader()
  reader.onload = () => {
    try {
      const text = decodeBufferToText(reader.result)
      const { headers, rows } = parseCsv(text)
      if (!headers.length) throw new Error('未检测到表头')
      csvHeaders.value = headers
      csvRows.value = rows
      if (csvPurpose.value === 'auto') {
        prepareAutoPreview()
        autoDialogVisible.value = true
      } else {
        // 预填映射：按完全相同或大小写不敏感匹配
        const keys = form.value?.allowedSubmitterKeys || []
        const map = {}
        for (const k of keys) {
          const exact = headers.find(h => h === k)
          const ci = exact || headers.find(h => h.toLowerCase() === k.toLowerCase())
          map[k] = ci || headers[0]
        }
        csvMapping.value = map
        csvDialogVisible.value = true
      }
    } catch (err) {
      ElMessage.error(err?.message || 'CSV 解析失败')
    } finally {
      // 重置 input 以便重复选择同一文件
      e.target.value = ''
    }
  }
  reader.onerror = () => { ElMessage.error('读取文件失败') }
  // 读取为二进制缓冲区，后续自行按编码解码，兼容 GBK/GB18030/UTF-8
  reader.readAsArrayBuffer(file)
}
function onCsvFileChange2(e) { return onCsvFileChange(e) }

function parseCsv(text) {
  // 简易 CSV 解析：支持逗号/分号/Tab 分隔、引号包裹、双引号转义、换行
  // 1) 自动探测分隔符（首行非引号内的分隔符数量最大者）
  function detectDelimiter(src) {
    const candidates = [',', ';', '\t']
    let inQ = false
    const counts = { ',': 0, ';': 0, '\t': 0 }
    for (let i = 0; i < src.length; i++) {
      const ch = src[i]
      if (ch === '\n' || ch === '\r') break
      if (ch === '"') { inQ = !inQ; continue }
      if (!inQ && (ch === ',' || ch === ';' || ch === '\t')) counts[ch]++
    }
    let best = ','
    let max = counts[best]
    for (const c of candidates) { if (counts[c] > max) { best = c; max = counts[c] } }
    return best
  }

  const delim = detectDelimiter(text)
  const rows = []
  let i = 0, inQuotes = false, field = ''
  const out = []
  function pushField() { out.push(field); field = '' }
  function pushRow() { rows.push(out.slice()); out.length = 0 }
  while (i < text.length) {
    const ch = text[i]
    if (inQuotes) {
      if (ch === '"') {
        if (text[i+1] === '"') { field += '"'; i++ } else { inQuotes = false }
      } else { field += ch }
    } else {
      if (ch === '"') { inQuotes = true }
      else if (ch === delim) { pushField() }
      else if (ch === '\n') { pushField(); pushRow() }
      else if (ch === '\r') { /* ignore */ }
      else { field += ch }
    }
    i++
  }
  // last
  pushField(); pushRow()
  // trim whitespace
  const trimmed = rows.map(r => r.map(c => (c??'').trim()))
  // remove empty trailing rows
  while (trimmed.length && trimmed[trimmed.length-1].every(c => c === '')) trimmed.pop()
  if (trimmed.length === 0) return { headers: [], rows: [] }
  const headers = trimmed[0]
  const dataRows = trimmed.slice(1).filter(r => r.some(c => c !== ''))
  const objs = dataRows.map(r => {
    const o = {}
    headers.forEach((h, idx) => { o[h] = r[idx] ?? '' })
    return o
  })
  return { headers, rows: objs }
}

// 将 ArrayBuffer 解码为字符串：优先 UTF-8（BOM 识别），若出现大量替换符则回退 GB18030/GBK
function decodeBufferToText(result) {
  try {
    if (!(result instanceof ArrayBuffer)) return String(result || '')
    let bytes = new Uint8Array(result)
    // BOM 检测
    if (bytes.length >= 3 && bytes[0] === 0xEF && bytes[1] === 0xBB && bytes[2] === 0xBF) {
      bytes = bytes.subarray(3)
      return new TextDecoder('utf-8', { fatal: false }).decode(bytes)
    }
    if (bytes.length >= 2) {
      // UTF-16 LE/BE
      if (bytes[0] === 0xFF && bytes[1] === 0xFE) return new TextDecoder('utf-16le').decode(bytes.subarray(2))
      if (bytes[0] === 0xFE && bytes[1] === 0xFF) return new TextDecoder('utf-16be').decode(bytes.subarray(2))
    }
    // 尝试 UTF-8
    let text = new TextDecoder('utf-8', { fatal: false }).decode(bytes)
    const bad = (text.match(/\uFFFD/g) || []).length
    const ratio = bad / Math.max(1, text.length)
    if (ratio > 0.01) {
      // 尝试 GB18030 或 GBK
      try { text = new TextDecoder('gb18030', { fatal: false }).decode(bytes); return text } catch {}
      try { text = new TextDecoder('gbk', { fatal: false }).decode(bytes); return text } catch {}
    }
    return text
  } catch {
    try { return new TextDecoder('utf-8').decode(new Uint8Array(result)) } catch { return '' }
  }
}

function confirmCsvImport() {
  try {
    const keys = form.value?.allowedSubmitterKeys || []
    if (keys.length === 0) { ElMessage.warning('请先选择限定字段'); return }
    // 校验映射
    for (const k of keys) {
      if (!csvMapping.value[k]) { ElMessage.warning('请为字段 '+k+' 选择对应列'); return }
    }
    if (listMode.value === 'csv') {
      if (keys.length !== 1) { ElMessage.error('逗号分隔模式仅支持单字段'); return }
      const col = csvMapping.value[keys[0]]
      const values = []
      const seen = new Set()
      for (const row of csvRows.value) {
        const v = String(row[col] ?? '').trim()
        if (!v || seen.has(v)) continue
        seen.add(v)
        values.push(v)
      }
      listText.value = values.join(',')
    } else {
      // JSON 数组
      const arr = []
      for (const row of csvRows.value) {
        const obj = {}
        let empty = true
        for (const k of keys) {
          const col = csvMapping.value[k]
          const v = String(row[col] ?? '').trim()
          obj[k] = v
          if (v) empty = false
        }
        if (!empty) arr.push(obj)
      }
      listText.value = JSON.stringify(arr, null, 2)
    }
    csvDialogVisible.value = false
    ElMessage.success('已从 CSV 生成允许名单')
  } catch (e) {
    ElMessage.error('生成失败')
  }
}

function prepareAutoPreview() {
  const headers = csvHeaders.value || []
  const rows = csvRows.value || []
  autoPreview.value = { headers, count: rows.length }
  const fields = []
  for (const h of headers) {
    const values = rows.map(r => String(r[h] ?? '').trim()).filter(Boolean)
    const uniq = Array.from(new Set(values))
    const uniqueCount = uniq.length
    const total = values.length
    const allUnique = uniqueCount >= total * 0.9 && total > 20
    const type = (!allUnique && uniqueCount <= 50) ? 'select' : 'text'
    fields.push({ key: h, type, uniqueCount, options: type==='select' ? uniq.slice(0, 500) : [], sample: values[0] || '' })
  }
  autoFieldPreview.value = fields
}

function applyAutoDetect() {
  try {
    // 1) 生成期望字段
    expectedFields.value = autoFieldPreview.value.map(f => ({
      _rid: nextRid(),
      key: f.key,
      label: f.key,
      placeholder: '',
      required: true,
      display: true,
      type: f.type,
      _options: Array.isArray(f.options) ? f.options : []
    }))
    // 2) 建议查询字段：优先 sid 学号
    const headers = csvHeaders.value || []
    const sidKey = headers.find(h => h.toLowerCase() === 'sid' || h.toLowerCase() === 'studentno' || h.toLowerCase() === '学号')
    form.value.queryFieldKey = sidKey || headers[0] || ''
    // 3) 限制名单
    form.value.allowedSubmitterKeys = headers.slice()
    const rows = csvRows.value || []
    const allowed = rows.map(r => {
      const o = {}
      for (const k of form.value.allowedSubmitterKeys) o[k] = String(r[k] ?? '').trim()
      return o
    })
    // 开启限制开关
    autoRestrict.value = true
    // 将名单缓存到 listText 以便保存时复用（JSON）
    listMode.value = 'json'
    listText.value = JSON.stringify(allowed, null, 2)
    // 关闭对话框并提示
    autoDialogVisible.value = false
    ElMessage.success('已自动识别并填充字段与名单')
  } catch (e) {
    ElMessage.error('自动识别失败')
  }
}
const listPlaceholder = computed(() => {
  const keys = form.value?.allowedSubmitterKeys || []
  if (keys.length === 1 && listMode.value === 'csv') {
    return `请输入 ${keys[0]} 的逗号分隔值，例如：1001,1002,1003`
  }
  const hint = keys.length > 1 ? `对象需包含字段：${keys.join(', ')}` : '建议为字符串数组或对象数组'
  return `请输入 JSON 数组，例如：\n[\n  { "${keys[0]||'field'}": "v1"${keys.length>1?`, "${keys[1]}": "w1"`:''} }\n] (${hint})`
})

// 从模板/项目数据还原表单（含独立 ref）。编辑页 load() 与模板回填共用。
// data 形状与 ProjectResponse / ProjectTemplateResponse 一致（只含可复用字段时其余为 undefined）。
function applyTemplateData(data) {
  if (!data) return
  // 基本开关型可复用字段（不覆盖 name/时间/大小/扩展名）
  form.value.allowResubmit = data.allowResubmit != null ? data.allowResubmit : true
  form.value.allowMultiFiles = data.allowMultiFiles != null ? data.allowMultiFiles : false
  form.value.allowOverdue = !!data.allowOverdue
  form.value.userSubmitStatusType = data.userSubmitStatusType || 'info'
  form.value.userSubmitStatusText = data.userSubmitStatusText || ''
  form.value.queryFieldKey = data.queryFieldKey || ''
  form.value.pathFieldKey = data.pathFieldKey || ''
  // 收集字段
  expectedFields.value = Array.isArray(data.expectedUserFields) ? data.expectedUserFields.map(f => ({
    _rid: nextRid(),
    key: f.key,
    label: f.label,
    placeholder: f.placeholder || '',
    required: !!f.required,
    display: f.display !== false,
    type: f.type || 'text',
    _options: Array.isArray(f.options) ? f.options : []
  })) : []
  // 路径层级
  pathSegments.value = Array.isArray(data.pathSegments)
    ? data.pathSegments.map(v => ({ value: v }))
    : (data.pathFieldKey ? [{ value: data.pathFieldKey }] : [{ value: '$project' }])
  // 提交者限制
  form.value.allowedSubmitterKeys = Array.isArray(data.allowedSubmitterKeys) ? data.allowedSubmitterKeys : []
  const hasSingleKey = (form.value.allowedSubmitterKeys || []).length === 1
  if (data.allowedSubmitterList != null) {
    const v = data.allowedSubmitterList
    if (hasSingleKey && Array.isArray(v) && v.every(x => typeof x === 'string')) {
      listMode.value = 'csv'
      listText.value = v.join(',')
    } else {
      listMode.value = 'json'
      listText.value = typeof v === 'string' ? v : JSON.stringify(v, null, 2)
    }
  } else {
    listMode.value = hasSingleKey ? 'csv' : 'json'
    listText.value = ''
  }
  autoRestrict.value = (Array.isArray(form.value.allowedSubmitterKeys) && form.value.allowedSubmitterKeys.length > 0 && data.allowedSubmitterList != null)
  // 自动命名
  autoFileNamingEnabled.value = !!data.autoFileNamingEnabled
  const cfg = (data.autoFileNamingConfig && typeof data.autoFileNamingConfig === 'object') ? data.autoFileNamingConfig : {}
  autoNameFields.value = Array.isArray(cfg.fields) ? cfg.fields.map(v => ({ value: v })) : []
  if (Array.isArray(cfg.customFields)) {
    autoNameCustomFields.value = cfg.customFields.map(x => ({
      key: x?.key == null ? '' : String(x.key),
      label: x?.label == null ? '' : String(x.label),
      value: x?.value == null ? '' : String(x.value)
    }))
  } else if (cfg.customFields && typeof cfg.customFields === 'object') {
    autoNameCustomFields.value = Object.entries(cfg.customFields).map(([k, v]) => ({ key: String(k), label: String(k), value: v == null ? '' : String(v) }))
  } else {
    autoNameCustomFields.value = []
  }
  const sep = cfg.separator == null ? ' ' : String(cfg.separator)
  const presets = new Set([' ', '+', '-', '_', ''])
  if (presets.has(sep)) { separatorChoice.value = sep; separatorCustom.value = '' }
  else { separatorChoice.value = '__custom__'; separatorCustom.value = sep }
  autoAliases.value = (cfg.aliases && typeof cfg.aliases === 'object') ? cfg.aliases : {}
  for (const sf of autoNameSelectFields.value) {
    if (!autoAliases.value[sf.key] || typeof autoAliases.value[sf.key] !== 'object') autoAliases.value[sf.key] = {}
    for (const opt of (sf._options || [])) {
      if (autoAliases.value[sf.key][opt] == null) autoAliases.value[sf.key][opt] = ''
    }
  }
}

// 重置表单到新建初始状态（切换/取消模板时避免残留）
function resetFormForNew() {
  form.value = {
    name: '', allowResubmit: true, allowMultiFiles: false, allowOverdue: false,
    userSubmitStatusType: 'info', userSubmitStatusText: '', queryFieldKey: '',
    fileSizeLimitBytes: null, startAt: null, endAt: null, offline: false, allowedSubmitterKeys: []
  }
  allowedTypes.value = []
  fileSizeLimitMB.value = null
  expectedFields.value = []
  pathSegments.value = [{ value: '$project' }]
  listMode.value = 'csv'
  listText.value = ''
  autoRestrict.value = false
  showAdvanced.value = false
  autoFileNamingEnabled.value = false
  autoNameFields.value = []
  autoNameCustomFields.value = []
  autoAliases.value = {}
  separatorChoice.value = ' '
  separatorCustom.value = ''
}

function onTemplateChange(templateId) {
  resetFormForNew()
  if (templateId == null) return
  const tpl = usableTemplates.value.find(t => t.id === templateId)
  if (!tpl) return
  applyTemplateData(tpl)
  ElMessage.success(`已套用模板：${tpl.name}`)
}

const openSaveTemplate = () => {
  newTemplateName.value = ''
  saveTemplateDialogVisible.value = true
}

// 收集当前表单的可复用字段为模板 payload（排除 name/时间/大小/扩展名）
function buildTemplatePayload(name) {
  const keys = new Set()
  for (const f of expectedFields.value) {
    if (!f.key || !f.label) throw new Error('期望字段的 Key 和 显示名称 不能为空')
    if (keys.has(f.key)) throw new Error('期望字段 Key 不可重复: ' + f.key)
    keys.add(f.key)
  }
  const segs = pathSegments.value.map(s => s.value).filter(Boolean)
  const expectedUserFields = expectedFields.value.map(f => ({
    key: f.key, label: f.label, placeholder: f.placeholder || '', required: !!f.required,
    display: f.display !== false, type: f.type || 'text',
    options: f.type === 'select' ? (Array.isArray(f._options) ? f._options : []) : undefined
  }))
  let allowedSubmitterList = null
  const akeys = Array.isArray(form.value.allowedSubmitterKeys) ? form.value.allowedSubmitterKeys.filter(Boolean) : []
  if (autoRestrict.value && akeys.length > 0 && listText.value && listText.value.trim()) {
    const parsed = JSON.parse(listText.value || '[]')
    if (!Array.isArray(parsed)) throw new Error('名单必须是 JSON 数组')
    allowedSubmitterList = parsed
  } else if (akeys.length > 0) {
    if (listMode.value === 'csv') {
      allowedSubmitterList = (listText.value || '').split(',').map(s => s.trim()).filter(Boolean)
    } else if (listText.value && listText.value.trim()) {
      allowedSubmitterList = JSON.parse(listText.value || '[]')
    }
  }
  const nameFields = autoNameFields.value.map(x => x.value).filter(Boolean)
  return {
    name,
    expectedUserFields,
    pathFieldKey: form.value.pathFieldKey || null,
    pathSegments: segs.length ? segs : null,
    userSubmitStatusType: form.value.userSubmitStatusType,
    userSubmitStatusText: form.value.userSubmitStatusText,
    queryFieldKey: form.value.queryFieldKey,
    allowedSubmitterKeys: akeys.length ? akeys : null,
    allowedSubmitterList,
    autoFileNamingEnabled: !!autoFileNamingEnabled.value,
    autoFileNamingConfig: {
      fields: nameFields,
      separator: (separatorChoice.value === '__custom__' ? (separatorCustom.value ?? '') : separatorChoice.value) ?? '',
      aliases: autoAliases.value || {},
      customFields: (autoNameCustomFields.value || []).map(f => ({ key: f.key, label: f.label, value: f.value })).filter(f => f.key && f.label && f.value)
    },
    allowResubmit: form.value.allowResubmit,
    allowMultiFiles: form.value.allowMultiFiles,
    allowOverdue: form.value.allowOverdue
  }
}

const confirmSaveTemplate = async () => {
  try {
    if (!newTemplateName.value || !newTemplateName.value.trim()) {
      ElMessage.warning('请输入模板名称'); return
    }
    const payload = buildTemplatePayload(newTemplateName.value.trim())
    await api.adminCreateTemplate(payload)
    ElMessage.success('已保存为模板')
    saveTemplateDialogVisible.value = false
    // 刷新可用模板列表
    try { const { data } = await api.adminListUsableTemplates(); usableTemplates.value = data || [] } catch {}
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '保存模板失败'
    ElMessage.error(msg)
  }
}

const load = async () => {
  if (!isEdit.value) return
  // 管理端使用 admin 接口，避免公共接口的敏感信息裁剪
  const { data } = await api.adminGetProject(route.params.id)
  // 项目特有字段（编辑时直接载入；这些字段不进模板）
  form.value = {
    id: data.id,
    name: data.name,
    fileSizeLimitBytes: data.fileSizeLimitBytes,
    startAt: data.startAt,
    endAt: data.endAt,
    offline: data.offline || false,
    // 可复用字段交给 applyTemplateData 还原（与模板回填共用同一逻辑）
    allowResubmit: true, allowMultiFiles: false, allowOverdue: false,
    userSubmitStatusType: 'info', userSubmitStatusText: '', queryFieldKey: '',
    pathFieldKey: '', allowedSubmitterKeys: []
  }
  allowedTypes.value = data.allowedFileTypes || []
  if (data.fileSizeLimitBytes && Number.isFinite(data.fileSizeLimitBytes)) {
    fileSizeLimitMB.value = +(data.fileSizeLimitBytes / (1024 * 1024)).toFixed(2)
  } else {
    fileSizeLimitMB.value = null
  }
  // 可复用字段还原（编辑页与模板回填共用）
  applyTemplateData(data)
  // 绑定拖拽（初次加载后）
  bindRowDrag()
  bindSegDrag()
  bindAutoNameDrag()
}

// 配额显示（仅新建页且非 SUPER 显示）
const quota = ref(null)
const isSuperUser = computed(() => {
  if (!auth.user) return false
  const role = (auth.user.role || '').toUpperCase()
  return role === 'SUPER' || role === 'ADMIN'
})
const showUserQuota = computed(() => auth.user && !isSuperUser.value && !isEdit.value)
const quotaDisplay = computed(() => {
  const q = quota.value || {}
  const limit = q?.unlimited ? '不限' : (q?.limit ?? '-')
  const remaining = q?.unlimited ? '不限' : (q?.remaining ?? '-')
  const resetDate = q?.resetAt ? new Date(q.resetAt).toLocaleDateString() : '-'
  const totalGB = q?.userTotalQuotaBytes ? Math.round(Number(q.userTotalQuotaBytes)/1024/1024/1024) : 1
  return { limit, remaining, resetDate, totalGB }
})
const loadQuota = async () => { try { const { data } = await api.creationQuota(); quota.value = data } catch {} }
onMounted(load)
watch(expectedFields, () => bindRowDrag(), { deep: true })
watch(pathSegments, () => bindSegDrag(), { deep: true })
watch(autoNameFields, () => bindAutoNameDrag(), { deep: true })
// 当字段改为“非必填”时，自动关闭“显示”
const requiredSnapshot = new Map()
watch(expectedFields, () => {
  const list = expectedFields.value || []
  const alive = new Set()
  for (const f of list) {
    if (!f) continue
    const rid = f._rid
    alive.add(rid)
    const prev = requiredSnapshot.get(rid)
    const now = !!f.required
    if (prev === true && now === false) f.display = false
    requiredSnapshot.set(rid, now)
  }
  // 清理已删除行
  for (const k of requiredSnapshot.keys()) {
    if (!alive.has(k)) requiredSnapshot.delete(k)
  }
}, { deep: true, immediate: true })
watch([expectedFields, autoNameFields], () => {
  // 别名配置跟随字段/选项变化（仅保留仍存在的字段）
  const current = autoAliases.value || {}
  const next = {}
  for (const field of autoNameSelectFields.value) {
    const map = (current[field.key] && typeof current[field.key] === 'object') ? current[field.key] : {}
    const m2 = {}
    for (const opt of (field._options || [])) m2[opt] = map[opt] ?? ''
    next[field.key] = m2
  }
  autoAliases.value = next
}, { deep: true, immediate: true })

const save = async () => {
  if (saving.value) return
  saving.value = true

  try {
    const payload = { ...form.value, allowedFileTypes: allowedTypes.value }
    // ensure numeric timestamps or null
    payload.startAt = payload.startAt ? Number(payload.startAt) : null
    payload.endAt = payload.endAt ? Number(payload.endAt) : null
    // 校验 expectedFields
    const keys = new Set()
    for (const f of expectedFields.value) {
      if (!f.key || !f.label) { throw new Error('期望字段的 Key 和 显示名称 不能为空') }
      if (keys.has(f.key)) { throw new Error('期望字段 Key 不可重复: ' + f.key) }
      keys.add(f.key)
    }
    // 映射字段（包含下拉选项）
    payload.expectedUserFields = expectedFields.value.map(f=>({
      key: f.key,
      label: f.label,
      placeholder: f.placeholder || '',
      required: !!f.required,
      display: f.display !== false,
      type: f.type || 'text',
      options: f.type === 'select' ? (Array.isArray(f._options) ? f._options : []) : undefined
    }))
    // 若为下拉类型，至少一个选项
    for (const f of payload.expectedUserFields) {
      if ((f.type||'text') === 'select') {
        if (!Array.isArray(f.options) || f.options.length === 0) {
          throw new Error(`字段 ${f.label||f.key} 的下拉选项不能为空`)
        }
      }
    }
    // 如果选择了路径字段，但在期望字段里不存在，阻止保存
    if (payload.pathFieldKey && !expectedFields.value.some(f => f.key === payload.pathFieldKey)) {
      throw new Error('上传路径字段必须在期望字段列表中')
    }
    // 路径层级
    const segs = pathSegments.value.map(s=>s.value).filter(Boolean)
    if (segs.length === 0) throw new Error('请至少配置一个上传路径层级')
    // 校验：除 $project 外，其它必须存在于期望字段
    for (const s of segs) {
      if (s !== '$project' && !expectedFields.value.some(f => f.key === s)) {
        throw new Error('路径层级中包含未知字段: ' + s)
      }
    }
    payload.pathSegments = segs

    // 自动命名文件：组装配置
    payload.autoFileNamingEnabled = !!autoFileNamingEnabled.value
    const nameFields = autoNameFields.value.map(x => x.value).filter(Boolean)
    // 校验自定义字段
    const expectedKeySet = new Set(expectedFields.value.map(f => f.key).filter(Boolean))
    const customKeySet = new Set()
    const cleanedCustomFields = []
    for (const f of (autoNameCustomFields.value || [])) {
      const k = String(f?.key ?? '').trim()
      const label = String(f?.label ?? '').trim()
      const val = String(f?.value ?? '').trim()
      if (!k && !label && !val) continue
      if (!k || !label) throw new Error('自定义字段的 Key 和 显示名称 不能为空')
      if (k === '$project') throw new Error('自定义字段 Key 不可为 $project')
      if (!val) throw new Error('自定义字段值不能为空: ' + k)
      if (expectedKeySet.has(k)) throw new Error('自定义字段 Key 与用户字段冲突: ' + k)
      if (customKeySet.has(k)) throw new Error('自定义字段 Key 不可重复: ' + k)
      customKeySet.add(k)
      cleanedCustomFields.push({ key: k, label, value: val })
    }
    const allNameKeys = new Set([ ...expectedKeySet, ...customKeySet ])
    if (payload.autoFileNamingEnabled) {
      if (!nameFields.length) throw new Error('已开启自动命名，但未配置字段顺序')
      for (const k of nameFields) {
        if (!allNameKeys.has(k)) throw new Error('自动命名包含未知字段: ' + k)
      }
    }
    payload.autoFileNamingConfig = {
      fields: nameFields,
      separator: separatorValue.value ?? '',
      aliases: autoAliases.value || {},
      customFields: cleanedCustomFields
    }
    // 提交者限制：若开启自动限制，则直接使用已识别的 keys + JSON 列表
    const akeys = Array.isArray(payload.allowedSubmitterKeys) ? payload.allowedSubmitterKeys.filter(Boolean) : []
    if (autoRestrict.value && akeys.length > 0) {
      try {
        if (!listText.value || !listText.value.trim()) throw new Error('EMPTY')
        const parsed = JSON.parse(listText.value || '[]')
        if (!Array.isArray(parsed)) throw new Error('JSON 列表必须是数组')
        if (parsed.length === 0) throw new Error('EMPTY')
        payload.allowedSubmitterList = parsed
      } catch (e) {
        throw new Error('自动识别的名单无效，请先导入 CSV 并点击“应用到项目”')
      }
    } else {
      // 高级：兼容手动模式
      if (akeys.length > 0) {
        if (listMode.value === 'csv') {
          if (akeys.length !== 1) throw new Error('逗号分隔模式仅支持单个字段')
          const arr = (listText.value || '').split(',').map(s => s.trim()).filter(Boolean)
          payload.allowedSubmitterList = arr
        } else {
          try {
            const parsed = JSON.parse(listText.value || '[]')
            if (!Array.isArray(parsed)) throw new Error('JSON 列表必须是数组')
            payload.allowedSubmitterList = parsed
          } catch (e) {
            throw new Error('允许名单 JSON 解析失败')
          }
        }
      } else {
        payload.allowedSubmitterList = null
        payload.allowedSubmitterKeys = []
      }
    }
    // 将 MB 转换为字节
    if (fileSizeLimitMB.value != null && fileSizeLimitMB.value !== '' && !Number.isNaN(fileSizeLimitMB.value)) {
      const bytes = Math.round(Number(fileSizeLimitMB.value) * 1024 * 1024)
      payload.fileSizeLimitBytes = bytes > 0 ? bytes : null
    } else {
      payload.fileSizeLimitBytes = null
    }
    if (isEdit.value) await api.updateProject(route.params.id, payload)
    else await api.createProject(payload)
    ElMessage.success('已保存')
    router.push('/admin/projects')
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '保存失败，请检查表单'
    ElMessage.error(msg)
  } finally {
    saving.value = false
  }
}

const addField = () => {
  expectedFields.value.push({
    _rid: nextRid(),
    key: '',
    label: '',
    required: false,
    display: true,
    placeholder: '',
    type: 'text',
    _options: []
  })
}
const removeField = (idx) => { expectedFields.value.splice(idx, 1); bindRowDrag() }
const addSeg = () => { pathSegments.value.push({ value: '$project' }); bindSegDrag() }
const removeSeg = (idx) => { pathSegments.value.splice(idx, 1); bindSegDrag() }
const addAutoNameField = () => { autoNameFields.value.push({ value: '' }); bindAutoNameDrag() }
const removeAutoNameField = (idx) => { autoNameFields.value.splice(idx, 1); bindAutoNameDrag() }
const addAutoNameCustomField = () => { autoNameCustomFields.value.push({ key: '', label: '', value: '' }) }
const removeAutoNameCustomField = (idx) => { autoNameCustomFields.value.splice(idx, 1) }
// 上移/下移由拖拽排序代替
const auth = useAuthStore()
// 新建项目时加载可用模板（编辑页不需要）
const loadUsableTemplates = async () => {
  if (isEdit.value) return
  try { const { data } = await api.adminListUsableTemplates(); usableTemplates.value = data || [] } catch {}
}
onMounted(async()=>{ if (!auth.loaded) await auth.loadMe(); await loadQuota(); await loadUsableTemplates() })

function bindRowDrag() {
  nextTick(() => {
    const tableEl = fieldsTable.value?.$el
    const tbody = tableEl?.querySelector('.el-table__body-wrapper tbody')
    if (!tbody) return
    const rows = tbody.querySelectorAll('tr')
    rows.forEach((tr, idx) => {
      tr.setAttribute('draggable', 'true')
      tr.style.cursor = 'move'
      tr.dataset.index = String(idx)
      tr.ondragstart = (ev) => {
        tr.classList.add('dragging')
        ev.dataTransfer?.setData('text/plain', String(idx))
      }
      tr.ondragend = () => {
        tr.classList.remove('dragging')
      }
      tr.ondragover = (ev) => {
        ev.preventDefault()
        tr.classList.add('drag-over')
      }
      tr.ondragleave = () => {
        tr.classList.remove('drag-over')
      }
      tr.ondrop = (ev) => {
        ev.preventDefault()
        tr.classList.remove('drag-over')
        const fromTxt = ev.dataTransfer?.getData('text/plain') || '-1'
        const from = parseInt(fromTxt)
        const to = parseInt(tr.dataset.index || '-1')
        if (!Number.isNaN(from) && !Number.isNaN(to) && from >= 0 && to >= 0 && from !== to) {
          const item = expectedFields.value.splice(from, 1)[0]
          expectedFields.value.splice(to, 0, item)
          bindRowDrag()
        }
      }
    })
  })
}

function bindSegDrag() {
  nextTick(() => {
    const tableEl = segmentsTable.value?.$el
    const tbody = tableEl?.querySelector('.el-table__body-wrapper tbody')
    if (!tbody) return
    const rows = tbody.querySelectorAll('tr')
    rows.forEach((tr, idx) => {
      tr.setAttribute('draggable', 'true')
      tr.style.cursor = 'move'
      tr.dataset.index = String(idx)
      tr.ondragstart = (ev) => {
        tr.classList.add('dragging')
        ev.dataTransfer?.setData('text/plain', String(idx))
      }
      tr.ondragend = () => {
        tr.classList.remove('dragging')
      }
      tr.ondragover = (ev) => {
        ev.preventDefault()
        tr.classList.add('drag-over')
      }
      tr.ondragleave = () => {
        tr.classList.remove('drag-over')
      }
      tr.ondrop = (ev) => {
        ev.preventDefault()
        tr.classList.remove('drag-over')
        const fromTxt = ev.dataTransfer?.getData('text/plain') || '-1'
        const from = parseInt(fromTxt)
        const to = parseInt(tr.dataset.index || '-1')
        if (!Number.isNaN(from) && !Number.isNaN(to) && from >= 0 && to >= 0 && from !== to) {
          const item = pathSegments.value.splice(from, 1)[0]
          pathSegments.value.splice(to, 0, item)
          bindSegDrag()
        }
      }
    })
  })
}

function bindAutoNameDrag() {
  nextTick(() => {
    const tableEl = nameFieldsTable.value?.$el
    const tbody = tableEl?.querySelector('.el-table__body-wrapper tbody')
    if (!tbody) return
    const rows = tbody.querySelectorAll('tr')
    rows.forEach((tr, idx) => {
      tr.setAttribute('draggable', 'true')
      tr.style.cursor = 'move'
      tr.dataset.index = String(idx)
      tr.ondragstart = (ev) => {
        tr.classList.add('dragging')
        ev.dataTransfer?.setData('text/plain', String(idx))
      }
      tr.ondragend = () => {
        tr.classList.remove('dragging')
      }
      tr.ondragover = (ev) => {
        ev.preventDefault()
        tr.classList.add('drag-over')
      }
      tr.ondragleave = () => {
        tr.classList.remove('drag-over')
      }
      tr.ondrop = (ev) => {
        ev.preventDefault()
        tr.classList.remove('drag-over')
        const fromTxt = ev.dataTransfer?.getData('text/plain') || '-1'
        const from = parseInt(fromTxt)
        const to = parseInt(tr.dataset.index || '-1')
        if (!Number.isNaN(from) && !Number.isNaN(to) && from >= 0 && to >= 0 && from !== to) {
          const item = autoNameFields.value.splice(from, 1)[0]
          autoNameFields.value.splice(to, 0, item)
          bindAutoNameDrag()
        }
      }
    })
  })
}
</script>

<style scoped>
.hidden { display: none; }

.header-right-inline {
  display: flex;
  align-items: center;
  gap: 10px;
}
.switch-label-inline {
  color: var(--form-muted);
  font-size: 13px;
}
.section-subtitle {
  margin: 10px 0 6px;
  font-weight: 600;
  color: var(--form-text-secondary);
  font-size: 13px;
}
.table-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.alias-block { margin-top: 10px; }
.alias-title { font-weight: 600; color: var(--form-text-secondary); font-size: 13px; margin: 6px 0; }
.preview-row { margin-top: 10px; }
.preview-text { font-weight: 600; color: var(--form-text); }
.text-gray {
  color: var(--form-soft, var(--kf-muted));
  font-size: 12px;
}
.step-badge {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 2px 8px;
  background: var(--form-badge-bg);
  color: var(--form-badge-text);
  font-size: 12px;
  font-weight: 600;
}
.mt-4 {
  margin-top: 16px;
}
.required-field :deep(.el-form-item__label)::before {
  content: '*';
  color: var(--form-danger);
  margin-right: 4px;
}
.form-hint {
  font-size: 12px;
  color: var(--form-soft);
}
.form-hint-top {
  margin-top: 4px;
}

.page-container {
  --form-bg: #f7f7f8;
  --form-header-bg: rgba(247, 247, 248, 0.88);
  --form-surface: #ffffff;
  --form-surface-muted: #f7f7f8;
  --form-surface-hover: #f0f0f0;
  --form-border: #e5e5e5;
  --form-border-soft: #f2f2f2;
  --form-text: #202123;
  --form-text-secondary: #353740;
  --form-muted: #6e6e80;
  --form-soft: #8e8ea0;
  --form-primary: #1a1a1a;
  --form-primary-hover: #353740;
  --form-primary-contrast: #ffffff;
  --form-accent: #10a37f;
  --form-accent-soft: rgba(16, 163, 127, 0.16);
  --form-danger: #ef4444;
  --form-success-bg: #f0fdf4;
  --form-success-border: #dcfce7;
  --form-success-text: #15803d;
  --form-badge-bg: #f0f0f0;
  --form-badge-text: #4b5563;
  --form-scrollbar: #d1d5db;
  --form-shadow: 0 1px 2px 0 rgba(0,0,0,0.03);
  height: calc(100vh - 65px);
  background-color: var(--form-bg);
  color: var(--form-text-secondary);
  font-family: -apple-system, "system-ui", "Segoe UI", Helvetica, Arial, sans-serif;
  padding-bottom: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.page-container.project-form-dark {
  --form-bg: #141414;
  --form-header-bg: rgba(20, 20, 20, 0.88);
  --form-surface: #1d1e1f;
  --form-surface-muted: #262728;
  --form-surface-hover: #2a2a2c;
  --form-border: #3a3a3c;
  --form-border-soft: #2c2c2e;
  --form-text: #e5eaf3;
  --form-text-secondary: #d8dee8;
  --form-muted: #a3a6ad;
  --form-soft: #7e858f;
  --form-primary: #5594c8;
  --form-primary-hover: #6aa6d8;
  --form-primary-contrast: #ffffff;
  --form-accent: #5bd0c8;
  --form-accent-soft: rgba(91, 208, 200, 0.16);
  --form-danger: #f87171;
  --form-success-bg: rgba(16, 185, 129, 0.12);
  --form-success-border: rgba(16, 185, 129, 0.24);
  --form-success-text: #34d399;
  --form-badge-bg: rgba(91, 208, 200, 0.12);
  --form-badge-text: #9de8e2;
  --form-scrollbar: #4b5563;
  --form-shadow: 0 18px 48px rgba(0, 0, 0, 0.26);
  background: linear-gradient(180deg, #141414 0%, #171817 55%, #141414 100%);
}

.project-form-container {
  width: 100%;
  max-width: 1000px;
  margin: 0 auto;
  padding: 0 20px;

  /* 关键修改：设置为相对定位，作为 Header 绝对定位的参考系 */
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

/* 顶部 Header - 核心修改：改为悬浮毛玻璃状态 */
.page-header {
  position: absolute; /* 悬浮在内容上方 */
  top: 0;
  left: 0;
  width: 100%; /* 占满容器宽度 */
  z-index: 100; /* 确保层级最高 */

  display: flex;
  align-items: center;
  justify-content: space-between;

  /* 调整高度和内边距 */
  height: 72px;
  padding: 0; /* 移除左右 padding，因为容器已有 padding */

  /* 毛玻璃特效核心代码 */
  background: var(--form-header-bg); /* 稍微降低透明度，让下方内容隐约可见 */
  backdrop-filter: blur(12px); /* 高斯模糊效果 */
  -webkit-backdrop-filter: blur(12px); /* Safari 支持 */

  /* 可选：添加一条极细的分割线，增加精致感 */
  border-bottom: 1px solid var(--form-border-soft);

  mask-image: linear-gradient(to bottom, black 85%, transparent 100%);
}

.header-left, .header-right {
  /* 确保内容不会贴边，因为 .page-header 现在 padding 是 0 */
  padding: 0 4px;
}

.content-wrapper {
  flex: 1;
  /* 确保溢出内容可滚动 */
  overflow-y: scroll;

  /* 顶部留白避开毛玻璃 Header */
  padding-top: 88px;
  padding-bottom: 20px;

  /* 方案 A: 标准隐藏 (增加 !important 确保生效) */
  scrollbar-width: none !important; /* Firefox */
  -ms-overflow-style: none !important; /* IE/Edge */
}

/* 方案 B: Webkit 强力隐藏 */
.content-wrapper::-webkit-scrollbar {
  width: 0 !important;
  height: 0 !important;
  display: none !important;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.back-btn {
  cursor: pointer;
  padding: 8px;
  border-radius: 6px;
  color: var(--form-muted);
  transition: background 0.2s;
  background: var(--form-surface);
  border: 1px solid var(--form-border-soft);
}
.back-btn:hover { background: var(--form-surface-hover); }

.title-group {
  display: flex;
  flex-direction: column;
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--form-text);
  line-height: 1.2;
}

.quota-text {
  font-size: 12px;
  color: var(--form-soft);
}

/* 核心修改：内容区域
   设置为 flex: 1 自动占据剩余高度
   并开启 overflow-y: auto 实现内部滚动
   调整 padding-bottom 减少留白
*/
.content-wrapper {
  flex: 1;
  overflow-y: auto; /* 开启垂直滚动 */
  padding-bottom: 20px; /* 减少底部留白 */

  /* 隐藏滚动条样式 (可选) */
  scrollbar-width: thin;
  scrollbar-color: var(--form-scrollbar) transparent;
}

/* Form Section 卡片 */
.form-section {
  margin-bottom: 24px;
}

.card-style {
  background: var(--form-surface);
  border: 1px solid var(--form-border);
  border-radius: 12px;
  box-shadow: var(--form-shadow);
  overflow: hidden; /* 确保子元素不溢出圆角 */
}

/* Section Header 放在卡片内部 */
.section-header {
  padding: 20px 24px 16px; /* 内部 padding */
  border-bottom: 1px solid var(--form-border-soft);
}

.section-header h3 {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 4px 0;
  color: var(--form-text);
}

.section-desc {
  font-size: 13px;
  color: var(--form-muted);
  margin: 0;
}

.section-header.with-action {
  display: flex;
  justify-content: space-between;
  align-items: flex-start; /* 对齐顶部 */
}

/* 表单内容区 */
.form-content {
  padding: 24px;
}

/* 分隔线 - 不需要了 */
.section-divider { display: none; }

/* 表单网格系统 */
.form-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.grid-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

.grid-row-sidebar {
  display: flex;
  gap: 20px;
}
.flex-grow { flex: 1; }

.grid-row-sidebar > .el-form-item:first-child {
  width: 160px;
  flex-shrink: 0;
}

.grid-row-sidebar#main_key > .el-form-item:first-child {
  width: 300px;
  flex-shrink: 0;
}

/* 开关卡片组 */
.switch-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
  margin-top: 8px;
}

.switch-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--form-surface-muted);
  padding: 12px 16px;
  border-radius: 8px;
  border: 1px solid transparent;
  transition: all 0.2s;
}
.switch-card:hover {
  background: var(--form-surface-hover);
}
.switch-label {
  font-size: 14px;
  font-weight: 500;
  color: var(--form-text-secondary);
}

/* 智能识别样式 */
.detect-status-bar {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  background: var(--form-success-bg);
  border: 1px solid var(--form-success-border);
  color: var(--form-success-text);
  padding: 12px;
  border-radius: 6px;
  font-size: 13px;
  margin-bottom: 12px;
}
.tags-wrapper {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.advanced-setting-area {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--form-surface-muted);
  padding: 10px 16px;
  border-radius: 6px;
}

/* 表格样式重写 - Modern */
.custom-table-wrapper {
  border: 1px solid var(--form-border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--form-surface);
}

.modern-table :deep(th.el-table__cell) {
  background-color: var(--form-surface-muted);
  color: var(--form-muted);
  font-weight: 500;
  font-size: 12px;
  text-transform: uppercase;
  border-bottom: 1px solid var(--form-border);
  height: 40px;
  padding: 4px 0;
}

.modern-table :deep(.el-table),
.modern-table :deep(.el-table__inner-wrapper),
.modern-table :deep(.el-table__body-wrapper),
.modern-table :deep(tr),
.modern-table :deep(td.el-table__cell) {
  background-color: var(--form-surface);
}

.modern-table :deep(.el-table__row:hover > td.el-table__cell) {
  background-color: var(--form-surface-muted) !important;
}

.modern-table :deep(td.el-table__cell) {
  border-bottom: 1px solid var(--form-border-soft);
  padding: 12px 0;
}
.modern-table :deep(.el-table__row:last-child td.el-table__cell) {
  border-bottom: none;
}

.table-input :deep(.el-input__wrapper),
.table-input :deep(.el-select__wrapper) {
  box-shadow: none !important;
  background: transparent;
  padding-left: 8px;
}
.table-input :deep(.el-input__wrapper.is-focus),
.table-input :deep(.el-select__wrapper.is-focus) {
  background: var(--form-surface);
  box-shadow: 0 0 0 1px var(--form-accent) inset !important;
}

.drag-handle-icon {
  cursor: grab;
  color: var(--form-border);
  font-weight: 900;
  letter-spacing: -1px;
}
.drag-handle-icon:hover { color: var(--form-muted); }

/* Element Plus 覆盖 */
:deep(.el-form-item__label) {
  font-weight: 500;
  color: var(--form-text-secondary);
  font-size: 13px;
  padding-bottom: 6px;
}
:deep(.el-input__wrapper), :deep(.el-select__wrapper) {
  border-radius: 6px;
  box-shadow: 0 0 0 1px var(--form-border) inset;
  background: var(--form-surface);
  color: var(--form-text);
  transition: all 0.2s;
}
:deep(.el-input__wrapper:hover), :deep(.el-select__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--form-soft) inset;
}
:deep(.el-input__wrapper.is-focus), :deep(.el-select__wrapper.is-focus) {
  box-shadow: 0 0 0 2px var(--form-accent-soft) inset, 0 0 0 1px var(--form-accent) inset;
}
:deep(.el-input__inner),
:deep(.el-select__placeholder),
:deep(.el-date-editor .el-range-input) {
  color: var(--form-text);
}
:deep(.el-input__inner::placeholder),
:deep(.el-select__placeholder.is-transparent),
:deep(.el-date-editor .el-range-input::placeholder) {
  color: var(--form-soft);
}
:deep(.el-input__prefix),
:deep(.el-input__suffix),
:deep(.el-select__caret),
:deep(.el-date-editor .el-range-separator) {
  color: var(--form-soft);
}
:deep(.el-input-group__append) {
  background: var(--form-surface-muted);
  border-color: var(--form-border);
  box-shadow: 0 0 0 1px var(--form-border) inset;
  color: var(--form-muted);
  font-weight: 600;
}
:deep(.el-textarea__inner) {
  background: var(--form-surface);
  box-shadow: 0 0 0 1px var(--form-border) inset;
  color: var(--form-text);
}
:deep(.el-textarea__inner::placeholder) {
  color: var(--form-soft);
}
:deep(.el-checkbox__label),
:deep(.el-radio-button__inner) {
  color: var(--form-text-secondary);
}
:deep(.el-checkbox__inner) {
  background: var(--form-surface);
  border-color: var(--form-border);
}
:deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  background: var(--form-accent);
  border-color: var(--form-accent);
}
:deep(.el-radio-button__inner) {
  background: var(--form-surface);
  border-color: var(--form-border);
}
:deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background: var(--form-accent);
  border-color: var(--form-accent);
  box-shadow: -1px 0 0 0 var(--form-accent);
  color: var(--form-primary-contrast);
}
:deep(.el-button) {
  border-radius: 6px;
  font-weight: 500;
}
:deep(.el-button--primary) {
  background-color: var(--form-primary);
  border-color: var(--form-primary);
  color: var(--form-primary-contrast);
}
:deep(.el-button--primary:hover) {
  background-color: var(--form-primary-hover);
  border-color: var(--form-primary-hover);
}
:deep(.el-button--primary.is-plain) {
  background: var(--form-surface);
  border-color: var(--form-border);
  color: var(--form-accent);
}
:deep(.el-button--primary.is-plain:hover) {
  background: var(--form-accent-soft);
  border-color: var(--form-accent);
  color: var(--form-accent);
}
:deep(.el-button.is-disabled),
:deep(.el-button.is-disabled:hover) {
  background: var(--form-surface-muted);
  border-color: var(--form-border);
  color: var(--form-soft);
  opacity: 0.72;
}
:deep(.status-switch) {
  --el-switch-on-color: #34d399;
  --el-switch-off-color: #f87171;
}

/* 列表编辑器 */
.list-editor-container {
  border: 1px solid var(--form-border);
  border-radius: 6px;
  overflow: hidden;
}
.editor-toolbar {
  background: var(--form-surface-muted);
  padding: 8px 12px;
  border-bottom: 1px solid var(--form-border);
  display: flex;
  align-items: center;
  gap: 12px;
}
.editor-hint { font-size: 12px; color: var(--form-soft); }
.code-textarea :deep(.el-textarea__inner) {
  border: none;
  box-shadow: none;
  padding: 12px;
  font-family: monospace;
  font-size: 13px;
  background: var(--form-surface);
  color: var(--form-text);
}

/* 拖拽反馈 */
:deep(.dragging) {
  background: var(--form-surface-muted) !important;
  opacity: 0.8;
}
:deep(.drag-over) {
  border-bottom: 2px solid var(--form-accent) !important;
}

:global(.modern-dialog.el-dialog),
:global(.modern-dialog .el-dialog) {
  --dialog-surface: #ffffff;
  --dialog-surface-muted: #f7f7f8;
  --dialog-border: #e5e5e5;
  --dialog-text: #202123;
  --dialog-muted: #6e6e80;
  background: var(--dialog-surface);
  border: 1px solid var(--dialog-border);
}
:global(.modern-dialog.el-dialog .el-dialog__header),
:global(.modern-dialog .el-dialog__header),
:global(.modern-dialog.el-dialog .el-dialog__footer),
:global(.modern-dialog .el-dialog__footer) {
  border-color: var(--dialog-border);
}
:global(.modern-dialog.el-dialog .el-dialog__title),
:global(.modern-dialog .el-dialog__title),
:global(.modern-dialog.el-dialog .stat-item strong),
:global(.modern-dialog .stat-item strong),
:global(.modern-dialog.el-dialog .empty-state),
:global(.modern-dialog .empty-state) {
  color: var(--dialog-text);
}
:global(.modern-dialog.el-dialog .dialog-desc),
:global(.modern-dialog .dialog-desc),
:global(.modern-dialog.el-dialog .dialog-content),
:global(.modern-dialog .dialog-content),
:global(.modern-dialog.el-dialog .text-gray),
:global(.modern-dialog .text-gray) {
  color: var(--dialog-muted);
}
:global(.modern-dialog.el-dialog .preview-stats),
:global(.modern-dialog .preview-stats) {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin: 12px 0 16px;
}
:global(.modern-dialog.el-dialog .stat-item),
:global(.modern-dialog .stat-item) {
  background: var(--dialog-surface-muted);
  border: 1px solid var(--dialog-border);
  border-radius: 8px;
  padding: 12px;
}
:global(.modern-dialog.el-dialog .stat-item strong),
:global(.modern-dialog .stat-item strong) {
  display: block;
  font-size: 20px;
  line-height: 1.1;
}
:global(.modern-dialog.el-dialog .stat-item span),
:global(.modern-dialog .stat-item span) {
  color: var(--dialog-muted);
  font-size: 12px;
}
:global(.modern-dialog.el-dialog .empty-state),
:global(.modern-dialog .empty-state) {
  padding: 28px 0;
  text-align: center;
}

:global(html.dark .modern-dialog.el-dialog),
:global(html.dark .modern-dialog .el-dialog) {
  --dialog-surface: #1d1e1f;
  --dialog-surface-muted: #262728;
  --dialog-border: #3a3a3c;
  --dialog-text: #e5eaf3;
  --dialog-muted: #a3a6ad;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.45);
}

/* 响应式 */
@media (max-width: 768px) {
  .page-header { flex-direction: column; align-items: stretch; gap: 12px; }
  .grid-row { grid-template-columns: 1fr; }
  .grid-row-sidebar { flex-direction: column; gap: 0; }
  .section-header { flex-direction: column; gap: 10px; }
}
</style>
