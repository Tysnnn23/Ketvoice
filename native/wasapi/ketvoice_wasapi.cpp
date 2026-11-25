// ketvoice_wasapi.dll: exclusive-mode WASAPI output for Ketvoice for Windows (« Mode exclusif », bit-perfect).
// A small C ABI loaded with JNA (desktop/.../audio/WasapiSink.kt):
//
//   int  kv_wasapi_version()                                         1
//   int  kv_wasapi_open(device, rate, channels, bits, bufferMs)      > 0: a handle; < 0: an HRESULT
//   int  kv_wasapi_format(handle, int out[3])                         rate, container bits, valid bits; 0 = ok
//   int  kv_wasapi_write(handle, const int32_t* samples, frames)     0 = ok, else an HRESULT; blocks until queued
//   int  kv_wasapi_padding(handle)                                    frames queued and not heard yet (< 0: HRESULT)
//   void kv_wasapi_reset(handle)                                      drops what is queued
//   void kv_wasapi_drain(handle)                                      waits until what is queued has been heard
//   void kv_wasapi_close(handle)
//   int  kv_wasapi_supports(device, rate, channels, bits)             1 yes, 0 no, < 0 an HRESULT
//
// device: the endpoint's friendly name (UTF-16) as Windows shows it, or null/empty for the default render device.
// Samples are interleaved signed 32-bit with the sound in the top bits (16-bit sound: low 16 bits zero; 24-bit sound:
// low 8 bits zero). The device is opened in AUDCLNT_SHAREMODE_EXCLUSIVE at exactly `rate`, in the first PCM format it
// accepts whose valid bits hold `bits` (0: 24): packed `bits`, `bits` in a 32-bit container, then wider. Samples are
// moved into that format by shifting only, so 16- and 24-bit sound reaches the device bit for bit. Nothing resamples,
// mixes or changes the level: Windows' mixer is bypassed. A device that refuses exclusive mode (another app holds it,
// or exclusive mode is not allowed in its properties) returns AUDCLNT_E_DEVICE_IN_USE / E_ACCESSDENIED / an
// unsupported-format error, and the app falls back to the shared output and says why.

#include <windows.h>
#include <objbase.h>
// Defines PKEY_Device_FriendlyName and KSDATAFORMAT_SUBTYPE_PCM here (no ksuser.lib or propsys needed).
#include <initguid.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <functiondiscoverykeys_devpkey.h>
#include <mmreg.h>
#include <ksmedia.h>
#include <cstdint>
#include <cstring>
#include <cwchar>
#include <mutex>
#include <vector>

#define KV_API extern "C" __declspec(dllexport)

namespace {

template <class T> void release(T*& p) { if (p) { p->Release(); p = nullptr; } }

struct ComScope {
    HRESULT hr;
    ComScope() : hr(CoInitializeEx(nullptr, COINIT_MULTITHREADED)) {}
    ~ComScope() { if (SUCCEEDED(hr)) CoUninitialize(); }
};

struct Stream {
    IMMDevice* device = nullptr;
    IAudioClient* client = nullptr;
    IAudioRenderClient* render = nullptr;
    UINT32 bufferFrames = 0;
    int rate = 0, channels = 0, container = 0, valid = 0;
    DWORD periodMs = 2;
    bool started = false;
};

std::mutex gLock;
Stream* gStreams[16] = {};

Stream* get(int handle) {
    std::lock_guard<std::mutex> g(gLock);
    return (handle >= 1 && handle <= 16) ? gStreams[handle - 1] : nullptr;
}

bool sameName(const wchar_t* wanted, const wchar_t* name) {
    if (!wanted || !name) return false;
    if (wcscmp(wanted, name) == 0) return true;
    size_t a = wcslen(wanted), b = wcslen(name);
    // Some APIs cut long names: a prefix of at least 24 characters counts.
    if (a >= 24 && a < b && wcsncmp(wanted, name, a) == 0) return true;
    if (b >= 24 && b < a && wcsncmp(wanted, name, b) == 0) return true;
    return false;
}

HRESULT findDevice(const wchar_t* wanted, IMMDevice** out) {
    *out = nullptr;
    IMMDeviceEnumerator* en = nullptr;
    HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator), (void**)&en);
    if (FAILED(hr)) return hr;
    if (!wanted || !*wanted) {
        hr = en->GetDefaultAudioEndpoint(eRender, eConsole, out);
        release(en);
        return hr;
    }
    IMMDeviceCollection* all = nullptr;
    hr = en->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &all);
    if (SUCCEEDED(hr)) {
        UINT count = 0;
        all->GetCount(&count);
        for (UINT i = 0; i < count && !*out; i++) {
            IMMDevice* dev = nullptr;
            if (FAILED(all->Item(i, &dev))) continue;
            IPropertyStore* props = nullptr;
            if (SUCCEEDED(dev->OpenPropertyStore(STGM_READ, &props))) {
                PROPVARIANT v;
                PropVariantInit(&v);
                if (SUCCEEDED(props->GetValue(PKEY_Device_FriendlyName, &v)) && v.vt == VT_LPWSTR && sameName(wanted, v.pwszVal)) {
                    *out = dev;
                    dev = nullptr;
                }
                PropVariantClear(&v);
                release(props);
            }
            release(dev);
        }
        release(all);
    }
    release(en);
    if (SUCCEEDED(hr) && !*out) hr = HRESULT_FROM_WIN32(ERROR_NOT_FOUND);
    return hr;
}

