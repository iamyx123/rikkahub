import { defineFunction } from "@openuidev/lang-core";
import { z } from "zod/v4";
import { finite } from "../vendor/functions/shared";

// 讲题时画几何图、波形、抛体轨迹都离不开三角函数和对数，上游函数库只覆盖了生活类计算
const unary = (name: string, description: string, fn: (x: number) => number) =>
  defineFunction({
    name,
    description,
    params: z.object({ x: z.number() }),
    returns: z.number(),
    fn: ({ x }) => finite(fn(x)),
  });

const gcd = (a: number, b: number): number => {
  a = Math.abs(Math.round(a));
  b = Math.abs(Math.round(b));
  while (b) [a, b] = [b, a % b];
  return a;
};

export const teachingFunctions = [
  unary("Sin", "sine of an angle in radians; use @Sin(@Rad(30)) for degrees", Math.sin),
  unary("Cos", "cosine of an angle in radians", Math.cos),
  unary("Tan", "tangent of an angle in radians", Math.tan),
  unary("Asin", "inverse sine, in radians", Math.asin),
  unary("Acos", "inverse cosine, in radians", Math.acos),
  unary("Atan", "inverse tangent, in radians", Math.atan),
  unary("Rad", "degrees to radians", (x) => (x * Math.PI) / 180),
  unary("Deg", "radians to degrees", (x) => (x * 180) / Math.PI),
  unary("Ln", "natural logarithm", Math.log),
  unary("Exp", "e raised to x", Math.exp),
  unary("Sign", "-1, 0 or 1", Math.sign),
  defineFunction({
    name: "Pi",
    description: "the number π",
    params: z.object({}),
    returns: z.number(),
    fn: () => Math.PI,
  }),
  defineFunction({
    name: "Atan2",
    description: "angle in radians of the point (x, y) from the origin",
    params: z.object({ y: z.number(), x: z.number() }),
    returns: z.number(),
    fn: ({ y, x }) => finite(Math.atan2(y, x)),
  }),
  defineFunction({
    name: "Log",
    description: "logarithm, base 10 unless a base is given",
    params: z.object({ x: z.number(), base: z.number().optional() }),
    returns: z.number(),
    // log10/log2 是精确的，换底公式会算出 2.9999999999999996 这种结果
    fn: ({ x, base }) => finite(base == null || base === 10 ? Math.log10(x) : base === 2 ? Math.log2(x) : Math.log(x) / Math.log(base)),
  }),
  defineFunction({
    name: "Hypot",
    description: "length of the vector (x, y), e.g. a distance or a hypotenuse",
    params: z.object({ x: z.number(), y: z.number() }),
    returns: z.number(),
    fn: ({ x, y }) => finite(Math.hypot(x, y)),
  }),
  defineFunction({
    name: "Gcd",
    description: "greatest common divisor of two whole numbers",
    params: z.object({ a: z.number(), b: z.number() }),
    returns: z.number(),
    fn: ({ a, b }) => gcd(a, b),
  }),
  defineFunction({
    name: "Lcm",
    description: "least common multiple of two whole numbers",
    params: z.object({ a: z.number(), b: z.number() }),
    returns: z.number(),
    fn: ({ a, b }) => (gcd(a, b) ? Math.abs(Math.round(a) * Math.round(b)) / gcd(a, b) : 0),
  }),
  defineFunction({
    name: "Factorial",
    description: "n! for a whole number n up to 170",
    params: z.object({ n: z.number() }),
    returns: z.number(),
    fn: ({ n }) => {
      const k = Math.round(n);
      if (k < 0 || k > 170) throw new Error("n out of range");
      let r = 1;
      for (let i = 2; i <= k; i++) r *= i;
      return r;
    },
  }),
  defineFunction({
    name: "Choose",
    description: "number of ways to choose k of n (combinations C(n, k))",
    params: z.object({ n: z.number(), k: z.number() }),
    returns: z.number(),
    fn: ({ n, k }) => {
      const nn = Math.round(n);
      let kk = Math.round(k);
      if (kk < 0 || kk > nn) return 0;
      kk = Math.min(kk, nn - kk);
      let r = 1;
      for (let i = 1; i <= kk; i++) r = (r * (nn - kk + i)) / i;
      return Math.round(r);
    },
  }),
];
