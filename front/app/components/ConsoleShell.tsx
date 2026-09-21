"use client"

import { ReactNode, useEffect, useState } from "react"
import { Menu } from "lucide-react"
import { ThemeProvider } from "next-themes"
import Sidebar from "./Sidebar"

const SIDEBAR_COLLAPSED_STORAGE_KEY = "get-jobs-sidebar-collapsed"

export default function ConsoleShell({ children }: { children: ReactNode }) {
  const [collapsed, setCollapsed] = useState(false)
  const [mobileOpen, setMobileOpen] = useState(false)
  const [preferenceLoaded, setPreferenceLoaded] = useState(false)

  useEffect(() => {
    try {
      setCollapsed(window.localStorage.getItem(SIDEBAR_COLLAPSED_STORAGE_KEY) === "true")
    } finally {
      setPreferenceLoaded(true)
    }
  }, [])

  useEffect(() => {
    if (!preferenceLoaded) return
    try {
      window.localStorage.setItem(SIDEBAR_COLLAPSED_STORAGE_KEY, String(collapsed))
    } catch {
      // Keep the in-memory preference when browser storage is unavailable.
    }
  }, [collapsed, preferenceLoaded])

  return (
    <ThemeProvider attribute="class" defaultTheme="dark" enableSystem={false}>
      <div className={`console-shell${collapsed ? " is-collapsed" : ""}`}>
        <header className="console-mobilebar">
          <button
            type="button"
            className="console-icon-button"
            aria-label="打开导航"
            title="打开导航"
            onClick={() => setMobileOpen(true)}
          >
            <Menu aria-hidden="true" />
          </button>
          <span>JOBRADAR</span>
          <span className="console-mobilebar-state" aria-hidden="true" />
        </header>
        <Sidebar
          collapsed={collapsed}
          mobileOpen={mobileOpen}
          onCollapsedChange={setCollapsed}
          onMobileOpenChange={setMobileOpen}
        />
        <main id="console-main" className="console-main">
          <div className="console-content">{children}</div>
        </main>
      </div>
    </ThemeProvider>
  )
}
