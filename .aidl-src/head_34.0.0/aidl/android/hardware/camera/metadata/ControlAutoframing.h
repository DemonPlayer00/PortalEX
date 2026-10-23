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
enum class ControlAutoframing : int32_t {
  ANDROID_CONTROL_AUTOFRAMING_OFF = 0,
  ANDROID_CONTROL_AUTOFRAMING_ON = 1,
  ANDROID_CONTROL_AUTOFRAMING_AUTO = 2,
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
[[nodiscard]] static inline std::string toString(ControlAutoframing val) {
  switch(val) {
  case ControlAutoframing::ANDROID_CONTROL_AUTOFRAMING_OFF:
    return "ANDROID_CONTROL_AUTOFRAMING_OFF";
  case ControlAutoframing::ANDROID_CONTROL_AUTOFRAMING_ON:
    return "ANDROID_CONTROL_AUTOFRAMING_ON";
  case ControlAutoframing::ANDROID_CONTROL_AUTOFRAMING_AUTO:
    return "ANDROID_CONTROL_AUTOFRAMING_AUTO";
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
constexpr inline std::array<aidl::android::hardware::camera::metadata::ControlAutoframing, 3> enum_values<aidl::android::hardware::camera::metadata::ControlAutoframing> = {
  aidl::android::hardware::camera::metadata::ControlAutoframing::ANDROID_CONTROL_AUTOFRAMING_OFF,
  aidl::android::hardware::camera::metadata::ControlAutoframing::ANDROID_CONTROL_AUTOFRAMING_ON,
  aidl::android::hardware::camera::metadata::ControlAutoframing::ANDROID_CONTROL_AUTOFRAMING_AUTO,
};
#pragma clang diagnostic pop
}  // namespace internal
}  // namespace ndk
