#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

// The header-only C++ wrapper definitions are emitted only in one translation
// unit; the exported C API lives in libdiscord_partner_sdk.so.
#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"

#define LOG_TAG "KetvoiceDiscord"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM *g_vm = nullptr;
jclass g_bridge = nullptr;

std::mutex g_mutex;
std::unique_ptr<discordpp::Client> g_client;
uint64_t g_app_id = 0;
std::atomic<int> g_status{0};

JNIEnv *Env(bool *attached) {
  JNIEnv *env = nullptr;
  *attached = false;
  if (g_vm == nullptr) return nullptr;
  if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
    JavaVMAttachArgs args{JNI_VERSION_1_6, nullptr, nullptr};
    if (g_vm->AttachCurrentThread(&env, &args) != JNI_OK) return nullptr;
    *attached = true;
  }
  return env;
}

void CallStatus(int status) {
  bool attached = false;
  JNIEnv *env = Env(&attached);
  if (env == nullptr || g_bridge == nullptr) return;
  jmethodID method = env->GetStaticMethodID(g_bridge, "onStatus", "(I)V");
  if (method != nullptr) env->CallStaticVoidMethod(g_bridge, method, static_cast<jint>(status));
  if (env->ExceptionCheck()) env->ExceptionClear();
  if (attached) g_vm->DetachCurrentThread();
}

void CallAuth(bool ok, const std::string &message, const std::string &refreshToken) {
  bool attached = false;
  JNIEnv *env = Env(&attached);
  if (env == nullptr || g_bridge == nullptr) return;
  jmethodID method = env->GetStaticMethodID(
      g_bridge, "onAuth", "(ZLjava/lang/String;Ljava/lang/String;)V");
  if (method != nullptr) {
    jstring jMessage = env->NewStringUTF(message.c_str());
    jstring jRefresh = env->NewStringUTF(refreshToken.c_str());
    env->CallStaticVoidMethod(g_bridge, method, static_cast<jboolean>(ok), jMessage, jRefresh);
    env->DeleteLocalRef(jMessage);
    env->DeleteLocalRef(jRefresh);
  }
  if (env->ExceptionCheck()) env->ExceptionClear();
  if (attached) g_vm->DetachCurrentThread();
}

void CallPresence(bool ok, const std::string &message) {
  bool attached = false;
  JNIEnv *env = Env(&attached);
  if (env == nullptr || g_bridge == nullptr) return;
  jmethodID method = env->GetStaticMethodID(
      g_bridge, "onPresence", "(ZLjava/lang/String;)V");
  if (method != nullptr) {
    jstring jMessage = env->NewStringUTF(message.c_str());
    env->CallStaticVoidMethod(g_bridge, method, static_cast<jboolean>(ok), jMessage);
    env->DeleteLocalRef(jMessage);
  }
  if (env->ExceptionCheck()) env->ExceptionClear();
  if (attached) g_vm->DetachCurrentThread();
}

void CallUser(jlong id, const std::string &username, const std::string &display) {
  bool attached = false;
  JNIEnv *env = Env(&attached);
  if (env == nullptr || g_bridge == nullptr) return;
  jmethodID method = env->GetStaticMethodID(
      g_bridge, "onUser", "(JLjava/lang/String;Ljava/lang/String;)V");
  if (method != nullptr) {
    jstring jUser = env->NewStringUTF(username.c_str());
    jstring jDisplay = env->NewStringUTF(display.c_str());
    env->CallStaticVoidMethod(g_bridge, method, id, jUser, jDisplay);
    env->DeleteLocalRef(jUser);
    env->DeleteLocalRef(jDisplay);
  }
  if (env->ExceptionCheck()) env->ExceptionClear();
  if (attached) g_vm->DetachCurrentThread();
}

std::string ToStd(JNIEnv *env, jstring value) {
  if (value == nullptr) return {};
  const char *chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) return {};
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

jstring ToJava(JNIEnv *env, const std::string &value) {
  return env->NewStringUTF(value.c_str());
}

discordpp::Client *ClientPtr() {
  std::lock_guard<std::mutex> lock(g_mutex);
  return g_client.get();
}

