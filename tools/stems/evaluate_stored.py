"""What is left of a UVR separation once it is stored as Ketvoice's 4-bit mask on its 4096/1024 grid."""
import sys, glob, os, numpy as np, soundfile as sf, sherpa_onnx
ROOT, MODEL = sys.argv[1], sys.argv[2]
N, HOP = 4096, 1024
W = (0.5 - 0.5*np.cos(2*np.pi*np.arange(N)/N))
def stft(x):
    T = 1 + (len(x)-N)//HOP; idx = np.arange(N)[None, :] + HOP*np.arange(T)[:, None]
    return np.fft.rfft(x[idx]*W, axis=1)
def istft(S, n):
    out = np.zeros(n); fr = np.fft.irfft(S, n=N, axis=1)*W
    for t in range(S.shape[0]): out[t*HOP:t*HOP+N] += fr[t]
    return out/1.5
def q4(m): q = np.clip(np.floor(np.sqrt(np.clip(m, 0, 1))*15 + .5), 0, 15); return (q/15)**2
def sdr(r, e): return 10*np.log10(np.sum(r**2)/(np.sum((r-e)**2)+1e-12))
sp = sherpa_onnx.OfflineSourceSeparation(sherpa_onnx.OfflineSourceSeparationConfig(model=sherpa_onnx.OfflineSourceSeparationModelConfig(
    uvr=sherpa_onnx.OfflineSourceSeparationUvrModelConfig(model=MODEL), num_threads=4)))
rows = []
for f in sorted(glob.glob(ROOT+'/*-mix.wav')):
    name = os.path.basename(f)[:-8]
    mix, sr = sf.read(f, dtype='float64'); voc, _ = sf.read(ROOT+f'/{name}-voc.wav', dtype='float64'); acc, _ = sf.read(ROOT+f'/{name}-acc.wav', dtype='float64')
    o = sp.process(sample_rate=sr, samples=np.ascontiguousarray(mix.T.astype(np.float32)))
    s0 = np.array(o.stems[0].data).T.astype(np.float64); s1 = np.array(o.stems[1].data).T.astype(np.float64)
    n = min(len(s0), len(mix)); lo, hi = 8192*2, n - 8192*2
    v = s1 if sdr(voc[lo:hi], s1[lo:hi]) > sdr(voc[lo:hi], s0[lo:hi]) else s0
    v = v[:n]; mix = mix[:n]; voc = voc[:n]; acc = acc[:n]
    row = [sdr(acc[lo:hi], (mix - v)[lo:hi]), sdr(voc[lo:hi], v[lo:hi])]
    for bins, quant, mono, p in [(1024, True, True, 2), (2049, True, True, 2), (2049, False, False, 2), (2049, True, True, 1), (2049, True, False, 1)]:
        outs_a = []; outs_v = []
        X = [stft(mix[:, c]) for c in range(2)]; V = [stft(v[:, c]) for c in range(2)]
        masks = []
        for c in range(2):
            A = X[c] - V[c]
            masks.append(np.abs(A)**p/(np.abs(A)**p + np.abs(V[c])**p + 1e-12))
        m = np.stack(masks)
        if mono: m = np.repeat(m.mean(0, keepdims=True), 2, 0)
        if quant: m = q4(m)
        full = np.ones_like(m); full[:, :, :bins] = m[:, :, :bins]
        a_est = np.stack([istft(X[c]*full[c], n) for c in range(2)], 1); v_est = np.stack([istft(X[c]*(1-full[c]), n) for c in range(2)], 1)
        row += [sdr(acc[lo:hi], a_est[lo:hi]), sdr(voc[lo:hi], v_est[lo:hi])]
    rows.append(row); print(name, ' '.join('%.2f' % x for x in row), flush=True)
r = np.median(np.array(rows), 0)
labels = ['UVR direct', 'mask 1024 bins 4-bit mono', 'mask full band 4-bit mono', 'mask full band float stereo', 'full band 4-bit mono, amplitude ratio', 'full band 4-bit stereo, amplitude ratio']
for i, l in enumerate(labels): print(f"{l:42s} instrumental {r[2*i]:6.2f}  voice {r[2*i+1]:6.2f}")
