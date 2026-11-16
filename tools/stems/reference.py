"""Reference data for StemsTest.matchesTheReferenceImplementation: the network input and outputs of a
song, computed with ONNX Runtime and an independent numpy STFT. Usage: reference.py song.wav onnx-dir out-dir
(the sherpa-onnx vocals of the same song go to out-dir/sherpa-vocals.f32, interleaved float32)."""
import sys, numpy as np, soundfile as sf, onnxruntime as ort
wav, models, out = sys.argv[1:4]
x, sr = sf.read(wav, dtype='float32', always_2d=True); x = x.T.copy()  # (2, N)
assert sr == 44100
n_fft, hop = 4096, 1024
w = (0.5 - 0.5*np.cos(2*np.pi*np.arange(n_fft)/n_fft)).astype(np.float32)
T = 1 + (x.shape[1]-n_fft)//hop
S = (T + 511)//512
mag = np.zeros((2, S*512, 1024), np.float32)
for c in range(2):
    fr = np.stack([x[c, t*hop:t*hop+n_fft]*w for t in range(T)])
    X = np.fft.rfft(fr, axis=1)
    mag[c, :T] = np.abs(X[:, :1024])
inp = mag.reshape(2, S, 512, 1024)
v = ort.InferenceSession(models + '/vocals.onnx').run(None, {'x': inp})[0]
a = ort.InferenceSession(models + '/accompaniment.onnx').run(None, {'x': inp})[0]
def per_split(z):  # -> (S, 2, 512, 1024) so the Kotlin replay can read split after split
    return np.ascontiguousarray(z.transpose(1, 0, 2, 3)).astype('<f4')
per_split(inp).tofile(out + '/input.f32'); per_split(v).tofile(out + '/vocals.f32'); per_split(a).tofile(out + '/accompaniment.f32')
x.T.astype('<f4').tofile(out + '/mix.f32')  # interleaved stereo
print('frames', T, 'splits', S)
