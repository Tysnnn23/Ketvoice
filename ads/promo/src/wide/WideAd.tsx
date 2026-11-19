import React from "react";
import { AbsoluteFill, Audio, interpolate, Sequence, spring, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import "../fonts";
import { KetmixGlyph } from "../components/KetmixGlyph";
import { Finish } from "../components/Layers";
import { CutBlur, Dim, Dust, Karaoke, MixPill, PopCard, Punch } from "../components/Studio";
import { VoiceSlider } from "../components/VoiceSlider";
import { LangCtx, useT, type Lang } from "../i18n";
import { theme } from "../theme";
import { EndCard, FreeLines, HookTiles, LogoReveal, Screen, WordLight } from "./WideParts";
import { PLAN_EL, PLAN_REF, wlen, type WidePlan } from "./timing";

type Span = readonly [number, number];
const At: React.FC<{ s: Span; children: React.ReactNode }> = ({ s, children }) => (
  <Sequence from={s[0]} durationInFrames={wlen(s)} layout="absolute-fill">{children}</Sequence>
);
const Sfx: React.FC<{ at: number; name: string; volume: number }> = ({ at, name, volume }) => (
  <Sequence from={Math.max(0, at)} layout="none">
    <Audio src={staticFile(`sfx/${name}.mp3`)} volume={volume} trimBefore={Math.max(0, -at)} />
  </Sequence>
);

const Hook: React.FC<{ p: WidePlan }> = ({ p }) => {
  const f = useCurrentFrame();
  const t = useT();
  return (
    <AbsoluteFill style={{ background: "#000" }}>
      {f < p.swap ? <WordLight text={t.wHook[0]} exitAt={p.swap} top={150} size={72} per={4} />
        : <Sequence from={p.swap} layout="absolute-fill"><WordLight text={t.wHook[1]} exitAt={p.S.hook[1] - p.swap} top={150} size={72} per={5} /></Sequence>}
      <HookTiles popAt={p.pops} mergeFrom={p.mergeFrom} mergeTo={p.mergeTo} />
    </AbsoluteFill>
  );
};

const Meet: React.FC<{ end: number }> = ({ end }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = useT();
  const sub = spring({ frame: f - 30, fps, config: theme.spring.smooth });
  const out = interpolate(f, [end - 7, end], [0, 1], { easing: theme.ease.in, extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <AbsoluteFill style={{ background: "#000", alignItems: "center", justifyContent: "center", fontFamily: theme.font, color: theme.colors.text }}>
      <div style={{ display: "flex", alignItems: "center", gap: 40, opacity: 1 - out, transform: `scale(${1 + out * 0.4})` }}>
        <KetmixGlyph size={300} at={2} />
        <div>
          <div style={{ display: "flex", fontSize: 170, fontWeight: 800, letterSpacing: "-0.045em" }}>
            {"Ketmix".split("").map((ch, i) => {
              const k = spring({ frame: f - 10 - i * 3, fps, config: theme.spring.snappy });
              return <span key={i} style={{ display: "inline-block", opacity: k, transform: `translateY(${interpolate(k, [0, 1], [60, 0])}px)` }}>{ch}</span>;
            })}
          </div>
          <div style={{ fontSize: 44, fontWeight: 520, color: theme.colors.textDim, opacity: sub, transform: `translateY(${(1 - sub) * 18}px)` }}>{t.dj}</div>
        </div>
      </div>
    </AbsoluteFill>
  );
};

const Music: React.FC<{ p: WidePlan }> = ({ p }) => {
  const f = useCurrentFrame();
  const v = interpolate(f, [p.total - p.music.fade - 5, p.total - 3], [p.music.volume, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return <Audio src={staticFile(p.music.src)} volume={v} />;
};

const Body: React.FC<{ p: WidePlan }> = ({ p }) => {
  const t = useT();
  const S = p.S;
  const n = (span: Span, k: number) => Math.round(wlen(span) * k); // a moment inside a scene, as a share of it
  const cuts = [S.home[0], S.player[0], S.lyrics[0], S.voice[0], S.editor[0]];
  return (
    <AbsoluteFill style={{ background: "#000" }}>
      <Punch at={p.punches}>
        <At s={S.hook}><Hook p={p} /></At>
        <At s={S.logo}><LogoReveal darkAt={n(S.logo, 0.36)} nameAt={n(S.logo, 0.46)} /></At>
        <CutBlur cuts={cuts}>
          <At s={S.home}>
            <Screen src="clips/s_home.mp4" duration={wlen(S.home)} rate={1.25} zoom={1} />
            <Dim at={n(S.home, 0.4)} until={n(S.home, 0.9)} amount={0.4} />
            <PopCard src="img/pop-home-card.png" ratio={796 / 452} from={{ x: 960, y: 489, w: 340 }} to={{ x: 1440, y: 470, w: 660 }} at={n(S.home, 0.4)} until={n(S.home, 0.9)} tilt={3} />
            <PopCard src="img/pop-moods.png" ratio={832 / 392} from={{ x: 960, y: 731, w: 355 }} to={{ x: 470, y: 660, w: 640 }} at={n(S.home, 0.5)} until={n(S.home, 0.92)} tilt={-3} />
            <WordLight text={t.wHome} exitAt={wlen(S.home)} />
          </At>
          <At s={S.player}>
            <Screen src="clips/s_player.mp4" duration={wlen(S.player)} rate={1.15} zoom={1} />
            <Dim at={n(S.player, 0.3)} until={n(S.player, 0.9)} amount={0.35} />
            <PopCard src="img/pop-play.png" ratio={762 / 130} from={{ x: 960, y: 609, w: 325 }} to={{ x: 1450, y: 620, w: 660 }} at={n(S.player, 0.3)} until={n(S.player, 0.9)} radius={64} tilt={2} />
            <WordLight text={t.wPlayer} exitAt={wlen(S.player)} />
          </At>
          <At s={S.lyrics}>
            <Screen src="clips/s_lyrics2.mp4" duration={wlen(S.lyrics)} x={660} zoom={1.04} />
            <Karaoke lines={["Only love never", "hesitates"]} start={20} perWord={Math.max(9, Math.round((wlen(S.lyrics) - 50) / 6))} x={1010} y={420} size={84} />
            <WordLight text={t.wLyrics} exitAt={wlen(S.lyrics)} />
          </At>
          <At s={S.voice}>
            <Screen src="clips/s_lyrics.mp4" duration={wlen(S.voice)} x={760} />
            <VoiceSlider downAt={n(S.voice, 0.29)} upAt={n(S.voice, 0.65)} right={560} top={300} label={t.vocals} />
            <WordLight text={t.wVoice} exitAt={wlen(S.voice)} />
          </At>
          {S.meet && <At s={S.meet}><Meet end={wlen(S.meet)} /></At>}
          <At s={S.ketmix}>
            <Screen src="clips/s_ketmix.mp4" duration={wlen(S.ketmix)} x={640} zoom={1} />
            <MixPill at={n(S.ketmix, 0.08)} until={n(S.ketmix, 0.94)} x={1330} y={470} scale={1.15} />
            <PopCard src="img/pop-mixbar.png" ratio={740 / 60} from={{ x: 640, y: 704, w: 316 }} to={{ x: 1330, y: 660, w: 880 }} at={n(S.ketmix, 0.34)} until={n(S.ketmix, 0.94)} radius={14} tilt={0} />
            <WordLight text={t.wKetmix} exitAt={wlen(S.ketmix)} />
          </At>
          <At s={S.editor}>
            <Screen src="clips/s_editor.mp4" duration={wlen(S.editor)} rate={1.34} h={880} zoom={1} />
            <Dim at={n(S.editor, 0.5)} until={n(S.editor, 0.93)} amount={0.55} />
            <PopCard src="img/pop-editor-box.png" ratio={796 / 530} from={{ x: 960, y: 533, w: 365 }} to={{ x: 960, y: 600, w: 1060 }} at={n(S.editor, 0.5)} until={n(S.editor, 0.93)} radius={26} tilt={-1.5} />
            <WordLight text={t.wEditor} exitAt={wlen(S.editor)} />
          </At>
        </CutBlur>
        <At s={S.free}><FreeLines at={p.freeAt.map((a) => a - S.free[0])} exitAt={wlen(S.free)} /></At>
        <At s={S.end}><EndCard /></At>
        <Dust w={1920} h={1080} count={18} />
      </Punch>
      <Finish />

      <Music p={p} />
      {/* Sounds for what only this edit shows. */}
      <Sfx at={S.voice[0] + n(S.voice, 0.29) - 3} name="slider" volume={p.fullSfx ? 0.6 : 0.4} />
      <Sfx at={S.voice[0] + n(S.voice, 0.65) - 3} name="slider" volume={p.fullSfx ? 0.45 : 0.3} />
      {[[S.home, 0.4], [S.player, 0.3], [S.editor, 0.5]].map(([span, k], i) => (
        <React.Fragment key={i}>
          <Sfx at={(span as Span)[0] + n(span as Span, k as number) - 3} name="swish" volume={p.fullSfx ? 0.5 : 0.3} />
          <Sfx at={(span as Span)[0] + n(span as Span, k as number) + 9} name="pop" volume={p.fullSfx ? 0.45 : 0.3} />
        </React.Fragment>
      ))}
      <Sfx at={S.ketmix[0] + n(S.ketmix, 0.08) - 2} name="pop" volume={p.fullSfx ? 0.5 : 0.3} />
      {p.fullSfx && (
        <>
          {p.pops.map((a) => <Sfx key={a} at={a - 2} name="tap" volume={0.5} />)}
          <Sfx at={p.mergeFrom - 3} name="whoosh" volume={0.45} />
          {p.riserAt !== null && <Sfx at={p.riserAt} name="riser" volume={0.45} />}
          <Sfx at={S.logo[0] - 1} name="impact" volume={0.55} />
          <Sfx at={S.logo[0] + 2} name="shimmer" volume={0.35} />
          {cuts.map((c) => <Sfx key={c} at={c - 8} name="whoosh" volume={0.35} />)}
          <Sfx at={S.lyrics[0] + 20} name="shimmer" volume={0.2} />
          {S.meet && <Sfx at={S.meet[0]} name="filter-sweep" volume={0.35} />}
          <Sfx at={S.ketmix[0] - 1} name="impact" volume={0.65} />
          <Sfx at={S.ketmix[0]} name="subdrop" volume={0.5} />
          {p.freeAt.map((a) => <Sfx key={a} at={a - 2} name="tap" volume={0.45} />)}
          <Sfx at={S.end[0]} name="logo-sting" volume={0.7} />
          <Sfx at={S.end[0] + 6} name="shimmer" volume={0.3} />
        </>
      )}
    </AbsoluteFill>
  );
};

export const KetvoiceAdWide: React.FC<{ lang: Lang }> = ({ lang }) => (
  <LangCtx.Provider value={lang}><Body p={PLAN_EL} /></LangCtx.Provider>
);
export const KetvoiceAdWideRef: React.FC<{ lang: Lang }> = ({ lang }) => (
  <LangCtx.Provider value={lang}><Body p={PLAN_REF} /></LangCtx.Provider>
);
