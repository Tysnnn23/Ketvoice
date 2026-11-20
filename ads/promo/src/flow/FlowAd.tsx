import React from "react";
import { AbsoluteFill, Audio, Img, OffthreadVideo, Sequence, interpolate, interpolateColors, spring, staticFile, useCurrentFrame } from "remotion";
import "../fonts";
import { Finish } from "../components/Layers";
import { CutBlur, Dust, MaskTitle, PopCard, Punch } from "../components/Studio";
import { theme } from "../theme";

// 9:16, 60 fps, cut on the sound of the reference clip (26.2 s): quiet opening, drop at 4.0 s with a beat about
// every second, breakdown from 16.0 s (hits at 21.06 and 23.06 s), second drop at 24.0 s.
export const FLOW = { fps: 60, w: 1080, h: 1920, total: 1574 };
const s = (t: number) => Math.round(t * 60);
const T = { drop: s(4.0), album: s(6.06), wall: s(8.06), player: s(10.3), mix: s(13.0), lyrics: s(16.0), claims: s(19.3), logo: s(23.06), end: s(24.0) };
const BEATS = [...Array.from({ length: 12 }, (_, n) => s(4.03 + n * 1.005)), s(24.03), s(25.05), s(26.05)];
const LIKE = s(4.03 + 8 * 1.005); // the heart is tapped on a beat
const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;
const FONT = theme.font;

type Lang = "en" | "fr";
const TEXT = {
  en: { tag: ["Your", "music,", "mixed."], home: ["Made", "for you."], album: ["Albums", "in motion."], wall: ["Find", "anything."], player: ["Love it.", "Keep it."],
    mix: ["Ketmix", "blends it."], lyrics: ["Feel", "every word."], claims: ["Free.", "No ads.", "Just music."], search: "Juice WRLD", get: "Free for Android" },
  fr: { tag: ["Ta", "musique,", "mixée."], home: ["Fait", "pour toi."], album: ["Albums", "en mouvement."], wall: ["Trouve", "tout."], player: ["Aime.", "Garde."],
    mix: ["Ketmix", "enchaîne."], lyrics: ["Ressens", "chaque mot."], claims: ["Gratuit.", "Sans pub.", "Juste la musique."], search: "Juice WRLD", get: "Gratuit sur Android" },
} as const;
const SITE = "ketvoice-web.vercel.app";

/** One colour per moment behind everything; it glides from one to the next. */
const Wash: React.FC = () => {
  const f = useCurrentFrame();
  const at = [0, T.drop, T.album, T.wall, T.player, T.mix, T.lyrics, T.claims, T.logo];
  const cols = ["#FA2D48", "#E0405E", "#3C7FD8", "#8A49D6", "#3A78C8", "#2F6FD0", "#234E86", "#FA2D48", "#E8508C"];
  const c = interpolateColors(f, at, cols);
  const quiet = interpolate(f, [T.drop - 30, T.drop, T.lyrics, T.lyrics + 40, T.end - 20, T.end], [0.5, 1, 1, 0.55, 0.55, 1], clamp);
  const dx = Math.sin(f / 150) * 60, dy = Math.cos(f / 190) * 50;
  return (
    <AbsoluteFill style={{ background: theme.colors.bg }}>
      <div style={{ position: "absolute", width: 1700, height: 1700, left: -310 + dx, top: 420 + dy, borderRadius: "50%", opacity: 0.34 * quiet,
        background: `radial-gradient(circle, ${c} 0%, rgba(0,0,0,0) 62%)` }} />
      <div style={{ position: "absolute", width: 1200, height: 1200, left: 220 - dx, top: -520 - dy, borderRadius: "50%", opacity: 0.16 * quiet,
        background: `radial-gradient(circle, ${c} 0%, rgba(0,0,0,0) 64%)` }} />
    </AbsoluteFill>
  );
};

