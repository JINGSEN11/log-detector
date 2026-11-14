import assert from 'node:assert/strict'
import test from 'node:test'
import { formatRule, formatRules } from '../src/utils/rules.ts'

test('已知规则只展示中文名称', () => {
  assert.equal(formatRule('R1'), 'SQL 注入')
  assert.equal(formatRule('R14'), '单来源高频请求')
  assert.equal(formatRule('INJECT'), '提示注入特征')
})

test('未知规则保留原值，多条规则可读且空列表安全', () => {
  assert.equal(formatRule('R99'), 'R99')
  assert.equal(formatRules(['R1', 'R99']), 'SQL 注入、R99')
  assert.equal(formatRules(undefined), '—')
})
