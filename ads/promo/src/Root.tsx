import React from "react";
import { Composition } from "remotion";
import { KetvoiceAd } from "./Ad";
import { FPS, HEIGHT, TOTAL, WIDTH } from "./timing";
import { KetvoiceAdWide, KetvoiceAdWideRef } from "./wide/WideAd";
import { H, PLAN_EL, PLAN_REF, W } from "./wide/timing";

// Language: --props='{"lang":"fr"}' (default English).
export const RemotionRoot: React.FC = () => (
  <>
    <Composition id="KetvoiceAd" component={KetvoiceAd} durationInFrames={TOTAL} fps={FPS} width={WIDTH} height={HEIGHT} defaultProps={{ lang: "en" as const }} />
    <Composition id="KetvoiceAdWide" component={KetvoiceAdWide} durationInFrames={PLAN_EL.total} fps={FPS} width={W} height={H} defaultProps={{ lang: "en" as const }} />
    <Composition id="KetvoiceAdWideRef" component={KetvoiceAdWideRef} durationInFrames={PLAN_REF.total} fps={FPS} width={W} height={H} defaultProps={{ lang: "en" as const }} />
  </>
);
