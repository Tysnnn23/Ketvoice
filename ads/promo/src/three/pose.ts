import { interpolate, spring } from "remotion";
import { theme } from "../theme";
import { beat, FPS } from "../timing";
import type { Pose } from "./Phone";

type Key = { at: number; cfg?: keyof typeof theme.spring } & Partial<Omit<Pose, "glow">>;

// The phone's resting pose per moment; each change springs from the previous one.
const KEYS: Key[] = [
  { at: 0, x: 0, y: -3.4, z: -1, rx: 22, ry: -75, rz: 4, s: 1 },
  { at: 4, y: -0.42, z: -0.3, rx: 6, ry: -20, rz: 0, s: 0.9, cfg: "smooth" },
  { at: beat(0), ry: -8, rx: 3, y: -0.14, z: 0, s: 0.93, cfg: "bouncy" },
  { at: beat(8) - 2, ry: 8, cfg: "snappy" },
  { at: beat(16) - 2, ry: 20, x: -0.42, s: 0.86, cfg: "snappy" },
  { at: beat(24) - 2, ry: -10, x: 0, s: 0.93, cfg: "snappy" },
  { at: beat(28.9), ry: 58, z: -2.2, y: -1.3, rx: 14, cfg: "smooth" },
  { at: beat(32) - 3, ry: -6, z: 0.15, y: -0.07, rx: 3, s: 0.8, cfg: "bouncy" },
  { at: beat(44) - 16, ry: 0, rx: 0, z: 3.9, y: 0.02, s: 0.9, cfg: "smooth" },
  { at: beat(52) - 2, ry: 12, rx: 3, z: 0, y: -0.04, s: 0.95, cfg: "snappy" },
  { at: beat(53) - 2, ry: -12, cfg: "snappy" },
  { at: beat(54) - 2, ry: 12, cfg: "snappy" },
  { at: beat(55) - 2, ry: -12, cfg: "snappy" },
  { at: beat(56), ry: 24, y: -1.75, z: -1.4, rx: 10, s: 0.8, cfg: "smooth" },
  { at: beat(60), y: -4.2, cfg: "smooth" },
];
const FIELDS = ["x", "y", "z", "rx", "ry", "rz", "s"] as const;

/** A quick turn through the cut: towards [dir] before it, back from the other side after. */
const whip = (g: number, at: number, dir: number) => {
  const d = g - at;
  if (d < -9 || d > 12) return 0;
  return d < 0 ? dir * 24 * (1 + d / 9) ** 2 : -dir * 18 * (1 - d / 12) ** 2;
};

export const poseAt = (g: number): Pose => {
  const v: Record<string, number> = { x: 0, y: 0, z: 0, rx: 0, ry: 0, rz: 0, s: 1 };
  let prev: Record<string, number> = { ...v };
  KEYS.forEach((k, i) => {
    const target = { ...prev };
    FIELDS.forEach((f) => { if (k[f] !== undefined) target[f] = k[f] as number; });
    if (i === 0) { Object.assign(v, target); prev = target; return; }
    const p = spring({ frame: g - k.at, fps: FPS, config: theme.spring[k.cfg ?? "smooth"] });
    FIELDS.forEach((f) => { v[f] += (target[f] - prev[f]) * p; });
    prev = target;
  });
  v.ry += whip(g, beat(8), 1) + whip(g, beat(16), -1) + whip(g, beat(24), 1);
  // Breathing while it rests.
  v.ry += Math.sin(g / 52) * 2.2;
  v.y += Math.sin(g / 74) * 0.025;
  // Drop punch.
  const punch = (at: number, k: number) => (g >= at && g < at + 20 ? k * Math.sin(((g - at) / 20) * Math.PI) : 0);
  v.s *= 1 + punch(beat(0), 0.05) + punch(beat(32), 0.07);
  // The screen wakes on the first drop, dims under the breakdown.
  const on = interpolate(g, [beat(0) - 2, beat(0) + 3, beat(0) + 14], [0, 1.5, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const dim = interpolate(g, [beat(28.9), beat(29.6), beat(32) - 3, beat(32) + 4], [1, 0.22, 0.22, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: theme.ease.inOut });
  return { x: v.x, y: v.y, z: v.z, rx: v.rx, ry: v.ry, rz: v.rz, s: v.s, glow: on * dim };
};
