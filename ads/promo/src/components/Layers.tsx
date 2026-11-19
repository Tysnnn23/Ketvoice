import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { theme } from "../theme";

/** A plain dark ground with one soft light behind the phone; it drifts slowly. No gradients elsewhere. */
export const Background: React.FC<{ lift?: number }> = ({ lift = 1 }) => {
  const f = useCurrentFrame();
  const dx = Math.sin(f / 140) * 40, dy = Math.cos(f / 170) * 30;
  return (
    <AbsoluteFill style={{ background: theme.colors.bg }}>
      <div style={{ position: "absolute", width: 1500, height: 1500, left: -210 + dx, top: 210 + dy, borderRadius: "50%",
        background: `radial-gradient(circle, rgba(250,45,72,${0.13 * lift}) 0%, rgba(250,45,72,0) 60%)` }} />
      <div style={{ position: "absolute", width: 1100, height: 1100, left: -10 - dx, top: -380 - dy, borderRadius: "50%",
        background: `radial-gradient(circle, rgba(255,255,255,${0.05 * lift}) 0%, rgba(255,255,255,0) 62%)` }} />
    </AbsoluteFill>
  );
};

const noise = `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='240' height='240'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='2' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='240' height='240' filter='url(%23n)' opacity='0.55'/%3E%3C/svg%3E")`;

/** Fine still grain and a light vignette, on top of everything (no flicker). */
export const Finish: React.FC = () => (
  <AbsoluteFill style={{ pointerEvents: "none" }}>
    <AbsoluteFill style={{ backgroundImage: noise, backgroundSize: "240px", opacity: 0.045, mixBlendMode: "overlay" }} />
    <AbsoluteFill style={{ background: "radial-gradient(ellipse at 50% 46%, rgba(0,0,0,0) 58%, rgba(0,0,0,0.38) 100%)" }} />
  </AbsoluteFill>
);
