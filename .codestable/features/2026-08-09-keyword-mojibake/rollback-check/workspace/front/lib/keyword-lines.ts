export function parseKeywordLines(raw?: string): string[] {
  if (!raw) return []

  const text = raw.trim()
  if (!text) return []

  if (text.startsWith('[') && text.endsWith(']')) {
    try {
      const parsed = JSON.parse(text)
      if (Array.isArray(parsed)) {
        return parsed
          .map((value) => String(value ?? '').trim())
          .filter(Boolean)
      }
    } catch {
      // Continue with legacy bracket and delimiter parsing.
    }
  }

  const body = text.startsWith('[') && text.endsWith(']')
    ? text.slice(1, -1)
    : text

  return body
    .split(/[\r\n,，]+/)
    .map((value) => value.trim().replace(/^"|"$/g, ''))
    .filter(Boolean)
}

export function keywordLinesForDisplay(raw?: string): string {
  return parseKeywordLines(raw).join('\n')
}

export function keywordLinesForStorage(raw?: string): string {
  return JSON.stringify(parseKeywordLines(raw))
}
