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

#include <windows.h>
#include <objbase.h>
#include <unknwn.h>
#include <systemmediatransportcontrolsinterop.h>
#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/Windows.Storage.h>
#include <winrt/Windows.Storage.Streams.h>
#include <chrono>
#include <mutex>
#include <string>

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

}  // extern "C"
