'use client'

import { useState, useEffect, useRef } from 'react'
import { createSSEWithBackoff } from '@/lib/sse'
import { BiSearch, BiSave, BiTargetLock, BiMoney, BiPlay, BiStop, BiLogOut, BiBriefcase, BiCheck, BiX } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import AnalysisContent from '@/app/liepin/analysis/AnalysisContent'
import PageHeader from '@/app/components/PageHeader'
import { keywordLinesForDisplay, keywordLinesForStorage } from '@/lib/keyword-lines'

interface LiepinConfig {
  id?: number
  keywords?: string
  city?: string
  salaryCode?: string
  enableAi?: number
  autoAiDelivery?: number
  aiDeliveryMode?: 'MANUAL' | 'SINGLE_AUTO' | 'BATCH_SHADOW' | 'BATCH_AUTO'
  aiMinScore?: number
  aiReviewMinScore?: number
  aiBatchSize?: number
  maxPerRun?: number
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

interface AiSummary {
  candidateCount?: number
  cacheHits?: number
  screenCalls?: number
  messageCalls?: number
  passed?: number
  review?: number
  skipped?: number
  invalid?: number
  avgLatencyMs?: number
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

export default function LiepinPage() {
  const [config, setConfig] = useState<LiepinConfig>({
    keywords: '',
    city: '',
    salaryCode: '',
    enableAi: 1,
    autoAiDelivery: 0,
    aiDeliveryMode: 'MANUAL',
    aiMinScore: 70,
    aiReviewMinScore: 60,
    aiBatchSize: 5,
    maxPerRun: 10,
    minDelaySeconds: 120,
    maxDelaySeconds: 240,
    searchMinDelaySeconds: 60,
    searchMaxDelaySeconds: 120,
    pageMinDelaySeconds: 30,
    pageMaxDelaySeconds: 60,
    detailMinDelaySeconds: 45,
    detailMaxDelaySeconds: 90,
    rateGuardBatchSize: 5,
    batchCooldownMinSeconds: 1200,
    batchCooldownMaxSeconds: 1800,
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
  const [checkingLogin, setCheckingLogin] = useState(true)
  const [showLogoutDialog, setShowLogoutDialog] = useState(false)
  const [showLogoutResultDialog, setShowLogoutResultDialog] = useState(false)
  const [logoutResult, setLogoutResult] = useState<{ success: boolean; message: string } | null>(null)
  const [taskMessage, setTaskMessage] = useState('尚未启动投递任务')
  const [taskMessageType, setTaskMessageType] = useState('idle')
  const [recentTaskMessages, setRecentTaskMessages] = useState<TaskStatusMessage[]>([
    { type: 'idle', message: '尚未启动投递任务' },
  ])
  const [aiSummary, setAiSummary] = useState<AiSummary>({})
  const statusLogRef = useRef<HTMLDivElement>(null)
  const [pendingGreeting, setPendingGreeting] = useState<PendingGreeting | null>(null)
  const [pageProgress, setPageProgress] = useState<PageProgressItem[]>([])
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
        normalized.maxPerRun = Math.min(data.config.maxPerRun ?? 10, 10)
        normalized.minDelaySeconds = Math.max(data.config.minDelaySeconds ?? 120, 120)
        normalized.maxDelaySeconds = Math.max(data.config.maxDelaySeconds ?? 240, normalized.minDelaySeconds, 240)
        normalized.searchMinDelaySeconds = Math.max(15, Math.min(300, data.config.searchMinDelaySeconds ?? 60))
        normalized.searchMaxDelaySeconds = Math.max(normalized.searchMinDelaySeconds,
          Math.min(300, Math.max(120, data.config.searchMaxDelaySeconds ?? 120)))
        normalized.pageMinDelaySeconds = Math.max(15, Math.min(300, data.config.pageMinDelaySeconds ?? 30))
        normalized.pageMaxDelaySeconds = Math.max(normalized.pageMinDelaySeconds,
          Math.min(300, Math.max(60, data.config.pageMaxDelaySeconds ?? 60)))
        normalized.detailMinDelaySeconds = Math.max(15, Math.min(300, data.config.detailMinDelaySeconds ?? 45))
        normalized.detailMaxDelaySeconds = Math.max(normalized.detailMinDelaySeconds,
          Math.min(300, Math.max(90, data.config.detailMaxDelaySeconds ?? 90)))
        normalized.rateGuardBatchSize = Math.max(1, Math.min(10, data.config.rateGuardBatchSize ?? 5))
        normalized.batchCooldownMinSeconds = Math.max(300, Math.min(3600, data.config.batchCooldownMinSeconds ?? 1200))
        normalized.batchCooldownMaxSeconds = Math.max(normalized.batchCooldownMinSeconds,
          Math.min(3600, data.config.batchCooldownMaxSeconds ?? 1800))
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
        setPageProgress(data.pageProgress)
      } else {
        setPageProgress([])
      }
    } catch (error) {
      console.error('Failed to fetch liepin data:', error)
    } finally {
      setLoading(false)
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
      setPageProgress(Array.isArray(data.pageProgress) ? data.pageProgress : [])
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
      setIsDelivering(Boolean(data.isRunning))
      if (data.message) setTaskMessage(data.message)
      if (data.messageType) setTaskMessageType(data.messageType)
      const serverMessages = Array.isArray(data.recentMessages)
        ? data.recentMessages.filter((item: TaskStatusMessage) => item && typeof item.message === 'string')
        : []
      if (serverMessages.length > 0) {
        setRecentTaskMessages(serverMessages.slice(-8))
      } else if (data.message) {
        setRecentTaskMessages([
          {
            type: data.messageType || 'info',
            message: data.message,
            timestamp: data.messageAt,
          },
        ])
      }
      setAiSummary(data.aiSummary || {})
      setPendingGreeting(data.pendingGreeting || null)
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
      if (disposed) return
      timer = window.setTimeout(poll, data?.isRunning ? 1000 : 3000)
    }

    poll()
    return () => {
      disposed = true
      if (timer !== undefined) window.clearTimeout(timer)
    }
    // 状态轮询只在页面生命周期内运行一次
  }, [])

