"""Writes a reproducible test clip for the model checks (no copyrighted music needed): 44.1 kHz stereo 16-bit WAV,
124 BPM in A minor, kick/snare/hat, a bass line, chords and a sung-like vibrato melody (enters after 8 bars).
Usage: python -I synth_clip.py out.wav [seconds]"""
import sys
import wave

import numpy as np

out = sys.argv[1]
seconds = float(sys.argv[2]) if len(sys.argv) > 2 else 30.0
RATE, BPM = 44100, 124.0
n = int(RATE * seconds)
t = np.arange(n) / RATE
beat = 60.0 / BPM
rng = np.random.default_rng(7)
mix = np.zeros((n, 2))


def place(sound, at, gain=1.0, pan=0.0):
    i = int(at * RATE)
    if i >= n:
        return
    s = sound[: n - i] * gain
    mix[i:i + len(s), 0] += s * (1 - pan) * 0.5
    mix[i:i + len(s), 1] += s * (1 + pan) * 0.5


tk = np.arange(int(0.25 * RATE)) / RATE
kick = np.sin(2 * np.pi * (50 + 90 * np.exp(-tk * 30)) * tk) * np.exp(-tk * 9)
snare = rng.standard_normal(len(tk)) * np.exp(-tk * 22) * 0.6
hat = rng.standard_normal(int(0.05 * RATE)) * np.exp(-np.arange(int(0.05 * RATE)) / RATE * 90) * 0.25
roots = [57, 53, 48, 55]  # A, F, C, G (MIDI)


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


k = 0
while k * beat < seconds:
    at = k * beat
    bar = k // 4
    place(kick, at, 1.0 if k % 4 in (0, 2) else 0.0)
    place(snare, at, 1.0 if k % 4 in (1, 3) else 0.0, 0.1)
    place(hat, at + beat / 2, 1.0, -0.3)
    root = roots[bar % 4]
    tb = np.arange(int(beat * RATE)) / RATE
    place(np.tanh(2 * np.sin(2 * np.pi * hz(root - 24) * tb)) * np.exp(-tb * 3) * 0.5, at)
    if k % 4 == 0:
        tc = np.arange(int(4 * beat * RATE)) / RATE
        third = 3 if root in (57,) else 4
        chord = sum(np.sin(2 * np.pi * hz(root + i) * tc) for i in (0, third, 7)) * 0.08 * np.minimum(1, tc * 20)
        place(chord, at, 1.0, 0.2)
        if bar >= 8:
            note = root + 12 + (0, 3, 7, 5)[bar % 4]
            vib = np.sin(2 * np.pi * 5.5 * tc) * 0.012
            voice = np.sin(2 * np.pi * np.cumsum(hz(note) * (1 + vib)) / RATE)
            voice += 0.4 * np.sin(2 * 2 * np.pi * np.cumsum(hz(note) * (1 + vib)) / RATE)
            place(voice * 0.18 * np.minimum(1, tc * 8) * np.minimum(1, (tc[-1] - tc) * 8), at)
    k += 1

mix /= max(1e-9, np.abs(mix).max()) / 0.8
with wave.open(out, "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(RATE)
    w.writeframes((mix * 32767).astype("<i2").tobytes())
print("wrote", out, seconds, "s")
