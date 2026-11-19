import React from "react";
import { interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { theme } from "../theme";
import { BEAT_S } from "../timing";
import { useT } from "../i18n";


/** One word per beat over the quick cuts. */
export const MontageWords: React.FC = () => {
  const f = useCurrentFrame();
  const WORDS = useT().words;
  const { fps } = useVideoConfig();
  const per = BEAT_S * fps;
  const i = Math.min(WORDS.length - 1, Math.floor(f / per));
  const local = f - i * per;
  const p = spring({ frame: local, fps, config: theme.spring.bouncy });
  return (
    <div style={{ position: "absolute", left: 0, right: 0, top: 230, textAlign: "center", fontFamily: theme.font, color: theme.colors.text,
      fontSize: 120, fontWeight: 800, letterSpacing: "-0.04em", opacity: interpolate(p, [0, 0.4], [0, 1], { extrapolateRight: "clamp" }),
      transform: `scale(${interpolate(p, [0, 1], [0.82, 1])})` }}>{WORDS[i]}</div>
  );
};