WAVEFORMATEXTENSIBLE pcm(int rate, int channels, int container, int valid) {
    WAVEFORMATEXTENSIBLE f = {};
    f.Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
    f.Format.nChannels = (WORD)channels;
    f.Format.nSamplesPerSec = (DWORD)rate;
    f.Format.wBitsPerSample = (WORD)container;
    f.Format.nBlockAlign = (WORD)(channels * container / 8);
    f.Format.nAvgBytesPerSec = (DWORD)rate * f.Format.nBlockAlign;
    f.Format.cbSize = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
    f.Samples.wValidBitsPerSample = (WORD)valid;
    f.dwChannelMask = channels == 1 ? SPEAKER_FRONT_CENTER : (SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT);
    f.SubFormat = KSDATAFORMAT_SUBTYPE_PCM;
    return f;
}

// Formats to try, best first, for sound of `bits` bits: each keeps every bit of it.
std::vector<std::pair<int, int>> candidates(int bits) {
    if (bits <= 0) bits = 24;
    std::vector<std::pair<int, int>> out;
    if (bits <= 16) { out.push_back({16, 16}); out.push_back({32, 16}); out.push_back({24, 24}); out.push_back({32, 24}); out.push_back({32, 32}); }
    else if (bits <= 24) { out.push_back({24, 24}); out.push_back({32, 24}); out.push_back({32, 32}); }
    else { out.push_back({32, 32}); }
    return out;
}

HRESULT pickFormat(IAudioClient* client, int rate, int channels, int bits, WAVEFORMATEXTENSIBLE* chosen) {
    HRESULT last = AUDCLNT_E_UNSUPPORTED_FORMAT;
    for (auto [container, valid] : candidates(bits)) {
        WAVEFORMATEXTENSIBLE f = pcm(rate, channels, container, valid);
        HRESULT hr = client->IsFormatSupported(AUDCLNT_SHAREMODE_EXCLUSIVE, &f.Format, nullptr);
        if (hr == S_OK) { *chosen = f; return S_OK; }
        last = hr;
    }
    return FAILED(last) ? last : AUDCLNT_E_UNSUPPORTED_FORMAT;
}

HRESULT initialize(Stream* s, const WAVEFORMATEXTENSIBLE& f, int bufferMs) {
    REFERENCE_TIME def = 0, min = 0;
    s->client->GetDevicePeriod(&def, &min);
    REFERENCE_TIME duration = (REFERENCE_TIME)bufferMs * 10000;
    if (duration < def) duration = def;
    HRESULT hr = s->client->Initialize(AUDCLNT_SHAREMODE_EXCLUSIVE, 0, duration, duration, &f.Format, nullptr);
    if (hr == AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED) {
        // The device wants a buffer of a whole number of its own periods: the aligned size, on a fresh client.
        UINT32 frames = 0;
        s->client->GetBufferSize(&frames);
        release(s->client);
        hr = s->device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void**)&s->client);
        if (FAILED(hr)) return hr;
        duration = (REFERENCE_TIME)(10000.0 * 1000 / f.Format.nSamplesPerSec * frames + 0.5);
        hr = s->client->Initialize(AUDCLNT_SHAREMODE_EXCLUSIVE, 0, duration, duration, &f.Format, nullptr);
    }
    if (FAILED(hr)) return hr;
    s->periodMs = (DWORD)(def / 10000 / 2);
    if (s->periodMs < 1) s->periodMs = 1;
    hr = s->client->GetBufferSize(&s->bufferFrames);
    if (FAILED(hr)) return hr;
    return s->client->GetService(__uuidof(IAudioRenderClient), (void**)&s->render);
}

void destroy(Stream* s) {
    if (!s) return;
    if (s->client && s->started) s->client->Stop();
    release(s->render);
    release(s->client);
    release(s->device);
    delete s;
}

void convert(const Stream* s, const int32_t* in, BYTE* out, UINT32 frames) {
    const size_t n = (size_t)frames * s->channels;
    if (s->container == 32) { std::memcpy(out, in, n * 4); return; }
    if (s->container == 24) {
        for (size_t i = 0; i < n; i++) {
            const uint32_t v = (uint32_t)in[i] >> 8;
            out[i * 3] = (BYTE)v; out[i * 3 + 1] = (BYTE)(v >> 8); out[i * 3 + 2] = (BYTE)(v >> 16);
        }
        return;
    }
    int16_t* o = (int16_t*)out;
    for (size_t i = 0; i < n; i++) o[i] = (int16_t)(in[i] >> 16);
}

