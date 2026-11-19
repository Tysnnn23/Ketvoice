import React from "react";
import { interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { theme } from "../theme";
import { beat } from "../timing";
import { useT } from "../i18n";

/** 0–1.9 s: moves within the first frames; the second line lands on the last beat before the drop. */
export const Hook: React.FC = () => {
  const f = useCurrentFrame();
  const t = useT();
  const { fps } = useVideoConfig();
  const line = (text: string, at: number, dim = false) => {
    const p = spring({ frame: f - at, fps, config: theme.spring.snappy });
    return <div style={{ opacity: p, transform: `translateY(${interpolate(p, [0, 1], [46, 0])}px)`, color: dim ? theme.colors.textDim : theme.colors.text }}>{text}</div>;
  };
  const out = interpolate(f, [beat(0) - 8, beat(0)], [0, 1], { easing: theme.ease.in, extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <div style={{ position: "absolute", left: 90, right: 90, top: 180, fontFamily: theme.font, fontSize: 104, fontWeight: 760,
      letterSpacing: "-0.035em", lineHeight: 1.02, opacity: 1 - out, transform: `scale(${1 + out * 0.06})` }}>
      {line(t.hook[0], 3)}
      {line(t.hook[1], beat(-2), true)}
    </div>
  );
};
