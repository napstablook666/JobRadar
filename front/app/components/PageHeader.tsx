"use client"

import { ReactNode } from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { ChartBar, Settings } from "lucide-react"

type PlatformWorkspace = {
  config: string
  analysis: string
}

const workspaces: PlatformWorkspace[] = [
  { config: "/boss", analysis: "/boss/analysis" },
  { config: "/liepin", analysis: "/liepin/analysis" },
  { config: "/51job", analysis: "/51job/analysis" },
  { config: "/zhilian", analysis: "/zhilian/analysis" },
]

type PageHeaderProps = {
  icon: ReactNode
  title: string
  subtitle?: string
  iconClass?: string
  accentBgClass?: string
  actions?: ReactNode
}

export default function PageHeader({ icon, title, iconClass, actions }: PageHeaderProps) {
  const pathname = usePathname() ?? ""
  const workspace = workspaces.find(({ config }) => pathname === config || pathname.startsWith(`${config}/`))
  const analyticsActive = Boolean(workspace && pathname === workspace.analysis)

  return (
    <header className="workspace-header">
      <div className="workspace-heading">
        <span className={`workspace-mark ${iconClass ?? ""}`}>{icon}</span>
        <h1>{title}</h1>
      </div>
      {workspace && (
        <nav className="workspace-modes" aria-label={`${title}视图`}>
          <Link className={!analyticsActive ? "is-active" : ""} href={workspace.config}>
            <Settings aria-hidden="true" />
            配置
          </Link>
          <Link className={analyticsActive ? "is-active" : ""} href={workspace.analysis}>
            <ChartBar aria-hidden="true" />
            分析
          </Link>
        </nav>
      )}
      {actions && <div className="workspace-actions">{actions}</div>}
    </header>
  )
}