type Pose = { x: number; y: number; rx: number; ry: number; rz: number; s: number };
const KEYS: ({ at: number; cfg: keyof typeof theme.spring } & Partial<Pose>)[] = [
  { at: 0, x: 0, y: 1750, rx: 26, ry: -20, rz: -4, s: 0.92, cfg: "smooth" },
  { at: T.drop - 2, y: 0, rx: 7, ry: -10, rz: 0, s: 1, cfg: "bouncy" },
  { at: T.album - 2, ry: 9, rx: 5, cfg: "snappy" },
  { at: T.wall - 5, y: 1800, rx: 20, s: 0.9, cfg: "snappy" },
  { at: T.player - 8, y: 0, rx: 6, ry: -9, s: 1, cfg: "bouncy" },
  { at: T.mix - 2, ry: 9, cfg: "snappy" },
  { at: T.lyrics - 2, ry: 0, rx: 0, s: 1.07, y: 26, cfg: "smooth" },
  { at: T.claims - 8, y: 1850, rx: 18, s: 0.9, cfg: "snappy" },
];
const FIELDS = ["x", "y", "rx", "ry", "rz", "s"] as const;
const poseAt = (f: number): Pose => {
  const v: Record<string, number> = {};
  let prev: Record<string, number> = {};
  KEYS.forEach((k, i) => {
    const target = { ...prev };
    FIELDS.forEach((n) => { if (k[n] !== undefined) target[n] = k[n] as number; });
    if (i === 0) { Object.assign(v, target); prev = target; return; }
    const p = spring({ frame: f - k.at, fps: 60, config: theme.spring[k.cfg] });
    FIELDS.forEach((n) => { v[n] += (target[n] - prev[n]) * p; });
    prev = target;
  });
  // It breathes while it rests, and kicks a little on each beat.
  v.ry += Math.sin(f / 46) * 1.6; v.y += Math.sin(f / 70) * 6;
  BEATS.forEach((b) => { if (f >= b && f < b + 14) v.s *= 1 + 0.018 * (1 - (f - b) / 14) ** 2; });
  return v as unknown as Pose;
};

const PW = 600, PH = Math.round((PW - 20) * (1920 / 870)) + 20, CY = 1150;

/** The phone: a dark body, the recording as its screen, a soft light that slides across the glass as it turns. */
const Device: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const f = useCurrentFrame();
  const p = poseAt(f);
  if (p.y > 1600) return null;
  return (
    <div style={{ position: "absolute", left: 540 - PW / 2 + p.x, top: CY - PH / 2 + p.y, width: PW, height: PH, perspective: 2400 }}>
      <div style={{ width: "100%", height: "100%", boxSizing: "border-box", padding: 10, borderRadius: 70,
        transform: `rotateX(${p.rx}deg) rotateY(${p.ry}deg) rotateZ(${p.rz}deg) scale(${p.s})`,
        background: "linear-gradient(145deg, #34343b 0%, #0d0d10 38%, #1d1d22 100%)",
        boxShadow: `${-p.ry * 2.4}px 70px 130px -34px rgba(0,0,0,0.85), 0 0 0 1.5px rgba(255,255,255,0.16) inset, 0 30px 60px -30px rgba(0,0,0,0.9)` }}>
        <div style={{ width: "100%", height: "100%", borderRadius: 60, overflow: "hidden", position: "relative", background: "#000" }}>
          {children}
          <div style={{ position: "absolute", inset: 0, pointerEvents: "none",
            background: `linear-gradient(${112 + p.ry * 2}deg, rgba(255,255,255,0.13) 0%, rgba(255,255,255,0) ${26 + p.ry}%)` }} />
        </div>
      </div>
    </div>
  );
};

const Clip: React.FC<{ from: number; to: number; name: string; start: number }> = ({ from, to, name, start }) => (
  <Sequence from={from} durationInFrames={to - from} layout="none">
    <OffthreadVideo muted src={staticFile(`flow/${name}.mp4`)} trimBefore={s(start)} style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover" }} />
  </Sequence>
);

const Title: React.FC<{ from: number; to: number; lines: readonly string[]; size?: number }> = ({ from, to, lines, size = 118 }) => (
  <Sequence from={from} durationInFrames={to - from} layout="none">
    <MaskTitle lines={[...lines]} exitAt={to - from} top={150} left={84} size={size} />
  </Sequence>
);

