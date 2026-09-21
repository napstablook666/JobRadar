'use client'

import { useState, useEffect, useRef } from 'react'
import { createSSEWithBackoff } from '@/lib/sse'
import { Check, LogOut, Save, Briefcase, Play, Square } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import PageHeader from '@/app/components/PageHeader'
import CookieLoginCard from '@/app/components/CookieLoginCard'
import { keywordLinesForDisplay, keywordLinesForStorage } from '@/lib/keyword-lines'

interface Job51Config {
  id?: number
  keywords?: string
  jobArea?: string
  salary?: string // 存储JSON数组字符串，如 ["03","04","05"]
  enableAi?: number
  aiMinScore?: number
  maxPerRun?: number
  stopAfterMaxPerRun?: number
  maxPerRunRestMinSeconds?: number
  maxPerRunRestMaxSeconds?: number
  minDelaySeconds?: number
  maxDelaySeconds?: number
  restEnabled?: number
  restStage1MinSeconds?: number
  restStage1MaxSeconds?: number
  restStage2MinSeconds?: number
  restStage2MaxSeconds?: number
  restStage3MinSeconds?: number
  restStage3MaxSeconds?: number
  restMaxConsecutive?: number
}

interface Job51Option { name: string; code: string }
interface Job51Options { jobArea: Job51Option[]; salary: Job51Option[] }

// 薪资选择状态（用于多选）
const MAX_SALARY_SELECTIONS = 5
const MIN_AI_DELAY_SECONDS = 20
const MAX_AI_DELAY_SECONDS = 300
const MIN_AI_SCORE = 0
const MAX_AI_SCORE = 100
const MIN_MAX_PER_RUN_REST_SECONDS = 30
const MAX_MAX_PER_RUN_REST_SECONDS = 300
const DEFAULT_MAX_PER_RUN_REST_MIN_SECONDS = 120
const DEFAULT_MAX_PER_RUN_REST_MAX_SECONDS = 240
const MIN_REST_SECONDS = 30
const MAX_REST_SECONDS = 86400
const DEFAULT_REST_STAGE1_MIN_SECONDS = 180
const DEFAULT_REST_STAGE1_MAX_SECONDS = 300
const DEFAULT_REST_STAGE2_MIN_SECONDS = 600
const DEFAULT_REST_STAGE2_MAX_SECONDS = 900
const DEFAULT_REST_STAGE3_MIN_SECONDS = 1800
const DEFAULT_REST_STAGE3_MAX_SECONDS = 2700
const DEFAULT_REST_MAX_CONSECUTIVE = 3
const MIN_REST_CONSECUTIVE = 1
const MAX_REST_CONSECUTIVE = 10
const MAX_RECENT_TASK_MESSAGES = 5
const API = process.env.API_BASE_URL || 'http://localhost:8888'

type ResumeCursor = {
  keyword?: string | null
  keywordIndex?: number
  page?: number
}

type VerificationCursor = {
  jobId?: number
  detailUrl?: string | null
  keyword?: string | null
  keywordIndex?: number
  page?: number
  title?: string | null
}

type TaskStatusMessage = {
  type?: string
  message: string
  current?: number | null
  total?: number | null
  timestamp?: number | null
}

type DeliverySummary = {
  candidates?: number
  aiProcessed?: number
  greetingSent?: number
  aiDelivered?: number
  skipped?: number
  failed?: number
  selected?: number
  confirmed?: number
}

const EMPTY_DELIVERY_SUMMARY: DeliverySummary = {
  candidates: 0,
  aiProcessed: 0,
  greetingSent: 0,
  aiDelivered: 0,
  skipped: 0,
  failed: 0,
  selected: 0,
  confirmed: 0,
}

const clampAiDelay = (value: number | undefined, fallback: number): number =>
  Math.min(MAX_AI_DELAY_SECONDS, Math.max(MIN_AI_DELAY_SECONDS, Number(value) || fallback))


const normalizeAiScore = (value: number | undefined): number => {
  const parsed = Number(value)
  const score = Number.isFinite(parsed) ? parsed : 70
  return Math.min(MAX_AI_SCORE, Math.max(MIN_AI_SCORE, score))
}
const clampRestSeconds = (value: number | undefined, fallback: number): number => {
  const parsed = Number(value)
  const seconds = Number.isFinite(parsed) ? parsed : fallback
  return Math.min(MAX_REST_SECONDS, Math.max(MIN_REST_SECONDS, seconds))
}

const clampRestConsecutive = (value: number | undefined): number => {
  const parsed = Number(value)
  const count = Number.isFinite(parsed) ? parsed : DEFAULT_REST_MAX_CONSECUTIVE
  return Math.min(MAX_REST_CONSECUTIVE, Math.max(MIN_REST_CONSECUTIVE, count))
}

const normalizeRestRange = (
  minValue: number | undefined,
  maxValue: number | undefined,
  defaultMin: number,
  defaultMax: number,
): [number, number] => {
  const min = clampRestSeconds(minValue, defaultMin)
  return [min, Math.max(min, clampRestSeconds(maxValue, defaultMax))]
}

const normalizeRestConfig = (value: Job51Config) => {
  const [stage1MinSeconds, stage1MaxSeconds] = normalizeRestRange(
    value.restStage1MinSeconds,
    value.restStage1MaxSeconds,
    DEFAULT_REST_STAGE1_MIN_SECONDS,
    DEFAULT_REST_STAGE1_MAX_SECONDS,
  )
  const [stage2MinSeconds, stage2MaxSeconds] = normalizeRestRange(
    value.restStage2MinSeconds,
    value.restStage2MaxSeconds,
    DEFAULT_REST_STAGE2_MIN_SECONDS,
    DEFAULT_REST_STAGE2_MAX_SECONDS,
  )
  const [stage3MinSeconds, stage3MaxSeconds] = normalizeRestRange(
    value.restStage3MinSeconds,
    value.restStage3MaxSeconds,
    DEFAULT_REST_STAGE3_MIN_SECONDS,
    DEFAULT_REST_STAGE3_MAX_SECONDS,
  )
  return {
    restEnabled: value.restEnabled === 0 ? 0 : 1,
    restStage1MinSeconds: stage1MinSeconds,
    restStage1MaxSeconds: stage1MaxSeconds,
    restStage2MinSeconds: stage2MinSeconds,
    restStage2MaxSeconds: stage2MaxSeconds,
    restStage3MinSeconds: stage3MinSeconds,
    restStage3MaxSeconds: stage3MaxSeconds,
    restMaxConsecutive: clampRestConsecutive(value.restMaxConsecutive),
  }
}

