import React from "react";
import { AbsoluteFill, Img, interpolate, OffthreadVideo, spring, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import { theme } from "../theme";
import { useT } from "../i18n";

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;

/** Top caption whose words light up one after the other (dim to white), as in the reference. */
export const WordLight: React.FC<{ text: string; exitAt: number; top?: number; size?: number; per?: number }> = ({ text, exitAt, top = 70, size = 58, per = 4 }) => {
  const f = useCurrentFrame();
  const out = interpolate(f, [exitAt - 9, exitAt - 1], [0, 1], { ...clamp, easing: theme.ease.in });
  return (
    <div style={{ position: "absolute", left: 0, right: 0, top, display: "flex", justifyContent: "center", columnGap: 16, flexWrap: "wrap",
      fontFamily: theme.font, fontSize: size, fontWeight: 720, letterSpacing: "-0.025em", color: theme.colors.text,
      opacity: 1 - out, transform: `translateY(${-out * 20}px)` }}>
      {text.split(" ").map((w, i) => {
        const p = interpolate(f, [3 + i * per, 13 + i * per], [0, 1], { ...clamp, easing: theme.ease.out });
        return <span key={i} style={{ opacity: 0.22 + 0.78 * p, filter: `blur(${(1 - p) * 6}px)`, transform: `translateY(${(1 - p) * 10}px)`, display: "inline-block" }}>{w}</span>;
      })}
    </div>
  );
};

/** A portrait recording as a floating phone screen, over a blurred, darkened copy of itself. */
export const Screen: React.FC<{ src: string; duration: number; rate?: number; x?: number; h?: number; zoom?: number; origin?: string }> = ({ src, duration, rate = 1, x = 960, h = 820, zoom = 1.05, origin = "50% 50%" }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  // Quick in, quicker out: the cut must not sit on black.
  const enter = spring({ frame: f, fps, config: { damping: 17, stiffness: 190, mass: 0.6 } });
  const wash = interpolate(f, [0, 5], [0, 1], clamp);
  const out = interpolate(f, [duration - 6, duration - 1], [0, 1], { ...clamp, easing: theme.ease.in });
  const push = interpolate(f, [0, duration], [1, zoom], { ...clamp, easing: theme.ease.inOut });
  const w = (h * 870) / 1920;
  const video = <OffthreadVideo src={staticFile(src)} playbackRate={rate} muted style={{ width: "100%", height: "100%", objectFit: "cover" }} />;
  return (
    <AbsoluteFill>
      <AbsoluteFill style={{ opacity: 0.55 * wash * (1 - out * 0.6), filter: "blur(70px) saturate(1.3)", transform: "scale(1.3)" }}>{video}</AbsoluteFill>
      <AbsoluteFill style={{ background: "rgba(0,0,0,0.5)" }} />
      <div style={{ position: "absolute", left: x - w / 2, top: 160 + (900 - h) / 2, width: w, height: h, borderRadius: 46, overflow: "hidden",
        border: "1px solid rgba(255,255,255,0.16)", boxShadow: "0 50px 100px -30px rgba(0,0,0,0.8)",
        opacity: enter * (1 - out), transform: `translateY(${(1 - enter) * 50 + out * -20}px) scale(${interpolate(enter, [0, 1], [0.92, 1]) * push})`,
        transformOrigin: origin }}>
        {video}
      </div>
    </AbsoluteFill>
  );
};

const Glyph: React.FC<{ kind: number; color: string; size: number }> = ({ kind, color, size }) => {
  const common = { width: size, height: size, viewBox: "0 0 24 24", fill: "none", stroke: color, strokeWidth: 1.9, strokeLinecap: "round" as const, strokeLinejoin: "round" as const };
  switch (kind) {
    case 0: return <svg {...common}><path d="M8 5.5v13l10.5-6.5z" fill={color} /></svg>;
    case 1: return <svg {...common}><path d="M4 7h16M4 12h11M4 17h7" /></svg>;
    case 2: return <svg {...common}><circle cx="9" cy="12" r="5" /><circle cx="15" cy="12" r="5" /></svg>;
    case 3: return <svg {...common}><rect x="9" y="3" width="6" height="11" rx="3" fill={color} stroke="none" /><path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21" /></svg>;
    default: return <svg {...common}><path d="M12 4v11M7 10.5l5 5 5-5M5 20h14" /></svg>;
  }
};

const TILE_COLORS = ["#2A8CFF", "#FF8D28", "#FA2D48", "#7C6BFF", "#34C759"];

/** The hook: five jobs, five icons popping on the beat, then they melt into one. */
export const HookTiles: React.FC<{ popAt: number[]; mergeFrom: number; mergeTo: number }> = ({ popAt, mergeFrom, mergeTo }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const names = useT().wTiles;
  const TILES = TILE_COLORS.map((color, i) => ({ color, name: names[i] }));
  const m = interpolate(f, [mergeFrom, mergeTo], [0, 1], { ...clamp, easing: theme.ease.inOut });
  const last = popAt.filter((a) => f >= a).length - 1;
  const labelP = last >= 0 ? spring({ frame: f - popAt[last], fps, config: theme.spring.snappy }) : 0;
  return (
    <AbsoluteFill>
      {TILES.map((t, i) => {
        const p = spring({ frame: f - popAt[i], fps, config: theme.spring.bouncy });
        const ring = interpolate(f - popAt[i], [0, 22], [0, 1], clamp);
        const baseX = 960 + (i - 2) * 210, baseY = 520;
        const ang = m * Math.PI * 1.6 + i * 1.25;
        const rad = (1 - m) * 0 + m * (1 - m) * 260;
        const x = baseX * (1 - m) + (960 + Math.cos(ang) * rad) * m;
        const y = baseY * (1 - m) + (540 + Math.sin(ang) * rad * 0.6) * m;
        const blur = m * 38;
        return (
          <div key={t.name} style={{ position: "absolute", left: x - 70, top: y - 70, width: 140, height: 140, display: "flex", alignItems: "center", justifyContent: "center",
            opacity: p, transform: `scale(${interpolate(p, [0, 1], [0.3, 1]) * (1 + m * 1.4)})`, filter: `blur(${blur}px)` }}>
            <div style={{ position: "absolute", inset: 0, borderRadius: "50%", border: `2px solid ${t.color}`, opacity: (1 - ring) * 0.7, transform: `scale(${1 + ring * 0.7})` }} />
            <div style={{ position: "absolute", inset: 8, borderRadius: "50%", opacity: 0.9,
              background: `radial-gradient(circle at 35% 28%, rgba(255,255,255,0.22), rgba(255,255,255,0) 42%), radial-gradient(circle at 50% 60%, ${t.color}55, ${t.color}14 70%)`,
              boxShadow: `inset 0 1px 0 rgba(255,255,255,0.18), 0 18px 40px -12px ${t.color}66` }} />
            <div style={{ position: "absolute", left: -90, top: -90, width: 320, height: 320, borderRadius: "50%", background: t.color, filter: "blur(60px)", opacity: m * 0.75 }} />
            <Glyph kind={i} color={t.color} size={88} />
          </div>
        );
      })}
      {last >= 0 && (
        <div style={{ position: "absolute", left: 0, right: 0, top: 650, textAlign: "center", fontFamily: theme.font, fontSize: 40, fontWeight: 650,
          color: theme.colors.text, opacity: labelP * (1 - m * 3), transform: `translateY(${(1 - labelP) * 14}px)` }}>{TILES[last].name}</div>
      )}
      <div style={{ position: "absolute", left: 960 - 30, top: 540 - 30, width: 60, height: 60, borderRadius: "50%", background: "#fff",
        opacity: interpolate(m, [0.55, 1], [0, 1], clamp), transform: `scale(${interpolate(m, [0.55, 1], [0.2, 2.4], clamp)})`, filter: "blur(6px)" }} />
    </AbsoluteFill>
  );
};

/** On the drop: a white flash, the K inside a ring of light on a pale ground, then the name on black. */
export const LogoReveal: React.FC<{ darkAt: number; nameAt: number }> = ({ darkAt, nameAt }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const flash = interpolate(f, [0, 10], [1, 0], { ...clamp, easing: theme.ease.out });
  const ring = spring({ frame: f, fps, config: theme.spring.smooth });
  const dark = interpolate(f, [darkAt, darkAt + 12], [0, 1], { ...clamp, easing: theme.ease.inOut });
  const name = spring({ frame: f - nameAt, fps, config: theme.spring.smooth });
  const shift = interpolate(name, [0, 1], [0, -250]);
  const breathe = 1 + Math.sin(f / 18) * 0.01;
  return (
    <AbsoluteFill style={{ background: `radial-gradient(circle at 50% 50%, #f3f3f6 0%, #c9c9cf 60%, #9a9aa2 100%)` }}>
      <AbsoluteFill style={{ background: "#000", opacity: dark }} />
      <div style={{ position: "absolute", left: 960 - 330, top: 540 - 330, width: 660, height: 660, borderRadius: "50%",
        border: "4px solid rgba(255,255,255,0.95)", boxShadow: "0 0 60px rgba(255,255,255,0.7), inset 0 0 50px rgba(255,255,255,0.5)",
        opacity: ring * (1 - dark), transform: `scale(${interpolate(ring, [0, 1], [0.6, 1]) * breathe})` }} />
      <div style={{ position: "absolute", left: 960 - 120 + shift, top: 540 - 120, width: 240, height: 240, borderRadius: 56, overflow: "hidden",
        background: "#000", transform: `scale(${interpolate(ring, [0, 1], [0.7, 1])})`, boxShadow: "0 30px 70px -20px rgba(0,0,0,0.6)" }}>
        <Img src={staticFile("img/ic_launcher27_glass_fg.png")} style={{ width: "100%", height: "100%" }} />
      </div>
      <div style={{ position: "absolute", left: 960 - 80, top: 540 - 95, fontFamily: theme.font, fontSize: 170, fontWeight: 800, letterSpacing: "-0.045em",
        color: "#F5F5F7", opacity: name, transform: `translateX(${interpolate(name, [0, 1], [40, 0])}px)` }}>Ketvoice</div>
      <AbsoluteFill style={{ opacity: interpolate(f, [0, 4, 26], [0, 0.9, 0], clamp) }}>
        {Array.from({ length: 36 }).map((_, i) => (
          <div key={i} style={{ position: "absolute", left: 960, top: 540, width: 2, height: 900, marginLeft: -1, transformOrigin: "50% 0%",
            transform: `rotate(${i * 10 + (i % 3) * 3}deg) translateY(${interpolate(f, [0, 26], [80, 260], clamp)}px)`,
            background: "linear-gradient(180deg, rgba(255,255,255,0.9), rgba(255,255,255,0))", opacity: 0.35 + (i % 4) * 0.15 }} />
        ))}
      </AbsoluteFill>
      <AbsoluteFill style={{ background: "#fff", opacity: flash }} />
    </AbsoluteFill>
  );
};

/** "Free. No ads. No subscription." Each line lights up on its beat. */
export const FreeLines: React.FC<{ at: number[]; exitAt: number }> = ({ at, exitAt }) => {
  const f = useCurrentFrame();
  const lines = useT().wFree;
  const out = interpolate(f, [exitAt - 9, exitAt - 1], [0, 1], { ...clamp, easing: theme.ease.in });
  return (
    <AbsoluteFill style={{ background: "#000" }}>
      <div style={{ position: "absolute", left: 1000, top: 200, width: 900, height: 700, borderRadius: "50%", filter: "blur(80px)", background: "radial-gradient(circle, rgba(255,255,255,0.10), transparent 65%)" }} />
      <div style={{ position: "absolute", left: 230, top: 300, fontFamily: theme.font, fontSize: 112, fontWeight: 760, letterSpacing: "-0.035em", lineHeight: 1.12, opacity: 1 - out }}>
        {lines.map((l, i) => {
          const seen = interpolate(f, [at[i] - 4, at[i] + 6], [0, 1], clamp);
          const lit = i === lines.length - 1 ? seen : seen * (1 - interpolate(f, [at[i + 1] - 2, at[i + 1] + 8], [0, 0.55], clamp));
          return <div key={l} style={{ color: "#F5F5F7", opacity: 0.18 + 0.82 * lit, transform: `translateX(${(1 - seen) * -24}px)` }}>{l}</div>;
        })}
      </div>
    </AbsoluteFill>
  );
};

/** The end card: K + name, address, where it runs, and the one red pill. */
export const EndCard: React.FC = () => {
  const f = useCurrentFrame();
  const t = useT();
  const { fps } = useVideoConfig();
  const a = spring({ frame: f - 2, fps, config: theme.spring.smooth });
  const b = spring({ frame: f - 14, fps, config: theme.spring.smooth });
  const c = spring({ frame: f - 24, fps, config: theme.spring.snappy });
  return (
    <AbsoluteFill style={{ background: "#000", fontFamily: theme.font, color: theme.colors.text }}>
      <div style={{ position: "absolute", left: 360, right: 360, top: 700, height: 600, borderRadius: "50%", filter: "blur(90px)",
        background: "radial-gradient(ellipse, rgba(255,255,255,0.16), transparent 65%)", opacity: a }} />
      <div style={{ position: "absolute", left: 0, right: 0, top: 300, display: "flex", justifyContent: "center", alignItems: "center", gap: 34,
        opacity: a, transform: `translateY(${(1 - a) * 30}px) scale(${interpolate(a, [0, 1], [0.94, 1])})` }}>
        <div style={{ width: 150, height: 150, borderRadius: 36, overflow: "hidden", background: "#000", border: `1px solid ${theme.colors.hairline}` }}>
          <Img src={staticFile("img/ic_launcher27_glass_fg.png")} style={{ width: "100%", height: "100%" }} />
        </div>
        <div style={{ fontSize: 150, fontWeight: 800, letterSpacing: "-0.045em" }}>Ketvoice</div>
      </div>
      <div style={{ position: "absolute", left: 0, right: 0, top: 520, textAlign: "center", opacity: b, transform: `translateY(${(1 - b) * 16}px)` }}>
        <div style={{ fontSize: 44, fontWeight: 650 }}>ketvoice-web.vercel.app</div>
        <div style={{ fontSize: 28, fontWeight: 500, color: theme.colors.textDim, marginTop: 14 }}>{t.wWhere}</div>
      </div>
      <div style={{ position: "absolute", left: 0, right: 0, top: 700, display: "flex", justifyContent: "center", opacity: c, transform: `scale(${interpolate(c, [0, 1], [0.85, 1])})` }}>
        <div style={{ padding: "18px 40px", borderRadius: 999, background: theme.colors.hero, fontSize: 32, fontWeight: 700 }}>{t.free}</div>
      </div>
    </AbsoluteFill>
  );
};
