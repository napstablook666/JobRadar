export type SalaryInfo = {
  minK: number
  maxK: number
  months: number
  medianK: number
  annualTotal: number
}

/**
 * 解析薪资并统一为月薪 K：支持 K、万元/月、元/月、年薪万元/元和 13 薪。
 */
export function parseSalary(raw?: string): SalaryInfo | undefined {
  if (!raw) return undefined
  let s = raw.trim()
    .replace(/\s+/g, "")
    .replace(/[，,]/g, "")
    .replace(/[－–—]/g, "-")
  if (!s || s.includes("面议") || isDailySalary(s)) return undefined

  let months = 12
  const annual = isAnnualSalary(s)
  const monthsMatch = s.match(/([0-9]{1,2})[·⋅×xX*]?(?:薪|月)/)
  if (monthsMatch) {
    const parsedMonths = Number(monthsMatch[1])
    months = parsedMonths > 0 && parsedMonths <= 24 ? parsedMonths : 12
    s = s.slice(0, monthsMatch.index ?? s.length)
  }
  s = cleanSalaryBase(s)

  const range = s.match(/^(\d+(?:\.\d+)?)(万元?|人民币|元|[Kk千])?-(\d+(?:\.\d+)?)(万元?|人民币|元|[Kk千])?$/)
  const single = s.match(/^(\d+(?:\.\d+)?)(万元?|人民币|元|[Kk千])?$/)
  let minK: number
  let maxK: number
  if (range) {
    const fallbackUnit = range[2] || range[4]
    minK = toMonthlyK(Number(range[1]), range[2], fallbackUnit, annual, months)
    maxK = toMonthlyK(Number(range[3]), range[4], fallbackUnit, annual, months)
  } else if (single) {
    minK = toMonthlyK(Number(single[1]), single[2], single[2], annual, months)
    maxK = minK
  } else {
    return undefined
  }

  if (!Number.isFinite(minK) || !Number.isFinite(maxK) || minK < 0 || maxK < minK) {
    return undefined
  }
  minK = Math.round(minK)
  maxK = Math.round(maxK)
  const medianK = (minK + maxK) / 2
  return {
    minK,
    maxK,
    months,
    medianK,
    annualTotal: Math.round(medianK * 1000 * months),
  }
}

function isAnnualSalary(value: string): boolean {
  return value.includes("年薪")
    || value.includes("每年")
    || value.includes("/年")
    || value.endsWith("年")
}

function isDailySalary(value: string): boolean {
  const normalized = value.toLowerCase()
  return normalized.includes("日薪")
    || normalized.includes("元/天")
    || normalized.includes("/天")
    || normalized.includes("每天")
    || normalized.includes("/day")
}

function cleanSalaryBase(value: string): string {
  return value
    .replace(/[·⋅×xX*].*$/, "")
    .replace(/[（(].*$/, "")
    .replace(/年薪/g, "")
    .replace(/月薪/g, "")
    .replace(/\/年/g, "")
    .replace(/每年/g, "")
    .replace(/\/月/g, "")
    .replace(/每月/g, "")
    .replace(/年/g, "")
    .replace(/月/g, "")
    .replace(/[:：]/g, "")
    .trim()
}

function toMonthlyK(
  value: number,
  unit: string | undefined,
  fallbackUnit: string | undefined,
  annual: boolean,
  months: number,
): number {
  let resolvedUnit = unit || fallbackUnit
  if (!resolvedUnit) {
    resolvedUnit = annual ? (value >= 1000 ? "元" : "万") : (value >= 1000 ? "元" : "K")
  }
  let totalK: number
  switch (resolvedUnit) {
    case "万":
    case "万元":
      totalK = value * 10
      break
    case "元":
    case "人民币":
      totalK = value / 1000
      break
    default:
      totalK = value
  }
  return annual ? totalK / Math.max(1, months) : totalK
}
