"""Measures how far the beats Ketmix reads with the beat model are from where they really are, on synthetic click
tracks whose beats sit between the 20 ms frames of the model: beats taken on whole frames (Ketmix up to 8.3) and
beats refined between frames by a parabola through the log of the activation at each peak and its two
neighbours (BeatPost.refine).
Runs the published model (ketmix-beat-small0.tflite) through ai-edge-litert; the chunks, their border and the peak
picking follow BeatPost.kt, the spectrogram tools/beat/reference.py.
Usage: python3 phase.py model.tflite [border]"""
import os
import sys
import numpy as np
from ai_edge_litert.interpreter import Interpreter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from reference import SR, log_mel  # noqa: E402

FPS, POOL = 50.0, 3


def click_track(bpm, first, seconds=30.0, seed=0):
    """A drum pattern placed to the sample: kick on every beat, snare on 2 and 4, hi-hat on the eighths, over a little noise."""
    rng = np.random.default_rng(seed)
    n = int(seconds * SR)
    x = rng.standard_normal(n) * 0.002
    period = 60.0 / bpm
    beats = np.arange(first, seconds - 0.3, period)
    t = np.arange(int(0.15 * SR)) / SR
    kick = np.sin(2 * np.pi * (50 + 90 * np.exp(-t * 40)) * t) * np.exp(-t * 18)
    snare = rng.standard_normal(len(t)) * np.exp(-t * 35) * 0.5 + np.sin(2 * np.pi * 190 * t) * np.exp(-t * 30) * 0.3
    hat = np.diff(rng.standard_normal(len(t) + 1)) * np.exp(-t * 160) * 0.15

    def put(sound, at, level):
        start = int(round(at * SR))
        end = min(n, start + len(sound))
        if 0 <= start < n:
            x[start:end] += level * sound[:end - start]

    for k, b in enumerate(beats):
        put(kick, b, 1.0 if k % 4 == 0 else 0.8)
        if k % 2 == 1:
            put(snare, b, 0.8)
        put(hat, b, 1.0)
        put(hat, b + period / 2, 0.7)
    return x.astype(np.float64), beats


def run(model, spect, window, border):
    frames = spect.shape[0]
    stride = window - 2 * border
    starts = list(range(-border, frames - border, stride)) or [-border]
    if frames > stride:
        starts[-1] = frames - (window - border)
    beat = np.full(frames, -1000.0)
    down = np.full(frames, -1000.0)
    inp = model.get_input_details()[0]
    outs = model.get_output_details()
    for s in reversed(starts):
        chunk = np.zeros((1, window, spect.shape[1]), np.float32)
        a, b = max(0, s), min(frames, s + window)
        chunk[0, a - s:b - s] = spect[a:b]
        model.set_tensor(inp["index"], chunk)
        model.invoke()
        y = [model.get_tensor(o["index"]).reshape(-1) for o in outs]
        for j in range(border, window - border):
            if 0 <= s + j < frames:
                beat[s + j], down[s + j] = y[0][j], y[1][j]
    return beat, down


def peaks(x, refine):
    found = []
    for i in range(len(x)):
        if x[i] <= 0 or x[max(0, i - POOL):i + POOL + 1].max() > x[i]:
            continue
        p = float(i)
        if refine and 0 < i < len(x) - 1 and x[i - 1] > -999 and x[i + 1] > -999:
            # The log of the activation (log-sigmoid of the logits), as BeatPost.refine.
            left, top, right = (-np.logaddexp(0.0, -float(v)) for v in x[i - 1:i + 2])
            curve = left - 2 * top + right
            if curve < 0:
                p += float(np.clip(0.5 * (left - right) / curve, -0.5, 0.5))
        found.append(p)
    merged, mean, count = [], 0.0, 0
    for p in found:
        if count and p - mean <= 1.0:
            count += 1
            mean += (p - mean) / count
        else:
            if count:
                merged.append(mean)
            mean, count = p, 1
    if count:
        merged.append(mean)
    return np.array(merged) / FPS


def errors(found, truth):
    """Signed offsets of the found beats from the nearest true beats (s), for those within 70 ms of one: the model's
    constant offset (their median) is the same with or without refinement; what refinement changes is the spread
    around it, and the phase of a grid fitted to the beats."""
    near = np.array([truth[np.argmin(np.abs(truth - f))] for f in found])
    each = found - near
    each = each[np.abs(each) < 0.07]
    bias = np.median(each)
    spread = np.abs(each - bias)
    return bias, spread


def grid_phase(found, truth):
    """How far the grid BeatMapping.fit keeps (median gap, beats counted on it, a straight line through those that agree)
    is from the true beats, at its anchor (s)."""
    period = np.median(np.diff(found))
    index = np.round((found - found[0]) / period)
    near = np.abs(found - (found[0] + index * period)) < period * .35
    slope, intercept = np.polyfit(index[near], found[near], 1)
    anchor = intercept + slope * index[len(found) // 2]
    return np.min(np.abs(truth - anchor))


def main():
    model = Interpreter(model_path=sys.argv[1], num_threads=2)
    model.allocate_tensors()
    window = int(model.get_input_details()[0]["shape"][1])
    border = int(sys.argv[2]) if len(sys.argv) > 2 else 60
    rows = []
    cases = [(97.3, .113), (104.9, .041), (112.7, .317), (123.4, .207), (128.0, .009), (133.3, .262), (141.9, .154), (151.7, .071)]
    for seed, (bpm, first) in enumerate(cases):
        x, truth = click_track(bpm, first, seed=seed)
        beat, down = run(model, log_mel(x).astype(np.float32), window, border)
        before, after = peaks(beat, False), peaks(beat, True)
        (bb, sb), (ba, sa) = errors(before, truth), errors(after, truth)
        gb, ga = grid_phase(before, truth), grid_phase(after, truth)
        rows.append((sb.mean(), sa.mean(), np.percentile(sb, 90), np.percentile(sa, 90), gb, ga))
        print(f"{bpm:6.1f} BPM  beats {len(after):3d}/{len(truth):3d}  offset {bb * 1000:+6.2f} / {ba * 1000:+6.2f} ms"
              f"  mean |error| {sb.mean() * 1000:5.2f} -> {sa.mean() * 1000:5.2f} ms  90th percentile {np.percentile(sb, 90) * 1000:5.2f} -> {np.percentile(sa, 90) * 1000:5.2f} ms"
              f"  grid {gb * 1000:5.2f} -> {ga * 1000:5.2f} ms")
    r = np.array(rows) * 1000
    print(f"window {window} frames, border {border}: mean |beat error| around the model's offset {r[:, 0].mean():.2f} -> {r[:, 1].mean():.2f} ms,"
          f" 90th percentile {r[:, 2].mean():.2f} -> {r[:, 3].mean():.2f} ms, grid phase {r[:, 4].mean():.2f} -> {r[:, 5].mean():.2f} ms")


if __name__ == "__main__":
    main()