void StartTokenExchange(const std::string &code, const std::string &verifier,
                        const std::string &redirectUri) {
  discordpp::Client *client = ClientPtr();
  if (client == nullptr) {
    CallAuth(false, "SDK not ready", "");
    return;
  }
  client->GetToken(
      g_app_id, code, verifier, redirectUri,
      [](discordpp::ClientResult result, std::string accessToken, std::string refreshToken,
         discordpp::AuthorizationTokenType type, int32_t /*expiresIn*/, std::string /*scopes*/) {
        if (!result.Successful()) {
          CallAuth(false, result.Error(), "");
          return;
        }
        discordpp::Client *target = ClientPtr();
        if (target == nullptr) {
          CallAuth(false, "SDK not ready", "");
          return;
        }
        target->UpdateToken(
            type, accessToken, [refreshToken](discordpp::ClientResult update) {
              if (!update.Successful()) {
                CallAuth(false, update.Error(), "");
                return;
              }
              CallAuth(true, "", refreshToken);
            });
      });
}

void RequestAuthorization(const std::string &scopes) {
  discordpp::Client *client = ClientPtr();
  if (client == nullptr) {
    CallAuth(false, "SDK not ready", "");
    return;
  }
  auto verifier = std::make_shared<discordpp::AuthorizationCodeVerifier>(
      client->CreateAuthorizationCodeVerifier());
  discordpp::AuthorizationArgs args;
  args.SetClientId(g_app_id);
  args.SetScopes(scopes);
  args.SetCodeChallenge(verifier->Challenge());
  client->Authorize(
      std::move(args),
      [verifier](discordpp::ClientResult result, std::string code, std::string redirectUri) {
        if (!result.Successful()) {
          CallAuth(false, result.Error(), "");
          return;
        }
        StartTokenExchange(code, verifier->Verifier(), redirectUri);
      });
}

