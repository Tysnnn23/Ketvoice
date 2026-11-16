"""SDR of sherpa-onnx's UVR models on the MUSDB excerpts (reference implementation), with timing."""
import sys, glob, os, time, numpy as np, soundfile as sf, sherpa_onnx
ROOT, MODEL = sys.argv[1], sys.argv[2]
inst = 'Inst' in os.path.basename(MODEL) or 'Main' in os.path.basename(MODEL) or '9482' in os.path.basename(MODEL)
cfg = sherpa_onnx.OfflineSourceSeparationConfig(model=sherpa_onnx.OfflineSourceSeparationModelConfig(
    uvr=sherpa_onnx.OfflineSourceSeparationUvrModelConfig(model=MODEL), num_threads=4))
sp = sherpa_onnx.OfflineSourceSeparation(cfg)
def sdr(r, e): return 10*np.log10(np.sum(r**2)/(np.sum((r-e)**2)+1e-12))
res = []; secs = 0; audio = 0
for f in sorted(glob.glob(ROOT+'/*-mix.wav')):
    name = os.path.basename(f)[:-8]
    mix, sr = sf.read(f, dtype='float32'); voc, _ = sf.read(ROOT+f'/{name}-voc.wav', dtype='float32'); acc, _ = sf.read(ROOT+f'/{name}-acc.wav', dtype='float32')
    t = time.time(); o = sp.process(sample_rate=sr, samples=np.ascontiguousarray(mix.T)); secs += time.time()-t; audio += len(mix)/sr
    s0 = np.array(o.stems[0].data).T; s1 = np.array(o.stems[1].data).T
    n = min(len(s0), len(mix)); lo, hi = 8192, n - 8192
    # which stem is the voice: the one closer to the true voice
    a, v = (s0, s1) if sdr(voc[lo:hi], s1[lo:hi]) > sdr(voc[lo:hi], s0[lo:hi]) else (s1, s0)
    res.append((sdr(acc[lo:hi], a[lo:hi]), sdr(voc[lo:hi], v[lo:hi])))
    print(name, '%.2f / %.2f' % res[-1], flush=True)
r = np.array(res)
print(f"{os.path.basename(MODEL):28s} instrumental SDR {np.median(r[:,0]):6.2f} (mean {r[:,0].mean():5.2f})  voice SDR {np.median(r[:,1]):6.2f} (mean {r[:,1].mean():5.2f})  x{audio/secs:.1f} real time on 4 x86 threads")
