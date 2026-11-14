const ruleNames: Readonly<Record<string, string>> = {
  R1: 'SQL 注入',
  R2: 'XSS 跨站脚本',
  R3: '路径穿越',
  R4: '扫描器识别',
  R5: '高频 404',
  R6: '登录失败高频',
  R7: '命令注入',
  R8: 'SSRF 特征',
  R9: '敏感文件探测',
  R10: 'Log4Shell 特征',
  R11: '疑似 WebShell 访问',
  R12: '特殊 HTTP 方法',
  R13: 'URL 凭据参数',
  R14: '单来源高频请求',
  INJECT: '提示注入特征',
}

export function formatRule(code: string): string {
  return ruleNames[code] ?? code
}

export function formatRules(codes: readonly string[] | null | undefined): string {
  return codes?.length ? codes.map(formatRule).join('、') : '—'
}
