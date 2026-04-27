export const isSafari = /^((?!chrome|android).)*safari/i.test(navigator.userAgent)

export async function copyText(text) {
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text)
      return
    } catch {}
  }
  const ta = document.createElement('textarea')
  ta.value = text
  ta.setAttribute('readonly', '')
  ta.style.cssText = 'position:fixed;left:-9999px;top:-9999px;opacity:0;pointer-events:none'
  document.body.appendChild(ta)
  ta.focus()
  ta.setSelectionRange(0, ta.value.length)
  let ok = false
  try { ok = document.execCommand('copy') } catch {}
  document.body.removeChild(ta)
  if (!ok) throw new Error('copy failed')
}
