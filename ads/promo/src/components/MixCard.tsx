import React from "react";
import { interpolate, useCurrentFrame, useVideoConfig } from "remotion";
import { theme } from "../theme";

const BARS = 46;
const amp = (seed: number, i: number) => 0.35 + 0.65 * Math.abs(Math.sin(i * 1.7 + seed) * Math.cos(i * 0.43 + seed * 2.1));

/** The live mix under the phone: the outgoing title's wave fades as the incoming one rises, a playhead crosses both. */
export const MixCard: React.FC<{ from: number; to: number }> = ({ from, to }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const enter = interpolate(f, [0, 16], [0, 1], { easing: theme.ease.out, extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const exit = interpolate(f, [to - from - 18, to - from - 6], [0, 1], { easing: theme.ease.in, extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const mix = interpolate(f, [0.4 * fps, 5.2 * fps], [0, 1], { easing: theme.ease.inOut, extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const row = (label: string, seed: number, level: number, tone: string) => (
    <div style={{ display: "flex", alignItems: "center", gap: 22, height: 74 }}>
      <div style={{ width: 150, fontSize: 26, fontWeight: 600, color: theme.colors.textDim }}>{label}</div>
      <div style={{ flex: 1, display: "flex", alignItems: "center", gap: 5, height: 74 }}>
        {Array.from({ length: BARS }).map((_, i) => {
          const pulse = 0.85 + 0.15 * Math.sin(f / 4 + i);
          return <div key={i} style={{ flex: 1, borderRadius: 3, background: tone, height: `${Math.max(6, 100 * amp(seed, i) * level * pulse)}%` }} />;
        })}
      </div>
    </div>
  );
  return (
    <div style={{ position: "absolute", left: 80, right: 80, top: 1500, padding: "22px 34px", borderRadius: 40, fontFamily: theme.font,
      background: "rgba(255,255,255,0.06)", border: `1px solid ${theme.colors.hairline}`,
      opacity: enter * (1 - exit), transform: `translateY(${(1 - enter) * 50 + exit * 30}px)` }}>
      {row("Chicago", 1.3, 1 - mix * 0.92, "rgba(245,245,247,0.55)")}
      {row("Sicario", 4.1, 0.08 + mix * 0.92, "#F5F5F7")}
      <div style={{ position: "absolute", top: 18, bottom: 18, left: `calc(${206 + 0}px + ${mix} * (100% - ${206 + 34}px))`, width: 3, borderRadius: 2, background: "#F5F5F7", opacity: 0.9 }} />
    </div>
  );
};
