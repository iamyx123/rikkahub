// 化学结构式引擎：SMILES -> SVG。打包后在 App 的 QuickJS 里运行，见 build.mjs
import { Molecule } from 'openchemlib';

const SUB = '₀₁₂₃₄₅₆₇₈₉';
const sub = (n) => String(n).replace(/\d/g, (d) => SUB[d]);

// 网格方向：右、上、下、左（OCL 的 y 轴向下）
const DIRS = [[1, 0], [0, -1], [0, 1], [-1, 0]];
const key = (x, y) => x + ',' + y;

/** 无环分子按课本习惯排成直角网格：主链水平，支链竖直，失败返回 false */
function gridLayout(m, withHydrogens) {
  const n = m.getAtoms();
  if (n === 0) return false;
  for (let a = 0; a < n; a++) if (m.isRingAtom(a)) return false;
  const nbr = (a) => { const r = []; for (let i = 0; i < m.getConnAtoms(a); i++) r.push(m.getConnAtom(a, i)); return r; };
  const far = (start) => {
    const prev = new Array(n).fill(-1), seen = new Array(n).fill(false), queue = [start];
    seen[start] = true;
    let last = start;
    while (queue.length) { const a = queue.shift(); last = a; for (const b of nbr(a)) if (!seen[b]) { seen[b] = true; prev[b] = a; queue.push(b); } }
    if (seen.some((s) => !s)) return null;
    const path = []; for (let a = last; a !== -1; a = prev[a]) path.push(a);
    return path;
  };
  if (!far(0)) return false;
  // 树上任意两点路径唯一。主链取原子最多的路径，同样长时取碳多的，尽量不让 =O 这类端基占主链两端
  const leaves = [];
  for (let a = 0; a < n; a++) if (m.getConnAtoms(a) <= 1) leaves.push(a);
  const pathBetween = (from, to) => {
    const prev = new Array(n).fill(-1), seen = new Array(n).fill(false), queue = [from];
    seen[from] = true;
    while (queue.length) { const a = queue.shift(); for (const b of nbr(a)) if (!seen[b]) { seen[b] = true; prev[b] = a; queue.push(b); } }
    const path = []; for (let a = to; a !== -1; a = prev[a]) path.push(a);
    return path.reverse();
  };
  const terminalPenalty = (a) => (m.getAtomicNo(a) !== 6 && m.getConnBondOrder(a, 0) > 1 ? 1 : 0);
  let chain = [0];
  let best = -Infinity;
  for (let i = 0; i < leaves.length; i++) {
    for (let j = i + 1; j < leaves.length; j++) {
      const path = pathBetween(leaves[i], leaves[j]);
      const carbons = path.filter((a) => m.getAtomicNo(a) === 6).length;
      const score = path.length * 100 + carbons * 10 - (terminalPenalty(path[0]) + terminalPenalty(path[path.length - 1])) * 150;
      if (score > best) { best = score; chain = path; }
    }
  }
  // 碳多的一端放左边，读起来像 CH3-CH2-OH
  if (m.getAtomicNo(chain[0]) !== 6 && m.getAtomicNo(chain[chain.length - 1]) === 6) chain.reverse();
  const pos = new Array(n), used = new Map();
  chain.forEach((a, i) => { pos[a] = [i, 0]; used.set(key(i, 0), a); });
  const occupiedDirs = (a) => nbr(a).filter((b) => pos[b]).map((b) => [pos[b][0] - pos[a][0], pos[b][1] - pos[a][1]]);
  const place = (a, from) => {
    const dirFrom = from === null ? null : [pos[a][0] - pos[from][0], pos[a][1] - pos[from][1]];
    const order = dirFrom === null || dirFrom[1] === 0 ? [DIRS[1], DIRS[2], DIRS[0], DIRS[3]] : [dirFrom, DIRS[0], DIRS[3], [-dirFrom[0], -dirFrom[1]]];
    for (const b of nbr(a)) {
      if (pos[b]) continue;
      const taken = occupiedDirs(a);
      const dir = order.find((d) => !taken.some((t) => t[0] === d[0] && t[1] === d[1]) && !used.has(key(pos[a][0] + d[0], pos[a][1] + d[1])));
      if (!dir) return false;
      pos[b] = [pos[a][0] + dir[0], pos[a][1] + dir[1]];
      used.set(key(...pos[b]), b);
      if (!place(b, a)) return false;
    }
    return true;
  };
  for (const a of chain) if (!place(a, null)) return false;
  // 结构式里重原子间距拉开，H 挂在一个单位外，相邻支链的 H 才不会挤在一起；
  // 结构简式没有 H，横向键要容纳 CH₃ 这类标签，比纵向键长一些
  const [sx, sy] = withHydrogens ? [1.7, 1.7] : [1.5, 1];
  const xy = pos.map(([x, y]) => [x * sx, y * sy]);
  const hydrogens = [];
  if (withHydrogens) {
    const placed = [...xy];
    for (let a = 0; a < n; a++) {
      for (let h = 0; h < m.getImplicitHydrogens(a); h++) {
        const taken = occupiedDirs(a).concat(hydrogens.filter((x) => x.atom === a).map((x) => x.dir));
        const dir = [DIRS[1], DIRS[2], DIRS[3], DIRS[0]].find((d) => {
          if (taken.some((t) => t[0] === d[0] && t[1] === d[1])) return false;
          const p = [xy[a][0] + d[0], xy[a][1] + d[1]];
          return placed.every((q) => Math.hypot(p[0] - q[0], p[1] - q[1]) >= 0.75);
        });
        if (!dir) return false;
        placed.push([xy[a][0] + dir[0], xy[a][1] + dir[1]]);
        hydrogens.push({ atom: a, dir });
      }
    }
  }
  for (let a = 0; a < n; a++) { m.setAtomX(a, xy[a][0]); m.setAtomY(a, xy[a][1]); }
  for (const { atom, dir } of hydrogens) {
    const h = m.addAtom(1);
    m.addBond(atom, h, 1);
    m.setAtomX(h, xy[atom][0] + dir[0]);
    m.setAtomY(h, xy[atom][1] + dir[1]);
  }
  return true;
}

