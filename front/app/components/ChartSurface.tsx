"use client"

import { useEffect, useMemo, useRef, useState } from "react"

type ChartType = "pie" | "bar" | "line"
type ChartInstance = { destroy: () => void }
type ChartConstructor = new (context: CanvasRenderingContext2D, config: Record<string, unknown>) => ChartInstance

type ChartSurfaceProps = {
  type: ChartType
  labels: string[]
  data: number[]
  title?: string
  color?: string
  colors?: string[]
}

const defaultColors = [
  "#d9ff43",
  "#6ce8dc",
  "#ffbd4a",
  "#ff6d4a",
  "#a6ad9d",
  "#a6d8ff",
  "#ed8fd4",
  "#b8f081",
]

export function ChartSurface({
  type,
  labels,
  data,
  title,
  color = "#d9ff43",
  colors,
}: ChartSurfaceProps) {
  const hostRef = useRef<HTMLDivElement | null>(null)
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const chartRef = useRef<ChartInstance | null>(null)
  const [visible, setVisible] = useState(false)
  const chartKey = useMemo(
    () => JSON.stringify({ type, labels, data, title, color, colors }),
    [type, labels, data, title, color, colors],
  )

  useEffect(() => {
    const host = hostRef.current
    if (!host) return
    if (!("IntersectionObserver" in window)) {
      setVisible(true)
      return
    }

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setVisible(true)
          observer.disconnect()
        }
      },
      { rootMargin: "240px 0px" },
    )
    observer.observe(host)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    if (!visible) return
    const context = canvasRef.current?.getContext("2d")
    if (!context) return

    let cancelled = false
    chartRef.current?.destroy()
    chartRef.current = null

    void import("chart.js/auto").then(({ default: Chart }) => {
      if (cancelled) return
      const palette = (colors?.length ? colors : defaultColors).slice(0, Math.max(labels.length, data.length))
      const backgroundColor = type === "pie" || type === "bar" ? palette : color
      const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches
      const ChartClass = Chart as unknown as ChartConstructor

      chartRef.current = new ChartClass(context, {
        type,
        data: {
          labels,
          datasets: [
            {
              label: title || "",
              data,
              backgroundColor,
              borderColor: type === "pie" ? undefined : backgroundColor,
              borderWidth: type === "line" ? 2 : 0,
              fill: false,
              pointRadius: type === "line" ? 2 : 0,
              pointHoverRadius: type === "line" ? 4 : 0,
            },
          ],
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          animation: reduceMotion ? false : { duration: 220 },
          plugins: {
            legend: { display: type === "pie", labels: { color: "#a6ad9d", boxWidth: 10 } },
            title: { display: false },
          },
          scales: type === "pie"
            ? undefined
            : {
                x: { ticks: { autoSkip: true, color: "#a6ad9d" }, grid: { color: "rgba(166,173,157,0.12)" } },
                y: { beginAtZero: true, ticks: { color: "#a6ad9d" }, grid: { color: "rgba(166,173,157,0.12)" } },
              },
        },
      })
    })

    return () => {
      cancelled = true
      chartRef.current?.destroy()
      chartRef.current = null
    }
  }, [chartKey, color, colors, data, labels, title, type, visible])

  return (
    <div ref={hostRef} className="chart-surface" aria-busy={!visible}>
      <canvas ref={canvasRef} className="h-64 w-full" />
    </div>
  )
}
