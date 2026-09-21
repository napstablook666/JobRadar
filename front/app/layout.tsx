import type { Metadata } from "next"
import "./globals.css"
import ConsoleShell from "./components/ConsoleShell"

export const metadata: Metadata = {
  title: "JobRadar",
  description: "JobRadar 控制台",
  icons: { icon: "/favicon.ico" },
}

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <body suppressHydrationWarning>
        <ConsoleShell>{children}</ConsoleShell>
      </body>
    </html>
  )
}
