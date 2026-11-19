// 16:9 English cut: the same ElevenLabs take C, from song 9.198 s, so that 13 beats of the calm intro carry the
// question and the logo lands on the first drop (ad 6.290 s). Breakdown at beat 28.95, second drop at beat 32.
export const FPS = 60;
export const W = 1920;
export const H = 1080;
const BEAT_S = 60 / 124;
export const DROP_S = 6.29;
export const wb = (n: number) => Math.round((DROP_S + n * BEAT_S) * FPS);
export const PER_BEAT = BEAT_S * FPS;

export const WS = {
  hook: [0, wb(0)],
  logo: [wb(0), wb(4)],
  home: [wb(4), wb(10)],
  player: [wb(10), wb(16)],
  lyrics: [wb(16), wb(22)],
  voice: [wb(22), wb(28.95)],
  meet: [wb(28.95), wb(32)],
  ketmix: [wb(32), wb(40)],
  editor: [wb(40), wb(46)],
  free: [wb(46), wb(50)],
  end: [wb(50), wb(58) + 30],
} as const;
export const WTOTAL = WS.end[1];
export const wlen = (s: readonly [number, number]) => s[1] - s[0];
