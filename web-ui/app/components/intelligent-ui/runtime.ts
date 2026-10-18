let worker: Worker | undefined;
let idle: ReturnType<typeof setTimeout> | undefined;
let queue: Promise<unknown> = Promise.resolve();
export type UiState = Record<string, unknown>;
export type UiEvent = { type: string; target?: string; value?: unknown };
export interface UiNode {
  type: string;
  id: string;
  props: Record<string, any>;
}
export interface UiResult {
  root: UiNode;
  state: UiState;
  effects: { type: string; text: string; state?: UiState }[];
  errors: string[];
  error?: string;
}

export function renderUi(source: string, state: UiState, event?: UiEvent): Promise<UiResult> {
  const next = queue
    .catch(() => {})
    .then(
      () =>
        new Promise<UiResult>((resolve, reject) => {
          clearTimeout(idle);
          if (source.length > 65536) {
            reject(Error("交互内容过大"));
            return;
          }
          worker ??= new Worker("/intelligent-ui/worker.js");
          const fail = (message: string) => {
            clearTimeout(timer);
            worker?.terminate();
            worker = undefined;
            reject(Error(message));
          };
          const timer = setTimeout(() => fail("卡片运行超时，请查看源码"), 2000);
          worker.onerror = () => fail("卡片无法解析，请查看源码");
          worker.onmessage = ({ data }) => {
            clearTimeout(timer);
            idle = setTimeout(() => {
              worker?.terminate();
              worker = undefined;
            }, 15000);
            if (data.error) reject(Error(data.error));
            else resolve(data);
          };
          worker.postMessage({ source, state, event });
        }),
    );
  queue = next;
  return next;
}

// SHA-256 also works on the app's HTTP LAN server, where crypto.subtle is unavailable.
export function sourceKey(source: string): string {
  const bytes = new TextEncoder().encode(source);
  const words = new Uint32Array(Math.ceil((bytes.length + 9) / 64) * 16);
  bytes.forEach((b, i) => {
    words[i >>> 2] |= b << (24 - (i % 4) * 8);
  });
  words[bytes.length >>> 2] |= 0x80 << (24 - (bytes.length % 4) * 8);
  words[words.length - 1] = bytes.length * 8;
  const primes: number[] = [];
  for (let n = 2; primes.length < 64; n++) if (primes.every((p) => n % p !== 0)) primes.push(n);
  const h = primes.slice(0, 8).map((p) => ((Math.sqrt(p) % 1) * 2 ** 32) >>> 0);
  const k = primes.map((p) => ((Math.cbrt(p) % 1) * 2 ** 32) >>> 0);
  const r = (x: number, n: number) => (x >>> n) | (x << (32 - n));
  for (let offset = 0; offset < words.length; offset += 16) {
    const w = new Uint32Array(64);
    w.set(words.subarray(offset, offset + 16));
    for (let i = 16; i < 64; i++) {
      const a = w[i - 15],
        b = w[i - 2];
      w[i] =
        (r(a, 7) ^ r(a, 18) ^ (a >>> 3)) +
        w[i - 16] +
        (r(b, 17) ^ r(b, 19) ^ (b >>> 10)) +
        w[i - 7];
    }
    let [a, b, c, d, e, f, g, hh] = h;
    for (let i = 0; i < 64; i++) {
      const t1 = (hh + (r(e, 6) ^ r(e, 11) ^ r(e, 25)) + ((e & f) ^ (~e & g)) + k[i] + w[i]) | 0;
      const t2 = ((r(a, 2) ^ r(a, 13) ^ r(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      hh = g;
      g = f;
      f = e;
      e = (d + t1) | 0;
      d = c;
      c = b;
      b = a;
      a = (t1 + t2) | 0;
    }
    [a, b, c, d, e, f, g, hh].forEach((n, i) => (h[i] = (h[i] + n) >>> 0));
  }
  return h.map((n) => n.toString(16).padStart(8, "0")).join("");
}
