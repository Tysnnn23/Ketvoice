// ketvoice_smtc.dll: System Media Transport Controls (media keys, the volume flyout's now-playing
// card, the lock screen) for Ketvoice for Windows. A small C ABI loaded with JNA:
//
//   int  kv_smtc_init(HWND hwnd, kv_smtc_callback callback)   0 = ok, else an HRESULT
//   int  kv_smtc_update(title, artist, album, artPath, playing, positionMs, durationMs)
//   void kv_smtc_clear()
//   void kv_smtc_shutdown()
//
// Strings are UTF-16 (wchar_t). artPath is a local file path or an http(s) URL, or null/empty.
// hwnd may be null: the DLL then makes its own hidden window (the installed `--smoke` does this).
// The callback runs on a Windows thread-pool thread with (command, value):
//   1 play, 2 pause, 3 next, 4 previous, 5 seek (value = position in ms), 6 stop.
// Command numbers are mirrored in desktop/.../platform/MediaCommand.kt.
//
// The taskbar's thumbnail toolbar (ITaskbarList3: previous, play/pause, next) and, while the window is
// minimised, the cover as its taskbar thumbnail and peek (DWM iconic bitmaps):
//
//   int  kv_thumb_init(HWND hwnd, kv_smtc_callback callback, int size, const uint32_t* icons)
//   int  kv_thumb_update(int playing, int enabled, tipPrevious, tipToggle, tipNext)
//   int  kv_thumb_cover(const uint32_t* argb, int width, int height)   null clears
//   void kv_thumb_shutdown()
//
// icons: 4 square ARGB images of size x size, top row first (previous, play, pause, next). Clicks
// reach the callback on the window's thread: 4 previous, 7 play/pause, 3 next. The window procedure
// is subclassed (SetWindowLongPtr, chained to AWT's); every taskbar and DWM call is made on the
// window's own thread, from that procedure, so the COM object stays in the apartment it was made in.

#include <windows.h>
#include <objbase.h>
#include <unknwn.h>
#include <systemmediatransportcontrolsinterop.h>
#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/Windows.Storage.h>
#include <winrt/Windows.Storage.Streams.h>
#include <shobjidl_core.h>
#include <dwmapi.h>
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

using namespace winrt;
using namespace winrt::Windows::Media;
using namespace winrt::Windows::Storage;
using namespace winrt::Windows::Storage::Streams;
using winrt::Windows::Foundation::TimeSpan;

typedef void(__cdecl* kv_smtc_callback)(int command, long long value);