  useEffect(() => {
    const log = statusLogRef.current
    if (log) log.scrollTop = log.scrollHeight
  }, [recentTaskMessages])

  const handleSave = async () => {
    try {
      const payload = { ...config, keywords: keywordLinesForStorage(config.keywords) }
      const response = await fetch('http://localhost:8888/api/liepin/config', {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
      })

      if (response.ok) {
        // 统一保存 Cookie（Liepin）
        try {
          await fetch('http://localhost:8888/api/cookie/save?platform=liepin', { method: 'POST' })
        } catch (e) {
          console.warn('保存 Cookie 失败（Liepin）:', e)
        }

        fetchAllData()
        setSaveResult({ success: true, message: '保存成功，配置与Cookie已更新。' })
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

  const handleConfirmGreeting = async (action: 'ai' | 'preset' | 'skip') => {
    if (!pendingGreeting) return
    try {
      const response = await fetch('http://localhost:8888/api/liepin/confirm', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ jobId: pendingGreeting.jobId, action }),
      })
      const data = await response.json()
      setTaskMessage(data.message || (data.success ? '已确认，继续处理岗位。' : '确认失败。'))
      setTaskMessageType(data.success ? 'success' : 'error')
      if (data.success) setPendingGreeting(null)
    } catch (error) {
      console.error('[猎聘] 确认发送失败:', error)
      setTaskMessage('确认发送失败：网络或服务异常。')
      setTaskMessageType('error')
    }
  }

  const handleStartDelivery = async () => {
    try {
      setIsDelivering(true)
      setTaskMessage('正在启动投递任务...')
      setTaskMessageType('info')
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
      setTaskMessage(data.message || '投递任务已启动。')
      setTaskMessageType('info')
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

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiSearch className="text-2xl" />}
        title="猎聘配置"
        subtitle="配置猎聘平台的求职参数"
        iconClass="text-white"
        accentBgClass="bg-purple-500"
        actions={
          <div className="flex items-center gap-2">
            {checkingLogin ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <BiPlay className="mr-1" /> 检查登录中...
              </Button>
            ) : !isLoggedIn ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <BiPlay className="mr-1" /> 请先登录猎聘
              </Button>
            ) : isDelivering ? (
              <Button onClick={handleStopDelivery} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
                <BiStop className="mr-1" /> 停止投递
              </Button>
            ) : (
              <Button onClick={handleStartDelivery} size="sm" className="rounded-full bg-gradient-to-r from-teal-500 to-green-500 hover:from-teal-600 hover:to-green-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
                <BiPlay className="mr-1" /> 开始投递
              </Button>
            )}
            <Button onClick={() => setShowLogoutDialog(true)} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-pink-500 hover:from-red-600 hover:to-pink-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
              <BiLogOut className="mr-1" /> 退出登录
            </Button>
            <Button onClick={handleSave} size="sm" className="rounded-full bg-gradient-to-r from-blue-500 to-indigo-500 hover:from-blue-600 hover:to-indigo-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
              <BiSave className="mr-1" /> 保存配置
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

