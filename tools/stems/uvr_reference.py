"""Usage: uvr_reference.py model.onnx musdb-excerpt-prefix out-dir seconds (local only; MUSDB18-HQ is not part of the repository).
Replay data for UvrTest: the model's inputs and outputs per segment (my numpy port, identical to sherpa-onnx),
sherpa-onnx's voice, and MUSDB's true stems, for the first SECONDS of an excerpt."""
import sys, os, numpy as np, soundfile as sf, onnxruntime as ort, sherpa_onnx
MODEL, NAME, OUT, SECONDS = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
os.makedirs(OUT, exist_ok=True)
so = ort.SessionOptions(); so.intra_op_num_threads = 2
sess = ort.InferenceSession(MODEL, so)
meta = sess.get_modelmeta().custom_metadata_map
NFFT, HOP, DF, DT = int(meta['n_fft']), 1024, int(meta['dim_f']), int(meta['dim_t'])
TRIM = NFFT//2; CHUNK = HOP*(DT-1); GEN = CHUNK - 2*TRIM; MARGIN = 44100; CHUNKS = 15*44100
W = 0.5 - 0.5*np.cos(2*np.pi*np.arange(NFFT)/NFFT)
def stft(x):
    p = np.pad(x, NFFT//2, mode='reflect'); T = 1 + (len(p)-NFFT)//HOP
    idx = np.arange(NFFT)[None, :] + HOP*np.arange(T)[:, None]
    return np.fft.rfft(p[idx]*W, axis=1)
ins, outs = [], []
def chunk_inputs(ch):
    n = ch.shape[1]; pad = GEN - n % GEN
    s = np.zeros((2, TRIM + n + pad + TRIM)); s[:, TRIM:TRIM+n] = ch
    for i in range(0, n + pad, GEN):
        X = [stft(s[c, i:i+CHUNK]) for c in range(2)]
        x = np.zeros((1, 4, DF, DT), np.float32)
        x[0, 0] = X[0][:, :DF].real.T; x[0, 1] = X[0][:, :DF].imag.T; x[0, 2] = X[1][:, :DF].real.T; x[0, 3] = X[1][:, :DF].imag.T
        ins.append(x); outs.append(sess.run(None, {'input': x})[0])
root = os.path.dirname(NAME)
base = os.path.basename(NAME)
mix, sr = sf.read(f'{root}/{base}-mix.wav', dtype='float64'); n = SECONDS*sr
x = mix[:n].T
starts = list(range(0, n, CHUNKS))
for j, i in enumerate(starts):
    a = max(0, i-MARGIN); b = min(i+CHUNKS+MARGIN, n); chunk_inputs(x[:, a:b])
    if b == n: break
np.concatenate([v.ravel() for v in ins]).astype('<f4').tofile(OUT+'/uvr-input.f32')
np.concatenate([v.ravel() for v in outs]).astype('<f4').tofile(OUT+'/uvr-output.f32')
mix[:n].astype('<f4').tofile(OUT+'/mix.f32')
for stem in ('voc', 'acc'):
    y, _ = sf.read(f'{root}/{base}-{stem}.wav', dtype='float32'); y[:n].astype('<f4').tofile(f'{OUT}/true-{stem}.f32')
sp = sherpa_onnx.OfflineSourceSeparation(sherpa_onnx.OfflineSourceSeparationConfig(model=sherpa_onnx.OfflineSourceSeparationModelConfig(
    uvr=sherpa_onnx.OfflineSourceSeparationUvrModelConfig(model=MODEL), num_threads=2)))
o = sp.process(sample_rate=sr, samples=np.ascontiguousarray(x.astype(np.float32)))
np.array(o.stems[0].data).T.astype('<f4').tofile(OUT+'/sherpa-voice.f32')
open(OUT+'/meta.txt', 'w').write(f'{NFFT} {DF} {DT} {len(ins)}\n')
print('segments', len(ins), 'nfft', NFFT)