namespace {
std::mutex gLock;
SystemMediaTransportControls controls{nullptr};
event_token buttonToken{};
event_token seekToken{};
kv_smtc_callback callback = nullptr;
HWND ownWindow = nullptr;
std::wstring lastArt;

void send(int command, long long value) {
  auto cb = callback;
  if (cb) cb(command, value);
}

// Each JVM thread that calls in joins the multithreaded apartment once (blocking .get() is
// allowed there, and thread-pool events need no message pump).
HRESULT ensureCom() {
  thread_local bool done = false;
  if (done) return S_OK;
  HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
  if (FAILED(hr) && hr != RPC_E_CHANGED_MODE) return hr;
  done = true;
  return S_OK;
}

HWND makeHiddenWindow() {
  WNDCLASSW wc{};
  wc.lpfnWndProc = DefWindowProcW;
  wc.hInstance = GetModuleHandleW(nullptr);
  wc.lpszClassName = L"KetvoiceSmtcHidden";
  RegisterClassW(&wc);  // fails harmlessly when already registered
  return CreateWindowExW(0, wc.lpszClassName, L"Ketvoice", WS_OVERLAPPED, 0, 0, 0, 0, nullptr, nullptr,
                         wc.hInstance, nullptr);
}

std::wstring str(const wchar_t* s) { return s ? std::wstring(s) : std::wstring(); }

TimeSpan ms(long long v) { return std::chrono::milliseconds(v < 0 ? 0 : v); }

void onButton(SystemMediaTransportControls const&, SystemMediaTransportControlsButtonPressedEventArgs const& args) {
  switch (args.Button()) {
    case SystemMediaTransportControlsButton::Play: send(1, 0); break;
    case SystemMediaTransportControlsButton::Pause: send(2, 0); break;
    case SystemMediaTransportControlsButton::Next: send(3, 0); break;
    case SystemMediaTransportControlsButton::Previous: send(4, 0); break;
    case SystemMediaTransportControlsButton::Stop: send(6, 0); break;
    default: break;
  }
}

void onSeek(SystemMediaTransportControls const&, PlaybackPositionChangeRequestedEventArgs const& args) {
  send(5, std::chrono::duration_cast<std::chrono::milliseconds>(args.RequestedPlaybackPosition()).count());
}

// ---------------------------------------------------------------------------------------------
// Thumbnail toolbar and iconic thumbnail

#ifndef WM_DWMSENDICONICTHUMBNAIL
#define WM_DWMSENDICONICTHUMBNAIL 0x0323
#endif
#ifndef WM_DWMSENDICONICLIVEPREVIEWBITMAP
#define WM_DWMSENDICONICLIVEPREVIEWBITMAP 0x0326
#endif

namespace thumb {
constexpr UINT kIdPrevious = 0x4B10, kIdToggle = 0x4B11, kIdNext = 0x4B12;

// Shared with the JVM's threads (under `lock`).
std::mutex lock;
HWND window = nullptr;
WNDPROC original = nullptr;
kv_smtc_callback onClick = nullptr;
HICON icons[4] = {};  // previous, play, pause, next
bool playing = false;
bool enabled = false;
std::wstring tips[3];
std::vector<uint32_t> cover;
int coverW = 0, coverH = 0;
UINT msgButtonCreated = 0, msgRefresh = 0, msgCleanup = 0;

// The window's thread only.
ITaskbarList3* taskbar = nullptr;
bool added = false;
bool iconic = false;

HBITMAP makeDib(int w, int h, uint32_t** bits) {
  BITMAPV5HEADER bi{};
  bi.bV5Size = sizeof(bi);
  bi.bV5Width = w;
  bi.bV5Height = -h;  // top row first
  bi.bV5Planes = 1;
  bi.bV5BitCount = 32;
  bi.bV5Compression = BI_BITFIELDS;
  bi.bV5RedMask = 0x00FF0000;
  bi.bV5GreenMask = 0x0000FF00;
  bi.bV5BlueMask = 0x000000FF;
  bi.bV5AlphaMask = 0xFF000000;
  void* raw = nullptr;
  HDC dc = GetDC(nullptr);
  HBITMAP bmp = CreateDIBSection(dc, reinterpret_cast<BITMAPINFO*>(&bi), DIB_RGB_COLORS, &raw, nullptr, 0);
  ReleaseDC(nullptr, dc);
  *bits = static_cast<uint32_t*>(raw);
  return bmp;
}

HICON makeIcon(const uint32_t* argb, int size) {
  uint32_t* bits = nullptr;
  HBITMAP color = makeDib(size, size, &bits);
  if (!color || !bits) return nullptr;
  std::memcpy(bits, argb, size_t(size) * size * 4);
  std::vector<BYTE> zeros(size_t((size + 15) / 16) * 2 * size, 0);  // monochrome rows are WORD-aligned
  HBITMAP mask = CreateBitmap(size, size, 1, 1, zeros.data());
  ICONINFO info{};
  info.fIcon = TRUE;
  info.hbmColor = color;
  info.hbmMask = mask;
  HICON icon = CreateIconIndirect(&info);
  DeleteObject(color);
  if (mask) DeleteObject(mask);
  return icon;
}

void destroyIcons() {
  for (auto& icon : icons) {
    if (icon) DestroyIcon(icon);
    icon = nullptr;
  }
}

// The cover scaled (bilinear) to w x h, centred on a dark canvas of canvasW x canvasH.
HBITMAP coverBitmap(const std::vector<uint32_t>& src, int sw, int sh, int canvasW, int canvasH, int w, int h) {
  uint32_t* bits = nullptr;
  HBITMAP bmp = makeDib(canvasW, canvasH, &bits);
  if (!bmp || !bits) return nullptr;
  for (size_t i = 0; i < size_t(canvasW) * canvasH; i++) bits[i] = 0xFF111113;
  int ox = (canvasW - w) / 2, oy = (canvasH - h) / 2;
  for (int y = 0; y < h; y++) {
    double fy = (y + 0.5) * sh / h - 0.5;
    int y0 = fy < 0 ? 0 : int(fy);
    int y1 = y0 + 1 < sh ? y0 + 1 : sh - 1;
    double ty = fy - y0 < 0 ? 0 : fy - y0;
    for (int x = 0; x < w; x++) {
      double fx = (x + 0.5) * sw / w - 0.5;
      int x0 = fx < 0 ? 0 : int(fx);
      int x1 = x0 + 1 < sw ? x0 + 1 : sw - 1;
      double tx = fx - x0 < 0 ? 0 : fx - x0;
      uint32_t a = src[size_t(y0) * sw + x0], b = src[size_t(y0) * sw + x1];
      uint32_t c = src[size_t(y1) * sw + x0], d = src[size_t(y1) * sw + x1];
      uint32_t out = 0xFF000000;
      for (int shift = 0; shift <= 16; shift += 8) {
        double top = ((a >> shift) & 0xFF) * (1 - tx) + ((b >> shift) & 0xFF) * tx;
        double bottom = ((c >> shift) & 0xFF) * (1 - tx) + ((d >> shift) & 0xFF) * tx;
        out |= uint32_t(top * (1 - ty) + bottom * ty + 0.5) << shift;
      }
      bits[size_t(y + oy) * canvasW + (x + ox)] = out;
    }
  }
  return bmp;
}

void fit(int sw, int sh, int maxW, int maxH, int* w, int* h) {
  double scale = (std::min)(double(maxW) / sw, double(maxH) / sh);
  *w = (std::max)(1, (std::min)(maxW, int(sw * scale)));
  *h = (std::max)(1, (std::min)(maxH, int(sh * scale)));
}

void setIconic(HWND hwnd, bool on) {
  if (on == iconic) return;
  BOOL value = on ? TRUE : FALSE;
  DwmSetWindowAttribute(hwnd, DWMWA_FORCE_ICONIC_REPRESENTATION, &value, sizeof(value));
  DwmSetWindowAttribute(hwnd, DWMWA_HAS_ICONIC_BITMAP, &value, sizeof(value));
  iconic = on;
}

bool hasCover() {
  std::lock_guard<std::mutex> guard(lock);
  return !cover.empty();
}

// Buttons and the iconic state, on the window's thread.
void refresh(HWND hwnd) {
  THUMBBUTTON buttons[3] = {};
  {
    std::lock_guard<std::mutex> guard(lock);
    const UINT ids[3] = {kIdPrevious, kIdToggle, kIdNext};
    HICON shown[3] = {icons[0], playing ? icons[2] : icons[1], icons[3]};
    for (int i = 0; i < 3; i++) {
      buttons[i].dwMask = THB_ICON | THB_TOOLTIP | THB_FLAGS;
      buttons[i].iId = ids[i];
      buttons[i].hIcon = shown[i];
      wcsncpy_s(buttons[i].szTip, tips[i].c_str(), _TRUNCATE);
      buttons[i].dwFlags = enabled ? THBF_ENABLED : THBF_DISABLED;
    }
  }
  if (!taskbar) {
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);  // AWT's thread has COM already; harmless either way
    if (FAILED(CoCreateInstance(CLSID_TaskbarList, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&taskbar)))) {
      taskbar = nullptr;
    } else if (FAILED(taskbar->HrInit())) {
      taskbar->Release();
      taskbar = nullptr;
    }
  }
  if (taskbar) {
    HRESULT hr = added ? taskbar->ThumbBarUpdateButtons(hwnd, 3, buttons) : taskbar->ThumbBarAddButtons(hwnd, 3, buttons);
    if (SUCCEEDED(hr)) added = true;
  }
  setIconic(hwnd, hasCover() && IsIconic(hwnd));
  if (iconic) DwmInvalidateIconicBitmaps(hwnd);
}

