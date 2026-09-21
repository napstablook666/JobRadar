const utf8Decoder = new TextDecoder('utf-8', { fatal: true })

function decodeLatin1AsUtf8(value: string): string | null {
  const chars = Array.from(value)
  if (chars.some((char) => char.charCodeAt(0) > 0xff)) {
    return null
  }

  try {
    return utf8Decoder.decode(Uint8Array.from(chars, (char) => char.charCodeAt(0)))
  } catch {
    return null
  }
}

export function repairUtf8Mojibake(value?: string): string {
  if (!value) return ''

  let current = value
  for (let round = 0; round < 3; round += 1) {
    const candidate = decodeLatin1AsUtf8(current)
    if (!candidate || candidate === current || candidate.includes('\uFFFD')) {
      break
    }
    current = candidate
  }
  return current
}

function normalizeKeyword(value: unknown): string {
  return repairUtf8Mojibake(String(value ?? '').trim())
}

export function parseKeywordLines(raw?: string): string[] {
  if (!raw) return []

  const text = raw.trim()
  if (!text) return []

  if (text.startsWith('[') && text.endsWith(']')) {
    try {
      const parsed = JSON.parse(text)
      if (Array.isArray(parsed)) {
        return parsed
          .map(normalizeKeyword)
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
    .map((value) => normalizeKeyword(value.replace(/^"|"$/g, '')))
    .filter(Boolean)
}

export function keywordLinesForDisplay(raw?: string): string {
  return parseKeywordLines(raw).join('\n')
}

export function keywordLinesForStorage(raw?: string): string {
  return JSON.stringify(parseKeywordLines(raw))
}
