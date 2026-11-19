import React from "react";
import { AbsoluteFill, Audio, Sequence, staticFile } from "remotion";
import "./fonts";
import { Background, Finish } from "./components/Layers";
import { MixCard } from "./components/MixCard";
import { CutBlur, Dim, MaskTitle, MixPill, PopCard } from "./components/Studio";
import { VoiceSlider } from "./components/VoiceSlider";
import { Credits } from "./scenes/Credits";
import { Cta } from "./scenes/Cta";
import { Editor } from "./scenes/Editor";
import { Hook } from "./scenes/Hook";
import { KetmixTitle } from "./scenes/KetmixTitle";
import { MontageWords } from "./scenes/MontageWords";
import { PhoneShot } from "./three/PhoneShot";
import { beat, len, PER, S } from "./timing";

const Sfx: React.FC<{ at: number; name: string; volume: number }> = ({ at, name, volume }) => (
  <Sequence from={Math.max(0, at)} layout="none">
    <Audio src={staticFile(`sfx/${name}.mp3`)} volume={volume} trimBefore={Math.max(0, -at)} />
  </Sequence>
);
const At: React.FC<{ s: readonly [number, number]; children: React.ReactNode }> = ({ s, children }) => (
  <Sequence from={s[0]} durationInFrames={len(s)} layout="absolute-fill">{children}</Sequence>
);
const b = (n: number) => Math.round(n * PER); // beats inside a scene

const CUTS = [beat(8), beat(16), beat(24), beat(52), beat(53), beat(54), beat(55)];

export const KetvoiceAd: React.FC = () => (
  <AbsoluteFill style={{ background: "#060608" }}>
    <Background />

    <CutBlur cuts={CUTS}>
      <PhoneShot from={S.hook[0]} to={S.hook[1]} src={null} />
      <PhoneShot from={S.home[0]} to={S.home[1]} src="clips/s_home.mp4" />
      <PhoneShot from={S.player[0]} to={S.player[1]} src="clips/s_player.mp4" />
      <PhoneShot from={S.lyrics[0]} to={S.lyrics[1]} src="clips/s_lyrics.mp4" />
      <PhoneShot from={S.stats[0]} to={S.stats[1]} src="clips/s_stats.mp4" />
      <PhoneShot from={S.ketmixTitle[0]} to={S.ketmixTitle[1]} src="clips/s_stats.mp4" />
      <PhoneShot from={S.ketmix[0]} to={S.ketmix[1]} src="clips/s_ketmix.mp4" />
      {["m3", "m4", "m2", "m1"].map((m, i) => (
        <PhoneShot key={m} from={beat(52 + i)} to={beat(53 + i)} src={`clips/${m}.mp4`} />
      ))}
      <PhoneShot from={S.cta[0]} to={beat(61)} src="clips/s_home.mp4" />
    </CutBlur>

    <At s={S.hook}><Hook /></At>
    <At s={S.home}>
      <Dim at={b(2.5)} until={b(6.6)} amount={0.35} />
      <PopCard src="img/pop-home-card.png" ratio={796 / 452} from={{ x: 540, y: 880, w: 300 }} to={{ x: 590, y: 1330, w: 780 }} at={b(2.5)} until={b(6.6)} />
      <MaskTitle kicker="FOR YOU" lines={["A home that", "knows you"]} exitAt={len(S.home)} />
    </At>
    <At s={S.player}>
      <Dim at={b(2.2)} until={b(6.4)} amount={0.3} />
      <PopCard src="img/pop-play.png" ratio={762 / 130} from={{ x: 540, y: 1150, w: 380 }} to={{ x: 540, y: 1500, w: 880 }} at={b(2.2)} until={b(6.4)} radius={64} tilt={3} />
      <MaskTitle kicker="PLAYER" lines={["Your sound,", "full screen"]} exitAt={len(S.player)} />
    </At>
    <At s={S.lyrics}>
      <MaskTitle kicker="VOICE LEVEL" lines={["Turn the vocals", "down. Sing."]} exitAt={len(S.lyrics)} />
      <VoiceSlider downAt={beat(18) - S.lyrics[0]} upAt={beat(21) - S.lyrics[0]} label="vocals" />
    </At>
    <At s={S.stats}><MaskTitle kicker="REPLAY" lines={["Your month", "in music"]} exitAt={len(S.stats)} /></At>
    <At s={S.ketmixTitle}><KetmixTitle from={S.ketmixTitle[0]} /></At>
    <At s={S.ketmix}>
      <MaskTitle kicker="KETMIX" lines={["Every song mixes", "into the next"]} exitAt={len(S.ketmix) - 14} size={76} />
      <MixPill at={b(0.6)} until={b(10.6)} x={540} y={520} scale={1.05} />
      <MixCard from={S.ketmix[0]} to={S.ketmix[1]} />
    </At>
    <At s={S.editor}>
      <Editor duration={len(S.editor)} />
      <Dim at={150} until={214} amount={0.5} />
      <PopCard src="img/pop-editor-box.png" ratio={796 / 530} from={{ x: 540, y: 1010, w: 620 }} to={{ x: 540, y: 1060, w: 1000 }} at={150} until={214} radius={28} tilt={-2} />
    </At>
    <At s={S.montage}><MontageWords /></At>
    <At s={S.cta}><Cta /></At>
    <At s={S.credits}><Credits /></At>

    <Finish />

    <Audio src={staticFile("music/track.wav")} volume={0.78} />
    <Sfx at={beat(0) - 122} name="riser" volume={0.45} />
    <Sfx at={beat(0) - 1} name="impact" volume={0.55} />
    <Sfx at={beat(0) + 2} name="shimmer" volume={0.25} />
    <Sfx at={S.home[0] + b(2.5) - 3} name="swish" volume={0.5} />
    <Sfx at={S.home[0] + b(2.5) + 9} name="pop" volume={0.45} />
    <Sfx at={beat(8) - 9} name="whoosh" volume={0.4} />
    <Sfx at={S.player[0] + b(2.2) - 3} name="swish" volume={0.5} />
    <Sfx at={S.player[0] + b(2.2) + 9} name="pop" volume={0.45} />
    <Sfx at={beat(16) - 9} name="whoosh" volume={0.4} />
    <Sfx at={beat(18) - 3} name="slider" volume={0.6} />
    <Sfx at={beat(21) - 3} name="slider" volume={0.45} />
    <Sfx at={beat(24) - 9} name="whoosh" volume={0.4} />
    <Sfx at={beat(28.9)} name="filter-sweep" volume={0.35} />
    <Sfx at={beat(32) - 1} name="impact" volume={0.6} />
    <Sfx at={beat(32)} name="subdrop" volume={0.5} />
    <Sfx at={S.ketmix[0] + b(0.6) - 2} name="pop" volume={0.5} />
    <Sfx at={beat(44) - 14} name="whoosh" volume={0.5} />
    <Sfx at={S.editor[0] + 147} name="swish" volume={0.5} />
    <Sfx at={S.editor[0] + 159} name="pop" volume={0.4} />
    {[52, 53, 54, 55].map((n) => <Sfx key={n} at={beat(n) - 3} name="whoosh" volume={0.22} />)}
    <Sfx at={beat(56)} name="logo-sting" volume={0.7} />
    <Sfx at={beat(56) + 8} name="shimmer" volume={0.3} />
  </AbsoluteFill>
);