const COVERS = ["c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9"];
/** A tilted wall of covers; rows drift in opposite directions. */
const Wall: React.FC<{ dim?: number; blur?: number }> = ({ dim = 0, blur = 0 }) => {
  const f = useCurrentFrame();
  const size = 300, gap = 26, rows = 9, per = 9;
  return (
    <AbsoluteFill style={{ overflow: "hidden", filter: blur ? `blur(${blur}px)` : undefined }}>
      <div style={{ position: "absolute", left: -900, top: -500, width: 2900, transform: "rotate(-22deg)", transformOrigin: "50% 50%" }}>
        {Array.from({ length: rows }).map((_, r) => {
          const dir = r % 2 ? 1 : -1;
          const shift = ((f * (0.9 + (r % 3) * 0.35) * dir) % (size + gap)) - (size + gap);
          return (
            <div key={r} style={{ display: "flex", gap, marginBottom: gap, transform: `translateX(${shift}px)` }}>
              {Array.from({ length: per + 2 }).map((__, i) => (
                <Img key={i} src={staticFile(`flow/${COVERS[(i + r * 4) % COVERS.length]}.jpg`)} style={{ width: size, height: size, borderRadius: 30, flex: "none",
                  boxShadow: "0 24px 50px -20px rgba(0,0,0,0.7)" }} />
              ))}
            </div>
          );
        })}
      </div>
      <AbsoluteFill style={{ background: `rgba(6,6,8,${dim})` }} />
    </AbsoluteFill>
  );
};

/** The wall scene: covers rush in, the words land over them, a search field types a name. */
const WallScene: React.FC<{ lines: readonly string[]; query: string }> = ({ lines, query }) => {
  const f = useCurrentFrame();
  const len = T.player - T.wall;
  const inP = interpolate(f, [0, 16], [0, 1], { ...clamp, easing: theme.ease.out });
  const outP = interpolate(f, [len - 12, len], [0, 1], { ...clamp, easing: theme.ease.in });
  const typed = query.slice(0, Math.round(interpolate(f, [34, 92], [0, query.length], clamp)));
  const pill = spring({ frame: f - 22, fps: 60, config: theme.spring.bouncy });
  return (
    <AbsoluteFill style={{ opacity: inP * (1 - outP), transform: `scale(${1.5 - 0.5 * inP + outP * 0.25})`, filter: `blur(${(1 - inP) * 16 + outP * 14}px)` }}>
      <Wall dim={0.52} />
      <MaskTitle lines={[...lines]} exitAt={len} top={640} size={150} align="center" />
      <div style={{ position: "absolute", left: 160, right: 160, top: 1080, height: 104, borderRadius: 52, display: "flex", alignItems: "center", gap: 22, padding: "0 38px",
        background: "rgba(24,24,28,0.92)", border: "1.5px solid rgba(255,255,255,0.16)", boxShadow: "0 40px 80px -30px rgba(0,0,0,0.8)",
        transform: `scale(${0.7 + 0.3 * pill})`, opacity: Math.min(1, pill * 3), fontFamily: FONT, fontSize: 42, fontWeight: 560, color: theme.colors.text }}>
        <svg width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="#9C9CA6" strokeWidth="2.2" strokeLinecap="round"><circle cx="10.5" cy="10.5" r="6.5" /><path d="M15.5 15.5 21 21" /></svg>
        <span>{typed}</span>
        <span style={{ width: 3, height: 46, background: theme.colors.hero, marginLeft: -12, opacity: Math.floor(f / 16) % 2 ? 0.2 : 1 }} />
      </div>
    </AbsoluteFill>
  );
};

/** On the beat the heart is tapped: a heart rises from the button and a ring opens. */
const LikeBurst: React.FC = () => {
  const f = useCurrentFrame() - LIKE;
  if (f < 0 || f > 70) return null;
  const p = spring({ frame: f, fps: 60, config: theme.spring.bouncy });
  const fade = interpolate(f, [34, 66], [1, 0], clamp);
  const ring = interpolate(f, [0, 30], [0, 1], { ...clamp, easing: theme.ease.out });
  return (
    <div style={{ position: "absolute", left: 664, top: 1196, width: 0, height: 0 }}>
      <div style={{ position: "absolute", left: -110, top: -110, width: 220, height: 220, borderRadius: "50%", border: `5px solid ${theme.colors.hero}`,
        transform: `scale(${0.3 + ring * 1.1})`, opacity: (1 - ring) * 0.9 }} />
      <svg width="190" height="190" viewBox="0 0 24 24" style={{ position: "absolute", left: -95, top: -95 - f * 2.6, transform: `scale(${p}) rotate(${-8 + f * 0.1}deg)`, opacity: fade,
        filter: "drop-shadow(0 18px 30px rgba(0,0,0,0.5))" }}>
        <path fill={theme.colors.hero} d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z" />
      </svg>
    </div>
  );
};

