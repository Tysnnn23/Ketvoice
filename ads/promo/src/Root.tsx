import React from "react";
import { Composition } from "remotion";
import { KetvoiceAd } from "./Ad";
import { FPS, HEIGHT, TOTAL, WIDTH } from "./timing";
import { KetvoiceAdWide } from "./wide/WideAd";
import { H, W, WTOTAL } from "./wide/timing";

export const RemotionRoot: React.FC = () => (
  <>
    <Composition id="KetvoiceAd" component={KetvoiceAd} durationInFrames={TOTAL} fps={FPS} width={WIDTH} height={HEIGHT} />
    <Composition id="KetvoiceAdWide" component={KetvoiceAdWide} durationInFrames={WTOTAL} fps={FPS} width={W} height={H} />
  </>
);
