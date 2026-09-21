'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { Download, Globe2, KeyRound, Monitor, RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'

export type CookieLoginPlatform = 'boss' | 'liepin' | '51job' | 'zhilian'

interface CookieLoginCardProps {
  platform: CookieLoginPlatform
  platformName: string
  siteUrl: string
  isLoggedIn: boolean
  onImportResult: (loggedIn: boolean) => void
  apiBase?: string
}

type ImportResponse = {
  success?: boolean
  message?: string
  data?: { loggedIn?: boolean }
}

type BrowserStatus = {
  open?: boolean
  loggedIn?: boolean
  saved?: boolean
  captured?: boolean
  count?: number
  stored?: boolean
  needsReimport?: boolean
  error?: string
}

type BrowserStatusResponse = {
  success?: boolean
  data?: BrowserStatus
  message?: string
}

type CookieStatusResponse = {
  success?: boolean
  data?: {
    stored?: boolean
    loggedIn?: boolean
    needsReimport?: boolean
  }
}

const DEFAULT_API_BASE = 'http://localhost:8888'

export default function CookieLoginCard({
  platform,
  platformName,
  siteUrl,
  isLoggedIn,
  onImportResult,
  apiBase = DEFAULT_API_BASE,
}: CookieLoginCardProps) {
  const [mode, setMode] = useState<'browser' | 'manual'>('browser')
  const [cookieInput, setCookieInput] = useState('')
  const [importing, setImporting] = useState(false)
  const [browserOpening, setBrowserOpening] = useState(false)
  const [browserCapturing, setBrowserCapturing] = useState(false)
  const [browserStatus, setBrowserStatus] = useState<BrowserStatus | null>(null)
  const [result, setResult] = useState<{ success: boolean; message: string } | null>(null)
  const [needsReimport, setNeedsReimport] = useState(false)
  const onImportResultRef = useRef(onImportResult)

  useEffect(() => {
    onImportResultRef.current = onImportResult
  }, [onImportResult])

  const refreshBrowserStatus = useCallback(async () => {
    try {
      const response = await fetch(`${apiBase}/api/cookie/browser/status?platform=${platform}`)
      const data = await response.json() as BrowserStatusResponse
      if (!response.ok || !data.success || !data.data) {
        return
      }
      const status = data.data
      setBrowserStatus(status)
      setNeedsReimport(Boolean(status.needsReimport))
      // 捕获成功后把后端确认的登录态同步到页面；打开窗口时的短暂 false 不覆盖已保存状态。
      if (status.loggedIn === true) {
        onImportResultRef.current(true)
      }
    } catch {
      // 状态轮询失败不影响手动 Cookie 入口。
    }
  }, [apiBase, platform])

  const refreshStoredStatus = useCallback(async () => {
    try {
      const response = await fetch(`${apiBase}/api/cookie/status?platform=${platform}`)
      const data = await response.json() as CookieStatusResponse
      if (response.ok && data.success) {
        setNeedsReimport(Boolean(data.data?.needsReimport))
        if (typeof data.data?.loggedIn === 'boolean') {
          onImportResultRef.current(data.data.loggedIn)
        }
      }
    } catch {
      // 页面登录状态仍由平台状态接口负责；提示读取失败不影响导入。
    }
  }, [apiBase, platform])

  useEffect(() => {
    void refreshBrowserStatus()
    void refreshStoredStatus()
  }, [refreshBrowserStatus, refreshStoredStatus, isLoggedIn])

  useEffect(() => {
    if (!browserStatus?.open || browserStatus.saved) {
      return undefined
    }
    const timer = window.setInterval(() => {
      void refreshBrowserStatus()
    }, 1500)
    return () => window.clearInterval(timer)
  }, [browserStatus?.open, browserStatus?.saved, refreshBrowserStatus])

  const handleOpenBrowser = async () => {
    setBrowserOpening(true)
    setResult(null)
    try {
      const response = await fetch(`${apiBase}/api/cookie/browser/open`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ platform }),
      })
      const data = await response.json() as BrowserStatusResponse
      if (!response.ok || !data.success) {
        setResult({ success: false, message: data.message || '打开浏览器登录失败' })
        return
      }
      const status = data.data || { open: true }
      setBrowserStatus(status)
      if (status.loggedIn) {
        onImportResult(true)
        setResult({ success: true, message: '已恢复本机 Cookie，当前已登录' })
      } else {
        setResult({ success: true, message: '浏览器已打开，请完成登录后点击“获取并保存 Cookie”' })
      }
    } catch {
      setResult({ success: false, message: '打开浏览器登录失败：网络或服务异常' })
    } finally {
      setBrowserOpening(false)
    }
  }

  const handleCaptureBrowser = async () => {
    if (!browserStatus?.open) {
      setResult({ success: false, message: '请先打开浏览器登录' })
      return
    }

    setBrowserCapturing(true)
    setResult(null)
    try {
      const response = await fetch(`${apiBase}/api/cookie/browser/capture`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ platform }),
      })
      const data = await response.json() as BrowserStatusResponse
      const status = data.data || {}
      setBrowserStatus((current) => ({ ...current, ...status }))
      setNeedsReimport(Boolean(status.needsReimport))
      if (!response.ok || !data.success) {
        setResult({ success: false, message: data.message || '获取 Cookie 失败' })
        return
      }

      const loggedIn = Boolean(status.loggedIn)
      onImportResult(loggedIn)
      setResult({
        success: true,
        message: data.message || (loggedIn ? 'Cookie 已获取并保存，当前已登录' : 'Cookie 已获取并保存，但登录状态尚未确认'),
      })
    } catch {
      setResult({ success: false, message: '获取 Cookie 失败：网络或服务异常' })
    } finally {
      setBrowserCapturing(false)
    }
  }

  const handleImport = async () => {
    const cookieValue = cookieInput.trim()
    if (!cookieValue) {
      setResult({ success: false, message: '请先粘贴 Cookie 内容' })
      return
    }

    setImporting(true)
    setResult(null)
    try {
      const response = await fetch(`${apiBase}/api/cookie/import`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          platform,
          cookie_value: cookieValue,
          remark: `${platform} manual cookie login`,
        }),
      })
      const data = await response.json() as ImportResponse
      if (!response.ok || !data.success) {
        setResult({ success: false, message: data.message || 'Cookie 导入失败' })
        return
      }

      const loggedIn = Boolean(data.data?.loggedIn)
      onImportResult(loggedIn)
      setNeedsReimport(!loggedIn)
      setResult({
        success: true,
        message: data.message || (loggedIn ? 'Cookie 导入成功，已登录' : 'Cookie 已导入，但未检测到登录态'),
      })
      if (loggedIn) {
        setCookieInput('')
      }
    } catch {
      setResult({ success: false, message: 'Cookie 导入失败：网络或服务异常' })
    } finally {
      setImporting(false)
    }
  }

  return (
    <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <KeyRound className="text-primary" />
          {platformName} Cookie 登录
        </CardTitle>
        <CardDescription>{isLoggedIn ? '当前已登录' : '选择浏览器登录或手动导入 Cookie'}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {needsReimport && (
          <p role="alert" className="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900">
            本机已保存 Cookie，但平台当前未认可登录态。请重新登录或导入完整 Cookie。
          </p>
        )}

        <Tabs value={mode} onValueChange={(value) => setMode(value as 'browser' | 'manual')}>
          <TabsList className="rounded-lg border bg-muted/40">
            <TabsTrigger value="browser"><Monitor className="mr-1.5 h-4 w-4" />浏览器登录</TabsTrigger>
            <TabsTrigger value="manual"><KeyRound className="mr-1.5 h-4 w-4" />手动填写 Cookie</TabsTrigger>
          </TabsList>

          <TabsContent value="browser" className="space-y-4">
            <div className="rounded-md border bg-muted/20 p-3 text-sm text-muted-foreground">
              点击按钮会打开应用专用浏览器。完成 {platformName} 登录后，再点击下方按钮获取并保存 Cookie。
            </div>
            <div className="flex flex-wrap gap-2">
              <Button onClick={handleOpenBrowser} disabled={browserOpening || browserCapturing} className="rounded-full">
              {browserOpening ? <RefreshCw className="mr-1.5 animate-spin" /> : <Globe2 className="mr-1.5" />}
              {browserOpening ? '打开中...' : '打开浏览器登录'}
              </Button>
              <Button
                onClick={handleCaptureBrowser}
                disabled={!browserStatus?.open || browserOpening || browserCapturing}
                variant="secondary"
                className="rounded-full"
              >
                {browserCapturing ? <RefreshCw className="mr-1.5 animate-spin" /> : <Download className="mr-1.5" />}
                {browserCapturing ? '获取中...' : '获取并保存 Cookie'}
              </Button>
            </div>
            {browserStatus?.open && browserStatus.loggedIn && browserStatus.stored && (
              <p className="text-sm text-emerald-600" role="status">已恢复本机 Cookie，当前已登录。</p>
            )}
            {browserStatus?.open && !browserStatus.saved && !browserStatus.loggedIn && (
              <p className="text-sm text-muted-foreground" role="status">
                登录窗口已打开，完成登录后点击“获取并保存 Cookie”。
              </p>
            )}
            {browserStatus?.saved && (
              <p className="text-sm text-emerald-600" role="status">Cookie 已获取并保存到本机。</p>
            )}
            {browserStatus?.error && (
              <p className="text-sm text-amber-700" role="alert">{browserStatus.error}</p>
            )}
          </TabsContent>

          <TabsContent value="manual" className="space-y-4">
            <ol className="list-decimal list-inside space-y-1 text-sm text-muted-foreground">
              <li>用系统 Chrome 打开 <span className="font-mono">{siteUrl}</span> 并完成登录</li>
              <li>F12 → Application/存储 → Cookies → 复制，或导出 JSON</li>
              <li>粘贴 JSON 数组或 <span className="font-mono">name=value; a=b</span> 后导入</li>
            </ol>
            <div className="space-y-2">
              <Label htmlFor={`${platform}-cookie`}>Cookie 内容</Label>
              <Textarea
                id={`${platform}-cookie`}
                value={cookieInput}
                onChange={(event) => setCookieInput(event.target.value)}
                placeholder='[{"name":"session","value":"...","domain":".example.com","path":"/"}] 或 session=xxx; token=yyy'
                className="min-h-[120px] font-mono text-xs"
                disabled={importing}
              />
            </div>
            <Button onClick={handleImport} disabled={importing || !cookieInput.trim()} className="rounded-full">
              <KeyRound className="mr-1.5" />
              {importing ? '导入中...' : '导入 Cookie 登录'}
            </Button>
          </TabsContent>
        </Tabs>

        {result && (
          <span role="status" className={`text-sm ${result.success ? 'text-green-600' : 'text-red-500'}`}>
            {result.message}
          </span>
        )}
        <p className="text-sm text-muted-foreground">Cookie 会写入本机数据库，下次启动自动恢复。</p>
      </CardContent>
    </Card>
  )
}
