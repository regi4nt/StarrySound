// JNI bridge: Kotlin (DiscordNative) -> Discord Social SDK rich presence. Built only when the SDK
// is present in ./sdk (see README.md). Rich presence works without signing in on Android as long
// as the Discord app is installed and signed in; only an Application ID is needed.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include "discordpp.h"

#define TAG "StarryDiscord"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {
std::mutex gMutex;                              // the SDK is not called from two threads at once
std::shared_ptr<discordpp::Client> gClient;
std::atomic<bool> gRun{false};
std::thread gPump;

std::string str(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

void startPump() {
    if (gRun.exchange(true)) return;
    gPump = std::thread([] {
        while (gRun) {
            {
                std::lock_guard<std::mutex> lock(gMutex);
                discordpp::RunCallbacks();
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
    });
    gPump.detach();
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fxsoundlite_app_DiscordNative_nativeInit(JNIEnv *, jclass, jlong appId) {
    try {
        std::lock_guard<std::mutex> lock(gMutex);
        if (!gClient) gClient = std::make_shared<discordpp::Client>();
        gClient->SetApplicationId(static_cast<uint64_t>(appId));
    } catch (...) {
        LOGW("init failed");
        return JNI_FALSE;
    }
    startPump();
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fxsoundlite_app_DiscordNative_nativeUpdate(JNIEnv *env, jclass, jstring name, jstring title,
                                                    jstring artist, jlong startSec, jlong endSec,
                                                    jstring largeImage, jstring largeText) {
    const std::string n = str(env, name), t = str(env, title), a = str(env, artist);
    const std::string img = str(env, largeImage), imgText = str(env, largeText);
    try {
        std::lock_guard<std::mutex> lock(gMutex);
        if (!gClient) return;

        discordpp::Activity activity;
        activity.SetType(discordpp::ActivityTypes::Listening);  // "Listening to <name>"
        activity.SetName(n);
        activity.SetDetails(t);                                 // song title
        if (!a.empty()) activity.SetState(a);                   // artist

        discordpp::ActivityTimestamps ts;                       // start + end = progress bar
        ts.SetStart(static_cast<uint64_t>(startSec));
        if (endSec > 0) ts.SetEnd(static_cast<uint64_t>(endSec));
        activity.SetTimestamps(ts);

        if (!img.empty()) {
            discordpp::ActivityAssets assets;
            assets.SetLargeImage(img);
            assets.SetLargeText(imgText);
            activity.SetAssets(assets);
        }

        gClient->UpdateRichPresence(activity, [](discordpp::ClientResult result) {
            if (!result.Successful()) LOGW("UpdateRichPresence failed: %s", result.Error().c_str());
        });
    } catch (...) {
        LOGW("update threw");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_fxsoundlite_app_DiscordNative_nativeClear(JNIEnv *, jclass) {
    try {
        std::lock_guard<std::mutex> lock(gMutex);
        if (gClient) gClient->ClearRichPresence();
    } catch (...) {
        LOGW("clear threw");
    }
}
