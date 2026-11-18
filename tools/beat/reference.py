"""numpy re-implementation of Beat This! (CPJKU, MIT) LogMelSpect, used to generate
app/src/test/resources/beat-logmel-reference.txt for the Kotlin front end (BeatSpectrogram).
Usage: python3 tools/beat/reference.py [out-file]"""
import sys
import numpy as np

SR, N_FFT, HOP, N_MELS, F_MIN, F_MAX = 22050, 1024, 441, 128, 30.0, 11000.0


def hz_to_mel(f):  # slaney
    f = np.asarray(f, dtype=np.float64)
    lin = f / (200.0 / 3)
    logstep = np.log(6.4) / 27.0
    return np.where(f >= 1000.0, 15.0 + np.log(np.maximum(f, 1e-9) / 1000.0) / logstep, lin)


def mel_to_hz(m):
    m = np.asarray(m, dtype=np.float64)
    logstep = np.log(6.4) / 27.0
    return np.where(m >= 15.0, 1000.0 * np.exp(logstep * (m - 15.0)), m * (200.0 / 3))


def fbanks():
    n_freqs = N_FFT // 2 + 1
    all_freqs = np.linspace(0, SR // 2, n_freqs)
    m_pts = np.linspace(hz_to_mel(F_MIN), hz_to_mel(F_MAX), N_MELS + 2)
    f_pts = mel_to_hz(m_pts)
    f_diff = f_pts[1:] - f_pts[:-1]
    slopes = f_pts[None, :] - all_freqs[:, None]
    down = -slopes[:, :-2] / f_diff[:-1]
    up = slopes[:, 2:] / f_diff[1:]
    fb = np.maximum(0.0, np.minimum(down, up))
    return fb * (2.0 / (f_pts[2:N_MELS + 2] - f_pts[:N_MELS]))[None, :]


def log_mel(x):
    x = np.asarray(x, dtype=np.float64)
    pad = N_FFT // 2
    p = np.pad(x, pad, mode="reflect")
    win = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(N_FFT) / N_FFT)  # periodic hann
    frames = 1 + len(x) // HOP
    out = np.zeros((frames, N_MELS))
    fb = fbanks()
    norm = np.sqrt((win ** 2).sum())  # normalized="frame_length"
    for t in range(frames):
        seg = p[t * HOP:t * HOP + N_FFT] * win
        mag = np.abs(np.fft.rfft(seg)) / norm  # power=1
        out[t] = np.log1p(1000.0 * (mag @ fb))
    return out


def signal(n=11025):
    t = np.arange(n) / SR
    x = 0.3 * np.sin(2 * np.pi * 440 * t) + 0.2 * np.sin(2 * np.pi * 3000 * t) + 0.1 * np.sin(2 * np.pi * 7000 * t)
    x[5000] += 0.5
    return x


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "app/src/test/resources/beat-logmel-reference.txt"
    m = log_mel(signal())
    with open(out, "w") as f:
        f.write(f"{m.shape[0]} {m.shape[1]}\n")
        for row in m:
            f.write(" ".join(f"{v:.6f}" for v in row) + "\n")
    print(m.shape, m.min(), m.max())
