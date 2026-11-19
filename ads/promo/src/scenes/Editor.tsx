import React from "react";
import { interpolate, OffthreadVideo, spring, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import { MaskTitle } from "../components/Studio";
import { theme } from "../theme";

/** We land inside the phone's screen: the editor fills the frame, then settles into a card. */
export const Editor: React.FC<{ duration: number }> = ({ duration }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const settle = spring({ frame: f - 2, fps, config: { damping: 22, stiffness: 70, mass: 1.1 } });
  const scale = interpolate(settle, [0, 1], [1.32, 0.74]);
  const radius = interpolate(settle, [0, 1], [0, 64]);
  const tilt = interpolate(settle, [0, 1], [0, 6]) + Math.sin(f / 40) * 1.2;
  const out = interpolate(f, [duration - 10, duration - 1], [0, 1], { easing: theme.ease.in, extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <>
      <div style={{ position: "absolute", left: 0, top: 0, width: 1080, height: 1920, perspective: 2200 }}>
        <div style={{ position: "absolute", left: (1080 - 870 * 1.2414) / 2, top: 0, width: 870 * 1.2414, height: 1920,
          transform: `translateY(${interpolate(settle, [0, 1], [0, 150])}px) scale(${scale * (1 - out * 0.08)}) rotateX(${tilt}deg)`,
          borderRadius: radius / scale, overflow: "hidden", opacity: 1 - out,
          boxShadow: `0 60px 120px -30px rgba(0,0,0,${0.7 * settle})`, border: `1px solid rgba(255,255,255,${0.12 * settle})` }}>
          <OffthreadVideo src={staticFile("clips/s_editor.mp4")} playbackRate={1.34} muted style={{ width: "100%", height: "100%", objectFit: "cover" }} />
        </div>
      </div>
      <MaskTitle kicker="KETMIX EDITOR" lines={["Or draw the", "transition yourself"]} exitAt={duration} top={130} size={74} />
    </>
  );
};