void ApplyPresence(discordpp::Client *client, const std::vector<std::string> &fields,
                   jlong startEpochMs) {
  discordpp::Activity activity;
  activity.SetType(discordpp::ActivityTypes::Playing);
  if (fields.size() > 0 && !fields[0].empty()) activity.SetDetails(fields[0]);
  if (fields.size() > 1 && !fields[1].empty()) activity.SetState(fields[1]);

  discordpp::ActivityAssets assets;
  bool hasAssets = false;
  if (fields.size() > 2 && !fields[2].empty()) {
    assets.SetLargeImage(fields[2]);
    hasAssets = true;
  }
  if (fields.size() > 3 && !fields[3].empty()) {
    assets.SetLargeText(fields[3]);
    hasAssets = true;
  }
  if (fields.size() > 4 && !fields[4].empty()) {
    assets.SetSmallImage(fields[4]);
    hasAssets = true;
  }
  if (fields.size() > 5 && !fields[5].empty()) {
    assets.SetSmallText(fields[5]);
    hasAssets = true;
  }
  if (hasAssets) activity.SetAssets(assets);

  if (startEpochMs > 0) {
    discordpp::ActivityTimestamps timestamps;
    timestamps.SetStart(static_cast<uint64_t>(startEpochMs));
    activity.SetTimestamps(timestamps);
  }

  if (fields.size() > 8 && !fields[6].empty() && !fields[7].empty()) {
    discordpp::ActivityButton button;
    button.SetLabel(fields[6]);
    button.SetUrl(fields[7]);
    activity.AddButton(button);
  }
  if (fields.size() > 9 && !fields[8].empty() && !fields[9].empty()) {
    discordpp::ActivityButton button;
    button.SetLabel(fields[8]);
    button.SetUrl(fields[9]);
    activity.AddButton(button);
  }

  client->UpdateRichPresence(
      std::move(activity), [](discordpp::ClientResult result) {
        if (!result.Successful()) {
          CallPresence(false, result.Error());
        } else {
          CallPresence(true, "");
        }
      });
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
  g_vm = vm;
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ketvoice_app_discord_NativeDiscord_nativeCreate(JNIEnv *env, jobject thiz,
                                                        jlong appId) {
  // Cache the bridge class from a call that runs on the app class loader; native
  // callback threads have no context loader and cannot FindClass it themselves.
  if (g_bridge == nullptr && thiz != nullptr) {
    jclass local = env->GetObjectClass(thiz);
    if (local != nullptr) {
      g_bridge = static_cast<jclass>(env->NewGlobalRef(local));
      env->DeleteLocalRef(local);
    }
  }
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_client != nullptr) return JNI_TRUE;
  g_app_id = static_cast<uint64_t>(appId);
  try {
    auto client = std::make_unique<discordpp::Client>();
    client->SetApplicationId(g_app_id);
    client->SetStatusChangedCallback(
        [](discordpp::Client::Status status, discordpp::Client::Error /*error*/,
           int32_t /*errorDetail*/) {
          g_status.store(static_cast<int>(status));
          CallStatus(g_status.load());
        });
    g_client = std::move(client);
    return JNI_TRUE;
  } catch (...) {
    LOGE("Discord client creation failed");
    return JNI_FALSE;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_ketvoice_app_discord_NativeDiscord_nativeDestroy(JNIEnv * /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_client == nullptr) return;
  g_client->ClearRichPresence();
  g_client->Disconnect();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ketvoice_app_discord_NativeDiscord_nativeAuthorize(JNIEnv *env, jobject /*thiz*/,
                                                           jstring scopes) {
  if (ClientPtr() == nullptr) return JNI_FALSE;
  std::string requested = ToStd(env, scopes);
  if (requested.empty()) requested = discordpp::Client::GetDefaultPresenceScopes();
  RequestAuthorization(requested);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ketvoice_app_discord_NativeDiscord_nativeRestore(JNIEnv *env, jobject /*thiz*/,
                                                         jstring refreshToken) {
  if (ClientPtr() == nullptr) return JNI_FALSE;
  const std::string token = ToStd(env, refreshToken);
  if (token.empty()) return JNI_FALSE;
  ClientPtr()->RefreshToken(
      g_app_id, token,
      [](discordpp::ClientResult result, std::string accessToken, std::string refreshToken,
         discordpp::AuthorizationTokenType type, int32_t /*expiresIn*/, std::string /*scopes*/) {
        if (!result.Successful()) {
          CallAuth(false, result.Error(), "");
          return;
        }
        discordpp::Client *target = ClientPtr();
        if (target == nullptr) {
          CallAuth(false, "SDK not ready", "");
          return;
        }
        target->UpdateToken(
            type, accessToken, [refreshToken](discordpp::ClientResult update) {
              if (!update.Successful()) {
                CallAuth(false, update.Error(), "");
              } else {
                CallAuth(true, "", refreshToken);
              }
            });
      });
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ketvoice_app_discord_NativeDiscord_nativeConnect(JNIEnv * /*env*/, jobject /*thiz*/) {
  discordpp::Client *client = ClientPtr();
  if (client != nullptr) client->Connect();
}

extern "C" JNIEXPORT void JNICALL
Java_com_ketvoice_app_discord_NativeDiscord_nativeDisconnect(JNIEnv * /*env*/, jobject /*thiz*/) {
  discordpp::Client *client = ClientPtr();
  if (client != nullptr) {
    client->ClearRichPresence();
    client->Disconnect();
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ketvoice_app_discord_NativeDiscord_nativeSetPresence(JNIEnv *env, jobject /*thiz*/,
                                                             jobjectArray values,
                                                             jlong startEpochMs) {
  discordpp::Client *client = ClientPtr();
  if (client == nullptr || g_status.load() != static_cast<int>(discordpp::Client::Status::Ready)) {
    return JNI_FALSE;
  }
  std::vector<std::string> fields;
  const jsize count = values == nullptr ? 0 : env->GetArrayLength(values);
  fields.reserve(static_cast<size_t>(count));
  for (jsize i = 0; i < count; ++i) {
    auto entry = static_cast<jstring>(env->GetObjectArrayElement(values, i));
    fields.push_back(ToStd(env, entry));
    if (entry != nullptr) env->DeleteLocalRef(entry);
  }
  ApplyPresence(client, fields, startEpochMs);
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ketvoice_app_discord_NativeDiscord_nativeClearPresence(JNIEnv * /*env*/,
                                                               jobject /*thiz*/) {
  discordpp::Client *client = ClientPtr();
  if (client != nullptr) client->ClearRichPresence();
}

extern "C" JNIEXPORT void JNICALL
Java_com_ketvoice_app_discord_NativeDiscord_nativeFetchUser(JNIEnv * /*env*/, jobject /*thiz*/) {
  discordpp::Client *client = ClientPtr();
  if (client == nullptr) return;
  auto user = client->GetCurrentUserV2();
  if (!user.has_value()) return;
  const auto id = static_cast<jlong>(user->Id());
  CallUser(id, user->Username(), user->DisplayName());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ketvoice_app_discord_NativeDiscord_nativeIsAuthenticated(JNIEnv * /*env*/,
                                                                 jobject /*thiz*/) {
  discordpp::Client *client = ClientPtr();
  return client != nullptr && client->IsAuthenticated() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ketvoice_app_discord_NativeDiscord_nativeProbe(JNIEnv *env, jobject /*thiz*/) {
  const std::string scopes = discordpp::Client::GetDefaultPresenceScopes();
  const std::string value = "discord-sdk:" + scopes;
  return ToJava(env, value);
}
