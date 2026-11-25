"""Exports OpenAI Whisper « small » (MIT code and weights) to two ONNX graphs for Ketvoice's word sync by AI on desktop PCs
(Windows 2.0, `ketvoice-align-v1`), and checks them against Whisper's own PyTorch code.

The app aligns lyrics it already has (it never transcribes): one encoder pass per window of at most 30 s of the separated
voice, one decoder pass with the window's known words, then Whisper's word-timing recipe (cross-attention of the
alignment heads, softmax over the window's frames, normalisation, median filter, DTW) in Kotlin
(desktop/.../lyrics/AlignMath.kt).

- ketvoice-align-encoder.onnx  `audio` [480000] (16 kHz mono, 30 s, zero-padded) -> `features` [1, 1500, 768].
  Whisper's log-mel (n_fft 400, hop 160, 80 bands, centred, reflect padding) is inside the graph as a convolution with
  the windowed DFT basis, so the app passes samples only.
- ketvoice-align-decoder.onnx  `tokens` [1, T] int64, `features` -> `cross` [H, T, 1500] (pre-softmax cross-attention
  of the H alignment heads, as Whisper's hooks read it), `next_probs` [T - 1] (probability of each next token among
  the text tokens, as `find_alignment` measures confidence), `lang_logits` [51865] (position 0: the language).
- ketvoice-align-vocab.tiktoken  Whisper's multilingual byte-pair ranks (copied from the repository, MIT).

Checks: log-mel, encoder and decoder outputs against the reference on noise and on the repository's `tests/jfk.flac`
(a public-domain speech), the word timings of `whisper.timing.find_alignment` on that clip against the same recipe run
on the ONNX outputs, and fixtures for the Kotlin tests (`--fixtures`).

Usage: python -I export_whisper.py <whisper source dir> <small.pt> <out dir> [--fixtures out.json]
"""
import json
import math
import shutil
import sys

import numpy as np
import onnxruntime as ort
import torch
import torch.nn.functional as F

src, ckpt_path, out_dir = sys.argv[1:4]
fixtures = sys.argv[sys.argv.index("--fixtures") + 1] if "--fixtures" in sys.argv else None
sys.path.insert(0, src)
sys.path.insert(0, __file__.rsplit("/", 2)[0] + "/models")
import safe_load  # noqa: E402
import whisper  # noqa: E402
from whisper.audio import N_SAMPLES, log_mel_spectrogram, mel_filters  # noqa: E402
from whisper.model import ModelDimensions, Whisper, disable_sdpa  # noqa: E402
from whisper.timing import dtw, find_alignment, median_filter  # noqa: E402
from whisper.tokenizer import get_tokenizer  # noqa: E402

torch.manual_seed(0)
ckpt = safe_load.load(ckpt_path)
dims = ModelDimensions(**ckpt["dims"])
assert dims.n_mels == 80 and dims.n_vocab == 51865, dims
model = Whisper(dims)
model.load_state_dict(ckpt["model_state_dict"])
model.set_alignment_heads(whisper._ALIGNMENT_HEADS["small"])
model = model.float().eval()
HEADS = [(int(l), int(h)) for l, h in model.alignment_heads.indices().T]
EOT = 50257
print("alignment heads", HEADS)


class LogMel(torch.nn.Module):
    """whisper.audio.log_mel_spectrogram for exactly 30 s, the STFT as a strided convolution (exports as Conv)."""

    def __init__(self):
        super().__init__()
        n = torch.arange(400, dtype=torch.float64)
        k = torch.arange(201, dtype=torch.float64)[:, None]
        window = torch.hann_window(400, dtype=torch.float64)
        angle = 2 * math.pi * k * n / 400
        basis = torch.cat([window * torch.cos(angle), -window * torch.sin(angle)]).float()
        self.register_buffer("basis", basis[:, None, :])
        self.register_buffer("filters", mel_filters("cpu", 80).float())

    def forward(self, audio):
        x = F.pad(audio.reshape(1, 1, -1), (200, 200), mode="reflect")
        spec = F.conv1d(x, self.basis, stride=160)
        power = (spec[:, :201] ** 2 + spec[:, 201:] ** 2)[..., :-1]
        log = torch.clamp(self.filters @ power, min=1e-10).log10()
        log = torch.maximum(log, log.max() - 8.0)
        return (log + 4.0) / 4.0