bool coverCopy(std::vector<uint32_t>* src, int* sw, int* sh) {
  std::lock_guard<std::mutex> guard(lock);
  *src = cover;
  *sw = coverW;
  *sh = coverH;
  return !src->empty();
}

void sendThumbnail(HWND hwnd, int maxW, int maxH) {
  std::vector<uint32_t> src;
  int sw, sh;
  if (!coverCopy(&src, &sw, &sh) || maxW <= 0 || maxH <= 0) return;
  int w, h;
  fit(sw, sh, maxW, maxH, &w, &h);
  HBITMAP bmp = coverBitmap(src, sw, sh, w, h, w, h);
  if (!bmp) return;
  DwmSetIconicThumbnail(hwnd, bmp, 0);
  DeleteObject(bmp);
}

// Peek on the minimised window: the cover in the middle of its normal size.
void sendPreview(HWND hwnd) {
  std::vector<uint32_t> src;
  int sw, sh;
  if (!coverCopy(&src, &sw, &sh)) return;
  WINDOWPLACEMENT place{};
  place.length = sizeof(place);
  if (!GetWindowPlacement(hwnd, &place)) return;
  int cw = place.rcNormalPosition.right - place.rcNormalPosition.left;
  int ch = place.rcNormalPosition.bottom - place.rcNormalPosition.top;
  if (cw <= 0 || ch <= 0 || cw > 16384 || ch > 16384) return;
  int w, h;
  fit(sw, sh, cw * 3 / 5, ch * 3 / 5, &w, &h);
  HBITMAP bmp = coverBitmap(src, sw, sh, cw, ch, w, h);
  if (!bmp) return;
  DwmSetIconicLivePreviewBitmap(hwnd, bmp, nullptr, 0);
  DeleteObject(bmp);
}

