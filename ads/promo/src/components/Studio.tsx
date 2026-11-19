import React from "react";
import { AbsoluteFill, Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import { theme } from "../theme";

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;

/**
 * Studio title: a hairline draws, the kicker slides along it, then each line rises out of its own mask
 * (overflow clip), 5 frames apart, tracking settling as it lands. Exit: lines rise away, faster.
 */
export const MaskTitle: React.FC<{ kicker?: string; lines: string[]; exitAt: number; top?: number; size?: number; align?: "left" | "center"; left?: number }> = ({ kicker, lines, exitAt, top = 200, size = 82, align = "left", left = 90 }) => {
  const f = useCurrentFrame();
  const rule = interpolate(f, [0, 16], [0, 1], { ...clamp, easing: theme.ease.out });
  const k = interpolate(f, [4, 18], [0, 1], { ...clamp, easing: theme.ease.out });
  const out = interpolate(f, [exitAt - 11, exitAt - 1], [0, 1], { ...clamp, easing: theme.ease.in });
  return (
    <div style={{ position: "absolute", left: align === "center" ? 0 : left, right: align === "center" ? 0 : 60, top, fontFamily: theme.font,
      color: theme.colors.text, textAlign: align, display: "flex", flexDirection: "column", alignItems: align === "center" ? "center" : "flex-start" }}>
      {kicker && (
        <div style={{ display: "flex", alignItems: "center", gap: 16, marginBottom: 18, opacity: 1 - out }}>
          <div style={{ width: 54 * rule, height: 3, borderRadius: 2, background: theme.colors.hero }} />
          <div style={{ fontSize: Math.round(size * 0.3), fontWeight: 680, letterSpacing: "0.22em", color: theme.colors.hero, opacity: k,
            transform: `translateX(${(1 - k) * -14}px)` }}>{kicker}</div>
        </div>
      )}
      {lines.map((line, i) => {
        const p = interpolate(f, [6 + i * 5, 30 + i * 5], [0, 1], { ...clamp, easing: theme.ease.out });
        const o = interpolate(f, [exitAt - 11 + i * 2, exitAt - 1], [0, 1], { ...clamp, easing: theme.ease.in });
        return (
          <div key={i} style={{ overflow: "hidden", paddingBottom: size * 0.12, marginBottom: -size * 0.1 }}>
            <div style={{ fontSize: size, fontWeight: 740, lineHeight: 1.04, letterSpacing: `${interpolate(p, [0, 1], [-0.005, -0.032])}em`,
              transform: `translateY(${(1 - p) * 108 - o * 108}%)`, opacity: 0.3 + 0.7 * p }}>{line}</div>
          </div>
        );
      })}
    </div>
  );
};

type Spot = { x: number; y: number; w: number };

/**
 * A piece of the real interface lifted out of the screen: it leaves its place ([from]), lands where it can be read
 * ([to]) with a spring, tilts back as it rises, floats, then drops back faster. Speed adds a little blur.
 */
export const PopCard: React.FC<{ src: string; ratio: number; from: Spot; to: Spot; at: number; until: number; radius?: number; tilt?: number }> = ({ src, ratio, from, to, at, until, radius = 26, tilt = -4 }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const pAt = (t: number) => {
    const inP = spring({ frame: t - at, fps, config: { damping: 17, stiffness: 120, mass: 0.9 } });
    const outP = spring({ frame: t - until, fps, config: { damping: 22, stiffness: 190, mass: 0.7 } });
    return Math.max(0, inP - outP);
  };
  const p = pAt(f);
  if (f < at - 1 || (f > until + 30 && p < 0.001)) return null;
  const v = Math.abs(p - pAt(f - 1));
  const x = from.x + (to.x - from.x) * p;
  const y = from.y + (to.y - from.y) * p + Math.sin(f / 24) * 5 * p;
  const w = from.w + (to.w - from.w) * p;
  const h = w / ratio;
  const lift = Math.min(1, p * 1.3);
  return (
    <div style={{ position: "absolute", left: x - w / 2, top: y - h / 2, width: w, height: h, perspective: 1400 }}>
      <div style={{ width: "100%", height: "100%", borderRadius: radius * (w / to.w), overflow: "hidden", opacity: Math.min(1, p * 4),
        transform: `rotateX(${(1 - lift) * 14}deg) rotateZ(${tilt * lift}deg)`, filter: `blur(${Math.min(6, v * 60)}px)`,
        boxShadow: `0 ${40 * lift}px ${90 * lift}px -${20 * lift}px rgba(0,0,0,${0.75 * lift}), 0 0 0 1px rgba(255,255,255,${0.12 * lift})` }}>
        <Img src={staticFile(src)} style={{ width: "100%", height: "100%", display: "block" }} />
        <div style={{ position: "absolute", inset: 0, background: `linear-gradient(${120 + f * 0.4}deg, rgba(255,255,255,${0.1 * lift}) 0%, rgba(255,255,255,0) 38%)` }} />
      </div>
    </div>
  );
};

/** Darkens what is behind a lifted card so the eye goes to it. */
export const Dim: React.FC<{ at: number; until: number; amount?: number }> = ({ at, until, amount = 0.45 }) => {
  const f = useCurrentFrame();
  const a = interpolate(f, [at, at + 12, until, until + 10], [0, amount, amount, 0], { ...clamp, easing: theme.ease.inOut });
  return <AbsoluteFill style={{ background: `rgba(0,0,0,${a})`, pointerEvents: "none" }} />;
};

/** Directional blur and a breath of scale through each cut, so cuts read as one camera move. */
export const CutBlur: React.FC<{ cuts: number[]; children: React.ReactNode; max?: number }> = ({ cuts, children, max = 9 }) => {
  const f = useCurrentFrame();
  let k = 0;
  cuts.forEach((c) => { const d = Math.abs(f - c); if (d < 7) k = Math.max(k, (1 - d / 7) ** 2); });
  return <AbsoluteFill style={{ filter: k > 0.01 ? `blur(${k * max}px)` : undefined, transform: `scale(${1 + k * 0.025})` }}>{children}</AbsoluteFill>;
};

/** Ketmix's « Mixing » badge, redrawn sharp: two rings, the word, the next title. */
export const MixPill: React.FC<{ at: number; until: number; x: number; y: number; scale?: number; next?: string }> = ({ at, until, x, y, scale = 1, next = "Sicario" }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = Math.max(0, spring({ frame: f - at, fps, config: theme.spring.bouncy }) - spring({ frame: f - until, fps, config: theme.spring.snappy }));
  const spin = (f - at) * 2.2;
  return (
    <div style={{ position: "absolute", left: x, top: y, transform: `translate(-50%, -50%) scale(${scale * interpolate(p, [0, 1], [0.6, 1])})`, opacity: Math.min(1, p * 3),
      display: "flex", alignItems: "center", gap: 18, padding: "18px 30px 18px 22px", borderRadius: 999, fontFamily: theme.font,
      background: "rgba(28,28,32,0.86)", border: "1px solid rgba(255,255,255,0.14)", boxShadow: "0 30px 60px -20px rgba(0,0,0,0.7)" }}>
      <svg width="56" height="36" viewBox="0 0 56 36" style={{ transform: `rotate(${Math.sin(spin / 40) * 8}deg)` }}>
        <circle cx="19" cy="18" r="13" fill="none" stroke="#F5F5F7" strokeWidth="3.4" />
        <circle cx="37" cy="18" r="13" fill="none" stroke={theme.colors.hero} strokeWidth="3.4" />
      </svg>
      <div style={{ fontSize: 34, fontWeight: 700, color: theme.colors.text }}>Mixing</div>
      <div style={{ fontSize: 34, fontWeight: 500, color: theme.colors.textDim }}>· {next}</div>
    </div>
  );
};

/** A lyric line sung word by word, Cascade-style: the sung words full white, the rest dim. */
export const Karaoke: React.FC<{ lines: string[]; start: number; perWord: number; x: number; y: number; size?: number }> = ({ lines, start, perWord, x, y, size = 76 }) => {
  const f = useCurrentFrame();
  let idx = 0;
  return (
    <div style={{ position: "absolute", left: x, top: y, fontFamily: theme.font, fontSize: size, fontWeight: 760, letterSpacing: "-0.03em", lineHeight: 1.08 }}>
      {lines.map((line, li) => {
        const enter = interpolate(f, [start - 14 + li * 6, start + 6 + li * 6], [0, 1], { ...clamp, easing: theme.ease.out });
        return (
          <div key={li} style={{ display: "flex", flexWrap: "wrap", columnGap: size * 0.26, opacity: enter, transform: `translateY(${(1 - enter) * 30}px)` }}>
            {line.split(" ").map((w, wi) => {
              const t0 = start + idx++ * perWord;
              const lit = interpolate(f, [t0, t0 + perWord * 0.8], [0, 1], { ...clamp, easing: theme.ease.out });
              return <span key={wi} style={{ color: "#F5F5F7", opacity: 0.32 + 0.68 * lit, transform: `translateY(${-3 * lit}px)`, display: "inline-block" }}>{w}</span>;
            })}
          </div>
        );
      })}
    </div>
  );
};
