import React from "react";
import { interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { theme } from "../theme";

/** Kicker (the frame's one red element) and a title whose words rise in, 3 frames apart; leaves faster than it came. */
export const Caption: React.FC<{ kicker: string; title: string; top?: number; size?: number; exitAt: number }> = ({ kicker, title, top = 210, size = 76, exitAt }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const out = interpolate(f, [exitAt - 10, exitAt - 1], [0, 1], { easing: theme.ease.in, extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const k = spring({ frame: f - 2, fps, config: theme.spring.snappy });
  return (
    <div style={{ position: "absolute", left: 90, right: 90, top, fontFamily: theme.font, color: theme.colors.text,
      opacity: 1 - out, transform: `translateY(${-out * 36}px)` }}>
      <div style={{ fontSize: 26, fontWeight: 650, letterSpacing: "0.2em", color: theme.colors.hero, opacity: k,
        transform: `translateY(${interpolate(k, [0, 1], [18, 0])}px)` }}>{kicker}</div>
      <div style={{ display: "flex", flexWrap: "wrap", columnGap: 20, marginTop: 14, fontSize: size, fontWeight: 720, letterSpacing: "-0.03em", lineHeight: 1.04 }}>
        {title.split(" ").map((w, i) => {
          const p = spring({ frame: f - 5 - i * 3, fps, config: theme.spring.snappy });
          return <span key={i} style={{ display: "inline-block", opacity: p, transform: `translateY(${interpolate(p, [0, 1], [34, 0])}px) scale(${interpolate(p, [0, 1], [0.96, 1])})` }}>{w}</span>;
        })}
      </div>
    </div>
  );
};
