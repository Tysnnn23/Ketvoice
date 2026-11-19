import React from "react";
import { AbsoluteFill, Audio, interpolate, Sequence, spring, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import "../fonts";
import { KetmixGlyph } from "../components/KetmixGlyph";
import { MixCard } from "../components/MixCard";
import { VoiceSlider } from "../components/VoiceSlider";
import { Finish } from "../components/Layers";
import { theme } from "../theme";
import { EndCard, FreeLines, HookTiles, LogoReveal, Screen, WordLight } from "./WideParts";
import { wb, wlen, WS, WTOTAL } from "./timing";

const At: React.FC<{ s: readonly [number, number]; children: React.ReactNode }> = ({ s, children }) => (
  <Sequence from={s[0]} durationInFrames={wlen(s)} layout="absolute-fill">{children}</Sequence>
);
const Sfx: React.FC<{ at: number; name: string; volume: number }> = ({ at, name, volume }) => (
  <Sequence from={Math.max(0, at)} layout="none">
    <Audio src={staticFile(`sfx/${name}.mp3`)} volume={volume} trimBefore={Math.max(0, -at)} />
  </Sequence>
);

const Hook: React.FC = () => {
  const f = useCurrentFrame();
  const swap = wb(-4);
  const pops = [-12, -10.5, -9, -7.5, -6].map(wb);
  return (
    <AbsoluteFill style={{ background: "#000" }}>
      {f < swap ? <WordLight text="Why use five apps for your music?" exitAt={swap} top={150} size={72} per={5} />
        : <Sequence from={swap} layout="absolute-fill"><WordLight text="What if they were one?" exitAt={wb(0) - swap} top={150} size={72} per={5} /></Sequence>}
      <HookTiles popAt={pops} mergeFrom={wb(-3.6)} mergeTo={wb(-0.15)} />
    </AbsoluteFill>
  );
};

const Meet: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const sub = spring({ frame: f - 30, fps, config: theme.spring.smooth });
  const end = wlen(WS.meet);
  const out = interpolate(f, [end - 7, end], [0, 1], { easing: theme.ease.in, extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <AbsoluteFill style={{ background: "#000", alignItems: "center", justifyContent: "center", fontFamily: theme.font, color: theme.colors.text }}>
      <div style={{ display: "flex", alignItems: "center", gap: 40, opacity: 1 - out, transform: `scale(${1 + out * 0.4})` }}>
        <KetmixGlyph size={300} at={2} />
        <div>
          <div style={{ display: "flex", fontSize: 170, fontWeight: 800, letterSpacing: "-0.045em" }}>
            {"Ketmix".split("").map((ch, i) => {
              const p = spring({ frame: f - 10 - i * 3, fps, config: theme.spring.snappy });
              return <span key={i} style={{ display: "inline-block", opacity: p, transform: `translateY(${interpolate(p, [0, 1], [60, 0])}px)` }}>{ch}</span>;
            })}
          </div>
          <div style={{ fontSize: 44, fontWeight: 520, color: theme.colors.textDim, opacity: sub, transform: `translateY(${(1 - sub) * 18}px)` }}>The DJ built into Ketvoice</div>
        </div>
      </div>
    </AbsoluteFill>
  );
};

const Music: React.FC = () => {
  const f = useCurrentFrame();
  const v = interpolate(f, [WTOTAL - 75, WTOTAL - 5], [0.9, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return <Audio src={staticFile("music/track-wide.wav")} volume={v} />;
};

export const KetvoiceAdWide: React.FC = () => (
  <AbsoluteFill style={{ background: "#000" }}>
    <At s={WS.hook}><Hook /></At>
    <At s={WS.logo}><LogoReveal darkAt={Math.round(1.5 * 29.03)} nameAt={Math.round(1.9 * 29.03)} /></At>
    <At s={WS.home}>
      <Screen src="clips/s_home.mp4" duration={wlen(WS.home)} rate={1.25} />
      <WordLight text="Made for you. Every single day." exitAt={wlen(WS.home)} />
    </At>
    <At s={WS.player}>
      <Screen src="clips/s_player.mp4" duration={wlen(WS.player)} rate={1.15} zoom={1.08} origin="50% 30%" />
      <WordLight text="Every album, in full screen." exitAt={wlen(WS.player)} />
    </At>
    <At s={WS.lyrics}>
      <Screen src="clips/s_lyrics2.mp4" duration={wlen(WS.lyrics)} zoom={1.12} origin="50% 35%" />
      <WordLight text="Lyrics, synced word by word." exitAt={wlen(WS.lyrics)} />
    </At>
    <At s={WS.voice}>
      <Screen src="clips/s_lyrics.mp4" duration={wlen(WS.voice)} x={760} />
      <VoiceSlider downAt={wb(24) - WS.voice[0]} upAt={wb(26.5) - WS.voice[0]} right={560} top={300} label="vocals" />
      <WordLight text="Turn the vocals down. Sing along." exitAt={wlen(WS.voice)} />
    </At>
    <At s={WS.meet}><Meet /></At>
    <At s={WS.ketmix}>
      <Screen src="clips/s_ketmix.mp4" duration={wlen(WS.ketmix)} x={640} />
      <MixCard from={WS.ketmix[0]} to={WS.ketmix[1]} box={{ left: 940, right: 170, top: 470 }} />
      <WordLight text="Every song flows into the next." exitAt={wlen(WS.ketmix)} />
    </At>
    <At s={WS.editor}>
      <Screen src="clips/s_editor.mp4" duration={wlen(WS.editor)} rate={1.34} h={880} zoom={1.32} origin="50% 34%" />
      <WordLight text="Or draw the transition yourself." exitAt={wlen(WS.editor)} />
    </At>
    <At s={WS.free}><FreeLines at={[2, Math.round(1.33 * 29.03), Math.round(2.66 * 29.03)]} exitAt={wlen(WS.free)} /></At>
    <At s={WS.end}><EndCard /></At>

    <Finish />

    <Music />
    {[-12, -10.5, -9, -7.5, -6].map((n) => <Sfx key={n} at={wb(n) - 2} name="tap" volume={0.5} />)}
    <Sfx at={wb(-3.6) - 3} name="whoosh" volume={0.45} />
    <Sfx at={wb(0) - 122} name="riser" volume={0.45} />
    <Sfx at={wb(0) - 1} name="impact" volume={0.75} />
    <Sfx at={wb(0) + 2} name="shimmer" volume={0.35} />
    {[4, 10, 16, 22, 40].map((n) => <Sfx key={n} at={wb(n) - 8} name="whoosh" volume={0.35} />)}
    <Sfx at={wb(24) - 3} name="slider" volume={0.6} />
    <Sfx at={wb(26.5) - 3} name="slider" volume={0.45} />
    <Sfx at={wb(28.95)} name="filter-sweep" volume={0.35} />
    <Sfx at={wb(32) - 1} name="impact" volume={0.9} />
    {[46, 47.33, 48.66].map((n) => <Sfx key={n} at={wb(n) - 2} name="tap" volume={0.45} />)}
    <Sfx at={wb(50)} name="logo-sting" volume={0.7} />
    <Sfx at={wb(50) + 6} name="shimmer" volume={0.3} />
  </AbsoluteFill>
);
