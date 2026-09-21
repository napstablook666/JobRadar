'use client'

import { useState, useEffect, useRef } from 'react'
import { createSSEWithBackoff } from '@/lib/sse'
import { Search, Save, Target, Banknote, Play, Square, LogOut, Check, X, RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import PageHeader from '@/app/components/PageHeader'
import CookieLoginCard from '@/app/components/CookieLoginCard'
import { keywordLinesForDisplay, keywordLinesForStorage, repairUtf8Mojibake } from '@/lib/keyword-lines'

interface LiepinConfig {
  id?: number
  keywords?: string
  searchProfile?: 'A' | 'B' | 'CUSTOM'
  city?: string
  salaryCode?: string
  enableAi?: number
  autoAiDelivery?: number
  aiDeliveryMode?: 'MANUAL' | 'SINGLE_AUTO' | 'BATCH_SHADOW' | 'BATCH_AUTO'
  aiMinScore?: number
  aiReviewMinScore?: number
  aiBatchSize?: number
  aiTimeoutRetryEnabled?: number
  aiTimeoutMaxRetries?: number
  aiTimeoutRetryDelaySeconds?: number
  maxPerRun?: number
  stopAfterMaxPerRun?: number
  maxPerRunRestMinSeconds?: number
  maxPerRunRestMaxSeconds?: number
  minDelaySeconds?: number
  maxDelaySeconds?: number
  searchMinDelaySeconds?: number
  searchMaxDelaySeconds?: number
  pageMinDelaySeconds?: number
  pageMaxDelaySeconds?: number
  detailMinDelaySeconds?: number
  detailMaxDelaySeconds?: number
  rateGuardBatchSize?: number
  batchCooldownMinSeconds?: number
  batchCooldownMaxSeconds?: number
}

interface PageProgressItem {
  keyword: string
  cityCode?: string
  salaryCode?: string
  lastCompletedPage: number
  nextStartPage: number
  updatedAt?: string
}

interface PendingGreeting {
  jobId: number
  companyName: string
  jobTitle: string
  salary: string
  jd?: string
  message: string
  aiAvailable: boolean
  source: 'ai' | 'preset'
}

interface TaskStatusMessage {
  platform?: string
  type?: string
  message: string
  current?: number | null
  total?: number | null
  timestamp?: number | null
}

type TaskState = 'IDLE' | 'WAITING' | 'RUNNING' | 'RESTING' | 'STOPPING' | 'CANCELLED' | 'PAUSED' | 'COMPLETED' | 'FAILED'

type ResumeCursor = {
  keyword?: string | null
  page?: number
}

interface DeliverySummary {
  scanned?: number
  salaryEligible?: number
  salarySkipped?: number
  otherSkipped?: number
  delivered?: number
  retryAttempts?: number
  retryDirectHits?: number
  retryRetained?: number
  retryReasons?: Record<string, number>
  collection?: CollectionSummary
}

interface ApplicationSummary {
  total: number
  chatSuccess: number
  formalApplySuccess: number
  pending: number
}

interface FunnelSummary {
  platform: string
  stages: Record<string, number>
  retryable: number
}

interface CollectionSummary {
  searchPages?: number
  jobsFetched?: number
  jobsPersisted?: number
  jobsInserted?: number
  jobsExisting?: number
  searchFailures?: number
  searchPersistDurationMs?: number
  detailAttempts?: number
  detailHits?: number
  detailMisses?: number
  detailCacheSkips?: number
  detailDurationMs?: number
  lastKeyword?: string
  lastPage?: number
  previewJobs?: string[]
}

interface AiSummary {
  candidateCount?: number
  cacheHits?: number
  screenCalls?: number
  messageCalls?: number
  passed?: number
  review?: number
  skipped?: number
  invalid?: number
  invalidResults?: number
  aiTimeouts?: number
  aiRetryAttempts?: number
  aiRetrySuccesses?: number
  aiRetryable?: number
  buttonFailures?: number
  confirmationFailures?: number
  avgLatencyMs?: number
}

interface BatchRetryStatus {
  active?: boolean
  scope?: 'NONE' | 'AI_TIMEOUT' | 'SUSPENDED'
  round?: number
  initialPending?: number
  remaining?: number
  delivered?: number
  reason?: string
}

interface AdaptivePacingStatus {
  mode?: 'NORMAL' | 'RISK_REST' | 'PROBE' | 'RECOVERY' | 'MANUAL_REQUIRED'
  riskReason?: string
  riskSignalCount?: number
  probeSuccessfulSends?: number
  probeLimit?: number
  recoverySuccessfulSends?: number
  recoveryLimit?: number
  successfulSendCount?: number
}

const EMPTY_AI_SUMMARY: AiSummary = {
  candidateCount: 0,
  cacheHits: 0,
  screenCalls: 0,
  messageCalls: 0,
  passed: 0,
  review: 0,
  skipped: 0,
  invalid: 0,
  invalidResults: 0,
  aiTimeouts: 0,
  aiRetryAttempts: 0,
  aiRetrySuccesses: 0,
  aiRetryable: 0,
  buttonFailures: 0,
  confirmationFailures: 0,
  avgLatencyMs: 0,
}

const EMPTY_DELIVERY_SUMMARY: DeliverySummary = {
  scanned: 0,
  salaryEligible: 0,
  salarySkipped: 0,
  otherSkipped: 0,
  delivered: 0,
  retryAttempts: 0,
  retryDirectHits: 0,
  retryRetained: 0,
  retryReasons: {},
  collection: {
    searchPages: 0,
    jobsFetched: 0,
    jobsPersisted: 0,
    jobsInserted: 0,
    jobsExisting: 0,
    searchFailures: 0,
    searchPersistDurationMs: 0,
    detailAttempts: 0,
    detailHits: 0,
    detailMisses: 0,
    detailCacheSkips: 0,
    detailDurationMs: 0,
    lastKeyword: '',
    lastPage: 0,
    previewJobs: [],
  },
}

const EMPTY_BATCH_RETRY: BatchRetryStatus = {
  active: false,
  round: 0,
  initialPending: 0,
  remaining: 0,
  delivered: 0,
  reason: '',
}

const MIN_MAX_PER_RUN_REST_SECONDS = 30
const MAX_MAX_PER_RUN_REST_SECONDS = 300
const DEFAULT_MAX_PER_RUN_REST_MIN_SECONDS = 30
const DEFAULT_MAX_PER_RUN_REST_MAX_SECONDS = 60

const PROFILE_DEFAULT_KEYWORDS: Record<'A' | 'B', string> = {
  A: '放疗应用工程师\n放疗临床应用\n放疗产品应用\n直线加速器应用\n放疗设备应用\n放疗产品专员',
  B: '病理 IVD\nIVD 应用工程师\n临床协调员\n临床项目协调\n医疗器械产品专员\n医疗运营',
}
const FUNNEL_STAGE_LABELS: Array<[string, string]> = [
  ['collected', '采集'],
  ['detail_link', '详情链接'],
  ['jd', 'JD'],
  ['ai_valid', 'AI有效'],
  ['ai_pass', 'AI通过'],
  ['review', '人工复核'],
  ['button_visible', '按钮可见'],
  ['chat_success', '聊天成功'],
  ['formal_apply_success', '正式申请成功'],
  ['external_pending', '外部待申请'],
]
const normalizeMaxPerRun = (value: number | undefined, fallback = 15): number => {
  const parsed = Number(value)
  return Math.max(0, Number.isFinite(parsed) ? parsed : fallback)
}

const normalizeMaxPerRunRestConfig = (value: LiepinConfig) => {
  const minValue = Number(value.maxPerRunRestMinSeconds)
  const min = Math.min(
    MAX_MAX_PER_RUN_REST_SECONDS,
    Math.max(
      MIN_MAX_PER_RUN_REST_SECONDS,
      Number.isFinite(minValue) ? minValue : DEFAULT_MAX_PER_RUN_REST_MIN_SECONDS,
    ),
  )
  const maxValue = Number(value.maxPerRunRestMaxSeconds)
  const max = Math.min(
    MAX_MAX_PER_RUN_REST_SECONDS,
    Math.max(
      min,
      Number.isFinite(maxValue) ? maxValue : DEFAULT_MAX_PER_RUN_REST_MAX_SECONDS,
    ),
  )
  return {
    stopAfterMaxPerRun: value.stopAfterMaxPerRun === 0 ? 0 : 1,
    maxPerRunRestMinSeconds: min,
    maxPerRunRestMaxSeconds: max,
  }
}

interface LiepinOption {
  id: number
  type: string
  name: string
  code: string
}

interface LiepinOptions {
  city: LiepinOption[]
}

function normalizePageProgress(items: PageProgressItem[]): PageProgressItem[] {
  return items.map((item) => ({
    ...item,
    keyword: repairUtf8Mojibake(item.keyword),
  }))
}

export default function LiepinPage() {
  const [config, setConfig] = useState<LiepinConfig>({
    keywords: '',
    searchProfile: 'A',
    city: '',
    salaryCode: '',
    enableAi: 1,
    autoAiDelivery: 0,
    aiDeliveryMode: 'MANUAL',
    aiMinScore: 70,
    aiReviewMinScore: 60,
    aiBatchSize: 5,
    aiTimeoutRetryEnabled: 1,
    aiTimeoutMaxRetries: 3,
    aiTimeoutRetryDelaySeconds: 3,
    maxPerRun: 15,
    stopAfterMaxPerRun: 1,
    maxPerRunRestMinSeconds: DEFAULT_MAX_PER_RUN_REST_MIN_SECONDS,
    maxPerRunRestMaxSeconds: DEFAULT_MAX_PER_RUN_REST_MAX_SECONDS,
    minDelaySeconds: 20,
    maxDelaySeconds: 35,
    searchMinDelaySeconds: 5,
    searchMaxDelaySeconds: 10,
    pageMinDelaySeconds: 3,
    pageMaxDelaySeconds: 5,
    detailMinDelaySeconds: 5,
    detailMaxDelaySeconds: 8,
    rateGuardBatchSize: 15,
    batchCooldownMinSeconds: 180,
    batchCooldownMaxSeconds: 300,
  })
  const [options, setOptions] = useState<LiepinOptions>({
    city: [],
  })
  const [loading, setLoading] = useState(true)
  const [showSaveDialog, setShowSaveDialog] = useState(false)
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string } | null>(null)
  const [isCustomCity, setIsCustomCity] = useState(false) // 是否手动输入城市
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  const [isDelivering, setIsDelivering] = useState(false)
  const [taskState, setTaskState] = useState<TaskState>('IDLE')
  const [restReason, setRestReason] = useState<string | null>(null)
  const [restUntil, setRestUntil] = useState(0)
  const [restAttempt, setRestAttempt] = useState(0)
  const [resumeCursor, setResumeCursor] = useState<ResumeCursor | null>(null)
  const [restNow, setRestNow] = useState(() => Date.now())
  const [adaptivePacing, setAdaptivePacing] = useState<AdaptivePacingStatus>({ mode: 'NORMAL' })
  const [checkingLogin, setCheckingLogin] = useState(true)
  const [showLogoutDialog, setShowLogoutDialog] = useState(false)
  const [showLogoutResultDialog, setShowLogoutResultDialog] = useState(false)
  const [logoutResult, setLogoutResult] = useState<{ success: boolean; message: string } | null>(null)
  const [taskMessage, setTaskMessage] = useState('尚未启动投递任务')
  const [taskMessageType, setTaskMessageType] = useState('idle')
  const [recentTaskMessages, setRecentTaskMessages] = useState<TaskStatusMessage[]>([
    { type: 'idle', message: '尚未启动投递任务' },
  ])
  const [aiSummary, setAiSummary] = useState<AiSummary>(EMPTY_AI_SUMMARY)
  const [deliverySummary, setDeliverySummary] = useState<DeliverySummary>(EMPTY_DELIVERY_SUMMARY)
  const [batchRetry, setBatchRetry] = useState<BatchRetryStatus>(EMPTY_BATCH_RETRY)
  const [suspendedTaskState, setSuspendedTaskState] = useState<TaskState>('IDLE')
  const [suspendedTaskMessage, setSuspendedTaskMessage] = useState('尚未启动暂缓岗位重试')
  const [suspendedTaskMessageType, setSuspendedTaskMessageType] = useState('idle')
  const [suspendedRecentMessages, setSuspendedRecentMessages] = useState<TaskStatusMessage[]>([
    { type: 'idle', message: '尚未启动暂缓岗位重试' },
  ])
  const [suspendedBatchRetry, setSuspendedBatchRetry] = useState<BatchRetryStatus>(EMPTY_BATCH_RETRY)
  const [suspendedDeliverySummary, setSuspendedDeliverySummary] = useState<DeliverySummary>(EMPTY_DELIVERY_SUMMARY)
  const [suspendedPendingGreeting, setSuspendedPendingGreeting] = useState<PendingGreeting | null>(null)
  const [suspendedRetrying, setSuspendedRetrying] = useState(false)
  const [suspendedRetryCount, setSuspendedRetryCount] = useState(0)
  const statusLogRef = useRef<HTMLDivElement>(null)
  const activeRunIdRef = useRef(0)
  const taskStateRef = useRef<TaskState>('IDLE')
  const suspendedTaskStateRef = useRef<TaskState>('IDLE')
  const [pendingGreeting, setPendingGreeting] = useState<PendingGreeting | null>(null)
  const [pageProgress, setPageProgress] = useState<PageProgressItem[]>([])
  const [applicationSummary, setApplicationSummary] = useState<ApplicationSummary>({
    total: 0,
    chatSuccess: 0,
    formalApplySuccess: 0,
    pending: 0,
  })
  const [funnelSummary, setFunnelSummary] = useState<FunnelSummary>({
    platform: 'liepin',
    stages: {},
    retryable: 0,
  })
  const [formalApplyJobId, setFormalApplyJobId] = useState('')
  const [formalApplyMessage, setFormalApplyMessage] = useState('')
  const [resettingProgress, setResettingProgress] = useState(false)

  useEffect(() => {
    fetchAllData()

    // 确保在客户端环境且 EventSource 可用
    if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
      console.warn('EventSource 不可用，无法连接SSE')
      setCheckingLogin(false)
      return
    }

    const client = createSSEWithBackoff('http://localhost:8888/api/jobs/login-status/stream', {
      onOpen: () => {
        console.log('[SSE] 连接已打开')
      },
      onError: (e, attempt, delay) => {
        console.warn(`[SSE] 连接错误，准备第${attempt}次重连，延迟 ${delay}ms`, e)
        setCheckingLogin(false)
      },
      listeners: [
        {
          name: 'connected',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              setIsLoggedIn(data.liepinLoggedIn || false)
              setCheckingLogin(false)
            } catch (error) {
              console.error('[SSE] 解析连接消息失败:', error)
            }
          },
        },
        {
          name: 'login-status',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              if (data.platform === 'liepin') {
                setIsLoggedIn(data.isLoggedIn)
                setCheckingLogin(false)
              }
            } catch (error) {
              console.error('[SSE] 解析登录状态消息失败:', error)
            }
          },
        },
        { name: 'ping', handler: () => {} },
      ],
    })

    return () => {
      client.close()
    }
    // 配置只在页面首次加载时读取，避免状态更新触发重复请求。
    // eslint-disable-next-line react-hooks/exhaustive-deps -- initial page data fetch only
  }, [])

  const formatStatusTime = (timestamp?: number | null): string => {
    if (!timestamp) return '--:--:--'
    return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false })
  }

  const statusTypeLabel = (type?: string): string => {
    switch (type) {
      case 'progress':
        return '进度'
      case 'success':
        return '成功'
      case 'warning':
        return '提醒'
      case 'error':
        return '错误'
      case 'idle':
        return '待机'
      default:
        return '信息'
    }
  }

  const statusRowClass = (type?: string): string => {
    switch (type) {
      case 'success':
        return 'border-green-200 bg-green-50 text-green-800'
      case 'warning':
        return 'border-amber-200 bg-amber-50 text-amber-800'
      case 'error':
        return 'border-red-200 bg-red-50 text-red-800'
      case 'progress':
        return 'border-indigo-200 bg-indigo-50 text-indigo-800'
      case 'idle':
        return 'border-gray-200 bg-gray-50 text-gray-700'
      default:
        return 'border-blue-100 bg-white text-blue-900'
    }
  }

  const refreshApplicationSummary = async () => {
    try {
      const response = await fetch('http://localhost:8888/api/liepin/application-summary')
      if (!response.ok) throw new Error('读取聊天/正式申请统计失败')
      const data = await response.json()
      setApplicationSummary({
        total: Number(data.total) || 0,
        chatSuccess: Number(data.chatSuccess) || 0,
        formalApplySuccess: Number(data.formalApplySuccess) || 0,
        pending: Number(data.pending) || 0,
      })
    } catch (error) {
      console.warn('[猎聘] 获取聊天/正式申请统计失败:', error)
    }
  }

  const refreshFunnel = async () => {
    try {
      const response = await fetch('http://localhost:8888/api/liepin/funnel')
      if (!response.ok) throw new Error('读取猎聘岗位漏斗失败')
      const data = await response.json()
      const stages = data && typeof data.stages === 'object' && data.stages !== null ? data.stages : {}
      setFunnelSummary({
        platform: typeof data.platform === 'string' ? data.platform : 'liepin',
        stages: Object.fromEntries(Object.entries(stages).map(([key, value]) => [key, Number(value) || 0])),
        retryable: Number(data.retryable) || 0,
      })
    } catch (error) {
      console.warn('[猎聘] 获取岗位漏斗失败:', error)
    }
  }

  const refreshSuspendedRetrySummary = async () => {
    try {
      const response = await fetch('http://localhost:8888/api/liepin/retry-suspended-summary')
      const data = await response.json()
      if (!response.ok || data.success === false) throw new Error(data.message || '读取暂缓岗位统计失败')
      setSuspendedRetryCount(Number(data.total) || 0)
    } catch (error) {
      console.warn('[猎聘] 获取暂缓岗位统计失败:', error)
      setSuspendedRetryCount(0)
    }
  }

  const fetchAllData = async () => {
    try {
      const response = await fetch('http://localhost:8888/api/liepin/config')
      const data = await response.json()

      console.log('Fetched liepin data:', data)

      if (data.config) {
        const normalized = { ...data.config }
        normalized.keywords = keywordLinesForDisplay(data.config.keywords)
        normalized.enableAi = data.config.enableAi === 0 ? 0 : 1
        normalized.autoAiDelivery = data.config.autoAiDelivery === 1 ? 1 : 0
        normalized.aiDeliveryMode = ['MANUAL', 'SINGLE_AUTO', 'BATCH_SHADOW', 'BATCH_AUTO'].includes(data.config.aiDeliveryMode)
          ? data.config.aiDeliveryMode
          : data.config.autoAiDelivery === 1 ? 'SINGLE_AUTO' : 'MANUAL'
        normalized.aiMinScore = Math.max(0, Math.min(100, data.config.aiMinScore ?? 70))
        normalized.aiReviewMinScore = Math.max(0, Math.min(normalized.aiMinScore, data.config.aiReviewMinScore ?? 60))
        normalized.aiBatchSize = Math.max(3, Math.min(10, data.config.aiBatchSize ?? 5))
        normalized.aiTimeoutRetryEnabled = data.config.aiTimeoutRetryEnabled === 0 ? 0 : 1
        normalized.aiTimeoutMaxRetries = Math.max(0, Math.min(10, data.config.aiTimeoutMaxRetries ?? 3))
        normalized.aiTimeoutRetryDelaySeconds = Math.max(0, Math.min(60, data.config.aiTimeoutRetryDelaySeconds ?? 3))
        normalized.maxPerRun = normalizeMaxPerRun(data.config.maxPerRun, 15)
        Object.assign(normalized, normalizeMaxPerRunRestConfig(normalized))
        normalized.minDelaySeconds = Math.max(10, Math.min(300, data.config.minDelaySeconds ?? 20))
        normalized.maxDelaySeconds = Math.max(normalized.minDelaySeconds,
          Math.min(300, data.config.maxDelaySeconds ?? 35))
        normalized.searchMinDelaySeconds = Math.max(5, Math.min(300, data.config.searchMinDelaySeconds ?? 5))
        normalized.searchMaxDelaySeconds = Math.max(normalized.searchMinDelaySeconds,
          Math.min(300, Math.max(5, data.config.searchMaxDelaySeconds ?? 10)))
        normalized.pageMinDelaySeconds = Math.max(3, Math.min(300, data.config.pageMinDelaySeconds ?? 3))
        normalized.pageMaxDelaySeconds = Math.max(normalized.pageMinDelaySeconds,
          Math.min(300, Math.max(3, data.config.pageMaxDelaySeconds ?? 5)))
        normalized.detailMinDelaySeconds = Math.max(5, Math.min(300, data.config.detailMinDelaySeconds ?? 5))
        normalized.detailMaxDelaySeconds = Math.max(normalized.detailMinDelaySeconds,
          Math.min(300, Math.max(5, data.config.detailMaxDelaySeconds ?? 8)))
        normalized.rateGuardBatchSize = Math.max(1, Math.min(50, data.config.rateGuardBatchSize ?? 15))
        normalized.batchCooldownMinSeconds = Math.max(60, Math.min(3600, data.config.batchCooldownMinSeconds ?? 180))
        normalized.batchCooldownMaxSeconds = Math.max(normalized.batchCooldownMinSeconds,
          Math.min(3600, Math.max(60, data.config.batchCooldownMaxSeconds ?? 300)))
        setConfig(normalized)
        // 检查当前城市是否在选项列表中
        if (data.options?.city && data.config.city) {
          const cityExists = data.options.city.some((c: LiepinOption) => c.name === data.config.city || c.code === data.config.city)
          setIsCustomCity(!cityExists)
        }
      }
      if (data.options) {
        setOptions(data.options)
      }
      if (Array.isArray(data.pageProgress)) {
        setPageProgress(normalizePageProgress(data.pageProgress))
      } else {
        setPageProgress([])
      }
      await refreshSuspendedRetrySummary()
      await refreshApplicationSummary()
      await refreshFunnel()
    } catch (error) {
      console.error('Failed to fetch liepin data:', error)
    } finally {
      setLoading(false)
    }
  }

  const refreshPageProgress = async () => {
    try {
      const response = await fetch('http://localhost:8888/api/liepin/config')
      const data = await response.json()
      if (!response.ok) {
        throw new Error(data.message || '读取投递页码进度失败')
      }
      setPageProgress(Array.isArray(data.pageProgress) ? normalizePageProgress(data.pageProgress) : [])
    } catch (error) {
      console.warn('[猎聘] 刷新投递页码进度失败:', error)
    }
  }

  const handleResetPageProgress = async () => {
    try {
      setResettingProgress(true)
      const response = await fetch('http://localhost:8888/api/liepin/page-progress/reset', {
        method: 'POST',
      })
      const data = await response.json()
      if (!response.ok || data.success === false) {
        throw new Error(data.message || '重置失败')
      }
      setPageProgress(Array.isArray(data.pageProgress) ? normalizePageProgress(data.pageProgress) : [])
      setSaveResult({ success: true, message: data.message || '投递页码进度已重置' })
      setShowSaveDialog(true)
    } catch (error) {
      console.error('重置投递页码进度失败:', error)
      setSaveResult({
        success: false,
        message: error instanceof Error ? error.message : '重置投递页码进度失败',
      })
      setShowSaveDialog(true)
    } finally {
      setResettingProgress(false)
    }
  }

  const refreshTaskStatus = async () => {
    try {
      const response = await fetch('http://localhost:8888/api/liepin/status')
      const data = await response.json()
      if (!response.ok || !data.success) {
        throw new Error(data.message || '状态接口返回异常')
      }
      const normalTask = data.normalTask || data
      const suspendedTask = data.suspendedRetryTask || data.suspendedTask || {}
      const responseRunId = Number(normalTask.runId || 0)
      if (responseRunId > activeRunIdRef.current) {
        activeRunIdRef.current = responseRunId
      }
      const nextTaskState = (normalTask.taskState || 'IDLE') as TaskState
      const nextSuspendedTaskState = (suspendedTask.taskState || 'IDLE') as TaskState
      const previousSuspendedTaskState = suspendedTaskStateRef.current
      const shouldRefreshPageProgress = !Boolean(normalTask.isRunning)
        && ['PAUSED', 'COMPLETED', 'FAILED'].includes(nextTaskState)
        && taskStateRef.current !== nextTaskState
      taskStateRef.current = nextTaskState
      suspendedTaskStateRef.current = nextSuspendedTaskState
      setIsDelivering(Boolean(normalTask.isRunning))
      setTaskState(nextTaskState)
      setRestReason(typeof normalTask.restReason === 'string' ? normalTask.restReason : null)
      setRestUntil(typeof normalTask.restUntil === 'number' ? normalTask.restUntil : 0)
      setRestAttempt(typeof normalTask.restAttempt === 'number' ? normalTask.restAttempt : 0)
      setAdaptivePacing({ mode: 'NORMAL', ...(normalTask.adaptivePacing || {}) })
      const cursor = normalTask.resumeCursor
      setResumeCursor(cursor && typeof cursor === 'object' ? cursor as ResumeCursor : null)
      setRestNow(Date.now())
      if (normalTask.message) setTaskMessage(normalTask.message)
      if (normalTask.messageType) setTaskMessageType(normalTask.messageType)
      const serverMessages = Array.isArray(normalTask.recentMessages)
        ? normalTask.recentMessages.filter((item: TaskStatusMessage) => item && typeof item.message === 'string')
        : []
      if (serverMessages.length > 0) {
        setRecentTaskMessages(serverMessages.slice(-8))
      } else if (normalTask.message) {
        setRecentTaskMessages([
          {
            type: normalTask.messageType || 'info',
            message: normalTask.message,
            timestamp: normalTask.messageAt,
          },
        ])
      }
      setAiSummary({ ...EMPTY_AI_SUMMARY, ...(normalTask.aiSummary || {}) })
      const nextDeliverySummary = normalTask.deliverySummary || {}
      setDeliverySummary({
        ...EMPTY_DELIVERY_SUMMARY,
        ...nextDeliverySummary,
        collection: {
          ...(EMPTY_DELIVERY_SUMMARY.collection || {}),
          ...(nextDeliverySummary.collection || {}),
        },
      })
      setBatchRetry({ ...EMPTY_BATCH_RETRY, ...(normalTask.batchRetry || {}) })
      setPendingGreeting(normalTask.pendingGreeting || null)
      setSuspendedTaskState(nextSuspendedTaskState)
      if (suspendedTask.message) setSuspendedTaskMessage(suspendedTask.message)
      if (suspendedTask.messageType) setSuspendedTaskMessageType(suspendedTask.messageType)
      const suspendedMessages = Array.isArray(suspendedTask.recentMessages)
        ? suspendedTask.recentMessages.filter((item: TaskStatusMessage) => item && typeof item.message === 'string')
        : []
      if (suspendedMessages.length > 0) {
        setSuspendedRecentMessages(suspendedMessages.slice(-8))
      }
      setSuspendedBatchRetry({ ...EMPTY_BATCH_RETRY, ...(suspendedTask.batchRetry || {}) })
      setSuspendedDeliverySummary({
        ...EMPTY_DELIVERY_SUMMARY,
        ...(suspendedTask.deliverySummary || {}),
      })
      setSuspendedPendingGreeting(suspendedTask.pendingGreeting || null)
      if (!Boolean(suspendedTask.isRunning)
        && ['PAUSED', 'COMPLETED', 'FAILED'].includes(nextSuspendedTaskState)
        && previousSuspendedTaskState !== nextSuspendedTaskState) {
        await refreshSuspendedRetrySummary()
      }
      if (shouldRefreshPageProgress) {
        await refreshPageProgress()
      }
      return data
    } catch (error) {
      console.error('[猎聘] 获取投递状态失败:', error)
      setTaskMessage('无法读取投递状态，请检查后端服务。')
      setTaskMessageType('error')
      return null
    }
  }

  useEffect(() => {
    let disposed = false
    let timer: number | undefined

    const poll = async () => {
      const data = await refreshTaskStatus()
      await refreshApplicationSummary()
      await refreshFunnel()
      if (disposed) return
      const normalTask = data?.normalTask || data
      const suspendedTask = data?.suspendedRetryTask || data?.suspendedTask
      const anyRunning = Boolean(data?.isAnyRunning || normalTask?.isRunning || suspendedTask?.isRunning)
      const stopping = normalTask?.taskState === 'STOPPING' || suspendedTask?.taskState === 'STOPPING'
      timer = window.setTimeout(poll, stopping ? 250 : anyRunning ? 1000 : 3000)
    }

    poll()
    return () => {
      disposed = true
      if (timer !== undefined) window.clearTimeout(timer)
    }
    // 状态轮询只在页面生命周期内运行一次
    // eslint-disable-next-line react-hooks/exhaustive-deps -- polling setup is mount-only
  }, [])

  useEffect(() => {
    const log = statusLogRef.current
    if (log) log.scrollTop = log.scrollHeight
  }, [recentTaskMessages])

  useEffect(() => {
    if (taskState !== 'RESTING') return
    setRestNow(Date.now())
    const timer = window.setInterval(() => setRestNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [taskState])
  const markFormalApplication = async () => {
    const jobId = Number(formalApplyJobId)
    if (!Number.isInteger(jobId) || jobId <= 0) {
      setFormalApplyMessage('请输入有效的岗位 ID')
      return
    }
    try {
      const response = await fetch(`http://localhost:8888/api/liepin/jobs/${jobId}/formal-apply-success`, {
        method: 'POST',
      })
      const data = await response.json()
      if (!response.ok || data.success === false) throw new Error(data.message || '标记正式申请失败')
      setFormalApplyMessage('已标记正式申请成功')
      setFormalApplyJobId('')
      await refreshApplicationSummary()
      await refreshFunnel()
    } catch (error) {
      setFormalApplyMessage(error instanceof Error ? error.message : '标记正式申请失败')
    }
  }

  const handleSave = async () => {
    try {
      const payload = {
        ...config,
        keywords: keywordLinesForStorage(config.keywords),
        maxPerRun: normalizeMaxPerRun(config.maxPerRun),
        ...normalizeMaxPerRunRestConfig(config),
        minDelaySeconds: Math.max(10, Math.min(300, Number(config.minDelaySeconds) || 20)),
        maxDelaySeconds: Math.max(
          Math.max(10, Math.min(300, Number(config.minDelaySeconds) || 20)),
          Math.min(300, Number(config.maxDelaySeconds) || 35),
        ),
        searchMinDelaySeconds: Math.max(5, Math.min(300, Number(config.searchMinDelaySeconds) || 5)),
        searchMaxDelaySeconds: Math.max(
          Math.max(5, Math.min(300, Number(config.searchMinDelaySeconds) || 5)),
          Math.min(300, Number(config.searchMaxDelaySeconds) || 10),
        ),
        pageMinDelaySeconds: Math.max(3, Math.min(300, Number(config.pageMinDelaySeconds) || 3)),
        pageMaxDelaySeconds: Math.max(
          Math.max(3, Math.min(300, Number(config.pageMinDelaySeconds) || 3)),
          Math.min(300, Number(config.pageMaxDelaySeconds) || 5),
        ),
        detailMinDelaySeconds: Math.max(5, Math.min(300, Number(config.detailMinDelaySeconds) || 5)),
        detailMaxDelaySeconds: Math.max(
          Math.max(5, Math.min(300, Number(config.detailMinDelaySeconds) || 5)),
          Math.min(300, Number(config.detailMaxDelaySeconds) || 8),
        ),
        rateGuardBatchSize: Math.max(1, Math.min(50, Number(config.rateGuardBatchSize) || 15)),
        batchCooldownMinSeconds: Math.max(60, Math.min(3600, Number(config.batchCooldownMinSeconds) || 180)),
        batchCooldownMaxSeconds: Math.max(
          Math.max(60, Math.min(3600, Number(config.batchCooldownMinSeconds) || 180)),
          Math.min(3600, Number(config.batchCooldownMaxSeconds) || 300),
        ),
      }
      const response = await fetch('http://localhost:8888/api/liepin/config', {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
      })

      if (response.ok) {
        fetchAllData()
        setSaveResult({ success: true, message: '保存成功，配置已更新。Cookie 请在登录卡片中手动获取。' })
        setShowSaveDialog(true)
      } else {
        console.warn('保存失败：后端返回非 2xx 状态')
        setSaveResult({ success: false, message: '保存失败：后端返回异常状态。' })
        setShowSaveDialog(true)
      }
    } catch (error) {
      console.error('Failed to save config:', error)
      setSaveResult({ success: false, message: '保存失败：网络或服务异常。' })
      setShowSaveDialog(true)
    }
  }

  const handleConfirmGreeting = async (
    action: 'ai' | 'preset' | 'skip',
    targetGreeting: PendingGreeting | null = pendingGreeting,
  ) => {
    if (!targetGreeting) return
    try {
      const response = await fetch('http://localhost:8888/api/liepin/confirm', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ jobId: targetGreeting.jobId, action }),
      })
      const data = await response.json()
      if (targetGreeting === suspendedPendingGreeting) {
        setSuspendedTaskMessage(data.message || (data.success ? '已确认，继续处理暂缓岗位。' : '确认失败。'))
        setSuspendedTaskMessageType(data.success ? 'success' : 'error')
        if (data.success) setSuspendedPendingGreeting(null)
      } else {
        setTaskMessage(data.message || (data.success ? '已确认，继续处理岗位。' : '确认失败。'))
        setTaskMessageType(data.success ? 'success' : 'error')
        if (data.success) setPendingGreeting(null)
      }
    } catch (error) {
      console.error('[猎聘] 确认发送失败:', error)
      if (targetGreeting === suspendedPendingGreeting) {
        setSuspendedTaskMessage('确认发送失败：网络或服务异常。')
        setSuspendedTaskMessageType('error')
      } else {
        setTaskMessage('确认发送失败：网络或服务异常。')
        setTaskMessageType('error')
      }
    }
  }

  const handleStartDelivery = async () => {
    try {
      setIsDelivering(true)
      setTaskMessage('正在启动投递任务...')
      setTaskMessageType('info')
      setRecentTaskMessages([{ type: 'info', message: '正在启动投递任务...' }])
      setAiSummary({ ...EMPTY_AI_SUMMARY })
      setDeliverySummary({ ...EMPTY_DELIVERY_SUMMARY })
      setBatchRetry({ ...EMPTY_BATCH_RETRY })
      setPendingGreeting(null)
      const response = await fetch('http://localhost:8888/api/liepin/start', {
        method: 'POST',
      })
      const data = await response.json()

      if (!data.success) {
        setIsDelivering(false)
        setTaskMessage(data.message || '启动投递失败。')
        setTaskMessageType('error')
        return
      }
      const startedRunId = Number(data.runId || 0)
      if (startedRunId > activeRunIdRef.current) {
        activeRunIdRef.current = startedRunId
      }
      setTaskMessage(data.message || '投递任务已启动。')
      setTaskMessageType('info')
      await refreshTaskStatus()
    } catch (error) {
      console.error('Failed to start delivery:', error)
      setIsDelivering(false)
      setTaskMessage('启动投递失败：网络或服务异常。')
      setTaskMessageType('error')
    }
  }

  const handleStopDelivery = async () => {
    try {
      const response = await fetch('http://localhost:8888/api/liepin/stop', {
        method: 'POST',
      })
      const data = await response.json()

      if (data.success) {
        setTaskState('STOPPING')
        setTaskMessage(data.message || '正在停止投递任务...')
        setTaskMessageType('info')
      } else {
        console.warn('停止失败：', data.message)
        setTaskMessage(data.message || '停止投递失败。')
        setTaskMessageType('error')
      }
    } catch (error) {
      console.error('Failed to stop delivery:', error)
      setTaskMessage('停止投递失败：网络或服务异常。')
      setTaskMessageType('error')
    }
  }

  const handleStartSuspendedRetry = async () => {
    if (suspendedRetrying || suspendedTaskState === 'WAITING' || suspendedTaskState === 'RUNNING' || suspendedTaskState === 'STOPPING') return
    try {
      setSuspendedRetrying(true)
      setSuspendedTaskState('WAITING')
      setSuspendedTaskMessage('正在启动暂缓岗位重试...')
      setSuspendedTaskMessageType('info')
      const response = await fetch('http://localhost:8888/api/liepin/retry-suspended-batch', { method: 'POST' })
      const data = await response.json()
      if (!response.ok || !data.success) {
        throw new Error(data.message || '启动暂缓岗位重试失败')
      }
      setSuspendedTaskMessage(data.message || '暂缓岗位重试已启动。')
      setSuspendedTaskMessageType('info')
      await refreshTaskStatus()
    } catch (error) {
      console.error('[猎聘] 启动暂缓岗位重试失败:', error)
      setSuspendedTaskState('FAILED')
      setSuspendedTaskMessage(error instanceof Error ? error.message : '启动暂缓岗位重试失败')
      setSuspendedTaskMessageType('error')
    } finally {
      setSuspendedRetrying(false)
      await refreshSuspendedRetrySummary()
    }
  }

  const handleStopSuspendedRetry = async () => {
    try {
      const response = await fetch('http://localhost:8888/api/liepin/retry-suspended-batch/stop', { method: 'POST' })
      const data = await response.json()
      if (data.success) {
        setSuspendedTaskState('STOPPING')
        setSuspendedTaskMessage(data.message || '正在停止暂缓岗位重试...')
        setSuspendedTaskMessageType('info')
      } else {
        setSuspendedTaskMessage(data.message || '停止暂缓岗位重试失败。')
        setSuspendedTaskMessageType('error')
      }
    } catch (error) {
      console.error('[猎聘] 停止暂缓岗位重试失败:', error)
      setSuspendedTaskMessage('停止暂缓岗位重试失败：网络或服务异常。')
      setSuspendedTaskMessageType('error')
    }
  }

  const triggerLogout = async () => {
    try {
      const response = await fetch('http://localhost:8888/api/liepin/logout', { method: 'POST' })
      const data = await response.json()
      if (data.success) {
        setIsLoggedIn(false)
        setIsDelivering(false)
        console.info('已退出登录，数据库Cookie已置空')
        setLogoutResult({ success: true, message: '已退出登录，Cookie已清空。' })
        setShowLogoutResultDialog(true)
      } else {
        console.warn('退出登录失败：', data.message)
        setLogoutResult({ success: false, message: `退出登录失败：${data.message || '服务返回异常。'}` })
        setShowLogoutResultDialog(true)
      }
    } catch (error) {
      console.error('Failed to logout:', error)
      setLogoutResult({ success: false, message: '退出登录失败：网络或服务异常。' })
      setShowLogoutResultDialog(true)
    }
  }

  if (loading) {
    return <div className="flex items-center justify-center h-screen">加载中...</div>
  }

  const restReasonLabel = (reason: string | null): string => {
    switch (reason) {
      case 'max_per_run': return '已达到本轮成功投递上限'
      case 'risk_signal': return adaptivePacing.riskReason ? `风控信号：${adaptivePacing.riskReason}` : '检测到平台风控信号'
      default: return reason ? `触发原因：${reason}` : '任务正在智能休息'
    }
  }

  const adaptiveMode = adaptivePacing.mode || 'NORMAL'
  const adaptiveModeLabel = (() => {
    switch (adaptiveMode) {
      case 'RISK_REST': return '风控长休息'
      case 'PROBE': return '风控探测'
      case 'RECOVERY': return '恢复节奏'
      case 'MANUAL_REQUIRED': return '需要人工处理'
      default: return '正常节奏'
    }
  })()
  const adaptiveModeClass = (() => {
    switch (adaptiveMode) {
      case 'MANUAL_REQUIRED': return 'border-red-300 bg-red-50 text-red-950'
      case 'RISK_REST': return 'border-amber-300 bg-amber-50 text-amber-950'
      case 'PROBE': return 'border-orange-200 bg-orange-50 text-orange-950'
      case 'RECOVERY': return 'border-blue-200 bg-blue-50 text-blue-950'
      default: return 'border-emerald-200 bg-emerald-50 text-emerald-950'
    }
  })()
  const adaptiveProgress = adaptiveMode === 'PROBE'
    ? `探测成功 ${adaptivePacing.probeSuccessfulSends ?? 0}/${adaptivePacing.probeLimit ?? 3}`
    : adaptiveMode === 'RECOVERY'
      ? `恢复成功 ${adaptivePacing.recoverySuccessfulSends ?? 0}/${adaptivePacing.recoveryLimit ?? 8}`
      : `账户累计成功发送 ${adaptivePacing.successfulSendCount ?? 0} 次`
  const adaptiveRiskReason = adaptivePacing.riskReason?.trim()
  const isRiskRest = adaptiveMode === 'RISK_REST' || restReason === 'risk_signal'

  const restRemainingSeconds = Math.max(0, Math.ceil((restUntil - restNow) / 1000))
  const restCountdown = restRemainingSeconds >= 3600
    ? `${Math.floor(restRemainingSeconds / 3600)}小时${Math.floor((restRemainingSeconds % 3600) / 60)}分钟`
    : restRemainingSeconds >= 60
      ? `${Math.floor(restRemainingSeconds / 60)}分${restRemainingSeconds % 60}秒`
      : `${restRemainingSeconds}秒`

  const collectionSummary: CollectionSummary = {
    ...(EMPTY_DELIVERY_SUMMARY.collection || {}),
    ...(deliverySummary.collection || {}),
  }
  const detailAverageMs = collectionSummary.detailAttempts
    ? Math.round((collectionSummary.detailDurationMs ?? 0) / collectionSummary.detailAttempts)
    : 0
  const aiDeliveryEligible = (aiSummary.passed ?? 0) + (aiSummary.review ?? 0)

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<Search className="text-2xl" />}
        title="猎聘配置"
        subtitle="配置猎聘平台的求职参数"
        iconClass="text-white"
        accentBgClass="bg-purple-500"
        actions={
          <div className="flex items-center gap-2">
            {checkingLogin ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <Play className="mr-1" /> 检查登录中...
              </Button>
            ) : !isLoggedIn ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <Play className="mr-1" /> 请先登录猎聘
              </Button>
            ) : isDelivering ? (
              <Button onClick={handleStopDelivery} disabled={taskState === 'STOPPING'} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 disabled:opacity-70 disabled:cursor-wait text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
                <Square className="mr-1" /> {taskState === 'STOPPING' ? '停止中...' : '停止投递'}
              </Button>
            ) : (
              <div className="flex items-center gap-2">
                <Button onClick={handleStartDelivery} size="sm" className="rounded-full bg-gradient-to-r from-teal-500 to-green-500 hover:from-teal-600 hover:to-green-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
                  <Play className="mr-1" /> 开始投递
                </Button>
              </div>
            )}
            <Button onClick={() => setShowLogoutDialog(true)} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-pink-500 hover:from-red-600 hover:to-pink-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
              <LogOut className="mr-1" /> 退出登录
            </Button>
            <Button onClick={handleSave} size="sm" className="rounded-full bg-gradient-to-r from-blue-500 to-indigo-500 hover:from-blue-600 hover:to-indigo-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
              <Save className="mr-1" /> 保存配置
            </Button>
          </div>
        }
      />

      <div
        role="status"
        className={`rounded-lg border px-4 py-3 text-sm ${
          taskMessageType === 'error' || taskMessageType === 'warning'
            ? 'border-red-200 bg-red-50 text-red-700'
            : taskMessageType === 'success'
              ? 'border-green-200 bg-green-50 text-green-700'
              : 'border-blue-200 bg-blue-50 text-blue-700'
        }`}
      >
        投递状态：{taskMessage}
      </div>

      <div className="rounded-lg border border-slate-200 bg-slate-50/70 px-4 py-3">
        <div className="mb-2 flex items-center justify-between gap-3">
          <span className="text-sm font-medium text-slate-800">实时运行记录</span>
          <span className="text-xs text-muted-foreground">最近 {recentTaskMessages.length} 条</span>
        </div>
        <div
          ref={statusLogRef}
          role="log"
          aria-live="polite"
          aria-label="投递实时运行记录"
          className="max-h-56 space-y-1 overflow-y-auto pr-1"
        >
          {recentTaskMessages.map((item, index) => (
            <div
              key={`${item.timestamp ?? 'local'}-${index}`}
              className={`flex items-start gap-2 rounded-md border px-2.5 py-2 text-sm ${statusRowClass(item.type)}`}
            >
              <span className="shrink-0 font-mono text-[11px] opacity-70">{formatStatusTime(item.timestamp)}</span>
              <span className="shrink-0 text-[11px] font-medium">{statusTypeLabel(item.type)}</span>
              <span className="min-w-0 flex-1 break-words">
                {item.message}
                {item.current != null && item.total != null && (
                  <span className="ml-1 text-xs opacity-75">({item.current}/{item.total})</span>
                )}
              </span>
            </div>
          ))}
        </div>
      </div>

      <div role="status" aria-live="polite" className={`rounded-lg border px-4 py-3 ${adaptiveModeClass}`}>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <span className="font-semibold">账户节奏：{adaptiveModeLabel}</span>
          <span className="text-sm font-medium tabular-nums">{adaptiveProgress}</span>
        </div>
        <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-sm">
          <span>风控信号 {adaptivePacing.riskSignalCount ?? 0} 次</span>
          {adaptiveRiskReason && <span>最近原因：{adaptiveRiskReason}</span>}
        </div>
        {adaptiveMode === 'MANUAL_REQUIRED' ? (
          <p className="mt-1 text-sm font-medium">已停止自动恢复，请确认账号状态后重新启动任务。</p>
        ) : adaptiveMode === 'RISK_REST' ? (
          <p className="mt-1 text-sm">长休息结束后进入探测档；探测期间会使用更长发送间隔。</p>
        ) : adaptiveMode === 'PROBE' || adaptiveMode === 'RECOVERY' ? (
          <p className="mt-1 text-sm">当前处于风险后的观察阶段，再次出现风控信号会暂停自动恢复。</p>
        ) : null}
      </div>

      {taskState === 'RESTING' && (
        <div role="status" aria-live="polite" className="rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-amber-950">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="font-semibold">{isRiskRest ? '风控长休息中' : '短间歇休息中'} · {restReasonLabel(restReason)}</span>
            <span className="font-mono text-lg font-semibold tabular-nums">{restCountdown}</span>
          </div>
          <div className="mt-1 text-sm">
            第 {restAttempt || 1} 次休息，预计 {restUntil ? new Date(restUntil).toLocaleTimeString('zh-CN', { hour12: false }) : '--:--:--'} 恢复；{isRiskRest ? '恢复后进入风控探测，任务会继续运行。' : '任务会自动继续，当前只保留停止操作。'}
          </div>
          {resumeCursor && (
            <div className="mt-1 text-xs text-amber-800">
              恢复位置：关键词 {resumeCursor.keyword || '当前关键词'}，第 {resumeCursor.page || 1} 页
            </div>
          )}
        </div>
      )}

      <div className="rounded-lg border border-amber-200 bg-amber-50/70 px-4 py-3">
        <div className="mb-2 flex flex-wrap items-center justify-between gap-3">
          <div>
            <span className="text-sm font-medium text-amber-950">暂缓岗位批量重试</span>
            <p className="mt-1 text-xs text-amber-800">按快照里的原岗位链接直达，沿用 BATCH_AUTO；岗位失配会保留并标明原因。</p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {['WAITING', 'RUNNING', 'STOPPING'].includes(suspendedTaskState) ? (
              <Button
                onClick={handleStopSuspendedRetry}
                disabled={suspendedTaskState === 'STOPPING'}
                size="sm"
                variant="destructive"
              >
                <Square className="mr-1" />
                {suspendedTaskState === 'STOPPING' ? '停止中...' : '停止暂缓重试'}
              </Button>
            ) : (
              <Button
                onClick={handleStartSuspendedRetry}
                disabled={!isLoggedIn || suspendedRetrying || suspendedRetryCount <= 0}
                size="sm"
                className="bg-amber-600 text-white hover:bg-amber-700"
              >
                <RefreshCw className="mr-1" />
                {suspendedRetrying ? '启动中...' : `开始重试（${suspendedRetryCount}）`}
              </Button>
            )}
          </div>
        </div>
        <div className="grid grid-cols-2 gap-2 text-xs text-amber-950 sm:grid-cols-5">
          <span>状态 {suspendedTaskState}</span>
          <span>轮次 {suspendedBatchRetry.round ?? 0}</span>
          <span>初始 {suspendedBatchRetry.initialPending ?? suspendedRetryCount}</span>
          <span>剩余 {suspendedBatchRetry.remaining ?? suspendedRetryCount}</span>
          <span>成功聊天 {suspendedBatchRetry.delivered ?? 0}</span>
        </div>
        <div className="mt-2 grid grid-cols-2 gap-2 text-xs text-amber-950 sm:grid-cols-4">
          <span>直达尝试 {suspendedDeliverySummary.retryAttempts ?? 0}</span>
          <span>岗位命中 {suspendedDeliverySummary.retryDirectHits ?? 0}</span>
          <span>保留待处理 {suspendedDeliverySummary.retryRetained ?? 0}</span>
          <span>
            主要原因 {Object.entries(suspendedDeliverySummary.retryReasons || {}).slice(0, 2).map(([reason, count]) => `${reason} ${count}`).join('、') || '暂无'}
          </span>
        </div>
        <div
          className={`mt-2 rounded-md border px-3 py-2 text-sm ${
            suspendedTaskMessageType === 'error'
              ? 'border-red-200 bg-red-50 text-red-700'
              : suspendedTaskMessageType === 'success'
                ? 'border-green-200 bg-green-50 text-green-700'
                : 'border-amber-200 bg-white/70 text-amber-900'
          }`}
          role="status"
        >
          {suspendedTaskMessage}
        </div>
        <div className="mt-2 max-h-48 space-y-1 overflow-y-auto pr-1" role="log" aria-live="polite" aria-label="暂缓岗位重试运行记录">
          {suspendedRecentMessages.map((item, index) => (
            <div
              key={`${item.timestamp ?? 'suspended-local'}-${index}`}
              className={`flex items-start gap-2 rounded-md border px-2.5 py-2 text-sm ${statusRowClass(item.type)}`}
            >
              <span className="shrink-0 font-mono text-[11px] opacity-70">{formatStatusTime(item.timestamp)}</span>
              <span className="shrink-0 text-[11px] font-medium">{statusTypeLabel(item.type)}</span>
              <span className="min-w-0 flex-1 break-words">
                {item.message}
                {item.current != null && item.total != null && (
                  <span className="ml-1 text-xs opacity-75">({item.current}/{item.total})</span>
                )}
              </span>
            </div>
          ))}
        </div>
      </div>

      <div className="rounded-lg border border-cyan-200 bg-cyan-50/60 px-4 py-3">
        <div className="mb-2 flex items-center justify-between gap-3">
          <span className="text-sm font-medium text-cyan-950">本轮岗位筛选</span>
          <span className="text-xs text-cyan-800">薪资按月薪 K 口径换算</span>
        </div>
        <div className="grid grid-cols-2 gap-2 text-xs text-cyan-950 sm:grid-cols-5">
          <span>已处理 {deliverySummary.scanned ?? 0}</span>
          <span>薪资放行 {deliverySummary.salaryEligible ?? 0}</span>
          <span>薪资跳过 {deliverySummary.salarySkipped ?? 0}</span>
          <span>其它跳过 {deliverySummary.otherSkipped ?? 0}</span>
          <span>成功聊天 {deliverySummary.delivered ?? 0}</span>
          <span>累计聊天 {applicationSummary.chatSuccess}</span>
          <span>正式申请成功 {applicationSummary.formalApplySuccess}</span>
          <span>待处理 {applicationSummary.pending}</span>
        </div>
        <div className="mt-2 grid grid-cols-2 gap-2 text-xs text-cyan-950 sm:grid-cols-5">
          <span>搜索页 {collectionSummary.searchPages ?? 0}</span>
          <span>搜索返回 {collectionSummary.jobsFetched ?? 0}</span>
          <span>入库候选 {collectionSummary.jobsPersisted ?? 0}</span>
          <span>新增入库 {collectionSummary.jobsInserted ?? 0}</span>
          <span>已有记录 {collectionSummary.jobsExisting ?? 0}</span>
          <span>详情读取 {collectionSummary.detailAttempts ?? 0}</span>
          <span>详情命中 {collectionSummary.detailHits ?? 0}</span>
          <span>详情失败 {collectionSummary.detailMisses ?? 0}</span>
          <span>详情缓存跳过 {collectionSummary.detailCacheSkips ?? 0}</span>
          <span>搜索失败 {collectionSummary.searchFailures ?? 0}</span>
        </div>
        <div className="mt-2 text-xs text-cyan-800">
          耗时诊断：搜索与入库 {collectionSummary.searchPersistDurationMs ?? 0}ms；详情平均 {detailAverageMs}ms（累计 {collectionSummary.detailDurationMs ?? 0}ms）。
        </div>
        {collectionSummary.lastKeyword && (
          <div className="mt-2 text-xs text-cyan-800">
            最近采集：{collectionSummary.lastKeyword} · 第 {collectionSummary.lastPage ?? 0} 页
          </div>
        )}
        {collectionSummary.previewJobs && collectionSummary.previewJobs.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-1.5 text-xs text-cyan-900">
            {collectionSummary.previewJobs.map((job, index) => (
              <span key={`${job}-${index}`} className="rounded border border-cyan-200 bg-white/70 px-2 py-1">
                {job}
              </span>
            ))}
          </div>
        )}
      </div>
      <div className="rounded-lg border border-violet-200 bg-violet-50/60 px-4 py-3">
        <div className="mb-2 flex items-center justify-between gap-3">
          <span className="text-sm font-medium text-violet-950">聊天与正式申请</span>
          <span className="text-xs text-violet-800">正式申请需人工确认后单独标记</span>
        </div>
        <div className="grid grid-cols-2 gap-2 text-xs text-violet-950 sm:grid-cols-4">
          <span>岗位总数 {applicationSummary.total}</span>
          <span>聊天成功 {applicationSummary.chatSuccess}</span>
          <span>正式申请成功 {applicationSummary.formalApplySuccess}</span>
          <span>待处理 {applicationSummary.pending}</span>
        </div>
        <div className="mt-3 flex flex-col gap-2 sm:flex-row sm:items-end">
          <div className="flex-1 space-y-1">
            <Label htmlFor="formalApplyJobId">正式申请成功岗位 ID</Label>
            <Input
              id="formalApplyJobId"
              inputMode="numeric"
              value={formalApplyJobId}
              onChange={(e) => setFormalApplyJobId(e.target.value)}
              placeholder="填写已在平台完成正式申请的岗位 ID"
            />
          </div>
          <Button type="button" onClick={markFormalApplication} className="bg-violet-600 text-white hover:bg-violet-700">
            确认正式申请成功
          </Button>
        </div>
        {formalApplyMessage && <p className="mt-2 text-xs text-violet-800" role="status">{formalApplyMessage}</p>}
      </div>
      <div className="rounded-lg border border-slate-200 bg-slate-50/70 px-4 py-3">
        <div className="mb-2 flex items-center justify-between gap-3">
          <span className="text-sm font-medium text-slate-950">岗位漏斗</span>
          <span className="text-xs text-slate-700">持久化阶段 · 待重试 {funnelSummary.retryable}</span>
        </div>
        <div className="grid grid-cols-2 gap-2 text-xs text-slate-900 sm:grid-cols-5">
          {FUNNEL_STAGE_LABELS.map(([key, label]) => (
            <span key={key}>{label} {funnelSummary.stages[key] ?? 0}</span>
          ))}
        </div>
      </div>

      <div className="rounded-lg border border-emerald-200 bg-emerald-50/60 px-4 py-3">
        <div className="mb-2 flex items-center justify-between gap-3">
          <span className="text-sm font-medium text-emerald-900">本轮 AI 处理</span>
          <span className="text-xs text-emerald-700">平均响应 {aiSummary.avgLatencyMs ?? 0}ms</span>
        </div>
        <div className="grid grid-cols-2 gap-2 text-xs text-emerald-900 sm:grid-cols-4">
          <span>候选 {aiSummary.candidateCount ?? 0}</span>
          <span>缓存命中 {aiSummary.cacheHits ?? 0}</span>
          <span>评分请求 {aiSummary.screenCalls ?? 0}</span>
          <span>话术请求 {aiSummary.messageCalls ?? 0}</span>
          <span>通过 / 复核 {aiSummary.passed ?? 0} / {aiSummary.review ?? 0}</span>
          <span>可发送 {aiDeliveryEligible}</span>
          <span>AI 跳过 {aiSummary.skipped ?? 0}</span>
          <span>AI超时 {aiSummary.aiTimeouts ?? 0}</span>
          <span>自动重试 {aiSummary.aiRetryAttempts ?? 0} 次</span>
          <span>重试成功 {aiSummary.aiRetrySuccesses ?? 0} 次</span>
          <span>待重试 {aiSummary.aiRetryable ?? 0}</span>
          <span>结果无效 {aiSummary.invalidResults ?? aiSummary.invalid ?? 0}</span>
          <span>按钮失效 {aiSummary.buttonFailures ?? 0}</span>
          {batchRetry.round && batchRetry.round > 0 && (
            <span>
              {batchRetry.scope === 'SUSPENDED' ? '筛选后暂缓岗位' : 'AI超时岗位'}批量重试第 {batchRetry.round} 轮{batchRetry.active ? '进行中' : '已收尾'}，剩余 {batchRetry.remaining ?? aiSummary.aiRetryable ?? 0}，累计成功聊天 {batchRetry.delivered ?? 0}
            </span>
          )}
        </div>
      </div>

      {pendingGreeting && (
        <Card className="border-amber-300 bg-amber-50/60">
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-base">
              <Target className="text-amber-600" />
              待确认发送
            </CardTitle>
            <CardDescription>
              {pendingGreeting.companyName} · {pendingGreeting.jobTitle} · {pendingGreeting.salary || '薪资未标明'}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="rounded-md border border-amber-200 bg-white px-3 py-2 text-sm whitespace-pre-wrap">
              {pendingGreeting.message}
            </div>
            {pendingGreeting.jd && (
              <div className="max-h-32 overflow-y-auto rounded-md border border-amber-200 bg-white px-3 py-2 text-xs text-muted-foreground">
                {pendingGreeting.jd}
              </div>
            )}
            <div className="flex flex-wrap gap-2">
              {pendingGreeting.aiAvailable && (
                <Button onClick={() => handleConfirmGreeting('ai')} size="sm" className="bg-emerald-600 text-white hover:bg-emerald-700">
                  <Check className="mr-1" />确认发送 AI 话术
                </Button>
              )}
              <Button onClick={() => handleConfirmGreeting('preset')} size="sm" variant="outline">
                <Check className="mr-1" />发送预设语
              </Button>
              <Button onClick={() => handleConfirmGreeting('skip')} size="sm" variant="ghost">
                <X className="mr-1" />跳过
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      {suspendedPendingGreeting && (
        <Card className="border-orange-300 bg-orange-50/60">
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-base">
              <RefreshCw className="text-orange-600" />
              暂缓岗位待确认发送
            </CardTitle>
            <CardDescription>
              {suspendedPendingGreeting.companyName} · {suspendedPendingGreeting.jobTitle} · {suspendedPendingGreeting.salary || '薪资未标明'}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="rounded-md border border-orange-200 bg-white px-3 py-2 text-sm whitespace-pre-wrap">
              {suspendedPendingGreeting.message}
            </div>
            {suspendedPendingGreeting.jd && (
              <div className="max-h-32 overflow-y-auto rounded-md border border-orange-200 bg-white px-3 py-2 text-xs text-muted-foreground">
                {suspendedPendingGreeting.jd}
              </div>
            )}
            <div className="flex flex-wrap gap-2">
              {suspendedPendingGreeting.aiAvailable && (
                <Button onClick={() => handleConfirmGreeting('ai', suspendedPendingGreeting)} size="sm" className="bg-emerald-600 text-white hover:bg-emerald-700">
                  <Check className="mr-1" />确认发送 AI 话术
                </Button>
              )}
              <Button onClick={() => handleConfirmGreeting('preset', suspendedPendingGreeting)} size="sm" variant="outline">
                <Check className="mr-1" />发送预设语
              </Button>
              <Button onClick={() => handleConfirmGreeting('skip', suspendedPendingGreeting)} size="sm" variant="ghost">
                <X className="mr-1" />跳过
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      <div className="space-y-6 mt-6">
        <CookieLoginCard
          platform="liepin"
          platformName="猎聘"
          siteUrl="https://www.liepin.com"
          isLoggedIn={isLoggedIn}
          onImportResult={(loggedIn) => {
            setIsLoggedIn(loggedIn)
            setCheckingLogin(false)
          }}
        />

        {/* 搜索配置 */}
        <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Search className="text-primary" />
              搜索配置
            </CardTitle>
            <CardDescription>设置职位搜索关键词和筛选条件</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              <div className="space-y-2">
                <Label htmlFor="searchProfile">搜索方案</Label>
                <Select
                  id="searchProfile"
                  value={config.searchProfile || 'A'}
                  onChange={(e) => {
                    const profile = e.target.value as LiepinConfig['searchProfile']
                    setConfig({
                      ...config,
                      searchProfile: profile,
                      keywords: profile === 'A' || profile === 'B'
                        ? PROFILE_DEFAULT_KEYWORDS[profile]
                        : config.keywords || '',
                    })
                  }}
                >
                  <option value="A">A：严格放疗应用</option>
                  <option value="B">B：病理 IVD / 临床协调 / 产品 / 医疗运营</option>
                  <option value="CUSTOM">CUSTOM：自定义关键词</option>
                </Select>
                <p className="text-xs text-muted-foreground">A/B 会使用对应默认关键词；选择 CUSTOM 后可编辑下方关键词。</p>
                <Label htmlFor="keywords">搜索关键词（每行一个）</Label>
                <Textarea
                  id="keywords"
                  disabled={config.searchProfile !== 'CUSTOM'}
                  value={config.keywords || ''}
                  onChange={(e) => setConfig({ ...config, keywords: e.target.value, searchProfile: 'CUSTOM' })}
                  placeholder="每行输入一个关键词"
                  rows={8}
                />
                <p className="text-xs text-muted-foreground">当前方案：{config.searchProfile || 'A'}；CUSTOM 可自行维护关键词。</p>
                <p className="text-xs text-muted-foreground">每行输入一个职位搜索关键词</p>
              </div>

              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <Label htmlFor="city">工作城市</Label>
                  <button
                    type="button"
                    onClick={() => {
                      setIsCustomCity(!isCustomCity)
                      if (!isCustomCity) {
                        // 切换到手动输入时，清空当前值
                        setConfig({ ...config, city: '' })
                      }
                    }}
                    className="text-xs text-primary hover:underline"
                  >
                    {isCustomCity ? '从列表选择' : '手动输入'}
                  </button>
                </div>
                {isCustomCity ? (
                  <Input
                    id="city"
                    value={config.city || ''}
                    onChange={(e) => setConfig({ ...config, city: e.target.value })}
                    placeholder="请输入城市码，例如：410"
                  />
                ) : (
                  <Select
                    id="city"
                    value={config.city || ''}
                    onChange={(e) => setConfig({ ...config, city: e.target.value })}
                  >
                    <option value="">请选择城市</option>
                    {options.city.map((city) => (
                      <option key={city.id} value={city.name}>
                        {city.name}
                      </option>
                    ))}
                  </Select>
                )}
                <p className="text-xs text-muted-foreground">
                  {isCustomCity ? '手动输入城市码（例如：410代表北京）' : '从列表选择城市，或点击"手动输入"自定义'}
                </p>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* 薪资配置 */}
        <Card className="animate-in fade-in slide-in-from-bottom-6 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Banknote className="text-primary" />
              薪资筛选
            </CardTitle>
            <CardDescription>按月薪 K 配置；开始投递时自动在网页选择“薪资 → 自定义”并换算成年薪万元</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 gap-6">
              <div className="space-y-2">
                <Label htmlFor="salaryCode">薪资范围</Label>
                <Input
                  id="salaryCode"
                  value={config.salaryCode || ''}
                  onChange={(e) => setConfig({ ...config, salaryCode: e.target.value })}
                  placeholder="例如：15$30"
                />
                <p className="text-xs text-muted-foreground">薪资范围码（例如：6$10 表示月薪 6K-10K；网页筛选约为年薪 7.2-12 万，平台按整数万元归一，岗位区间有交集才进入后续筛选）</p>
              </div>
            </div>
          </CardContent>
        </Card>

        <Card className="animate-in fade-in slide-in-from-bottom-6 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Target className="text-primary" />
              AI 投递控制
            </CardTitle>
            <CardDescription>逐岗位读取 JD；关闭自动投递时等待确认，开启后按 AI 评分阈值自动发送</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
              <div className="space-y-2">
                <Label htmlFor="enableAi">AI 招呼语</Label>
                <label className="flex h-10 items-center gap-3 rounded-md border px-3 text-sm">
                  <input
                    id="enableAi"
                    type="checkbox"
                    checked={config.enableAi !== 0}
                    onChange={(e) => setConfig({
                      ...config,
                      enableAi: e.target.checked ? 1 : 0,
                      autoAiDelivery: e.target.checked ? config.autoAiDelivery : 0,
                      aiDeliveryMode: e.target.checked ? config.aiDeliveryMode : 'MANUAL',
                    })}
                  />
                  <span>{config.enableAi !== 0 ? '已开启' : '已关闭，使用猎聘预设语'}</span>
                </label>
              </div>
              <div className="space-y-2">
                <Label htmlFor="aiDeliveryMode">AI投递模式</Label>
                <Select
                  id="aiDeliveryMode"
                  disabled={config.enableAi === 0}
                  value={config.aiDeliveryMode || 'MANUAL'}
                  onChange={(e) => {
                    const mode = e.target.value as LiepinConfig['aiDeliveryMode']
                    setConfig({
                      ...config,
                      aiDeliveryMode: mode,
                      autoAiDelivery: mode === 'SINGLE_AUTO' || mode === 'BATCH_AUTO' ? 1 : 0,
                    })
                  }}
                >
                  <option value="MANUAL">手动确认</option>
                  <option value="BATCH_SHADOW">批量评分预演</option>
                  <option value="BATCH_AUTO">批量评分自动投递</option>
                  <option value="SINGLE_AUTO">兼容逐岗自动投递</option>
                </Select>
                <p className="text-xs text-muted-foreground">预演模式只评分和生成话术；批量自动模式发送 PASS，或达到复核阈值且不含硬性不匹配的 REVIEW 岗位。</p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="aiMinScore">AI最低评分</Label>
                <Input
                  id="aiMinScore"
                  type="number"
                  min={0}
                  max={100}
                  disabled={config.aiDeliveryMode === 'MANUAL'}
                  value={config.aiMinScore ?? 70}
                  onChange={(e) => setConfig({ ...config, aiMinScore: Number(e.target.value) })}
                />
                <p className="text-xs text-muted-foreground">评分达到该分数才自动发送，默认 70 分。</p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="aiReviewMinScore">AI复核最低评分</Label>
                <Input
                  id="aiReviewMinScore"
                  type="number"
                  min={0}
                  max={config.aiMinScore ?? 70}
                  disabled={config.aiDeliveryMode !== 'BATCH_AUTO' && config.aiDeliveryMode !== 'BATCH_SHADOW'}
                  value={config.aiReviewMinScore ?? 60}
                  onChange={(e) => setConfig({ ...config, aiReviewMinScore: Number(e.target.value) })}
                />
                <p className="text-xs text-muted-foreground">低于通过分但达到该分数的岗位进入 REVIEW；批量自动模式会发送未标记硬性不匹配的 REVIEW 岗位。</p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="aiBatchSize">AI批量大小</Label>
                <Input
                  id="aiBatchSize"
                  type="number"
                  min={3}
                  max={10}
                  disabled={config.aiDeliveryMode !== 'BATCH_AUTO' && config.aiDeliveryMode !== 'BATCH_SHADOW'}
                  value={config.aiBatchSize ?? 5}
                  onChange={(e) => setConfig({ ...config, aiBatchSize: Number(e.target.value) })}
                />
                <p className="text-xs text-muted-foreground">每次 AI 请求处理 3-10 个岗位，默认 5 个。</p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="aiTimeoutRetryEnabled">AI超时自动重试</Label>
                <label className="flex h-10 items-center gap-3 rounded-md border px-3 text-sm">
                  <input
                    id="aiTimeoutRetryEnabled"
                    type="checkbox"
                    checked={config.aiTimeoutRetryEnabled !== 0}
                    onChange={(e) => setConfig({ ...config, aiTimeoutRetryEnabled: e.target.checked ? 1 : 0 })}
                  />
                  <span>{config.aiTimeoutRetryEnabled !== 0 ? '已开启' : '已关闭'}</span>
                </label>
                <p className="text-xs text-muted-foreground">只对 AI 请求超时重试，结果格式无效仍按无效处理。</p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="aiTimeoutMaxRetries">超时追加次数</Label>
                <Input
                  id="aiTimeoutMaxRetries"
                  type="number"
                  min={0}
                  max={10}
                  disabled={config.aiTimeoutRetryEnabled === 0}
                  value={config.aiTimeoutMaxRetries ?? 3}
                  onChange={(e) => setConfig({ ...config, aiTimeoutMaxRetries: Number(e.target.value) })}
                />
                <p className="text-xs text-muted-foreground">每次请求最多追加 0-10 次，默认 3 次。</p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="aiTimeoutRetryDelaySeconds">超时重试间隔（秒）</Label>
                <Input
                  id="aiTimeoutRetryDelaySeconds"
                  type="number"
                  min={0}
                  max={60}
                  disabled={config.aiTimeoutRetryEnabled === 0}
                  value={config.aiTimeoutRetryDelaySeconds ?? 3}
                  onChange={(e) => setConfig({ ...config, aiTimeoutRetryDelaySeconds: Number(e.target.value) })}
                />
                <p className="text-xs text-muted-foreground">按第几次重试递增等待，范围 0-60 秒。</p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="maxPerRun">单次岗位上限</Label>
                <Input
                  id="maxPerRun"
                  type="number"
                  min={0}
                  value={config.maxPerRun ?? 15}
                  onChange={(e) => setConfig({ ...config, maxPerRun: Number(e.target.value) })}
                />
                <p className="text-xs text-muted-foreground">0 表示不设置最大值。</p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="stopAfterMaxPerRun">达到上限后</Label>
                <label className="flex h-10 items-center gap-3 rounded-md border px-3 text-sm">
                  <input
                    id="stopAfterMaxPerRun"
                    type="checkbox"
                    checked={config.stopAfterMaxPerRun !== 0}
                    disabled={config.maxPerRun === 0}
                    onChange={(e) => setConfig({
                      ...config,
                      stopAfterMaxPerRun: e.target.checked ? 1 : 0,
                    })}
                    className="h-4 w-4 accent-blue-600"
                  />
                  <span>{config.stopAfterMaxPerRun !== 0 ? '停止任务' : '休息后继续'}</span>
                </label>
                <p className="text-xs text-muted-foreground">关闭后按短间歇自动继续。</p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="maxPerRunRestMinSeconds">继续前最短休息（秒）</Label>
                <Input
                  id="maxPerRunRestMinSeconds"
                  type="number"
                  min={MIN_MAX_PER_RUN_REST_SECONDS}
                  max={MAX_MAX_PER_RUN_REST_SECONDS}
                  disabled={config.stopAfterMaxPerRun !== 0 || config.maxPerRun === 0}
                  value={config.maxPerRunRestMinSeconds ?? DEFAULT_MAX_PER_RUN_REST_MIN_SECONDS}
                  onChange={(e) => setConfig({ ...config, maxPerRunRestMinSeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="maxPerRunRestMaxSeconds">继续前最长休息（秒）</Label>
                <Input
                  id="maxPerRunRestMaxSeconds"
                  type="number"
                  min={MIN_MAX_PER_RUN_REST_SECONDS}
                  max={MAX_MAX_PER_RUN_REST_SECONDS}
                  disabled={config.stopAfterMaxPerRun !== 0 || config.maxPerRun === 0}
                  value={config.maxPerRunRestMaxSeconds ?? DEFAULT_MAX_PER_RUN_REST_MAX_SECONDS}
                  onChange={(e) => setConfig({ ...config, maxPerRunRestMaxSeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="minDelaySeconds">发送最小间隔（秒）</Label>
                <Input
                  id="minDelaySeconds"
                  type="number"
                  min={10}
                  max={300}
                  value={config.minDelaySeconds ?? 20}
                  onChange={(e) => setConfig({ ...config, minDelaySeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="maxDelaySeconds">发送最大间隔（秒）</Label>
                <Input
                  id="maxDelaySeconds"
                  type="number"
                  min={10}
                  max={300}
                  value={config.maxDelaySeconds ?? 35}
                  onChange={(e) => setConfig({ ...config, maxDelaySeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="searchMinDelaySeconds">搜索最小间隔（秒）</Label>
                <Input
                  id="searchMinDelaySeconds"
                  type="number"
                  min={5}
                  max={300}
                  value={config.searchMinDelaySeconds ?? 5}
                  onChange={(e) => setConfig({ ...config, searchMinDelaySeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="searchMaxDelaySeconds">搜索最大间隔（秒）</Label>
                <Input
                  id="searchMaxDelaySeconds"
                  type="number"
                  min={5}
                  max={300}
                  value={config.searchMaxDelaySeconds ?? 10}
                  onChange={(e) => setConfig({ ...config, searchMaxDelaySeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="pageMinDelaySeconds">翻页最小间隔（秒）</Label>
                <Input
                  id="pageMinDelaySeconds"
                  type="number"
                  min={5}
                  max={300}
                  value={config.pageMinDelaySeconds ?? 3}
                  onChange={(e) => setConfig({ ...config, pageMinDelaySeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="pageMaxDelaySeconds">翻页最大间隔（秒）</Label>
                <Input
                  id="pageMaxDelaySeconds"
                  type="number"
                  min={5}
                  max={300}
                  value={config.pageMaxDelaySeconds ?? 5}
                  onChange={(e) => setConfig({ ...config, pageMaxDelaySeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="detailMinDelaySeconds">详情最小间隔（秒）</Label>
                <Input
                  id="detailMinDelaySeconds"
                  type="number"
                  min={5}
                  max={300}
                  value={config.detailMinDelaySeconds ?? 5}
                  onChange={(e) => setConfig({ ...config, detailMinDelaySeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="detailMaxDelaySeconds">详情最大间隔（秒）</Label>
                <Input
                  id="detailMaxDelaySeconds"
                  type="number"
                  min={5}
                  max={300}
                  value={config.detailMaxDelaySeconds ?? 8}
                  onChange={(e) => setConfig({ ...config, detailMaxDelaySeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="rateGuardBatchSize">批次成功次数</Label>
                <Input
                  id="rateGuardBatchSize"
                  type="number"
                  min={1}
                  max={50}
                  value={config.rateGuardBatchSize ?? 15}
                  onChange={(e) => setConfig({ ...config, rateGuardBatchSize: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="batchCooldownMinSeconds">批次冷却最小值（秒）</Label>
                <Input
                  id="batchCooldownMinSeconds"
                  type="number"
                  min={60}
                  max={3600}
                  value={config.batchCooldownMinSeconds ?? 180}
                  onChange={(e) => setConfig({ ...config, batchCooldownMinSeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="batchCooldownMaxSeconds">批次冷却最大值（秒）</Label>
                <Input
                  id="batchCooldownMaxSeconds"
                  type="number"
                  min={60}
                  max={3600}
                  value={config.batchCooldownMaxSeconds ?? 300}
                  onChange={(e) => setConfig({ ...config, batchCooldownMaxSeconds: Number(e.target.value) })}
                />
              </div>
            </div>
            <p className="mt-3 text-xs text-muted-foreground">
              默认正常档：搜索 5-10 秒、翻页 3-5 秒、详情 5-8 秒、发送 20-35 秒；每成功 15 次冷却 180-300 秒。命中风控信号后自动长休息 30-45 分钟，再以探测档最多成功 3 次、恢复档最多成功 8 次逐步恢复；再次触发会暂停自动恢复。
            </p>
            <div className="mt-6 space-y-3 rounded-md border p-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <p className="text-sm font-medium">投递页码进度</p>
                  <p className="text-xs text-muted-foreground">
                    按关键词记住上次完成页，下次从下一页继续；前几页新岗需点重置后从头扫。
                  </p>
                </div>
                <Button
                  type="button"
                  variant="outline"
                  disabled={resettingProgress || pageProgress.length === 0}
                  onClick={handleResetPageProgress}
                  className="rounded-full"
                >
                  {resettingProgress ? '重置中...' : '重置投递进度'}
                </Button>
              </div>
              {pageProgress.length === 0 ? (
                <p className="text-sm text-muted-foreground">暂无断点，下次从第 1 页开始。</p>
              ) : (
                <ul className="space-y-2 text-sm">
                  {pageProgress.map((item) => (
                    <li
                      key={`${item.keyword}|${item.cityCode || ''}|${item.salaryCode || ''}`}
                      className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-muted/40 px-3 py-2"
                    >
                      <span className="font-medium">{item.keyword}</span>
                      <span className="text-muted-foreground">
                        已完成第 {item.lastCompletedPage} 页，下次从第 {item.nextStartPage} 页
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </CardContent>
        </Card>
      </div>

      {/* 退出确认弹框 */}
      {showLogoutDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border border-gray-200 dark:border-neutral-800 animate-in fade-in zoom-in-95">
            <Card className="border-0">
              <CardHeader className="pb-2">
                <CardTitle className="text-lg flex items-center gap-2">
                  <LogOut className="text-red-500" />
                  确认退出登录
                </CardTitle>
              </CardHeader>
              <CardContent className="pt-0">
                <p className="text-sm text-muted-foreground mb-4">退出后将清除Cookie并切换为未登录状态。</p>
                <div className="flex justify-end gap-2">
                  <Button
                    variant="ghost"
                    onClick={() => setShowLogoutDialog(false)}
                    className="rounded-full px-4"
                  >
                    取消
                  </Button>
                  <Button
                    onClick={async () => {
                      await triggerLogout()
                      setShowLogoutDialog(false)
                    }}
                    className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white px-4"
                  >
                    确认退出
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      )}

      {/* 退出登录结果弹框 */}
      {showLogoutResultDialog && logoutResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30" role="dialog" aria-modal="true">
          <div className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border border-gray-200 dark:border-neutral-800 animate-in fade-in zoom-in-95">
            <Card className="border-0">
              <CardHeader className="pb-2">
                <CardTitle className="text-lg flex items-center gap-2">
                  <LogOut className={logoutResult.success ? 'text-green-500' : 'text-red-500'} />
                  {logoutResult.success ? '退出登录成功' : '退出登录失败'}
                </CardTitle>
              </CardHeader>
              <CardContent className="pt-0">
                <p className="text-sm text-muted-foreground mb-4">{logoutResult.message}</p>
                <div className="flex justify-end gap-2">
                  <Button
                    onClick={() => setShowLogoutResultDialog(false)}
                    className={`rounded-full px-4 ${logoutResult.success ? 'bg-gradient-to-r from-green-500 to-emerald-600 hover:from-green-600 hover:to-emerald-700 text-white' : 'bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white'}`}
                  >
                    知道了
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      )}

      {/* 保存结果弹框 */}
      {showSaveDialog && saveResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30" role="dialog" aria-modal="true">
          <div className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border border-gray-200 dark:border-neutral-800 animate-in fade-in zoom-in-95">
            <Card className="border-0">
              <CardHeader className="pb-2">
                <CardTitle className="text-lg flex items-center gap-2">
                  <Save className={saveResult.success ? 'text-green-500' : 'text-red-500'} />
                  {saveResult.success ? '保存成功' : '保存失败'}
                </CardTitle>
              </CardHeader>
              <CardContent className="pt-0">
                <p className="text-sm text-muted-foreground mb-4">{saveResult.message}</p>
                <div className="flex justify-end gap-2">
                  <Button
                    onClick={() => setShowSaveDialog(false)}
                    className={`rounded-full px-4 ${saveResult.success ? 'bg-gradient-to-r from-green-500 to-emerald-600 hover:from-green-600 hover:to-emerald-700 text-white' : 'bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white'}`}
                  >
                    知道了
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      )}
    </div>
  )
}
