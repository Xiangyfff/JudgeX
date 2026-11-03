/** Minimal syntax highlighter: regex tokenizer, no external dependency. */

const KEYWORDS: Record<string, string[]> = {
  java: [
    'abstract', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class', 'const', 'continue',
    'default', 'do', 'double', 'else', 'enum', 'extends', 'final', 'finally', 'float', 'for', 'if',
    'implements', 'import', 'instanceof', 'int', 'interface', 'long', 'new', 'package', 'private',
    'protected', 'public', 'return', 'short', 'static', 'super', 'switch', 'synchronized', 'this',
    'throw', 'throws', 'try', 'void', 'volatile', 'while', 'var', 'record', 'true', 'false', 'null'
  ],
  python: [
    'and', 'as', 'assert', 'break', 'class', 'continue', 'def', 'del', 'elif', 'else', 'except',
    'finally', 'for', 'from', 'global', 'if', 'import', 'in', 'is', 'lambda', 'nonlocal', 'not',
    'or', 'pass', 'raise', 'return', 'try', 'while', 'with', 'yield', 'True', 'False', 'None'
  ],
  javascript: [
    'async', 'await', 'break', 'case', 'catch', 'class', 'const', 'continue', 'default', 'delete',
    'do', 'else', 'export', 'extends', 'finally', 'for', 'function', 'if', 'import', 'in', 'instanceof',
    'let', 'new', 'return', 'super', 'switch', 'this', 'throw', 'try', 'typeof', 'var', 'void',
    'while', 'yield', 'true', 'false', 'null', 'undefined'
  ],
  cpp: [
    'auto', 'bool', 'break', 'case', 'catch', 'char', 'class', 'const', 'constexpr', 'continue',
    'default', 'delete', 'do', 'double', 'else', 'enum', 'explicit', 'false', 'float', 'for',
    'friend', 'if', 'inline', 'int', 'long', 'namespace', 'new', 'nullptr', 'operator', 'private',
    'protected', 'public', 'return', 'short', 'sizeof', 'static', 'struct', 'switch', 'template',
    'this', 'throw', 'true', 'try', 'typedef', 'typename', 'unsigned', 'using', 'virtual', 'void',
    'volatile', 'while', 'std'
  ]
}

function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

export function highlight(code: string, lang: string): string {
  const keywords = KEYWORDS[lang] ?? KEYWORDS.java
  const comment = lang === 'python' ? '#[^\\n]*' : '//[^\\n]*|/\\*[\\s\\S]*?\\*/'
  const re = new RegExp(
    `("(?:\\\\.|[^"\\\\])*"|'(?:\\\\.|[^'\\\\])*'|\`(?:\\\\.|[^\`\\\\])*\`)|(${comment})|\\b(0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?)\\b|\\b([A-Za-z_$][A-Za-z0-9_$]*)\\b`,
    'g'
  )
  let out = ''
  let last = 0
  let m: RegExpExecArray | null
  while ((m = re.exec(code)) !== null) {
    out += esc(code.slice(last, m.index))
    if (m[1]) out += `<span class="tk-str">${esc(m[1])}</span>`
    else if (m[2]) out += `<span class="tk-com">${esc(m[2])}</span>`
    else if (m[3]) out += `<span class="tk-num">${esc(m[3])}</span>`
    else if (m[4])
      out += keywords.includes(m[4])
        ? `<span class="tk-kw">${esc(m[4])}</span>`
        : esc(m[4])
    last = re.lastIndex
  }
  out += esc(code.slice(last))
  return out
}
