import React from "react";
import { interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { theme } from "../theme";

const Mic: React.FC<{ color: string }> = ({ color }) => (
  <svg width="54" height="54" viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="1.9" strokeLinecap="round">
    <rect x="9" y="3" width="6" height="11" rx="3" fill={color} stroke="none" />
    <path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21" />
  </svg>
);

/**
 * Ketvoice's voice slider (the lyrics screen's vertical pill), drawn large: it swells under the finger like
 * Ket 27's sliders, the level glides down on the beat, and the percentage follows in tabular figures.
 */
export const VoiceSlider: React.FC<{ downAt: number; upAt: number; right?: number; top?: number; label?: string }> = ({ downAt, upAt, right = 120, top = 760, label = "voix" }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const enter = spring({ frame: f - 4, fps, config: theme.spring.smooth });
  const press = interpolate(f, [downAt - 8, downAt - 2, upAt + 26, upAt + 34], [0, 1, 1, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: theme.ease.inOut });
  const down = spring({ frame: f - downAt, fps, config: { damping: 18, stiffness: 70, mass: 1 } });
  const up = spring({ frame: f - upAt, fps, config: { damping: 16, stiffness: 90, mass: 0.9 } });
  const level = 1 - 0.82 * down + 0.37 * up; // 100 % -> 18 % -> 55 %
  const H = 470, W = 150;
  const breathe = Math.sin(f / 26) * 4;
  return (
    <div style={{ position: "absolute", right, top: top + breathe, width: W, fontFamily: theme.font,
      opacity: enter, transform: `translateX(${interpolate(enter, [0, 1], [80, 0])}px) scale(${1 + 0.06 * press}, ${1 + 0.035 * press})` }}>
      <div style={{ position: "relative", width: W, height: H, borderRadius: W / 2, overflow: "hidden",
        background: "rgba(255,255,255,0.14)", border: `1px solid ${theme.colors.hairline}` }}>
        <div style={{ position: "absolute", left: 0, right: 0, bottom: 0, height: H * level, background: "#F5F5F7" }} />
        <div style={{ position: "absolute", left: 0, right: 0, bottom: 34, display: "flex", justifyContent: "center" }}>
          <Mic color={level > 0.16 ? "#16161a" : "#F5F5F7"} />
        </div>
      </div>
      <div style={{ marginTop: 26, textAlign: "center", color: theme.colors.text, fontSize: 40, fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>
        {Math.round(level * 100)} %
      </div>
      <div style={{ textAlign: "center", color: theme.colors.textDim, fontSize: 24, fontWeight: 500, marginTop: 4 }}>{label}</div>
    </div>
  );
};
