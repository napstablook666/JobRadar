import * as React from "react"
import { createPortal } from "react-dom"
import { Check, ChevronDown } from "lucide-react"
import { cn } from "@/lib/utils"

type OptionItem = { value: string; label: React.ReactNode; text: string }

function nodeToText(node: React.ReactNode): string {
  if (node == null || typeof node === "boolean") return ""
  if (typeof node === "string" || typeof node === "number") return String(node)
  if (Array.isArray(node)) return node.map(nodeToText).join("")
  if (React.isValidElement(node)) {
    return nodeToText((node.props as { children?: React.ReactNode }).children)
  }
  return ""
}

export interface SelectProps {
  value?: string
  onChange?: (e: { target: { value: string } }) => void
  placeholder?: string
  className?: string
  id?: string
  disabled?: boolean
  children?: React.ReactNode
  /** 是否显示搜索框；默认选项数 > 5 时自动开启，也可强制 true/false */
  searchable?: boolean
  searchPlaceholder?: string
}

const Select = React.forwardRef<HTMLDivElement, SelectProps>(
  (
    {
      className,
      children,
      value,
      onChange,
      placeholder,
      disabled,
      id,
      searchable,
      searchPlaceholder = "搜索选项…",
      ...props
    },
    ref
  ) => {
    const [open, setOpen] = React.useState(false)
    const [mounted, setMounted] = React.useState(false)
    const [query, setQuery] = React.useState("")
    const wrapperRef = React.useRef<HTMLDivElement>(null)
    const buttonRef = React.useRef<HTMLButtonElement>(null)
    const dropdownRef = React.useRef<HTMLDivElement>(null)
    const searchInputRef = React.useRef<HTMLInputElement>(null)
    const [dropdownPosition, setDropdownPosition] = React.useState({
      top: 0,
      left: 0,
      width: 0,
      maxHeight: 288,
      placement: "below" as "above" | "below",
    })

    React.useEffect(() => {
      setMounted(true)
    }, [])

    const options = React.useMemo<OptionItem[]>(() => {
      return React.Children.toArray(children)
        .filter((c) => React.isValidElement(c) && (c as React.ReactElement).type === "option")
        .map((c) => {
          const el = c as React.ReactElement<{ value?: string | number; children?: React.ReactNode }>
          const label = el.props.children
          const val = String(el.props.value ?? label ?? "")
          return { value: val, label, text: nodeToText(label) }
        })
    }, [children])

    const enableSearch = searchable ?? options.length > 5

    const filteredOptions = React.useMemo(() => {
      const q = query.trim().toLowerCase()
      if (!q) return options
      return options.filter((o) => {
        const name = o.text.toLowerCase()
        const code = o.value.toLowerCase()
        return name.includes(q) || code.includes(q)
      })
    }, [options, query])

    const selected = options.find((o) => String(value ?? "") === String(o.value))

    const emitChange = (val: string) => onChange?.({ target: { value: val } } as { target: { value: string } })

    const updatePosition = React.useCallback(() => {
      if (!buttonRef.current) return

      const rect = buttonRef.current.getBoundingClientRect()
      const viewportPadding = 8
      const gap = 8
      const maxDropdownHeight = 288
      const viewportWidth = Math.max(window.innerWidth, 1)
      const viewportHeight = Math.max(window.innerHeight, 1)
      const width = Math.min(
        Math.max(rect.width, 180),
        Math.max(viewportWidth - viewportPadding * 2, 1)
      )
      const left = Math.min(
        Math.max(rect.left, viewportPadding),
        Math.max(viewportWidth - viewportPadding - width, viewportPadding)
      )
      const spaceBelow = Math.max(viewportHeight - rect.bottom - viewportPadding - gap, 0)
      const spaceAbove = Math.max(rect.top - viewportPadding - gap, 0)
      const openAbove = spaceBelow < maxDropdownHeight && spaceAbove > spaceBelow
      const maxHeight = Math.max(
        Math.min(maxDropdownHeight, openAbove ? spaceAbove : spaceBelow),
        1
      )

      setDropdownPosition({
        top: openAbove ? rect.top - gap : rect.bottom + gap,
        left,
        width,
        maxHeight,
        placement: openAbove ? "above" : "below",
      })
    }, [])

    React.useEffect(() => {
      if (open) {
        updatePosition()
        const handleUpdate = () => updatePosition()
        window.addEventListener("scroll", handleUpdate, true)
        window.addEventListener("resize", handleUpdate)
        return () => {
          window.removeEventListener("scroll", handleUpdate, true)
          window.removeEventListener("resize", handleUpdate)
        }
      }
    }, [open, updatePosition])

    React.useEffect(() => {
      if (open) {
        setQuery("")
        // 下一帧聚焦搜索框，便于直接输入
        requestAnimationFrame(() => {
          searchInputRef.current?.focus()
        })
      }
    }, [open])

    React.useEffect(() => {
      const handleClickOutside = (event: MouseEvent) => {
        const target = event.target as Node
        const clickedButton = wrapperRef.current?.contains(target)
        const clickedDropdown = dropdownRef.current?.contains(target)
        if (!clickedButton && !clickedDropdown) {
          setOpen(false)
        }
      }

      const handleEscape = (event: KeyboardEvent) => {
        if (event.key === "Escape") {
          setOpen(false)
        }
      }

      if (open) {
        setTimeout(() => {
          document.addEventListener("mousedown", handleClickOutside)
          document.addEventListener("keydown", handleEscape)
        }, 0)
      }

      return () => {
        document.removeEventListener("mousedown", handleClickOutside)
        document.removeEventListener("keydown", handleEscape)
      }
    }, [open])

    return (
      <div ref={ref} {...props}>
        <div ref={wrapperRef} className="relative">
          <button
            ref={buttonRef}
            id={id as string}
            type="button"
            disabled={disabled}
            onClick={() => {
              if (!open) updatePosition()
              setOpen((v) => !v)
            }}
            aria-expanded={open}
            className={cn(
              "console-field relative flex h-10 w-full px-3 py-2 pr-8 text-sm",
              disabled
                ? "cursor-not-allowed opacity-50"
                : "focus:outline-none",
              className
            )}
          >
            <span className="truncate text-sm">{selected ? selected.label : (placeholder ?? "")}</span>
            <ChevronDown className="absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2" aria-hidden="true" />
          </button>

          {open && mounted && createPortal(
            <div
              ref={dropdownRef}
              className="dropdown-panel"
              style={{
                top: `${dropdownPosition.top}px`,
                left: `${dropdownPosition.left}px`,
                width: `${dropdownPosition.width}px`,
                maxHeight: `${dropdownPosition.maxHeight}px`,
                transform: dropdownPosition.placement === "above" ? "translateY(-100%)" : undefined,
              }}
            >
              {enableSearch && (
                <div className="dropdown-search sticky top-0 z-10 border-b border-black/5 bg-white/95 p-2 dark:border-white/10 dark:bg-neutral-900/95">
                  <input
                    ref={searchInputRef}
                    type="text"
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    onClick={(e) => e.stopPropagation()}
                    onKeyDown={(e) => e.stopPropagation()}
                    placeholder={searchPlaceholder}
                    className="console-field h-8 w-full px-3 text-sm outline-none placeholder:text-muted-foreground"
                  />
                </div>
              )}
              <ul className="dropdown-panel-list py-1">
                {filteredOptions.length === 0 ? (
                  <li className="px-3 py-3 text-center text-sm text-muted-foreground">无匹配选项</li>
                ) : (
                  filteredOptions.map((o) => {
                    const active = String(value ?? "") === String(o.value)
                    return (
                      <li
                        key={String(o.value)}
                        className={cn(
                          "group flex items-center justify-between gap-3 px-3 py-2 cursor-pointer transition-all border-b border-white/12 last:border-b-0",
                          active ? "bg-primary/15 text-foreground" : "hover:bg-muted"
                        )}
                        onClick={() => {
                          emitChange(String(o.value))
                          setOpen(false)
                        }}
                      >
                        <span className="flex items-center gap-3 min-w-0">
                          <span className={cn("inline-flex h-4 w-4 shrink-0 items-center justify-center border", active && "border-primary bg-primary text-primary-foreground")}>
                            {active && <Check className="h-3 w-3" aria-hidden="true" />}
                          </span>
                          <span className="text-sm truncate">{o.label}</span>
                        </span>
                      </li>
                    )
                  })
                )}
              </ul>
            </div>,
            document.body
          )}
        </div>
      </div>
    )
  }
)
Select.displayName = "Select"

export { Select }