thread_local bool comReady = false;
void ensureCom() {
    if (comReady) return;
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    comReady = SUCCEEDED(hr) || hr == RPC_E_CHANGED_MODE;
}

} // namespace

KV_API int kv_wasapi_version() { return 1; }

KV_API int kv_wasapi_supports(const wchar_t* device, int rate, int channels, int bits) {
    ComScope com;
    IMMDevice* dev = nullptr;
    HRESULT hr = findDevice(device, &dev);
    if (FAILED(hr)) return (int)hr;
    IAudioClient* client = nullptr;
    hr = dev->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void**)&client);
    int result = (int)hr;
    if (SUCCEEDED(hr)) {
        WAVEFORMATEXTENSIBLE f;
        hr = pickFormat(client, rate, channels, bits, &f);
        result = hr == S_OK ? 1 : (hr == AUDCLNT_E_UNSUPPORTED_FORMAT || hr == S_FALSE ? 0 : (int)hr);
    }
    release(client);
    release(dev);
    return result;
}

KV_API int kv_wasapi_open(const wchar_t* device, int rate, int channels, int bits, int bufferMs) {
    if (rate <= 0 || channels < 1 || channels > 8) return (int)E_INVALIDARG;
    ensureCom();
    Stream* s = new Stream();
    s->rate = rate; s->channels = channels;
    HRESULT hr = findDevice(device, &s->device);
    if (SUCCEEDED(hr)) hr = s->device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void**)&s->client);
    WAVEFORMATEXTENSIBLE f = {};
    if (SUCCEEDED(hr)) hr = pickFormat(s->client, rate, channels, bits, &f);
    if (SUCCEEDED(hr)) hr = initialize(s, f, bufferMs < 20 ? 20 : bufferMs);
    if (FAILED(hr)) { destroy(s); return (int)hr; }
    s->container = f.Format.wBitsPerSample;
    s->valid = f.Samples.wValidBitsPerSample;
    std::lock_guard<std::mutex> g(gLock);
    for (int i = 0; i < 16; i++) {
        if (!gStreams[i]) { gStreams[i] = s; return i + 1; }
    }
    destroy(s);
    return (int)E_OUTOFMEMORY;
}

KV_API int kv_wasapi_format(int handle, int* out) {
    Stream* s = get(handle);
    if (!s || !out) return (int)E_HANDLE;
    out[0] = s->rate; out[1] = s->container; out[2] = s->valid;
    return 0;
}

KV_API int kv_wasapi_write(int handle, const int32_t* samples, int frames) {
    Stream* s = get(handle);
    if (!s || !samples || frames < 0) return (int)E_HANDLE;
    ensureCom();
    int done = 0;
    while (done < frames) {
        UINT32 padding = 0;
        HRESULT hr = s->client->GetCurrentPadding(&padding);
        if (FAILED(hr)) return (int)hr;
        UINT32 room = s->bufferFrames - padding;
        if (room == 0) {
            if (!s->started) { hr = s->client->Start(); if (FAILED(hr)) return (int)hr; s->started = true; }
            Sleep(s->periodMs);
            continue;
        }
        UINT32 n = (UINT32)(frames - done) < room ? (UINT32)(frames - done) : room;
        BYTE* data = nullptr;
        hr = s->render->GetBuffer(n, &data);
        if (FAILED(hr)) return (int)hr;
        convert(s, samples + (size_t)done * s->channels, data, n);
        hr = s->render->ReleaseBuffer(n, 0);
        if (FAILED(hr)) return (int)hr;
        done += (int)n;
    }
    return 0;
}

KV_API int kv_wasapi_padding(int handle) {
    Stream* s = get(handle);
    if (!s) return (int)E_HANDLE;
    UINT32 padding = 0;
    HRESULT hr = s->client->GetCurrentPadding(&padding);
    return FAILED(hr) ? (int)hr : (int)padding;
}

KV_API void kv_wasapi_reset(int handle) {
    Stream* s = get(handle);
    if (!s) return;
    if (s->started) { s->client->Stop(); s->started = false; }
    s->client->Reset();
}

KV_API void kv_wasapi_drain(int handle) {
    Stream* s = get(handle);
    if (!s) return;
    if (!s->started) { if (SUCCEEDED(s->client->Start())) s->started = true; }
    for (int waited = 0; waited < 3000; waited += (int)s->periodMs) {
        UINT32 padding = 0;
        if (FAILED(s->client->GetCurrentPadding(&padding)) || padding == 0) break;
        Sleep(s->periodMs);
    }
}

KV_API void kv_wasapi_close(int handle) {
    Stream* s = nullptr;
    {
        std::lock_guard<std::mutex> g(gLock);
        if (handle >= 1 && handle <= 16) { s = gStreams[handle - 1]; gStreams[handle - 1] = nullptr; }
    }
    destroy(s);
}
