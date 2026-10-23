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
enum class ControlLowLightBoostState : int32_t {
  ANDROID_CONTROL_LOW_LIGHT_BOOST_STATE_INACTIVE = 0,
  ANDROID_CONTROL_LOW_LIGHT_BOOST_STATE_ACTIVE = 1,
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
[[nodiscard]] static inline std::string toString(ControlLowLightBoostState val) {
  switch(val) {
  case ControlLowLightBoostState::ANDROID_CONTROL_LOW_LIGHT_BOOST_STATE_INACTIVE:
    return "ANDROID_CONTROL_LOW_LIGHT_BOOST_STATE_INACTIVE";
  case ControlLowLightBoostState::ANDROID_CONTROL_LOW_LIGHT_BOOST_STATE_ACTIVE:
    return "ANDROID_CONTROL_LOW_LIGHT_BOOST_STATE_ACTIVE";
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
constexpr inline std::array<aidl::android::hardware::camera::metadata::ControlLowLightBoostState, 2> enum_values<aidl::android::hardware::camera::metadata::ControlLowLightBoostState> = {
  aidl::android::hardware::camera::metadata::ControlLowLightBoostState::ANDROID_CONTROL_LOW_LIGHT_BOOST_STATE_INACTIVE,
  aidl::android::hardware::camera::metadata::ControlLowLightBoostState::ANDROID_CONTROL_LOW_LIGHT_BOOST_STATE_ACTIVE,
};
#pragma clang diagnostic pop
}  // namespace internal
}  // namespace ndk
