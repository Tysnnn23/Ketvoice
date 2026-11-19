import React from "react";
import { Sequence, useCurrentFrame } from "remotion";
import { ThreeCanvas } from "@remotion/three";
import { HEIGHT, WIDTH } from "../timing";
import { PhoneScene } from "./Phone";
import { poseAt } from "./pose";

const Posed: React.FC<{ from: number; src: string | null }> = ({ from, src }) => {
  const f = useCurrentFrame();
  return <PhoneScene src={src} pose={poseAt(from + f)} />;
};

/** One stretch of the 3D phone; [src] is the clip on its screen, played from its start. */
export const PhoneShot: React.FC<{ from: number; to: number; src: string | null }> = ({ from, to, src }) => (
  <Sequence from={from} durationInFrames={to - from} layout="absolute-fill">
    <ThreeCanvas width={WIDTH} height={HEIGHT} camera={{ fov: 30, position: [0, 0, 6.4] }} gl={{ antialias: true }}>
      <Posed from={from} src={src} />
    </ThreeCanvas>
  </Sequence>
);
