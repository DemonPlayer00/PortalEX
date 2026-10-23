#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>
#include <android/binder_enums.h>
#ifdef BINDER_STABILITY_SUPPORT
#include <android/binder_stability.h>
#endif  // BINDER_STABILITY_SUPPORT

namespace aidl {
namespace android {
namespace hardware {
namespace camera {
namespace metadata {
enum class ControlSettingsOverride : int32_t {
  ANDROID_CONTROL_SETTINGS_OVERRIDE_OFF = 0,
  ANDROID_CONTROL_SETTINGS_OVERRIDE_ZOOM = 1,
  ANDROID_CONTROL_SETTINGS_OVERRIDE_VENDOR_START = 16384,
};

}  // namespace metadata
}  // namespace camera
}  // namespace hardware
}  // namespace android
}  // namespace aidl
namespace aidl {
namespace android {
namespace hardware {
namespace camera {
namespace metadata {
[[nodiscard]] static inline std::string toString(ControlSettingsOverride val) {
  switch(val) {
  case ControlSettingsOverride::ANDROID_CONTROL_SETTINGS_OVERRIDE_OFF:
    return "ANDROID_CONTROL_SETTINGS_OVERRIDE_OFF";
  case ControlSettingsOverride::ANDROID_CONTROL_SETTINGS_OVERRIDE_ZOOM:
    return "ANDROID_CONTROL_SETTINGS_OVERRIDE_ZOOM";
  case ControlSettingsOverride::ANDROID_CONTROL_SETTINGS_OVERRIDE_VENDOR_START:
    return "ANDROID_CONTROL_SETTINGS_OVERRIDE_VENDOR_START";
  default:
    return std::to_string(static_cast<int32_t>(val));
  }
}
}  // namespace metadata
}  // namespace camera
}  // namespace hardware
}  // namespace android
}  // namespace aidl
namespace ndk {
namespace internal {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wc++17-extensions"
template <>
constexpr inline std::array<aidl::android::hardware::camera::metadata::ControlSettingsOverride, 3> enum_values<aidl::android::hardware::camera::metadata::ControlSettingsOverride> = {
  aidl::android::hardware::camera::metadata::ControlSettingsOverride::ANDROID_CONTROL_SETTINGS_OVERRIDE_OFF,
  aidl::android::hardware::camera::metadata::ControlSettingsOverride::ANDROID_CONTROL_SETTINGS_OVERRIDE_ZOOM,
  aidl::android::hardware::camera::metadata::ControlSettingsOverride::ANDROID_CONTROL_SETTINGS_OVERRIDE_VENDOR_START,
};
#pragma clang diagnostic pop
}  // namespace internal
}  // namespace ndk
