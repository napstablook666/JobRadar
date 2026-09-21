"use client"

import Link from "next/link"
import { usePathname } from "next/navigation"
import { useEffect, useRef, useState } from "react"
import {
  Activity,
  Bot,
  Briefcase,
  ChevronLeft,
  ChevronRight,
  CircleUserRound,
  ClipboardList,
  Moon,
  Search,
  Settings,
  Sun,
  X,
  type LucideIcon,
} from "lucide-react"
import { useTheme } from "next-themes"

type Health = "up" | "degraded" | "down" | "unknown"

type NavigationItem = {
  href: string
  icon: LucideIcon
  label: string
}

const environmentItems: NavigationItem[] = [
  { href: "/env-config", icon: Settings, label: "环境配置" },
  { href: "/ai-config", icon: Bot, label: "AI 配置" },
]

const platformItems: NavigationItem[] = [
  { href: "/boss", icon: Briefcase, label: "Boss 直聘" },
  { href: "/liepin", icon: Search, label: "猎聘" },
  { href: "/51job", icon: ClipboardList, label: "51job" },
  { href: "/zhilian", icon: CircleUserRound, label: "智联招聘" },
]

function healthLabel(health: Health) {
  if (health === "up") return "服务在线"
  if (health === "degraded") return "服务降级"
  if (health === "down") return "服务异常"
  return "未连接"
}

export default function Sidebar({
  collapsed,
  mobileOpen,
  onCollapsedChange,
  onMobileOpenChange,
}: {
  collapsed: boolean
  mobileOpen: boolean
  onCollapsedChange: (collapsed: boolean) => void
  onMobileOpenChange: (open: boolean) => void
}) {
  const pathname = usePathname() ?? ""
  const { resolvedTheme, setTheme } = useTheme()
  const [mounted, setMounted] = useState(false)
  const [health, setHealth] = useState<Health>("unknown")
  const inFlightRef = useRef(false)

  useEffect(() => setMounted(true), [])

  useEffect(() => {
    onMobileOpenChange(false)
  }, [pathname, onMobileOpenChange])

  useEffect(() => {
    if (!mobileOpen) return

    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") onMobileOpenChange(false)
    }

    window.addEventListener("keydown", handleEscape)
    return () => window.removeEventListener("keydown", handleEscape)
  }, [mobileOpen, onMobileOpenChange])

  useEffect(() => {
    let activeController: AbortController | undefined

    const checkHealth = async () => {
      if (document.visibilityState === "hidden" || inFlightRef.current) return

      inFlightRef.current = true
      activeController?.abort()
      const controller = new AbortController()
      activeController = controller
      const timeout = window.setTimeout(() => controller.abort(), 3000)
      const baseUrl = process.env.API_BASE_URL || "http://localhost:8888"

      try {
        let response = await fetch(`${baseUrl}/api/health`, { signal: controller.signal })
        if (response.status === 404) {
          response = await fetch(`${baseUrl}/actuator/health`, { signal: controller.signal })
        }
        if (!response.ok) throw new Error(`status ${response.status}`)
        const data = await response.json()
        const status = String(data.status || data.state || "").toUpperCase()
        setHealth(status === "UP" || status === "HEALTHY" ? "up" : status === "DEGRADED" || status === "WARN" ? "degraded" : "down")
      } catch {
        if (!controller.signal.aborted) setHealth("unknown")
      } finally {
        window.clearTimeout(timeout)
        inFlightRef.current = false
      }
    }

    const handleVisibilityChange = () => {
      if (document.visibilityState === "visible") void checkHealth()
    }

    void checkHealth()
    const interval = window.setInterval(() => void checkHealth(), 30000)
    document.addEventListener("visibilitychange", handleVisibilityChange)
    return () => {
      activeController?.abort()
      window.clearInterval(interval)
      document.removeEventListener("visibilitychange", handleVisibilityChange)
    }
  }, [])

  const isActive = (href: string) => pathname === href || pathname.startsWith(`${href}/`)

  const renderGroup = (label: string, items: NavigationItem[]) => (
    <section className="console-nav-group" aria-label={label}>
      <p className="console-nav-group-label">{label}</p>
      <div className="console-nav-list">
        {items.map((item) => {
          const Icon = item.icon
          const active = isActive(item.href)
          return (
            <Link
              key={item.href}
              href={item.href}
              className={`console-nav-item${active ? " is-active" : ""}`}
              title={item.label}
              onClick={() => onMobileOpenChange(false)}
            >
              <Icon aria-hidden="true" />
              <span>{item.label}</span>
              {active && <i aria-hidden="true" />}
            </Link>
          )
        })}
      </div>
    </section>
  )

  return (
    <>
      <button
        type="button"
        className={`console-nav-backdrop${mobileOpen ? " is-visible" : ""}`}
        aria-label="关闭导航"
        tabIndex={mobileOpen ? 0 : -1}
        onClick={() => onMobileOpenChange(false)}
      />
      <aside className={`console-rail${mobileOpen ? " is-mobile-open" : ""}`} aria-label="主导航">
        <div className="console-rail-head">
          <div className="console-brand">
            <Activity aria-hidden="true" />
            <div>
              <strong>JOBRADAR</strong>
              <span>CONTROL DESK</span>
            </div>
          </div>
          <button
            type="button"
            className="console-rail-close console-icon-button"
            aria-label="关闭导航"
            title="关闭导航"
            onClick={() => onMobileOpenChange(false)}
          >
            <X aria-hidden="true" />
          </button>
          <div className={`console-health status-${health}`}>
            <span aria-hidden="true" />
            <span>{healthLabel(health)}</span>
          </div>
        </div>

        <nav className="console-nav" id="console-navigation">
          {renderGroup("系统", environmentItems)}
          {renderGroup("平台", platformItems)}
        </nav>

        <div className="console-rail-foot">
          {mounted && (
            <button
              type="button"
              className="console-rail-action"
              onClick={() => setTheme(resolvedTheme === "dark" ? "light" : "dark")}
              title={resolvedTheme === "dark" ? "切换浅色主题" : "切换深色主题"}
            >
              {resolvedTheme === "dark" ? <Sun aria-hidden="true" /> : <Moon aria-hidden="true" />}
              <span>{resolvedTheme === "dark" ? "浅色" : "深色"}</span>
            </button>
          )}
          <button
            type="button"
            className="console-rail-action console-rail-collapse"
            onClick={() => onCollapsedChange(!collapsed)}
            aria-label={collapsed ? "展开侧边栏" : "收起侧边栏"}
            title={collapsed ? "展开侧边栏" : "收起侧边栏"}
          >
            {collapsed ? <ChevronRight aria-hidden="true" /> : <ChevronLeft aria-hidden="true" />}
            <span>{collapsed ? "展开" : "收起"}</span>
          </button>
          <div className="console-version">v1.0.0</div>
        </div>
      </aside>
    </>
  )
}