      <div className="rounded-lg border border-emerald-200 bg-emerald-50/60 px-4 py-3">
        <div className="mb-2 flex items-center justify-between gap-3">
          <span className="text-sm font-medium text-emerald-900">本轮 AI 处理</span>
          <span className="text-xs text-emerald-700">平均响应 {aiSummary.avgLatencyMs ?? 0}ms</span>
        </div>
        <div className="grid grid-cols-2 gap-2 text-xs text-emerald-900 sm:grid-cols-5">
          <span>候选 {aiSummary.candidateCount ?? 0}</span>
          <span>缓存命中 {aiSummary.cacheHits ?? 0}</span>
          <span>评分请求 {aiSummary.screenCalls ?? 0}</span>
          <span>话术请求 {aiSummary.messageCalls ?? 0}</span>
          <span>通过 / 复核 {aiSummary.passed ?? 0} / {aiSummary.review ?? 0}</span>
        </div>
      </div>

      {pendingGreeting && (
        <Card className="border-amber-300 bg-amber-50/60">
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-base">
              <BiTargetLock className="text-amber-600" />
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
                  <BiCheck className="mr-1" />确认发送 AI 话术
                </Button>
              )}
              <Button onClick={() => handleConfirmGreeting('preset')} size="sm" variant="outline">
                <BiCheck className="mr-1" />发送预设语
              </Button>
              <Button onClick={() => handleConfirmGreeting('skip')} size="sm" variant="ghost">
                <BiX className="mr-1" />跳过
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      <Tabs defaultValue="config" className="w-full">
        <TabsList className="grid w-full grid-cols-2">
          <TabsTrigger value="config">平台配置</TabsTrigger>
          <TabsTrigger value="analytics">投递分析</TabsTrigger>
        </TabsList>

        <TabsContent value="config" className="space-y-6 mt-6">
        {/* 平台说明 */}
        <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <BiBriefcase className="text-primary" />
              猎聘平台说明
            </CardTitle>
            <CardDescription>登录与投递操作提示</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              <p className="text-sm text-muted-foreground">请在浏览器标签页中登录 猎聘 平台，登录成功后系统会自动检测登录状态。</p>
              <p className="text-sm text-muted-foreground">登录成功后，点击“开始投递”按钮启动自动投递任务。</p>
              <p className="text-sm text-muted-foreground">点击“保存配置”按钮可手动保存当前登录相关信息到数据库。</p>
            </div>
          </CardContent>
        </Card>

        {/* 搜索配置 */}
        <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <BiSearch className="text-primary" />
              搜索配置
            </CardTitle>
            <CardDescription>设置职位搜索关键词和筛选条件</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              <div className="space-y-2">
                <Label htmlFor="keywords">搜索关键词（每行一个）</Label>
                <Textarea
                  id="keywords"
                  value={config.keywords || ''}
                  onChange={(e) => setConfig({ ...config, keywords: e.target.value })}
                  placeholder="每行输入一个关键词"
                  rows={8}
                />
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
              <BiMoney className="text-primary" />
              薪资筛选
            </CardTitle>
            <CardDescription>设置期望薪资范围</CardDescription>
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
                <p className="text-xs text-muted-foreground">薪资范围码（例如：15$30表示15k-30k）</p>
              </div>
            </div>
          </CardContent>
        </Card>