void cleanupOnThread(HWND hwnd) {
  setIconic(hwnd, false);
  if (taskbar) {
    taskbar->Release();
    taskbar = nullptr;
  }
  added = false;
}

LRESULT CALLBACK proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
  if (msgButtonCreated && msg == msgButtonCreated) {
    // A new taskbar (first show, or Explorer restarted): the buttons go on again.
    added = false;
    if (taskbar) {
      taskbar->Release();
      taskbar = nullptr;
    }
    refresh(hwnd);
  } else if (msgRefresh && msg == msgRefresh) {
    refresh(hwnd);
    return 0;
  } else if (msgCleanup && msg == msgCleanup) {
    cleanupOnThread(hwnd);
    return 0;
  } else if (msg == WM_COMMAND && HIWORD(wp) == THBN_CLICKED) {
    UINT id = LOWORD(wp);
    int command = id == kIdPrevious ? 4 : id == kIdNext ? 3 : id == kIdToggle ? 7 : 0;
    if (command) {
      kv_smtc_callback cb;
      {
        std::lock_guard<std::mutex> guard(lock);
        cb = onClick;
      }
      if (cb) cb(command, 0);
      return 0;
    }
  } else if (msg == WM_DWMSENDICONICTHUMBNAIL) {
    sendThumbnail(hwnd, HIWORD(lp), LOWORD(lp));
    return 0;
  } else if (msg == WM_DWMSENDICONICLIVEPREVIEWBITMAP) {
    sendPreview(hwnd);
    return 0;
  } else if (msg == WM_SIZE) {
    LRESULT result = CallWindowProcW(original, hwnd, msg, wp, lp);
    setIconic(hwnd, hasCover() && wp == SIZE_MINIMIZED);
    if (iconic) DwmInvalidateIconicBitmaps(hwnd);
    return result;
  }
  return CallWindowProcW(original, hwnd, msg, wp, lp);
}

void post() {
  std::lock_guard<std::mutex> guard(lock);
  if (window) PostMessageW(window, msgRefresh, 0, 0);
}
}  // namespace thumb

}  // namespace

