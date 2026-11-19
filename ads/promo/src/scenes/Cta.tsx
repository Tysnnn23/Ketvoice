import React from "react";
import { Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import { theme } from "../theme";

/** The ask: the glass K, the name, one line, the address. The red pill is the frame's only red. */
export const Cta: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const logo = spring({ frame: f - 2, fps, config: theme.spring.bouncy });
  const line = spring({ frame: f - 22, fps, config: theme.spring.smooth });
  const pill = spring({ frame: f - 32, fps, config: theme.spring.snappy });
  const url = spring({ frame: f - 40, fps, config: theme.spring.smooth });
  const sweep = interpolate(f, [10, 46], [-120, 120], { easing: theme.ease.inOut, extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const float = Math.sin(f / 30) * 6;
  return (
    <div style={{ position: "absolute", left: 0, right: 0, top: 330, display: "flex", flexDirection: "column", alignItems: "center", fontFamily: theme.font, color: theme.colors.text }}>
      <div style={{ position: "relative", width: 300, height: 300, borderRadius: 70, overflow: "hidden", background: "#000",
        transform: `translateY(${float}px) scale(${interpolate(logo, [0, 1], [0.55, 1])}) rotate(${interpolate(logo, [0, 1], [-10, 0])}deg)`,
        opacity: interpolate(logo, [0, 0.3], [0, 1], { extrapolateRight: "clamp" }), boxShadow: "0 40px 90px -30px rgba(0,0,0,0.8)",
        border: `1px solid ${theme.colors.hairline}` }}>
        <Img src={staticFile("img/ic_launcher27_glass_fg.png")} style={{ width: "100%", height: "100%" }} />
        <div style={{ position: "absolute", top: -40, bottom: -40, width: 70, left: `calc(50% + ${sweep}%)`,
          background: "linear-gradient(90deg, transparent, rgba(255,255,255,0.22), transparent)", transform: "rotate(18deg)" }} />
      </div>
      <div style={{ display: "flex", marginTop: 54, fontSize: 132, fontWeight: 800, letterSpacing: "-0.045em" }}>
        {"Ketvoice".split("").map((ch, i) => {
          const p = spring({ frame: f - 8 - i * 2.5, fps, config: theme.spring.snappy });
          return <span key={i} style={{ display: "inline-block", opacity: p, transform: `translateY(${interpolate(p, [0, 1], [50, 0])}px)` }}>{ch}</span>;
        })}
      </div>
      <div style={{ marginTop: 10, fontSize: 42, fontWeight: 520, color: theme.colors.textDim, opacity: line, transform: `translateY(${(1 - line) * 20}px)` }}>
        Ta musique, mixée comme un DJ.
      </div>
      <div style={{ marginTop: 54, padding: "22px 46px", borderRadius: 999, background: theme.colors.hero, fontSize: 38, fontWeight: 700,
        opacity: pill, transform: `scale(${interpolate(pill, [0, 1], [0.8, 1])})` }}>Gratuit sur Android</div>
      <div style={{ marginTop: 30, fontSize: 34, fontWeight: 560, color: theme.colors.text, opacity: url * 0.9, letterSpacing: "0.01em",
        transform: `translateY(${(1 - url) * 16}px)` }}>ketvoice-web.vercel.app</div>
    </div>
  );
};
