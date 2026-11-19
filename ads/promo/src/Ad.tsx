import React from "react";
import { AbsoluteFill, Audio, Sequence, staticFile } from "remotion";
import "./fonts";
import { Background, Finish } from "./components/Layers";
import { Caption } from "./components/Caption";
import { MixCard } from "./components/MixCard";
import { VoiceSlider } from "./components/VoiceSlider";
import { Credits } from "./scenes/Credits";
import { Cta } from "./scenes/Cta";
import { Editor } from "./scenes/Editor";
import { Hook } from "./scenes/Hook";
import { KetmixTitle } from "./scenes/KetmixTitle";
import { MontageWords } from "./scenes/MontageWords";
import { PhoneShot } from "./three/PhoneShot";
import { beat, len, S } from "./timing";

const Sfx: React.FC<{ at: number; name: string; volume: number; trim?: number }> = ({ at, name, volume, trim = 0 }) => (
  <Sequence from={Math.max(0, at)} layout="none">
    <Audio src={staticFile(`sfx/${name}.mp3`)} volume={volume} trimBefore={trim + Math.max(0, -at)} />
  </Sequence>
);

const At: React.FC<{ s: readonly [number, number]; children: React.ReactNode }> = ({ s, children }) => (
  <Sequence from={s[0]} durationInFrames={len(s)} layout="absolute-fill">{children}</Sequence>
);

export const KetvoiceAd: React.FC = () => (
  <AbsoluteFill style={{ background: "#060608" }}>
    <Background />

    {/* The 3D phone, one stretch per screen content. */}
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

    {/* Type and motion-designed pieces over it. */}
    <At s={S.hook}><Hook /></At>
    <At s={S.home}><Caption kicker="POUR TOI" title="Un accueil qui te connaît" exitAt={len(S.home)} /></At>
    <At s={S.player}><Caption kicker="LECTEUR" title="Ton son, en grand" exitAt={len(S.player)} /></At>
    <At s={S.lyrics}>
      <Caption kicker="NIVEAU DE VOIX" title="Baisse la voix. Chante." exitAt={len(S.lyrics)} />
      <VoiceSlider downAt={beat(18) - S.lyrics[0]} upAt={beat(21) - S.lyrics[0]} />
    </At>
    <At s={S.stats}><Caption kicker="REPLAY" title="Ton mois en musique" exitAt={len(S.stats)} /></At>
    <At s={S.ketmixTitle}><KetmixTitle from={S.ketmixTitle[0]} /></At>
    <At s={S.ketmix}>
      <Caption kicker="KETMIX" title="Chaque titre se mixe au suivant" exitAt={len(S.ketmix) - 14} size={70} />
      <MixCard from={S.ketmix[0]} to={S.ketmix[1]} />
    </At>
    <At s={S.editor}><Editor duration={len(S.editor)} /></At>
    <At s={S.montage}><MontageWords /></At>
    <At s={S.cta}><Cta /></At>
    <At s={S.credits}><Credits /></At>

    <Finish />

    {/* Sound: the music, then effects that start 2–3 frames before what they mark. */}
    <Audio src={staticFile("music/track.wav")} volume={0.78} />
    <Sfx at={beat(0) - 122} name="riser" volume={0.45} />
    <Sfx at={beat(0) - 1} name="impact" volume={0.55} />
    <Sfx at={beat(0) + 2} name="shimmer" volume={0.25} />
    <Sfx at={beat(8) - 9} name="whoosh" volume={0.4} />
    <Sfx at={beat(8) + 28} name="tap" volume={0.55} />
    <Sfx at={beat(16) - 9} name="whoosh" volume={0.4} />
    <Sfx at={beat(18) - 3} name="slider" volume={0.6} />
    <Sfx at={beat(21) - 3} name="slider" volume={0.45} />
    <Sfx at={beat(24) - 9} name="whoosh" volume={0.4} />
    <Sfx at={beat(28.9)} name="filter-sweep" volume={0.35} />
    <Sfx at={beat(32) - 1} name="impact" volume={0.65} />
    <Sfx at={beat(44) - 14} name="whoosh" volume={0.5} />
    <Sfx at={beat(44) + 150} name="tap" volume={0.55} />
    {[52, 53, 54, 55].map((n) => <Sfx key={n} at={beat(n) - 3} name="whoosh" volume={0.22} />)}
    <Sfx at={beat(56)} name="logo-sting" volume={0.7} />
    <Sfx at={beat(56) + 8} name="shimmer" volume={0.3} />
  </AbsoluteFill>
);
