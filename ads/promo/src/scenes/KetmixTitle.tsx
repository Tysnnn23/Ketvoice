import React from "react";
import { interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { KetmixGlyph } from "../components/KetmixGlyph";
import { theme } from "../theme";
import { beat } from "../timing";

/** The breakdown: the phone steps back, Ketmix's sign draws itself, its name lands letter by letter. */
export const KetmixTitle: React.FC<{ from: number }> = ({ from }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const at = beat(28.95) - from;
  const end = beat(32) - from;
  const out = interpolate(f, [end - 7, end], [0, 1], { easing: theme.ease.in, extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const sub = spring({ frame: f - at - 30, fps, config: theme.spring.smooth });
  return (
    <div style={{ position: "absolute", left: 0, right: 0, top: 560, display: "flex", flexDirection: "column", alignItems: "center",
      fontFamily: theme.font, color: theme.colors.text, opacity: 1 - out, transform: `scale(${1 + out * 0.5})` }}>
      <KetmixGlyph size={330} at={at} />
      <div style={{ display: "flex", fontSize: 150, fontWeight: 800, letterSpacing: "-0.045em", marginTop: 26 }}>
        {"Ketmix".split("").map((ch, i) => {
          const p = spring({ frame: f - at - 10 - i * 3, fps, config: theme.spring.snappy });
          return <span key={i} style={{ display: "inline-block", opacity: p, transform: `translateY(${interpolate(p, [0, 1], [60, 0])}px)` }}>{ch}</span>;
        })}
      </div>
      <div style={{ marginTop: 12, fontSize: 40, fontWeight: 520, color: theme.colors.textDim, opacity: sub,
        transform: `translateY(${interpolate(sub, [0, 1], [22, 0])}px)` }}>The DJ built into Ketvoice</div>
    </div>
  );
};
