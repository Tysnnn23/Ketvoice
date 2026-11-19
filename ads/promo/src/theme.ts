import { Easing } from "remotion";

// Ketvoice ad: one dark base, ONE hero colour (Ketvoice red #FA2D48), neutrals. No neon, no glow stacks.
export const theme = {
  colors: {
    bg: "#060608",
    bgAlt: "#101014",
    hero: "#FA2D48",
    text: "#F5F5F7",
    textDim: "#9C9CA6",
    hairline: "rgba(255,255,255,0.10)",
  },
  font: "InterKet",
  ease: {
    out: Easing.bezier(0.16, 1, 0.3, 1),
    inOut: Easing.bezier(0.83, 0, 0.17, 1),
    in: Easing.bezier(0.7, 0, 0.84, 0),
  },
  spring: {
    snappy: { damping: 14, stiffness: 160, mass: 0.6 },
    smooth: { damping: 20, stiffness: 90, mass: 1 },
    bouncy: { damping: 11, stiffness: 170, mass: 0.7 },
  },
} as const;
