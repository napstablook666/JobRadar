'use client'

import { useState, useEffect } from 'react'
import { createSSEWithBackoff } from '@/lib/sse'
import { LogOut, Save, Briefcase, Play, Square } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import PageHeader from '@/app/components/PageHeader'
import CookieLoginCard from '@/app/components/CookieLoginCard'
import { keywordLinesForDisplay, keywordLinesForStorage } from '@/lib/keyword-lines'

interface ZhilianConfig {
  id?: number
  keywords?: string
  cityCode?: string
  salary?: string
  enableAiScreening?: number
  aiMinScore?: number
}

interface Option { name: string; code: string }
interface ZhilianOptions { city: Option[] }
interface FunnelSummary {
  platform: string
  stages: Record<string, number>
  retryable: number
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

export default function ZhilianPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  const [isDelivering, setIsDelivering] = useState(false)
  const [checkingLogin, setCheckingLogin] = useState(true)
  const [showLogoutDialog, setShowLogoutDialog] = useState(false)
  const [showSaveDialog, setShowSaveDialog] = useState(false)
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string } | null>(null)
  const [showLogoutResultDialog, setShowLogoutResultDialog] = useState(false)
  const [logoutResult, setLogoutResult] = useState<{ success: boolean; message: string } | null>(null)
  const [taskMessage, setTaskMessage] = useState('')
  const [taskMessageType, setTaskMessageType] = useState('info')

  const [config, setConfig] = useState<ZhilianConfig>({ keywords: '', cityCode: '', salary: '', enableAiScreening: 1 })
  const [options, setOptions] = useState<ZhilianOptions>({ city: [] })
  const [loadingConfig, setLoadingConfig] = useState(true)
  const [funnelSummary, setFunnelSummary] = useState<FunnelSummary>({
    platform: 'zhilian',
    stages: {},
    retryable: 0,
  })

  useEffect(() => {
    if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
      console.warn('[智联招聘] EventSource 不可用，无法连接SSE')
      setCheckingLogin(false)
      return
    }

    const client = createSSEWithBackoff('http://localhost:8888/api/jobs/login-status/stream', {
      onOpen: () => console.log('[智联招聘 SSE] 连接已打开'),
      onError: (e, attempt, delay) => {
        console.warn(`[智联招聘 SSE] 连接错误，第${attempt}次重连，延迟 ${delay}ms`, e)
        setCheckingLogin(false)
      },
      listeners: [
        {
          name: 'connected',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              console.log('[智联招聘 SSE] connected事件数据:', data)
              console.log('[智联招聘 SSE] zhilianLoggedIn状态:', data.zhilianLoggedIn)
              setIsLoggedIn(data.zhilianLoggedIn || false)
              setCheckingLogin(false)
            } catch (error) {
              console.error('[智联招聘 SSE] 解析连接消息失败:', error)
            }
          },
        },
        {
          name: 'login-status',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              console.log('[智联招聘 SSE] login-status事件数据:', data)
              if (data.platform === 'zhilian') {
                console.log('[智联招聘 SSE] 智联登录状态变更:', data.isLoggedIn)
                setIsLoggedIn(data.isLoggedIn)
                setCheckingLogin(false)
              }
            } catch (error) {
              console.error('[智联招聘 SSE] 解析登录状态消息失败:', error)
            }
          },
        },
        { name: 'ping', handler: () => {} },
      ],
    })

    return () => client.close()
  }, [])

  const refreshFunnel = async () => {
    try {
      const response = await fetch('http://localhost:8888/api/zhilian/funnel')
      if (!response.ok) throw new Error('读取智联岗位漏斗失败')
      const data = await response.json()
      const stages = data && typeof data.stages === 'object' && data.stages !== null ? data.stages : {}
      setFunnelSummary({
        platform: typeof data.platform === 'string' ? data.platform : 'zhilian',
        stages: Object.fromEntries(Object.entries(stages).map(([key, value]) => [key, Number(value) || 0])),
        retryable: Number(data.retryable) || 0,
      })
    } catch (error) {
      console.warn('[智联] 获取岗位漏斗失败:', error)
    }
  }

  const fetchAllData = async () => {
    try {
      const res = await fetch('http://localhost:8888/api/zhilian/config')
      const data = await res.json()
      if (data.config) {
        const normalized = { ...data.config }
        normalized.keywords = keywordLinesForDisplay(data.config.keywords)
        normalized.enableAiScreening = data.config.enableAiScreening == null ? 1 : data.config.enableAiScreening
        setConfig(normalized)
      }
      if (data.options) setOptions(data.options)
      await refreshFunnel()
    } catch (e) {
      console.error('[智联] 获取配置失败:', e)
    } finally {
      setLoadingConfig(false)
    }
  }

  const refreshTaskStatus = async () => {
    try {
      const res = await fetch('http://localhost:8888/api/zhilian/status')
      const data = await res.json()
      if (!res.ok || !data.success) {
        throw new Error(data.message || '状态接口返回异常')
      }
      setIsDelivering(Boolean(data.isRunning))
      if (data.message) setTaskMessage(data.message)
      if (data.messageType) setTaskMessageType(data.messageType)
      return data
    } catch (error) {
      console.error('[智联] 获取投递状态失败:', error)
      setTaskMessage('无法读取投递状态，请检查后端服务。')
      setTaskMessageType('error')
      return null
    }
  }

  // eslint-disable-next-line react-hooks/exhaustive-deps -- initial configuration load only
  useEffect(() => { fetchAllData() }, [])

  // 探测后端可用性（与 51job 保持一致风格）
  useEffect(() => {
    (async () => {
      try {
        const res = await fetch('http://localhost:8888/api/zhilian/config', { method: 'GET' })
        const ok = !!res && res.ok
        if (ok) {
          await fetchAllData()
        } else {
          setLoadingConfig(false)
        }
      } catch {
        setLoadingConfig(false)
      }
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- configuration probe is mount-only
  }, [])

  useEffect(() => {
    let disposed = false
    let timer: number | undefined

    const poll = async () => {
      const data = await refreshTaskStatus()
      await refreshFunnel()
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

  const handleStartDelivery = async () => {
    try {
      setIsDelivering(true)
      setTaskMessage('正在启动投递任务...')
      setTaskMessageType('info')
      const response = await fetch('http://localhost:8888/api/zhilian/start', { method: 'POST' })
      const data = await response.json()
      if (!data.success) {
        setIsDelivering(false)
        setTaskMessage(data.message || '启动投递失败。')
        setTaskMessageType('error')
        return
      }
      setTaskMessage(data.message || '投递任务已启动。')
      await refreshTaskStatus()
    } catch {
      setIsDelivering(false)
      setTaskMessage('启动投递失败：网络或服务异常。')
      setTaskMessageType('error')
    }
  }

  const handleStopDelivery = async () => {
    try {
      const response = await fetch('http://localhost:8888/api/zhilian/stop', { method: 'POST' })
      const data = await response.json()
      setTaskMessage(data.message || (data.success ? '正在停止投递任务...' : '停止投递失败。'))
      setTaskMessageType(data.success ? 'info' : 'error')
      await refreshTaskStatus()
    } catch {
      setTaskMessage('停止投递失败：网络或服务异常。')
      setTaskMessageType('error')
    }
  }

  const triggerLogout = async () => {
    try {
      const response = await fetch('http://localhost:8888/api/zhilian/logout', { method: 'POST' })
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
      const payload = { ...config, keywords: keywordLinesForStorage(config.keywords) }
      const response = await fetch('http://localhost:8888/api/zhilian/config', {
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
      console.error('[智联] 保存配置失败:', error)
      setSaveResult({ success: false, message: '保存失败：网络或服务异常。' })
      setShowSaveDialog(true)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<Briefcase className="text-2xl" />}
        title="智联招聘配置"
        subtitle="配置智联招聘平台的求职参数"
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
                <Play className="mr-1" /> 请先登录智联招聘
              </Button>
            ) : isDelivering ? (
              <Button onClick={handleStopDelivery} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
                <Square className="mr-1" /> 停止投递
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

      {taskMessage && (
        <div
          role="status"
          className={`rounded-lg border px-4 py-3 text-sm ${
            taskMessageType === 'error'
              ? 'border-red-200 bg-red-50 text-red-700'
              : taskMessageType === 'success'
                ? 'border-green-200 bg-green-50 text-green-700'
                : 'border-blue-200 bg-blue-50 text-blue-700'
          }`}
        >
          投递状态：{taskMessage}
        </div>
      )}

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

      <div className="space-y-6 mt-6">
          <CookieLoginCard
            platform="zhilian"
            platformName="智联招聘"
            siteUrl="https://www.zhaopin.com"
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
                    <Label>城市</Label>
                    <Select
                      value={config.cityCode || ''}
                      onChange={(e) => setConfig((c) => ({ ...c, cityCode: e.target.value }))}
                      placeholder="请选择城市"
                    >
                      {options.city.map((o) => (
                        <option key={o.code} value={o.code}>{o.name}</option>
                      ))}
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>薪资范围（最低和最高工资，用逗号分割）</Label>
                    <Input
                      placeholder="如：12000, 20000 或 不限"
                      value={config.salary || ''}
                      onChange={(e) => setConfig((c) => ({ ...c, salary: e.target.value }))}
                    />
                  </div>
                  <div className="space-y-3">
                    <Label htmlFor="zhilian-ai-screening">AI 岗位筛选</Label>
                    <label className="flex items-center gap-2 text-sm">
                      <input
                        id="zhilian-ai-screening"
                        type="checkbox"
                        checked={config.enableAiScreening === 1}
                        onChange={(e) => setConfig((c) => ({ ...c, enableAiScreening: e.target.checked ? 1 : 0 }))}
                      />
                      投递前分析岗位 JD
                    </label>
                    {config.enableAiScreening === 1 && (
                      <Input
                        type="number"
                        min="0"
                        max="100"
                        value={config.aiMinScore ?? 70}
                        onChange={(e) => setConfig((c) => ({ ...c, aiMinScore: Number(e.target.value) }))}
                        aria-label="AI 最低通过分数"
                      />
                    )}
                  </div>
                </div>
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