function labelCarbons(m, mode) {
  m.ensureHelperArrays(Molecule.cHelperRings);
  for (let a = 0; a < m.getAtoms(); a++) {
    if (m.getAtomicNo(a) !== 6) continue;
    // 和元素符号相同的自定义标签会被忽略，补一个零宽字符让 C 照常显示
    if (mode === 'full') m.setAtomCustomLabel(a, 'C\u200B');
    else if (!m.isRingAtom(a)) {
      const h = m.getImplicitHydrogens(a);
      m.setAtomCustomLabel(a, h ? 'CH' + (h > 1 ? sub(h) : '') : 'C\u200B');
    }
  }
}

let counter = 0;

/**
 * @param smiles SMILES
 * @param mode skeletal 键线式 / condensed 结构简式 / full 结构式
 * @param mono 单色（墨水屏），不给杂原子上色
 */
export function render(smiles, mode = 'skeletal', mono = false) {
  try {
    // 默认的坐标模板只对金刚烷这类笼状分子有用，初始化却要几秒（QuickJS 里），跳过
    const m = Molecule.fromSmiles(String(smiles).trim(), { noCoordinates: true });
    if (m.getAllAtoms() === 0) throw new Error('empty');
    m.inventCoordinates({ skipDefaultTemplates: true });
    m.ensureHelperArrays(Molecule.cHelperRings);
    const fragments = m.getFragmentNumbers(new Array(m.getAllAtoms()).fill(0), false, false);
    let gridded = false;
    if (mode !== 'skeletal' && fragments === 1) {
      gridded = gridLayout(m, mode === 'full');
    }
    if (mode === 'full' && !gridded) {
      m.addImplicitHydrogens();
      m.inventCoordinates({ keepHydrogens: true, skipDefaultTemplates: true });
    }
    if (mode !== 'skeletal') labelCarbons(m, mode);
    // 文字大小跟着键长缩放，带标签的式子把字缩小，给键留出长度
    const factorTextSize = mode === 'skeletal' ? 1 : mode === 'full' ? 0.8 : 0.68;
    const svg = m.toSVG(800, 600, 'chem' + (counter++), {
      autoCrop: true,
      autoCropMargin: 4,
      factorTextSize,
      suppressChiralText: true,
      suppressCIPParity: true,
      suppressESR: true,
      noStereoProblem: true,
      noCarbonLabelWithCustomLabel: true,
      noImplicitAtomLabelColors: mono || mode === 'full',
    });
    const fontSize = Number((/font-size="([\d.]+)"/.exec(svg) || [])[1]) || 14 * factorTextSize;
    return JSON.stringify({ svg: mono || mode === 'full' ? svg.replace(/rgb\(\d+,\d+,\d+\)/g, 'rgb(0,0,0)') : svg, fontSize });
  } catch (e) {
    return JSON.stringify({ error: String((e && e.message) || e) });
  }
}
