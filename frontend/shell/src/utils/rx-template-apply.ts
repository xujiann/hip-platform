/**
 * v80 审阅修补（D2，v80 复核·方案三审计）：门诊医生站套用处方模板 / 协定处方的纯逻辑。
 *
 * 修前：「撤组」按 `l.locked && l.tplId === row.tplId` 过滤，而套用时从不给行设 tplId——
 * `undefined === undefined` 恒真，撤一组会把所有已套用的协定处方锁定行一起撤掉；提示的「移除 N 行」还只数药品行。
 * 现在套用时给每行打上来源模板 id 与本次套用的批次号（同一张协定处方套两次也是两批，互不牵连），撤组只撤本批。
 */

type Line = Record<string, unknown>

/** 给一次套用的每一行打上来源模板 id 与批次号（返回新对象，不改入参） */
export function stampTemplateLines(lines: Line[], tplId: number, batch: string): Line[] {
  return lines.map((ln) => ({ ...ln, tplId, tplBatch: batch }))
}

/**
 * 撤回 row 所在的那一批锁定行（药品表与检查检验表一并撤），返回撤后的两表与撤掉的总行数。
 * row 没有批次号（不应出现：锁定行只来自套用）时只撤 row 本身，绝不按「都为空」去匹配别的行。
 */
export function dropTemplateBatch(rx: Line[], lab: Line[], row: Line): { rx: Line[]; lab: Line[]; removed: number } {
  const batch = row.tplBatch
  const hit = (l: Line) => (batch == null ? l === row : l.locked === true && l.tplBatch === batch)
  const nextRx = rx.filter((l) => !hit(l))
  const nextLab = lab.filter((l) => !hit(l))
  return { rx: nextRx, lab: nextLab, removed: rx.length - nextRx.length + lab.length - nextLab.length }
}