        <Card className="animate-in fade-in slide-in-from-bottom-6 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <BiTargetLock className="text-primary" />
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
                <p className="text-xs text-muted-foreground">预演模式只评分和生成话术，自动模式只发送 PASS 且话术校验通过的岗位。</p>
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
                <p className="text-xs text-muted-foreground">低于通过分但达到该分数的岗位只记录复核。</p>
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
                <Label htmlFor="maxPerRun">单次岗位上限</Label>
                <Input
                  id="maxPerRun"
                  type="number"
                  min={1}
                  max={10}
                  value={config.maxPerRun ?? 10}
                  onChange={(e) => setConfig({ ...config, maxPerRun: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="minDelaySeconds">发送最小间隔（秒）</Label>
                <Input
                  id="minDelaySeconds"
                  type="number"
                  min={120}
                  max={300}
                  value={config.minDelaySeconds ?? 120}
                  onChange={(e) => setConfig({ ...config, minDelaySeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="maxDelaySeconds">发送最大间隔（秒）</Label>
                <Input
                  id="maxDelaySeconds"
                  type="number"
                  min={120}
                  max={300}
                  value={config.maxDelaySeconds ?? 240}
                  onChange={(e) => setConfig({ ...config, maxDelaySeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="searchMinDelaySeconds">搜索最小间隔（秒）</Label>
                <Input
                  id="searchMinDelaySeconds"
                  type="number"
                  min={15}
                  max={300}
                  value={config.searchMinDelaySeconds ?? 60}
                  onChange={(e) => setConfig({ ...config, searchMinDelaySeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="searchMaxDelaySeconds">搜索最大间隔（秒）</Label>
                <Input
                  id="searchMaxDelaySeconds"
                  type="number"
                  min={15}
                  max={300}
                  value={config.searchMaxDelaySeconds ?? 120}
                  onChange={(e) => setConfig({ ...config, searchMaxDelaySeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="pageMinDelaySeconds">翻页最小间隔（秒）</Label>
                <Input
                  id="pageMinDelaySeconds"
                  type="number"
                  min={15}
                  max={300}
                  value={config.pageMinDelaySeconds ?? 30}
                  onChange={(e) => setConfig({ ...config, pageMinDelaySeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="pageMaxDelaySeconds">翻页最大间隔（秒）</Label>
                <Input
                  id="pageMaxDelaySeconds"
                  type="number"
                  min={15}
                  max={300}
                  value={config.pageMaxDelaySeconds ?? 60}
                  onChange={(e) => setConfig({ ...config, pageMaxDelaySeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="detailMinDelaySeconds">详情最小间隔（秒）</Label>
                <Input
                  id="detailMinDelaySeconds"
                  type="number"
                  min={15}
                  max={300}
                  value={config.detailMinDelaySeconds ?? 45}
                  onChange={(e) => setConfig({ ...config, detailMinDelaySeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="detailMaxDelaySeconds">详情最大间隔（秒）</Label>
                <Input
                  id="detailMaxDelaySeconds"
                  type="number"
                  min={15}
                  max={300}
                  value={config.detailMaxDelaySeconds ?? 90}
                  onChange={(e) => setConfig({ ...config, detailMaxDelaySeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="rateGuardBatchSize">批次成功次数</Label>
                <Input
                  id="rateGuardBatchSize"
                  type="number"
                  min={1}
                  max={10}
                  value={config.rateGuardBatchSize ?? 5}
                  onChange={(e) => setConfig({ ...config, rateGuardBatchSize: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="batchCooldownMinSeconds">批次冷却最小值（秒）</Label>
                <Input
                  id="batchCooldownMinSeconds"
                  type="number"
                  min={300}
                  max={3600}
                  value={config.batchCooldownMinSeconds ?? 1200}
                  onChange={(e) => setConfig({ ...config, batchCooldownMinSeconds: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="batchCooldownMaxSeconds">批次冷却最大值（秒）</Label>
                <Input
                  id="batchCooldownMaxSeconds"
                  type="number"
                  min={300}
                  max={3600}
                  value={config.batchCooldownMaxSeconds ?? 1800}
                  onChange={(e) => setConfig({ ...config, batchCooldownMaxSeconds: Number(e.target.value) })}
                />
              </div>
            </div>
            <p className="mt-3 text-xs text-muted-foreground">
              当前保守基线：搜索 60-120 秒、翻页 30-60 秒、详情 45-90 秒、发送 120-240 秒；命中平台风控信号后任务会停止并记录原因。
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
        </TabsContent>

        <TabsContent value="analytics" className="space-y-6 mt-6">
          <AnalysisContent />
        </TabsContent>
      </Tabs>

      {/* 退出确认弹框 */}
      {showLogoutDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border border-gray-200 dark:border-neutral-800 animate-in fade-in zoom-in-95">
            <Card className="border-0">
              <CardHeader className="pb-2">
                <CardTitle className="text-lg flex items-center gap-2">
                  <BiLogOut className="text-red-500" />
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
                  <BiLogOut className={logoutResult.success ? 'text-green-500' : 'text-red-500'} />
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
                  <BiSave className={saveResult.success ? 'text-green-500' : 'text-red-500'} />
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
