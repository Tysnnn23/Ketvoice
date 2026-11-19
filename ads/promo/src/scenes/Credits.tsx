import React from "react";
import { AbsoluteFill, interpolate, useCurrentFrame } from "remotion";
import { theme } from "../theme";

/** The last screen, on its own: the 3D model's author (CC BY 4.0 asks for it) and the music. */
export const Credits: React.FC = () => {
  const f = useCurrentFrame();
  const a = interpolate(f, [0, 12], [0, 1], { easing: theme.ease.out, extrapolateRight: "clamp" });
  return (
    <AbsoluteFill style={{ background: "#000", alignItems: "center", justifyContent: "center", fontFamily: theme.font, textAlign: "center" }}>
      <div style={{ opacity: a, transform: `translateY(${(1 - a) * 14}px)`, color: theme.colors.textDim, fontSize: 30, lineHeight: 1.5 }}>
        <div style={{ color: theme.colors.text, fontSize: 34, fontWeight: 650, marginBottom: 10 }}>Phone model : SabasGT</div>
        <div>« Off-brand Android Smartphone » · sketchfab.com/sabasgme</div>
        <div>CC BY 4.0 · couleurs modifiées</div>
        <div style={{ marginTop: 34 }}>Musique : ElevenLabs Music</div>
      </div>
    </AbsoluteFill>
  );
};