extern "C" {

__declspec(dllexport) int __cdecl kv_smtc_init(HWND hwnd, kv_smtc_callback cb) {
  std::lock_guard<std::mutex> guard(gLock);
  if (controls) return 0;
  HRESULT co = ensureCom();
  if (FAILED(co)) return co;
  try {
    if (!hwnd) {
      ownWindow = makeHiddenWindow();
      if (!ownWindow) return HRESULT_FROM_WIN32(GetLastError());
      hwnd = ownWindow;
    }
    auto interop = get_activation_factory<SystemMediaTransportControls, ISystemMediaTransportControlsInterop>();
    SystemMediaTransportControls smtc{nullptr};
    check_hresult(interop->GetForWindow(hwnd, reinterpret_cast<GUID const&>(guid_of<SystemMediaTransportControls>()),
                                        put_abi(smtc)));
    smtc.IsEnabled(true);
    smtc.IsPlayEnabled(true);
    smtc.IsPauseEnabled(true);
    smtc.IsNextEnabled(true);
    smtc.IsPreviousEnabled(true);
    smtc.IsStopEnabled(true);
    smtc.PlaybackStatus(MediaPlaybackStatus::Closed);
    callback = cb;
    buttonToken = smtc.ButtonPressed(&onButton);
    seekToken = smtc.PlaybackPositionChangeRequested(&onSeek);
    controls = smtc;
    return 0;
  } catch (hresult_error const& e) {
    if (ownWindow) { DestroyWindow(ownWindow); ownWindow = nullptr; }
    return e.code();
  } catch (...) {
    if (ownWindow) { DestroyWindow(ownWindow); ownWindow = nullptr; }
    return E_FAIL;
  }
}

__declspec(dllexport) int __cdecl kv_smtc_update(const wchar_t* title, const wchar_t* artist, const wchar_t* album,
                                                 const wchar_t* artPath, int playing, long long positionMs,
                                                 long long durationMs) {
  std::lock_guard<std::mutex> guard(gLock);
  if (!controls) return E_NOT_VALID_STATE;
  HRESULT co = ensureCom();
  if (FAILED(co)) return co;
  try {
    controls.PlaybackStatus(playing ? MediaPlaybackStatus::Playing : MediaPlaybackStatus::Paused);
    auto updater = controls.DisplayUpdater();
    updater.Type(MediaPlaybackType::Music);
    auto music = updater.MusicProperties();
    music.Title(str(title));
    music.Artist(str(artist));
    music.AlbumTitle(str(album));
    std::wstring art = str(artPath);
    if (art != lastArt) {
      lastArt = art;
      if (art.empty()) {
        updater.Thumbnail(nullptr);
      } else if (art.rfind(L"http://", 0) == 0 || art.rfind(L"https://", 0) == 0) {
        updater.Thumbnail(RandomAccessStreamReference::CreateFromUri(winrt::Windows::Foundation::Uri(art)));
      } else {
        try {
          auto file = StorageFile::GetFileFromPathAsync(art).get();
          updater.Thumbnail(RandomAccessStreamReference::CreateFromFile(file));
        } catch (...) {
          updater.Thumbnail(nullptr);
        }
      }
    }
    updater.Update();

    long long duration = durationMs < 0 ? 0 : durationMs;
    long long position = positionMs < 0 ? 0 : (duration > 0 && positionMs > duration ? duration : positionMs);
    SystemMediaTransportControlsTimelineProperties timeline;
    timeline.StartTime(ms(0));
    timeline.MinSeekTime(ms(0));
    timeline.Position(ms(position));
    timeline.MaxSeekTime(ms(duration));
    timeline.EndTime(ms(duration));
    controls.UpdateTimelineProperties(timeline);
    return 0;
  } catch (hresult_error const& e) {
    return e.code();
  } catch (...) {
    return E_FAIL;
  }
}

__declspec(dllexport) void __cdecl kv_smtc_clear() {
  std::lock_guard<std::mutex> guard(gLock);
  if (!controls) return;
  try {
    lastArt.clear();
    auto updater = controls.DisplayUpdater();
    updater.ClearAll();
    updater.Update();
    controls.PlaybackStatus(MediaPlaybackStatus::Closed);
  } catch (...) {
  }
}

__declspec(dllexport) void __cdecl kv_smtc_shutdown() {
  std::lock_guard<std::mutex> guard(gLock);
  if (controls) {
    try {
      controls.ButtonPressed(buttonToken);
      controls.PlaybackPositionChangeRequested(seekToken);
      controls.DisplayUpdater().ClearAll();
      controls.DisplayUpdater().Update();
      controls.IsEnabled(false);
    } catch (...) {
    }
    controls = nullptr;
  }
  callback = nullptr;
  lastArt.clear();
  if (ownWindow) { DestroyWindow(ownWindow); ownWindow = nullptr; }
}

__declspec(dllexport) int __cdecl kv_thumb_init(HWND hwnd, kv_smtc_callback cb, int size, const uint32_t* argb) {
  using namespace thumb;
  if (!hwnd || !IsWindow(hwnd) || size <= 0 || size > 256 || !argb) return E_INVALIDARG;
  std::lock_guard<std::mutex> guard(lock);
  if (window) return window == hwnd ? S_OK : E_NOT_VALID_STATE;
  for (int i = 0; i < 4; i++) {
    icons[i] = makeIcon(argb + size_t(i) * size * size, size);
    if (!icons[i]) {
      destroyIcons();
      return E_FAIL;
    }
  }
  msgButtonCreated = RegisterWindowMessageW(L"TaskbarButtonCreated");
  msgRefresh = RegisterWindowMessageW(L"KetvoiceThumbRefresh");
  msgCleanup = RegisterWindowMessageW(L"KetvoiceThumbCleanup");
  ChangeWindowMessageFilterEx(hwnd, msgButtonCreated, MSGFLT_ALLOW, nullptr);
  ChangeWindowMessageFilterEx(hwnd, WM_COMMAND, MSGFLT_ALLOW, nullptr);
  onClick = cb;
  // `original` first: a message may arrive the moment the procedure is swapped.
  original = reinterpret_cast<WNDPROC>(GetWindowLongPtrW(hwnd, GWLP_WNDPROC));
  if (!original) {
    destroyIcons();
    return E_FAIL;
  }
  SetLastError(0);
  LONG_PTR previous = SetWindowLongPtrW(hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(&proc));
  if (!previous) {
    DWORD error = GetLastError();
    destroyIcons();
    return error ? HRESULT_FROM_WIN32(error) : E_FAIL;
  }
  original = reinterpret_cast<WNDPROC>(previous);
  window = hwnd;
  PostMessageW(hwnd, msgRefresh, 0, 0);
  return 0;
}

__declspec(dllexport) int __cdecl kv_thumb_update(int isPlaying, int isEnabled, const wchar_t* tipPrevious,
                                                  const wchar_t* tipToggle, const wchar_t* tipNext) {
  using namespace thumb;
  {
    std::lock_guard<std::mutex> guard(lock);
    if (!window) return E_NOT_VALID_STATE;
    playing = isPlaying != 0;
    enabled = isEnabled != 0;
    tips[0] = str(tipPrevious);
    tips[1] = str(tipToggle);
    tips[2] = str(tipNext);
  }
  post();
  return 0;
}

__declspec(dllexport) int __cdecl kv_thumb_cover(const uint32_t* argb, int width, int height) {
  using namespace thumb;
  {
    std::lock_guard<std::mutex> guard(lock);
    if (!window) return E_NOT_VALID_STATE;
    if (!argb || width <= 0 || height <= 0 || width > 1024 || height > 1024) {
      cover.clear();
      coverW = coverH = 0;
    } else {
      cover.assign(argb, argb + size_t(width) * height);
      coverW = width;
      coverH = height;
    }
  }
  post();
  return 0;
}

__declspec(dllexport) void __cdecl kv_thumb_shutdown() {
  using namespace thumb;
  HWND hwnd;
  {
    std::lock_guard<std::mutex> guard(lock);
    hwnd = window;
  }
  if (!hwnd) return;
  if (IsWindow(hwnd)) {
    DWORD_PTR ignored = 0;
    SendMessageTimeoutW(hwnd, msgCleanup, 0, 0, SMTO_ABORTIFHUNG | SMTO_BLOCK, 1000, &ignored);
  }
  std::lock_guard<std::mutex> guard(lock);
  if (IsWindow(hwnd) && reinterpret_cast<WNDPROC>(GetWindowLongPtrW(hwnd, GWLP_WNDPROC)) == &proc)
    SetWindowLongPtrW(hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(original));
  destroyIcons();
  window = nullptr;
  onClick = nullptr;
  cover.clear();
  coverW = coverH = 0;
}

}  // extern "C"