/** Three short lines, one per hit; the earlier ones step back. */
const Claims: React.FC<{ lines: readonly string[] }> = ({ lines }) => {
  const f = useCurrentFrame();
  const at = [0, s(20.05) - T.claims, s(21.06) - T.claims];
  const len = T.logo - T.claims;
  const out = interpolate(f, [len - 16, len - 2], [0, 1], { ...clamp, easing: theme.ease.in });
  return (
    <div style={{ position: "absolute", left: 96, right: 60, top: 700, fontFamily: FONT, color: theme.colors.text, opacity: 1 - out, transform: `translateY(${-out * 60}px)`, filter: `blur(${out * 10}px)` }}>
      {lines.map((line, i) => {
        const p = interpolate(f, [at[i], at[i] + 22], [0, 1], { ...clamp, easing: theme.ease.out });
        const newer = i < 2 ? interpolate(f, [at[i + 1], at[i + 1] + 16], [0, 1], clamp) : 0;
        return (
          <div key={i} style={{ overflow: "hidden", paddingBottom: 18, marginBottom: -4 }}>
            <div style={{ fontSize: 132, fontWeight: 760, letterSpacing: "-0.035em", lineHeight: 1.08, transform: `translateY(${(1 - p) * 110}%)`, opacity: (0.2 + 0.8 * p) * (1 - newer * 0.6),
              color: i === 2 ? theme.colors.hero : theme.colors.text }}>{line}</div>
          </div>
        );
      })}
    </div>
  );
};

const Icon: React.FC<{ size: number; style?: React.CSSProperties }> = ({ size, style }) => (
  <Img src={staticFile("flow/icon.png")} style={{ width: size, height: size, borderRadius: size * 0.235, boxShadow: "0 50px 100px -30px rgba(0,0,0,0.85), 0 0 0 1.5px rgba(255,255,255,0.16) inset", ...style }} />
);

/** Opening: the icon lands on the first hit, the name rises, the line arrives word by word; all of it is pulled in before the drop. */
const Intro: React.FC<{ tag: readonly string[] }> = ({ tag }) => {
  const f = useCurrentFrame();
  const land = spring({ frame: f - 3, fps: 60, config: { damping: 15, stiffness: 110, mass: 0.9 } });
  const name = interpolate(f, [58, 92], [0, 1], { ...clamp, easing: theme.ease.out });
  const out = interpolate(f, [206, 238], [0, 1], { ...clamp, easing: theme.ease.in });
  return (
    <AbsoluteFill style={{ alignItems: "center", fontFamily: FONT, color: theme.colors.text, transform: `scale(${1 - out * 0.22})`, opacity: 1 - out, filter: `blur(${out * 16}px)` }}>
      <div style={{ marginTop: 610, transform: `scale(${1.6 - 0.6 * land}) translateY(${Math.sin(f / 50) * 6}px)`, opacity: Math.min(1, land * 2.4), filter: `blur(${(1 - Math.min(1, land)) * 18}px)` }}>
        <Icon size={300} />
      </div>
      <div style={{ overflow: "hidden", marginTop: 56, paddingBottom: 16 }}>
        <div style={{ fontSize: 132, fontWeight: 760, letterSpacing: "-0.04em", lineHeight: 1.05, transform: `translateY(${(1 - name) * 108}%)` }}>Ketvoice</div>
      </div>
      <div style={{ display: "flex", gap: 16, marginTop: 14, fontSize: 54, fontWeight: 560, color: theme.colors.textDim }}>
        {tag.map((w, i) => {
          const p = interpolate(f, [122 + i * 12, 144 + i * 12], [0, 1], { ...clamp, easing: theme.ease.out });
          return <span key={i} style={{ opacity: p, transform: `translateY(${(1 - p) * 22}px)`, color: i === tag.length - 1 ? theme.colors.hero : undefined }}>{w}</span>;
        })}
      </div>
    </AbsoluteFill>
  );
};