class Encoder(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.mel, self.encoder = LogMel(), model.encoder

    def forward(self, audio):
        return self.encoder(self.mel(audio))


class Decoder(torch.nn.Module):
    """TextDecoder.forward without a cache, returning the alignment heads' raw cross-attention as find_alignment's hooks."""

    def __init__(self):
        super().__init__()
        self.dec = model.decoder

    def forward(self, tokens, features):
        d = self.dec
        x = d.token_embedding(tokens) + d.positional_embedding[: tokens.shape[1]]
        qks = []
        for block in d.blocks:
            x = x + block.attn(block.attn_ln(x), mask=d.mask)[0]
            out, qk = block.cross_attn(block.cross_attn_ln(x), features)
            x = x + out
            qks.append(qk)
            x = x + block.mlp(block.mlp_ln(x))
        x = d.ln(x)
        logits = F.linear(x, d.token_embedding.weight)
        cross = torch.stack([qks[layer][0, head] for layer, head in HEADS])
        probs = torch.softmax(logits[0, :, :EOT], dim=-1)
        following = tokens[0, 1:].clamp(max=EOT - 1)
        next_probs = probs[:-1].gather(1, following[:, None])[:, 0]
        return cross, next_probs, logits[0, 0]


enc_path, dec_path = f"{out_dir}/ketvoice-align-encoder.onnx", f"{out_dir}/ketvoice-align-decoder.onnx"
with torch.no_grad(), disable_sdpa():
    noise = torch.from_numpy(np.random.default_rng(1).standard_normal(N_SAMPLES).astype(np.float32) * 0.05)
    torch.onnx.export(Encoder().eval(), (noise,), enc_path, input_names=["audio"], output_names=["features"],
                      opset_version=17, dynamo=False)
    feats = model.encoder(log_mel_spectrogram(noise).unsqueeze(0))
    toks = torch.tensor([[50258, 50259, 50359, 50363, 400, 500, 600, EOT]])
    torch.onnx.export(Decoder().eval(), (toks, feats), dec_path, input_names=["tokens", "features"],
                      output_names=["cross", "next_probs", "lang_logits"], opset_version=17, dynamo=False,
                      # Folding the output projection's transpose would store the token embedding twice (+159 MB).
                      do_constant_folding=False,
                      dynamic_axes={"tokens": {1: "tokens"}, "cross": {1: "tokens"}, "next_probs": {0: "following"}})
shutil.copy(f"{src}/whisper/assets/multilingual.tiktoken", f"{out_dir}/ketvoice-align-vocab.tiktoken")

options = ort.SessionOptions()
options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
enc = ort.InferenceSession(enc_path, options, providers=["CPUExecutionProvider"])
dec = ort.InferenceSession(dec_path, options, providers=["CPUExecutionProvider"])


def window_of(audio):
    w = np.zeros(N_SAMPLES, dtype=np.float32)
    w[: min(len(audio), N_SAMPLES)] = audio[:N_SAMPLES]
    return w


def check_encoder(audio, label):
    w = window_of(audio)
    with torch.no_grad():
        mel = log_mel_spectrogram(torch.from_numpy(w))
        ref = model.encoder(mel.unsqueeze(0)).numpy()
        mel_mine = LogMel()(torch.from_numpy(w)).numpy()[0]
    got = enc.run(None, {"audio": w})[0]
    mel_diff = float(np.abs(mel_mine - mel.numpy()).max())
    diff = float(np.abs(got - ref).max())
    print(f"{label}: log-mel max abs difference {mel_diff:.2e}, encoder {diff:.2e} (scale {np.abs(ref).max():.1f})")
    assert mel_diff < 2e-3 and diff < 2e-2, label
    return got, mel


def reference_decoder(tokens, features):
    qks = [None] * dims.n_text_layer
    hooks = [b.cross_attn.register_forward_hook(lambda _, ins, outs, index=i: qks.__setitem__(index, outs[-1][0]))
             for i, b in enumerate(model.decoder.blocks)]
    with torch.no_grad(), disable_sdpa():
        logits = model.decoder(torch.tensor([tokens]), torch.from_numpy(features))[0]
    for h in hooks:
        h.remove()
    cross = torch.stack([qks[layer][head] for layer, head in HEADS]).numpy()
    probs = logits[:, :EOT].softmax(-1)
    nxt = [float(probs[i, min(tokens[i + 1], EOT - 1)]) for i in range(len(tokens) - 1)]
    return cross, np.array(nxt), logits[0].numpy()


def check_decoder(tokens, features, label):
    cross, nxt, lang = dec.run(None, {"tokens": np.array([tokens], dtype=np.int64), "features": features})
    rc, rn, rl = reference_decoder(tokens, features)
    d = (float(np.abs(cross - rc).max()), float(np.abs(nxt - rn).max()), float(np.abs(lang - rl).max()))
    print(f"{label}: {len(tokens)} tokens, cross {d[0]:.2e} (scale {np.abs(rc).max():.1f}), next probs {d[1]:.2e}, lang {d[2]:.2e}")
    assert d[0] < 5e-2 and d[1] < 2e-3 and d[2] < 5e-2, label
    return cross, nxt, lang


def recipe(cross, num_frames, sot_len, word_lengths):
    """find_alignment's arithmetic on the decoder's outputs (what AlignMath.kt does): word starts and ends in seconds.
    The matrix keeps the rows from the no-timestamps token to the last text token (sot_len = 3)."""
    w = torch.from_numpy(cross[:, :, : num_frames // 2]).softmax(dim=-1)
    std, mean = torch.std_mean(w, dim=-2, keepdim=True, unbiased=False)
    w = median_filter((w - mean) / std, 7)
    matrix = w.mean(axis=0)[sot_len:-1]
    text_idx, time_idx = dtw(-matrix)
    bounds = np.pad(np.cumsum(word_lengths), (1, 0))
    jumps = np.pad(np.diff(text_idx), (1, 0), constant_values=1).astype(bool)
    times = time_idx[jumps] / 50.0
    return times[bounds[:-1]], times[bounds[1:]]


tokenizer = get_tokenizer(True, num_languages=99, language="en", task="transcribe")
assert tokenizer.sot_sequence == (50258, 50259, 50359) and tokenizer.no_timestamps == 50363 and tokenizer.eot == EOT

check_encoder(np.random.default_rng(2).standard_normal(N_SAMPLES).astype(np.float32) * 0.1, "noise 30 s")
check_encoder(np.random.default_rng(3).standard_normal(16000 * 7).astype(np.float32) * 0.2, "noise 7 s")

# Whisper's own loader (ffmpeg, 16 kHz mono), as transcribe() reads files.
wave = whisper.audio.load_audio(f"{src}/tests/jfk.flac")
features, mel = check_encoder(wave, "jfk.flac")
text = "And so my fellow Americans ask not what your country can do for you ask what you can do for your country"
words = text.split(" ")
word_tokens = [tokenizer.encode(" " + w) for w in words]
text_tokens = [t for ts in word_tokens for t in ts]
tokens = [*tokenizer.sot_sequence, tokenizer.no_timestamps, *text_tokens, EOT]
cross, nxt, lang = check_decoder(tokens, features, "jfk.flac decoder")
check_decoder(tokens[:6], features, "jfk.flac decoder, 6 tokens")
_, _, lang1 = dec.run(None, {"tokens": np.array([[50258]], dtype=np.int64), "features": features})
assert int(np.argmax(lang1[50259:50358])) == 0, "jfk.flac is English"

num_frames = len(wave) // 160
starts, ends = recipe(cross, num_frames, 3, [len(t) for t in word_tokens])
with torch.no_grad():
    ref = find_alignment(model, tokenizer, text_tokens, mel, num_frames)
assert len(ref) == len(words)
worst = 0.0
for word, s, e, r in zip(words, starts, ends, ref):
    worst = max(worst, abs(s - r.start), abs(e - r.end))
print(f"jfk.flac: {len(words)} words, worst difference to find_alignment {worst:.3f} s;",
      " ".join(f"{w}@{s:.2f}" for w, s in zip(words, starts)))
assert worst <= 0.04
probs = [nxt[3 + i] for i in range(len(text_tokens))]
print(f"jfk.flac: mean text-token probability {np.mean(probs):.2f}")
assert np.mean(probs) > 0.5

if fixtures:
    # A cross-attention made from a formula (computed alike in Kotlin), run through Whisper's own arithmetic.
    H, T, FR, NUM = 3, 12, 1500, 320
    t = np.arange(T)[None, :, None].astype(np.float64)
    h = np.arange(H)[:, None, None].astype(np.float64)
    f = np.arange(FR)[None, None, :].astype(np.float64)
    centre = 6.0 + 13.0 * (t - 4.0)
    qk = (-((f - centre) ** 2) / 50.0 + 0.5 * np.sin(0.37 * h + 1.3 * t + 0.11 * f)).astype(np.float32)
    lengths = [2, 1, 2, 1, 1]
    s, e = recipe(qk, NUM, 3, lengths)
    samples = ["Hello world", "Don't stop me now, I'm having such a good time!", "Je t'aime à la folie, ça ira",
               "Смотри на меня", "사랑해요 너를", "夜に駆ける", "愛してる", "Γεια σου κόσμε", "حبيبي يا نور العين",
               "1999 rock'n'roll 🎸", "  spaced   out  "]
    with open(fixtures, "w", encoding="utf-8") as out:
        json.dump({"synthetic": {"heads": H, "tokens": T, "numFrames": NUM, "wordLengths": lengths,
                                 "starts": [round(float(x), 4) for x in s], "ends": [round(float(x), 4) for x in e]},
                   "tokenizer": [{"text": x, "tokens": tokenizer.encode(x)} for x in samples],
                   "jfk": {"words": words, "starts": [round(float(x), 3) for x in starts]}}, out, ensure_ascii=False, indent=1)
    print("fixtures", fixtures)
print("ok", enc_path, dec_path)
