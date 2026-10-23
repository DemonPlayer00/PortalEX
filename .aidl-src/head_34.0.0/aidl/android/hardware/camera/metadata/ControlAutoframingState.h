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
enum class ControlAutoframingState : int32_t {
  ANDROID_CONTROL_AUTOFRAMING_STATE_INACTIVE = 0,
  ANDROID_CONTROL_AUTOFRAMING_STATE_FRAMING = 1,
  ANDROID_CONTROL_AUTOFRAMING_STATE_CONVERGED = 2,
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
[[nodiscard]] static inline std::string toString(ControlAutoframingState val) {
  switch(val) {
  case ControlAutoframingState::ANDROID_CONTROL_AUTOFRAMING_STATE_INACTIVE:
    return "ANDROID_CONTROL_AUTOFRAMING_STATE_INACTIVE";
  case ControlAutoframingState::ANDROID_CONTROL_AUTOFRAMING_STATE_FRAMING:
    return "ANDROID_CONTROL_AUTOFRAMING_STATE_FRAMING";
  case ControlAutoframingState::ANDROID_CONTROL_AUTOFRAMING_STATE_CONVERGED:
    return "ANDROID_CONTROL_AUTOFRAMING_STATE_CONVERGED";
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
constexpr inline std::array<aidl::android::hardware::camera::metadata::ControlAutoframingState, 3> enum_values<aidl::android::hardware::camera::metadata::ControlAutoframingState> = {
  aidl::android::hardware::camera::metadata::ControlAutoframingState::ANDROID_CONTROL_AUTOFRAMING_STATE_INACTIVE,
  aidl::android::hardware::camera::metadata::ControlAutoframingState::ANDROID_CONTROL_AUTOFRAMING_STATE_FRAMING,
  aidl::android::hardware::camera::metadata::ControlAutoframingState::ANDROID_CONTROL_AUTOFRAMING_STATE_CONVERGED,
};
#pragma clang diagnostic pop
}  // namespace internal
}  // namespace ndk
