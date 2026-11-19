import React from "react";
import { Composition } from "remotion";
import { KetvoiceAd } from "./Ad";
import { FPS, HEIGHT, TOTAL, WIDTH } from "./timing";

export const RemotionRoot: React.FC = () => (
  <Composition id="KetvoiceAd" component={KetvoiceAd} durationInFrames={TOTAL} fps={FPS} width={WIDTH} height={HEIGHT} />
);
