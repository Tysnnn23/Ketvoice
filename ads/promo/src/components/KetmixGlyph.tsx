import React from "react";
import { interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { theme } from "../theme";

/** Ketmix's own sign: two rings that slide into each other, one of them red. */
export const KetmixGlyph: React.FC<{ size: number; at: number }> = ({ size, at }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const meet = spring({ frame: f - at, fps, config: theme.spring.smooth });
  const draw = interpolate(f - at, [0, 26], [1, 0], { easing: theme.ease.out, extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const r = size * 0.3, c = 2 * Math.PI * r;
  const off = interpolate(meet, [0, 1], [size * 0.42, size * 0.15]);
  const spin = interpolate(meet, [0, 1], [-50, 0]);
  return (
    <svg width={size} height={size * 0.7} viewBox={`0 0 ${size} ${size * 0.7}`} style={{ transform: `rotate(${spin}deg)` }}>
      <circle cx={size / 2 - off} cy={size * 0.35} r={r} fill="none" stroke="#F5F5F7" strokeWidth={size * 0.05}
        strokeDasharray={c} strokeDashoffset={c * draw} strokeLinecap="round" />
      <circle cx={size / 2 + off} cy={size * 0.35} r={r} fill="none" stroke={theme.colors.hero} strokeWidth={size * 0.05}
        strokeDasharray={c} strokeDashoffset={c * draw} strokeLinecap="round" transform={`rotate(180 ${size / 2 + off} ${size * 0.35})`} />
    </svg>
  );
};