const normalizeMaxPerRunRestConfig = (value: Job51Config) => {
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
    stopAfterMaxPerRun: value.stopAfterMaxPerRun === 1 ? 1 : 0,
    maxPerRunRestMinSeconds: min,
    maxPerRunRestMaxSeconds: max,
  }
}

const normalizeMaxPerRun = (value: number | undefined, fallback = 8): number => {
  const parsed = Number(value)
  return Math.max(0, Number.isFinite(parsed) ? parsed : fallback)
}

const normalizeTaskMessages = (value: unknown): TaskStatusMessage[] => {
  if (!Array.isArray(value)) return []
  return value
    .filter((item): item is TaskStatusMessage =>
      !!item && typeof item === 'object' && typeof (item as TaskStatusMessage).message === 'string',
    )
    .slice(-MAX_RECENT_TASK_MESSAGES)
}

export default function Job51Page() {
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  const [isDelivering, setIsDelivering] = useState(false)
  const [checkingLogin, setCheckingLogin] = useState(true)
  const [showLogoutDialog, setShowLogoutDialog] = useState(false)
  const [showSaveDialog, setShowSaveDialog] = useState(false)
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string } | null>(null)
  const [showLogoutResultDialog, setShowLogoutResultDialog] = useState(false)
  const [logoutResult, setLogoutResult] = useState<{ success: boolean; message: string } | null>(null)
  const [taskMessage, setTaskMessage] = useState('尚未启动投递任务')
  const [taskMessageType, setTaskMessageType] = useState('idle')
  const [taskState, setTaskState] = useState('IDLE')
  const [restReason, setRestReason] = useState<string | null>(null)
  const [restUntil, setRestUntil] = useState(0)
  const [restAttempt, setRestAttempt] = useState(0)
  const [resumeCursor, setResumeCursor] = useState<ResumeCursor | null>(null)
  const [verificationCursor, setVerificationCursor] = useState<VerificationCursor | null>(null)
  const [deliverySummary, setDeliverySummary] = useState<DeliverySummary>(EMPTY_DELIVERY_SUMMARY)
  const [restNow, setRestNow] = useState(() => Date.now())
  const [recentTaskMessages, setRecentTaskMessages] = useState<TaskStatusMessage[]>([
    { type: 'idle', message: '尚未启动投递任务' },
  ])
  const [isStopping, setIsStopping] = useState(false)
  const statusLogRef = useRef<HTMLDivElement>(null)

  const [config, setConfig] = useState<Job51Config>({
    keywords: '',
    jobArea: '',
    salary: '',
    enableAi: 0,
    aiMinScore: 70,
    maxPerRun: 8,
    stopAfterMaxPerRun: 0,
    maxPerRunRestMinSeconds: DEFAULT_MAX_PER_RUN_REST_MIN_SECONDS,
    maxPerRunRestMaxSeconds: DEFAULT_MAX_PER_RUN_REST_MAX_SECONDS,
    minDelaySeconds: 20,
    maxDelaySeconds: 40,
    restEnabled: 1,
    restStage1MinSeconds: DEFAULT_REST_STAGE1_MIN_SECONDS,
    restStage1MaxSeconds: DEFAULT_REST_STAGE1_MAX_SECONDS,
    restStage2MinSeconds: DEFAULT_REST_STAGE2_MIN_SECONDS,
    restStage2MaxSeconds: DEFAULT_REST_STAGE2_MAX_SECONDS,
    restStage3MinSeconds: DEFAULT_REST_STAGE3_MIN_SECONDS,
    restStage3MaxSeconds: DEFAULT_REST_STAGE3_MAX_SECONDS,
    restMaxConsecutive: DEFAULT_REST_MAX_CONSECUTIVE,
  })
  const [options, setOptions] = useState<Job51Options>({ jobArea: [], salary: [] })
  const [loadingConfig, setLoadingConfig] = useState(true)
  const [isCustomArea, setIsCustomArea] = useState(false)
  const [backendAvailable, setBackendAvailable] = useState(false)
  // 薪资多选状态：存储选中的code数组
  const [selectedSalaries, setSelectedSalaries] = useState<string[]>([])
  const [salarySearchQuery, setSalarySearchQuery] = useState('')
  // 薪资下拉面板开关状态
  const [salaryDropdownOpen, setSalaryDropdownOpen] = useState(false)

  useEffect(() => {
    if (!backendAvailable) {
      setCheckingLogin(false)
      return
    }
    console.log('[51job] useEffect 开始执行')
    console.log('[51job] window:', typeof window)
    console.log('[51job] EventSource:', typeof EventSource)

    if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
      console.warn('[51job] EventSource 不可用，无法连接SSE')
      setCheckingLogin(false)
      return
    }

    const client = createSSEWithBackoff(`${API}/api/jobs/login-status/stream`, {
      onOpen: () => {
        console.log('[51job SSE] 连接已打开')
      },
      onError: (e, attempt, delay) => {
        console.warn(`[51job SSE] 连接错误，准备第${attempt}次重连，延迟 ${delay}ms`, e)
        setCheckingLogin(false)
      },
      listeners: [
        {
          name: 'connected',
          handler: (event) => {
            console.log('[51job SSE] 收到 connected 事件:', event.data)
            try {
              const data = JSON.parse(event.data)
              setIsLoggedIn(data.job51LoggedIn || false)
              setCheckingLogin(false)
            } catch (error) {
              console.error('[51job SSE] 解析连接消息失败:', error)
            }
          },
        },
        {
          name: 'login-status',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              if (data.platform === '51job') {
                setIsLoggedIn(data.isLoggedIn)
                setCheckingLogin(false)
              }
            } catch (error) {
              console.error('[51job SSE] 解析登录状态消息失败:', error)
            }
          },
        },
        {
          name: 'ping',
          handler: () => {
            // 心跳事件，无需处理。
          },
        },
      ],
    })

    return () => {
      console.log('[51job SSE] 关闭 SSE 连接')
      client.close()
    }
  }, [backendAvailable])

  // 点击外部关闭薪资下拉面板
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      const target = event.target as HTMLElement
      // 如果点击的不是薪资下拉框或其子元素，则关闭
      if (!target.closest('.salary-dropdown-container')) {
        setSalaryDropdownOpen(false)
      }
    }

    if (salaryDropdownOpen) {
      document.addEventListener('mousedown', handleClickOutside)
    }

    return () => {
      document.removeEventListener('mousedown', handleClickOutside)
    }
  }, [salaryDropdownOpen])

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

  const appendTaskMessage = (message: TaskStatusMessage) => {
    if (!message.message) return
    setTaskMessage(message.message)
    setTaskMessageType(message.type || 'info')
    setRecentTaskMessages((current) => [...current, message].slice(-MAX_RECENT_TASK_MESSAGES))
  }

  const applyTaskStatus = (data: Record<string, unknown>) => {
    if (typeof data.isRunning === 'boolean') {
      setIsDelivering(data.isRunning)
      if (!data.isRunning) setIsStopping(false)
    }
    if (typeof data.taskState === 'string') {
      setTaskState(data.taskState)
      setIsStopping(data.taskState === 'STOPPING')
    }
    setRestReason(typeof data.restReason === 'string' ? data.restReason : null)
    setRestUntil(typeof data.restUntil === 'number' ? data.restUntil : 0)
    setRestAttempt(typeof data.restAttempt === 'number' ? data.restAttempt : 0)
    const cursor = data.resumeCursor
    setResumeCursor(cursor && typeof cursor === 'object' ? cursor as ResumeCursor : null)
    const verification = data.verificationCursor
    setVerificationCursor(verification && typeof verification === 'object'
      ? verification as VerificationCursor : null)
    setRestNow(Date.now())
    const summary = data.deliverySummary
    setDeliverySummary(summary && typeof summary === 'object'
      ? { ...EMPTY_DELIVERY_SUMMARY, ...(summary as DeliverySummary) }
      : EMPTY_DELIVERY_SUMMARY)
    if (typeof data.message === 'string' && data.message) {
      setTaskMessage(data.message)
      setTaskMessageType(typeof data.messageType === 'string' ? data.messageType : 'info')
    }
    const serverMessages = normalizeTaskMessages(data.recentMessages)
    if (serverMessages.length > 0) {
      setRecentTaskMessages(serverMessages)
    } else if (typeof data.message === 'string' && data.message) {
      setRecentTaskMessages([{
        type: typeof data.messageType === 'string' ? data.messageType : 'info',
        message: data.message,
        timestamp: typeof data.messageAt === 'number' ? data.messageAt : Date.now(),
      }])
    }
  }

  // 解析后端存储的列表字符串，返回第一个元素（用于单选）
  const parseSingleTokenFromDb = (raw?: string): string => {
    if (!raw) return ''
    const t = raw.trim()
    if (t.startsWith('[') && t.endsWith(']')) {
      try {
        const arr = JSON.parse(t)
        if (Array.isArray(arr) && arr.length > 0) {
          return String(arr[0] ?? '').trim()
        }
      } catch {
        // ignore, fall through
      }
    }
    // 非 JSON 数组时，按逗号拆分，取第一个
    const parts = t.replace(/，/g, ',').split(',').map((s) => s.trim()).filter(Boolean)
    return parts[0] || ''
  }

  // 解析后端存储的薪资列表（多选）
  const parseMultiTokensFromDb = (raw?: string): string[] => {
    if (!raw) return []
    const t = raw.trim()
    if (t.startsWith('[') && t.endsWith(']')) {
      try {
        const arr = JSON.parse(t)
        if (Array.isArray(arr)) {
          return arr.map(v => String(v ?? '').trim()).filter(Boolean)
        }
      } catch {
        // ignore, fall through
      }
    }
    // 非 JSON 数组时，按逗号拆分
    return t.replace(/，/g, ',').split(',').map((s) => s.trim()).filter(Boolean)
  }

  const fetchAllData = async () => {
    try {
      const res = await fetch(`${API}/api/51job/config`)
      if (!res.ok) {
        console.warn(`[51job] 获取配置失败: ${res.status}`)
        setConfig({ keywords: '', jobArea: '', salary: '' })
        setOptions({ jobArea: [], salary: [] })
        return
      }
      const data = await res.json()
      if (data.config || data.options) {
        const opts: Job51Options = data.options || { jobArea: [], salary: [] }
        const conf: Job51Config = data.config || { keywords: '', jobArea: '', salary: '' }

        // 关键词标准化（展示用）
        const normalizedKeywords = keywordLinesForDisplay(conf.keywords)

        // 从服务端字段提取第一个token，然后映射到code
        const rawArea = parseSingleTokenFromDb(conf.jobArea)
        const rawSalaries = parseMultiTokensFromDb(conf.salary) // 薪资支持多个

        const areaList = opts.jobArea || []
        const salaryList = opts.salary || []

        const matchArea = areaList.find((o) => o.code === rawArea || o.name === rawArea)
        
        // 薪资多选：将每个值映射为code
        const salaryCodes = rawSalaries
          .map(raw => {
            const match = salaryList.find(o => o.code === raw || o.name === raw)
            return match?.code || raw
          })
          .filter(Boolean)
          .slice(0, MAX_SALARY_SELECTIONS) // 最多5个

        const areaCode = matchArea?.code || (areaList.find((o) => o.name === '不限')?.code || areaList.find((o) => o.code === '0')?.code || '')

        setOptions(opts)
        const minDelaySeconds = clampAiDelay(conf.minDelaySeconds, MIN_AI_DELAY_SECONDS)
        const maxDelaySeconds = Math.max(
          minDelaySeconds,
          clampAiDelay(conf.maxDelaySeconds, 40),
        )
        const rest = normalizeRestConfig(conf)
        const maxPerRunRest = normalizeMaxPerRunRestConfig(conf)
        setConfig({
          ...conf,
          keywords: normalizedKeywords,
          jobArea: areaCode,
          salary: JSON.stringify(salaryCodes),
          enableAi: conf.enableAi ?? 0,
          aiMinScore: normalizeAiScore(conf.aiMinScore),
          maxPerRun: normalizeMaxPerRun(conf.maxPerRun, 8),
          ...maxPerRunRest,
          minDelaySeconds,
          maxDelaySeconds,
          ...rest,
        })
        setSelectedSalaries(salaryCodes)
        setIsCustomArea(false)
      }
    } catch (e) {
      console.warn('[51job] 获取配置异常（可能后端未启动）:', e)
    } finally {
      setLoadingConfig(false)
    }
  }

  // 后端可用性探测：成功则加载配置，否则停止加载并禁用SSE
  useEffect(() => {
    (async () => {
      try {
        const res = await fetch(`${API}/api/51job/config`, { method: 'GET' })
        const ok = !!res && res.ok
        setBackendAvailable(ok)
        if (ok) {
          await fetchAllData()
        } else {
          setLoadingConfig(false)
        }
      } catch {
        setBackendAvailable(false)
        setLoadingConfig(false)
      }
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- initial configuration probe only
  }, [])

  // 投递进度用 SSE 实时推送，状态接口负责刷新按钮状态和补回最近日志。
  useEffect(() => {
    if (!backendAvailable || typeof window === 'undefined' || typeof EventSource === 'undefined') {
      return
    }

    let disposed = false
    let timer: number | undefined

    const refreshTaskStatus = async (): Promise<Record<string, unknown> | null> => {
      try {
        const response = await fetch(`${API}/api/51job/status`)
        const data = await response.json() as Record<string, unknown>
        if (!response.ok || data.success === false) {
          throw new Error(typeof data.message === 'string' ? data.message : '状态接口返回异常')
        }
        applyTaskStatus(data)
        return data
      } catch (error) {
        console.error('[51job] 获取投递状态失败:', error)
        if (!disposed) {
          setTaskMessage('无法读取投递状态，请检查后端服务。')
          setTaskMessageType('error')
        }
        return null
      }
    }

    const poll = async () => {
      const data = await refreshTaskStatus()
      if (disposed) return
      const running = data?.isRunning === true
      const taskState = typeof data?.taskState === 'string' ? data.taskState : ''
      const delay = taskState === 'WAITING' || taskState === 'STOPPING'
        ? 250
        : taskState === 'WAITING_VERIFICATION' || taskState === 'VERIFICATION_REQUIRED'
          ? 500
          : running ? 1000 : 3000
      timer = window.setTimeout(poll, delay)
    }

    const client = createSSEWithBackoff(`${API}/api/51job/stream`, {
      onOpen: () => console.log('[51job 进度 SSE] 连接已打开'),
      onError: (error, attempt, delay) => {
        console.warn(`[51job 进度 SSE] 连接错误，准备第${attempt}次重连，延迟 ${delay}ms`, error)
      },
      listeners: [
        {
          name: 'progress',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data) as TaskStatusMessage & { platform?: string }
              if (data.platform === '51job') appendTaskMessage(data)
            } catch (error) {
              console.error('[51job 进度 SSE] 解析消息失败:', error)
            }
          },
        },
        { name: 'connected', handler: () => {} },
        { name: 'ping', handler: () => {} },
      ],
    })

    poll()
    return () => {
      disposed = true
      if (timer !== undefined) window.clearTimeout(timer)
      client.close()
    }
  }, [backendAvailable])

  useEffect(() => {
    if (taskState !== 'RESTING') return
    setRestNow(Date.now())
    const timer = window.setInterval(() => setRestNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [taskState])

  useEffect(() => {
    const log = statusLogRef.current
    if (log) log.scrollTop = log.scrollHeight
  }, [recentTaskMessages])

  const handleStartDelivery = async () => {
    try {
      setIsDelivering(true)
      setIsStopping(false)
      const startingMessage: TaskStatusMessage = {
        type: 'info',
        message: '正在启动投递任务...',
        timestamp: Date.now(),
      }
      setTaskMessage(startingMessage.message)
      setTaskMessageType(startingMessage.type || 'info')
      setRecentTaskMessages([startingMessage])
      const response = await fetch(`${API}/api/51job/start`, { method: 'POST' })
      const data = await response.json() as { success?: boolean; message?: string }
      if (!response.ok || !data.success) {
        const message = data.message || '启动投递失败。'
        console.warn('[51job] 启动失败：', message)
        setIsDelivering(false)
        appendTaskMessage({ type: 'error', message, timestamp: Date.now() })
        return
      }
      appendTaskMessage({ type: 'info', message: data.message || '投递任务已启动。', timestamp: Date.now() })
    } catch (error) {
      console.error('[51job] 启动投递失败：', error)
      setIsDelivering(false)
      appendTaskMessage({ type: 'error', message: '启动投递失败：网络或服务异常。', timestamp: Date.now() })
    }
  }

  const handleStopDelivery = async () => {
    try {
      setIsStopping(true)
      appendTaskMessage({ type: 'info', message: '正在发送停止请求...', timestamp: Date.now() })
      const response = await fetch(`${API}/api/51job/stop`, { method: 'POST' })
      if (!response.ok) {
        console.warn('[51job] 停止投递请求失败，状态码:', response.status)
        setIsStopping(false)
        appendTaskMessage({ type: 'error', message: '停止投递请求失败。', timestamp: Date.now() })
        return
      }
      const data = await response.json() as { success?: boolean; message?: string }
      if (data.success) {
        appendTaskMessage({ type: 'info', message: data.message || '停止请求已发送，等待任务收尾。', timestamp: Date.now() })
      } else {
        console.warn('[51job] 停止投递失败:', data.message)
        setIsStopping(false)
        appendTaskMessage({ type: 'warning', message: data.message || '当前没有正在运行的任务。', timestamp: Date.now() })
      }
    } catch (error) {
      console.error('[51job] 停止投递请求异常:', error)
      setIsStopping(false)
      appendTaskMessage({ type: 'error', message: '停止投递失败：网络或服务异常。', timestamp: Date.now() })
    }
  }

  const triggerLogout = async () => {
    try {
      const response = await fetch(`${API}/api/51job/logout`, { method: 'POST' })
      const data = await response.json()
      setIsLoggedIn(false)
      setLogoutResult({ success: data.success, message: data.success ? '已退出登录，Cookie已清空。' : data.message })
      setShowLogoutResultDialog(true)
    } catch {
      setLogoutResult({ success: false, message: '退出登录失败：网络或服务异常。' })
      setShowLogoutResultDialog(true)
    }
  }

  const handleSaveConfig = async () => {
    try {
      // 将 jobArea / salary 统一为“中文名”的括号列表字符串，满足后端保存中文的要求
      const toBracketListString = (v?: string, type?: 'jobArea' | 'salary') => {
        const t = (v || '').trim()
        if (!t) return '[]'
        if (type === 'jobArea') {
          // 城市允许手动输入，若下拉匹配不到则直接保存输入值
          const match = (options.jobArea || []).find((o) => o.code === t || o.name === t)
          const name = match?.name || t
          return `["${name.replace(/"/g, '\\"')}"]`
        }
        if (type === 'salary') {
          // 薪资多选：将selectedSalaries数组映射为中文名数组
          const names = selectedSalaries
            .map(code => {
              const match = (options.salary || []).find(o => o.code === code)
              return match?.name || ''
            })
            .filter(Boolean)
          return names.length > 0 ? JSON.stringify(names) : '[]'
        }
        return `["${t.replace(/"/g, '\\"')}"]"`
      }
      const minDelaySeconds = clampAiDelay(config.minDelaySeconds, MIN_AI_DELAY_SECONDS)
      const maxDelaySeconds = Math.max(
        minDelaySeconds,
        clampAiDelay(config.maxDelaySeconds, 40),
      )
      const rest = normalizeRestConfig(config)
      const maxPerRunRest = normalizeMaxPerRunRestConfig(config)
      const payload = {
        ...config,
        keywords: keywordLinesForStorage(config.keywords),
        jobArea: toBracketListString(config.jobArea, 'jobArea'),
        salary: toBracketListString(config.salary, 'salary'),
        enableAi: config.enableAi ? 1 : 0,
        aiMinScore: normalizeAiScore(config.aiMinScore),
        maxPerRun: normalizeMaxPerRun(config.maxPerRun, 8),
        ...maxPerRunRest,
        minDelaySeconds,
        maxDelaySeconds,
        ...rest,
      }
      const response = await fetch(`${API}/api/51job/config`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })
      if (response.ok) {
        await fetchAllData()
        setSaveResult({ success: true, message: '保存成功，配置已更新。Cookie 请在登录卡片中手动获取。' })
      } else {
        setSaveResult({ success: false, message: '保存失败：后端返回异常状态。' })
      }
      setShowSaveDialog(true)
    } catch (error) {
      console.error('[51job] 保存配置失败:', error)
      setSaveResult({ success: false, message: '保存失败：网络或服务异常。' })
      setShowSaveDialog(true)
    }
  }

  const restReasonLabel = (reason: string | null): string => {
    switch (reason) {
      case 'daily_limit': return '已达到今日投递上限'
      case 'access_verification': return '页面出现访问验证'
      case 'detail_blocked': return '详情页访问受限'
      case 'empty_job_ids': return '岗位标识读取异常'
      case 'verification_timeout': return '访问验证等待超时'
      default: return reason ? `触发原因：${reason}` : '任务正在智能休息'
    }
  }

  const restRemainingSeconds = Math.max(0, Math.ceil((restUntil - restNow) / 1000))
  const restCountdown = restRemainingSeconds >= 3600
    ? `${Math.floor(restRemainingSeconds / 3600)}小时${Math.floor((restRemainingSeconds % 3600) / 60)}分钟`
    : restRemainingSeconds >= 60
      ? `${Math.floor(restRemainingSeconds / 60)}分${restRemainingSeconds % 60}秒`
      : `${restRemainingSeconds}秒`

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<Briefcase className="text-2xl" />}
        title="51job配置"
        subtitle="配置51job平台的求职参数"
        iconClass="text-white"
        accentBgClass="bg-blue-500"
        actions={
          <div className="flex items-center gap-2">
            {checkingLogin ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <Play className="mr-1" /> 检查登录中...
              </Button>
            ) : !isLoggedIn ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <Play className="mr-1" /> 请先登录51job
              </Button>
            ) : isDelivering ? (
              <Button onClick={handleStopDelivery} disabled={isStopping} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 disabled:opacity-70 disabled:cursor-wait text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
                <Square className="mr-1" /> {isStopping ? '停止中...' : '停止投递'}
              </Button>
            ) : (
              <Button onClick={handleStartDelivery} size="sm" className="rounded-full bg-gradient-to-r from-teal-500 to-green-500 hover:from-teal-600 hover:to-green-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
                <Play className="mr-1" /> 开始投递
              </Button>
            )}
            <Button onClick={() => setShowLogoutDialog(true)} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-pink-500 hover:from-red-600 hover:to-pink-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
              <LogOut className="mr-1" /> 退出登录
            </Button>
            <Button onClick={handleSaveConfig} size="sm" className="rounded-full bg-gradient-to-r from-blue-500 to-indigo-500 hover:from-blue-600 hover:to-indigo-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
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
          aria-label="51job投递实时运行记录"
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

      <div className="rounded-lg border border-emerald-200 bg-emerald-50/60 px-4 py-3">
        <div className="mb-2 flex flex-wrap items-center justify-between gap-3">
          <span className="text-sm font-medium text-emerald-900">本轮 AI 投递漏斗</span>
          <span className="text-xs text-emerald-700">状态 {taskState}</span>
        </div>
        <div className="grid grid-cols-2 gap-2 text-xs text-emerald-900 sm:grid-cols-4">
          <span>候选 {deliverySummary.candidates ?? 0}</span>
          <span>已读取 JD {deliverySummary.aiProcessed ?? 0}</span>
          <span>发送招呼 {deliverySummary.greetingSent ?? 0}</span>
          <span>直接投递 {deliverySummary.aiDelivered ?? 0}</span>
          <span>跳过 {deliverySummary.skipped ?? 0}</span>
          <span>失败 {deliverySummary.failed ?? 0}</span>
          <span>普通批量已选 {deliverySummary.selected ?? 0}</span>
          <span>普通批量确认 {deliverySummary.confirmed ?? 0}</span>
        </div>
      </div>

      {taskState === 'RESTING' && (
        <div role="status" aria-live="polite" className="rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-amber-950">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="font-semibold">智能休息中 · {restReasonLabel(restReason)}</span>
            <span className="font-mono text-lg font-semibold tabular-nums">{restCountdown}</span>
          </div>
          <div className="mt-1 text-sm">
            第 {restAttempt || 1} 次休息，预计 {restUntil ? new Date(restUntil).toLocaleTimeString('zh-CN', { hour12: false }) : '--:--:--'} 恢复；任务会自动继续，当前只保留停止操作。
          </div>
          {resumeCursor && (
            <div className="mt-1 text-xs text-amber-800">
              恢复位置：关键词 {resumeCursor.keyword || `#${(resumeCursor.keywordIndex ?? 0) + 1}`}，第 {resumeCursor.page || 1} 页
            </div>
          )}
        </div>
      )}

      {(taskState === 'VERIFICATION_REQUIRED' || taskState === 'WAITING_VERIFICATION') && (
        <div role="status" aria-live="polite" className="rounded-lg border border-sky-300 bg-sky-50 px-4 py-3 text-sky-950">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="font-semibold">正在等待访问验证</span>
            <span className="text-sm">请在已打开的浏览器窗口完成验证</span>
          </div>
          {verificationCursor && (
            <div className="mt-1 text-xs text-sky-800">
              当前岗位：{verificationCursor.title || `#${verificationCursor.jobId ?? '-'}`}；恢复位置：关键词 {verificationCursor.keyword || `#${(verificationCursor.keywordIndex ?? 0) + 1}`}，第 {verificationCursor.page || 1} 页
            </div>
          )}
        </div>
      )}

      <div className="space-y-6 mt-6">
          <CookieLoginCard
            platform="51job"
            platformName="51job"
            siteUrl="https://www.51job.com"
            apiBase={API}
            isLoggedIn={isLoggedIn}
            onImportResult={(loggedIn) => {
              setIsLoggedIn(loggedIn)
              setCheckingLogin(false)
            }}
          />

          {/* 配置表单 */}
          <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Briefcase className="text-primary" />
                配置参数
              </CardTitle>
            </CardHeader>
            <CardContent>
              {loadingConfig ? (
                <p className="text-sm text-muted-foreground">配置加载中...</p>
              ) : (
                <>
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label>搜索关键词（每行一个）</Label>
                    <Textarea
                      placeholder="每行输入一个关键词"
                      value={config.keywords || ''}
                      onChange={(e) => setConfig((c) => ({ ...c, keywords: e.target.value }))}
                      rows={8}
                    />
                  </div>
                  <div className="space-y-2">
                    <div className="flex items-center justify-between">
                      <Label>城市区域</Label>
                      <button
                        type="button"
                        onClick={() => {
                          setIsCustomArea(!isCustomArea)
                          if (!isCustomArea) setConfig((c) => ({ ...c, jobArea: '' }))
                        }}
                        className="text-xs text-primary hover:underline"
                      >
                        {isCustomArea ? '从列表选择' : '手动输入'}
                      </button>
                    </div>
                    {isCustomArea ? (
                      <Input
                        placeholder="请输入城市码，例如：410"
                        value={config.jobArea || ''}
                        onChange={(e) => setConfig((c) => ({ ...c, jobArea: e.target.value }))}
                      />
                    ) : (
                      <Select
                        value={config.jobArea || ''}
                        onChange={(e) => setConfig((c) => ({ ...c, jobArea: e.target.value }))}
                        placeholder="请选择城市"
                      >
                        <option value="">请选择城市</option>
                        {options.jobArea.map((o) => (
                          <option key={o.code} value={o.code}>{o.name}</option>
                        ))}
                      </Select>
                    )}
                    <p className="text-xs text-muted-foreground">
                      {isCustomArea ? '手动输入城市码（例如：410代表北京）' : '从列表选择城市，或点击"手动输入"自定义'}
                    </p>
                  </div>
                  <div className="space-y-2">
                    <Label>薪资范围（最多选择5个）</Label>
                    <div className="relative salary-dropdown-container">
                      {/* 下拉多选框 */}
                      <div
                        className="flex h-10 w-full rounded-full px-4 py-2 text-sm border border-white/40 bg-white/5 shadow-[inset_0_1px_0_rgba(255,255,255,.25)] cursor-pointer hover:bg-white/10 transition-all duration-200"
                        onClick={() => {
                          setSalaryDropdownOpen((v) => {
                            const next = !v
                            if (next) setSalarySearchQuery('')
                            return next
                          })
                        }}
                      >
                        <span className="truncate text-sm">
                          {selectedSalaries.length > 0
                            ? selectedSalaries
                                .map((code) => options.salary.find((o) => o.code === code)?.name)
                                .filter(Boolean)
                                .join(', ')
                            : '请选择薪资范围'}
                        </span>
                      </div>
                      {/* 下拉选项面板 */}
                      {salaryDropdownOpen && (
                        <div className="dropdown-panel" style={{ position: 'absolute', marginTop: '8px', width: '100%', zIndex: 50 }}>
                          <div className="dropdown-search sticky top-0 z-10 border-b border-black/5 bg-white/95 p-2 dark:border-white/10 dark:bg-neutral-900/95">
                            <input
                              type="text"
                              value={salarySearchQuery}
                              onChange={(e) => setSalarySearchQuery(e.target.value)}
                              onClick={(e) => e.stopPropagation()}
                              onKeyDown={(e) => e.stopPropagation()}
                              placeholder="搜索薪资…"
                              autoFocus
                              className="h-8 w-full rounded-full border border-black/10 bg-white/80 px-3 text-sm outline-none ring-0 placeholder:text-muted-foreground focus:border-emerald-300/70 focus:ring-2 focus:ring-emerald-400/30 dark:border-white/15 dark:bg-white/5"
                            />
                          </div>
                          <ul className="dropdown-panel-list py-1">
                            {options.salary
                              .filter((o) => {
                                const q = salarySearchQuery.trim().toLowerCase()
                                if (!q) return true
                                return (
                                  (o.name || '').toLowerCase().includes(q) ||
                                  (o.code || '').toLowerCase().includes(q)
                                )
                              })
                              .map((o) => {
                              const isSelected = selectedSalaries.includes(o.code)
                              const canSelect = selectedSalaries.length < MAX_SALARY_SELECTIONS || isSelected
                              return (
                                <li
                                  key={o.code}
                                  className={`group flex items-center gap-3 px-3 py-2 cursor-pointer transition-all border-b border-white/12 last:border-b-0 ${
                                    !canSelect ? 'opacity-50 cursor-not-allowed' : 'hover:bg-white/12'
                                  } ${
                                    isSelected ? 'bg-gradient-to-r from-emerald-500/12 to-cyan-500/12' : ''
                                  }`}
                                  onClick={() => {
                                    if (!canSelect) return
                                    let newSalaries: string[]
                                    if (isSelected) {
                                      newSalaries = selectedSalaries.filter((code) => code !== o.code)
                                    } else {
                                      if (selectedSalaries.length < MAX_SALARY_SELECTIONS) {
                                        newSalaries = [...selectedSalaries, o.code]
                                      } else {
                                        return
                                      }
                                    }
                                    setSelectedSalaries(newSalaries)
                                    setConfig((c) => ({ ...c, salary: JSON.stringify(newSalaries) }))
                                  }}
                                >
                                  <span
                                    className={`inline-flex h-4 w-4 items-center justify-center rounded-md border border-white/30 bg-white/10 shadow-inner transition-all ${
                                      isSelected ? 'bg-emerald-400/60 border-emerald-300/80' : ''
                                    }`}
                                  >
                                    {isSelected && <Check className="h-3 w-3 text-white" strokeWidth={3} aria-hidden="true" />}
                                  </span>
                                  <span className="text-sm truncate">{o.name}</span>
                                </li>
                              )
                            })}
                          </ul>
                        </div>
                      )}
                    </div>
                    <p className="text-xs text-muted-foreground">
                      已选择 {selectedSalaries.length}/{MAX_SALARY_SELECTIONS} 个薪资范围
                    </p>
                  </div>
                  </div>
                  <div className="mt-6 rounded-xl border border-blue-200/60 bg-blue-50/50 p-4 dark:border-blue-800/50 dark:bg-blue-950/20">
                  <div className="flex flex-wrap items-center justify-between gap-4">
                    <div>
                      <Label htmlFor="job51-ai-toggle" className="text-sm font-semibold">AI逐岗位打招呼</Label>
                      <p className="mt-1 text-xs text-muted-foreground">逐个读取岗位JD，按 AI JD 分析筛选通过后才生成文案并发送。</p>
                    </div>
                    <label className="inline-flex cursor-pointer items-center gap-2 text-sm">
                      <input
                        id="job51-ai-toggle"
                        type="checkbox"
                        checked={config.enableAi === 1}
                        onChange={(e) => setConfig((c) => ({ ...c, enableAi: e.target.checked ? 1 : 0 }))}
                        className="h-4 w-4 accent-blue-600"
                      />
                      启用
                    </label>
                  </div>
                  <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-4">
                    <div className="space-y-2">
                      <Label htmlFor="job51-ai-min-score">JD分析通过分数</Label>
                      <Input
                        id="job51-ai-min-score"
                        type="number"
                        min={MIN_AI_SCORE}
                        max={MAX_AI_SCORE}
                        value={config.aiMinScore ?? 70}
                        disabled={config.enableAi !== 1}
                        onChange={(e) => setConfig((c) => ({
                          ...c,
                          aiMinScore: Math.min(MAX_AI_SCORE, Math.max(MIN_AI_SCORE, Number(e.target.value) || 0)),
                        }))}
                      />
                      <p className="text-xs text-muted-foreground">仅 PASS 且达到此分数的岗位会继续</p>
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="job51-max-per-run">单次处理上限</Label>
                      <Input
                        id="job51-max-per-run"
                        type="number"
                        min={0}
                        value={config.maxPerRun ?? 8}
                        disabled={config.enableAi !== 1}
                        onChange={(e) => setConfig((c) => ({ ...c, maxPerRun: Number(e.target.value) }))}
                      />
                      <p className="text-xs text-muted-foreground">0 表示不设置最大值</p>
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="job51-stop-after-max">达到上限后</Label>
                      <label className="flex h-10 items-center gap-3 rounded-md border px-3 text-sm">
                        <input
                          id="job51-stop-after-max"
                          type="checkbox"
                          checked={config.stopAfterMaxPerRun !== 0}
                          disabled={config.enableAi !== 1 || config.maxPerRun === 0}
                          onChange={(e) => setConfig((c) => ({
                            ...c,
                            stopAfterMaxPerRun: e.target.checked ? 1 : 0,
                          }))}
                          className="h-4 w-4 accent-blue-600"
                        />
                        <span>{config.stopAfterMaxPerRun !== 0 ? '停止任务' : '休息后继续'}</span>
                      </label>
                      <p className="text-xs text-muted-foreground">关闭后按短间歇自动继续</p>
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="job51-max-rest-min">继续前最短休息（秒）</Label>
                      <Input
                        id="job51-max-rest-min"
                        type="number"
                        min={MIN_MAX_PER_RUN_REST_SECONDS}
                        max={MAX_MAX_PER_RUN_REST_SECONDS}
                        value={config.maxPerRunRestMinSeconds ?? DEFAULT_MAX_PER_RUN_REST_MIN_SECONDS}
                        disabled={config.enableAi !== 1 || config.stopAfterMaxPerRun !== 0 || config.maxPerRun === 0}
                        onChange={(e) => setConfig((c) => ({ ...c, maxPerRunRestMinSeconds: Number(e.target.value) }))}
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="job51-max-rest-max">继续前最长休息（秒）</Label>
                      <Input
                        id="job51-max-rest-max"
                        type="number"
                        min={MIN_MAX_PER_RUN_REST_SECONDS}
                        max={MAX_MAX_PER_RUN_REST_SECONDS}
                        value={config.maxPerRunRestMaxSeconds ?? DEFAULT_MAX_PER_RUN_REST_MAX_SECONDS}
                        disabled={config.enableAi !== 1 || config.stopAfterMaxPerRun !== 0 || config.maxPerRun === 0}
                        onChange={(e) => setConfig((c) => ({ ...c, maxPerRunRestMaxSeconds: Number(e.target.value) }))}
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="job51-min-delay">最小间隔（秒）</Label>
                      <Input
                        id="job51-min-delay"
                        type="number"
                        min={MIN_AI_DELAY_SECONDS}
                        max={MAX_AI_DELAY_SECONDS}
                        value={config.minDelaySeconds ?? 20}
                        disabled={config.enableAi !== 1}
                        onChange={(e) => setConfig((c) => {
                          const minDelaySeconds = clampAiDelay(Number(e.target.value), MIN_AI_DELAY_SECONDS)
                          return {
                            ...c,
                            minDelaySeconds,
                            maxDelaySeconds: Math.max(
                              minDelaySeconds,
                              clampAiDelay(c.maxDelaySeconds, 40),
                            ),
                          }
                        })}
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="job51-max-delay">最大间隔（秒）</Label>
                      <Input
                        id="job51-max-delay"
                        type="number"
                        min={MIN_AI_DELAY_SECONDS}
                        max={MAX_AI_DELAY_SECONDS}
                        value={config.maxDelaySeconds ?? 40}
                        disabled={config.enableAi !== 1}
                        onChange={(e) => setConfig((c) => ({
                          ...c,
                          maxDelaySeconds: Math.max(
                            clampAiDelay(c.minDelaySeconds, MIN_AI_DELAY_SECONDS),
                            clampAiDelay(Number(e.target.value), 40),
                          ),
                        }))}
                      />
                    </div>
                  </div>
                  </div>
                  <div className="mt-6 rounded-xl border border-amber-200/70 bg-amber-50/60 p-4 dark:border-amber-800/50 dark:bg-amber-950/20">
                    <div className="flex flex-wrap items-center justify-between gap-4">
                      <div>
                        <Label htmlFor="job51-rest-toggle" className="text-sm font-semibold">间歇智能休息</Label>
                        <p className="mt-1 text-xs text-muted-foreground">遇到访问验证、详情受限或岗位标识异常时暂歇，恢复后从当前关键词和页面继续。</p>
                      </div>
                      <label className="inline-flex cursor-pointer items-center gap-2 text-sm">
                        <input
                          id="job51-rest-toggle"
                          type="checkbox"
                          checked={config.restEnabled !== 0}
                          onChange={(e) => setConfig((c) => ({ ...c, restEnabled: e.target.checked ? 1 : 0 }))}
                          className="h-4 w-4 accent-amber-600"
                        />
                        启用
                      </label>
                    </div>
                    <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-4">
                      <div className="space-y-2">
                        <Label htmlFor="job51-rest-stage1-min">第一档最短（秒）</Label>
                        <Input id="job51-rest-stage1-min" type="number" min={MIN_REST_SECONDS} max={MAX_REST_SECONDS} disabled={config.restEnabled === 0} value={config.restStage1MinSeconds ?? DEFAULT_REST_STAGE1_MIN_SECONDS} onChange={(e) => setConfig((c) => ({ ...c, restStage1MinSeconds: Number(e.target.value) }))} />
                      </div>
                      <div className="space-y-2">
                        <Label htmlFor="job51-rest-stage1-max">第一档最长（秒）</Label>
                        <Input id="job51-rest-stage1-max" type="number" min={MIN_REST_SECONDS} max={MAX_REST_SECONDS} disabled={config.restEnabled === 0} value={config.restStage1MaxSeconds ?? DEFAULT_REST_STAGE1_MAX_SECONDS} onChange={(e) => setConfig((c) => ({ ...c, restStage1MaxSeconds: Number(e.target.value) }))} />
                      </div>
                      <div className="space-y-2">
                        <Label htmlFor="job51-rest-stage2-min">第二档最短（秒）</Label>
                        <Input id="job51-rest-stage2-min" type="number" min={MIN_REST_SECONDS} max={MAX_REST_SECONDS} disabled={config.restEnabled === 0} value={config.restStage2MinSeconds ?? DEFAULT_REST_STAGE2_MIN_SECONDS} onChange={(e) => setConfig((c) => ({ ...c, restStage2MinSeconds: Number(e.target.value) }))} />
                      </div>
                      <div className="space-y-2">
                        <Label htmlFor="job51-rest-stage2-max">第二档最长（秒）</Label>
                        <Input id="job51-rest-stage2-max" type="number" min={MIN_REST_SECONDS} max={MAX_REST_SECONDS} disabled={config.restEnabled === 0} value={config.restStage2MaxSeconds ?? DEFAULT_REST_STAGE2_MAX_SECONDS} onChange={(e) => setConfig((c) => ({ ...c, restStage2MaxSeconds: Number(e.target.value) }))} />
                      </div>
                      <div className="space-y-2">
                        <Label htmlFor="job51-rest-stage3-min">第三档最短（秒）</Label>
                        <Input id="job51-rest-stage3-min" type="number" min={MIN_REST_SECONDS} max={MAX_REST_SECONDS} disabled={config.restEnabled === 0} value={config.restStage3MinSeconds ?? DEFAULT_REST_STAGE3_MIN_SECONDS} onChange={(e) => setConfig((c) => ({ ...c, restStage3MinSeconds: Number(e.target.value) }))} />
                      </div>
                      <div className="space-y-2">
                        <Label htmlFor="job51-rest-stage3-max">第三档最长（秒）</Label>
                        <Input id="job51-rest-stage3-max" type="number" min={MIN_REST_SECONDS} max={MAX_REST_SECONDS} disabled={config.restEnabled === 0} value={config.restStage3MaxSeconds ?? DEFAULT_REST_STAGE3_MAX_SECONDS} onChange={(e) => setConfig((c) => ({ ...c, restStage3MaxSeconds: Number(e.target.value) }))} />
                      </div>
                      <div className="space-y-2">
                        <Label htmlFor="job51-rest-consecutive">进入第三档的连续次数</Label>
                        <Input id="job51-rest-consecutive" type="number" min={MIN_REST_CONSECUTIVE} max={MAX_REST_CONSECUTIVE} disabled={config.restEnabled === 0} value={config.restMaxConsecutive ?? DEFAULT_REST_MAX_CONSECUTIVE} onChange={(e) => setConfig((c) => ({ ...c, restMaxConsecutive: Number(e.target.value) }))} />
                      </div>
                    </div>
                    <p className="mt-3 text-xs text-muted-foreground">默认第一档 180-300 秒、第二档 600-900 秒、第三档 1800-2700 秒；达到连续次数后持续使用第三档，触发每日上限则等到本地下一自然日。</p>
                  </div>
                </>
              )}
            </CardContent>
          </Card>
      </div>

      {/* 退出确认弹框 */}
      {showLogoutDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <Card className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border-0">
            <CardHeader className="pb-2">
              <CardTitle className="text-lg flex items-center gap-2">
                <LogOut className="text-red-500" /> 确认退出登录
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">退出后将清除Cookie并切换为未登录状态。</p>
              <div className="flex justify-end gap-2">
                <Button variant="ghost" onClick={() => setShowLogoutDialog(false)} className="rounded-full px-4">取消</Button>
                <Button onClick={async () => { await triggerLogout(); setShowLogoutDialog(false) }} className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 text-white px-4">确认退出</Button>
              </div>
            </CardContent>
          </Card>
        </div>
      )}

      {/* 退出登录结果弹框 */}
      {showLogoutResultDialog && logoutResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
          <Card className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border-0">
            <CardHeader className="pb-2">
              <CardTitle className="text-lg flex items-center gap-2">
                <LogOut className={logoutResult.success ? 'text-green-500' : 'text-red-500'} />
                {logoutResult.success ? '退出登录成功' : '退出登录失败'}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">{logoutResult.message}</p>
              <Button onClick={() => setShowLogoutResultDialog(false)} className={`rounded-full px-4 ${logoutResult.success ? 'bg-green-500' : 'bg-red-500'} text-white`}>知道了</Button>
            </CardContent>
          </Card>
        </div>
      )}

      {/* 保存Cookie结果弹框 */}
      {showSaveDialog && saveResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
          <Card className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border-0">
            <CardHeader className="pb-2">
              <CardTitle className="text-lg flex items-center gap-2">
                <Save className={saveResult.success ? 'text-green-500' : 'text-red-500'} />
                {saveResult.success ? '保存成功' : '保存失败'}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">{saveResult.message}</p>
              <Button onClick={() => setShowSaveDialog(false)} className={`rounded-full px-4 ${saveResult.success ? 'bg-green-500' : 'bg-red-500'} text-white`}>知道了</Button>
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  )
}