/** Ending: the icon lands on the last hit of the breakdown; on the drop the name and the address arrive. */
const Outro: React.FC<{ get: string }> = ({ get }) => {
  const f = useCurrentFrame(); // local: the sequence starts on the hit
  const land = spring({ frame: f, fps: 60, config: { damping: 14, stiffness: 130, mass: 0.8 } });
  const d = f - (T.end - T.logo);
  const up = spring({ frame: d, fps: 60, config: theme.spring.smooth });
  const name = interpolate(d, [2, 30], [0, 1], { ...clamp, easing: theme.ease.out });
  const pill = spring({ frame: d - 16, fps: 60, config: theme.spring.bouncy });
  const wall = interpolate(d, [0, 30], [0, 1], clamp);
  return (
    <AbsoluteFill style={{ fontFamily: FONT, color: theme.colors.text }}>
      <AbsoluteFill style={{ opacity: wall * 0.3 }}><Wall dim={0.3} blur={7} /></AbsoluteFill>
      <AbsoluteFill style={{ background: "radial-gradient(ellipse at 50% 46%, rgba(6,6,8,0.55) 0%, rgba(6,6,8,0.9) 70%)", opacity: wall }} />
      <AbsoluteFill style={{ alignItems: "center" }}>
        <div style={{ marginTop: 700 - up * 150, transform: `scale(${1.7 - 0.7 * land})`, opacity: Math.min(1, land * 2.5), filter: `blur(${(1 - Math.min(1, land)) * 20}px)` }}>
          <Icon size={300} />
        </div>
        <div style={{ overflow: "hidden", marginTop: 50, paddingBottom: 16 }}>
          <div style={{ fontSize: 136, fontWeight: 760, letterSpacing: "-0.04em", lineHeight: 1.05, transform: `translateY(${(1 - name) * 108}%)` }}>Ketvoice</div>
        </div>
        <div style={{ marginTop: 34, padding: "26px 54px", borderRadius: 999, background: theme.colors.hero, color: "#fff", fontSize: 52, fontWeight: 700, letterSpacing: "-0.01em",
          transform: `scale(${0.6 + 0.4 * pill})`, opacity: Math.min(1, pill * 3), boxShadow: "0 40px 80px -30px rgba(250,45,72,0.55)" }}>{SITE}</div>
        <div style={{ marginTop: 34, fontSize: 40, fontWeight: 560, color: theme.colors.textDim, opacity: interpolate(d, [34, 56], [0, 1], clamp) }}>{get}</div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

export const KetvoiceFlow: React.FC<{ lang?: Lang }> = ({ lang = "en" }) => {
  const t = TEXT[lang];
  const f = useCurrentFrame();
  const fadeOut = interpolate(f, [FLOW.total - 14, FLOW.total - 1], [1, 0], clamp);
  return (
    <AbsoluteFill style={{ background: theme.colors.bg }}>
      <Audio src={staticFile("music/track-flow.wav")} />
      <AbsoluteFill style={{ opacity: fadeOut }}>
        <Punch at={BEATS} amount={0.022}>
          <Wash />
          <Dust w={FLOW.w} h={FLOW.h} count={18} />
          <Sequence durationInFrames={T.drop} layout="none"><Intro tag={t.tag} /></Sequence>
          <CutBlur cuts={[T.album, T.mix]} max={10}>
            <Device>
              <Clip from={T.drop - 20} to={T.album} name="home" start={1.6} />
              <Clip from={T.album} to={T.wall + 10} name="album" start={2.2} />
              <Clip from={T.player - 10} to={T.mix} name="player" start={4.4 - (LIKE - (T.player - 10)) / 60} />
              <Clip from={T.mix} to={T.lyrics} name="ketmix" start={5.7} />
              <Clip from={T.lyrics} to={T.claims + 10} name="lyrics" start={1.6} />
            </Device>
          </CutBlur>
          <Title from={T.drop + 4} to={T.album} lines={t.home} />
          <Title from={T.album + 4} to={T.wall - 4} lines={t.album} />
          <Sequence from={T.wall} durationInFrames={T.player - T.wall} layout="none"><WallScene lines={t.wall} query={t.search} /></Sequence>
          <Title from={T.player + 4} to={T.mix} lines={t.player} />
          <LikeBurst />
          <Title from={T.mix + 4} to={T.lyrics - 2} lines={t.mix} />
          <PopCard src="flow/pop-curves.jpg" ratio={1120 / 752} from={{ x: 540, y: 1150, w: 360 }} to={{ x: 540, y: 1560, w: 860 }} at={T.mix + 50} until={T.lyrics - 26} radius={36} tilt={-3} />
          <Title from={T.lyrics + 8} to={T.claims - 4} lines={t.lyrics} />
          <Sequence from={T.claims} durationInFrames={T.logo - T.claims} layout="none"><Claims lines={t.claims} /></Sequence>
          <Sequence from={T.logo} layout="none"><Outro get={t.get} /></Sequence>
        </Punch>
        <Finish />
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
