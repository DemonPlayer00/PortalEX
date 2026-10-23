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
enum class RequestAvailableColorSpaceProfilesMap : int64_t {
  ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_UNSPECIFIED = -1L,
  ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_SRGB = 0L,
  ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_DISPLAY_P3 = 7L,
  ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_BT2020_HLG = 16L,
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
[[nodiscard]] static inline std::string toString(RequestAvailableColorSpaceProfilesMap val) {
  switch(val) {
  case RequestAvailableColorSpaceProfilesMap::ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_UNSPECIFIED:
    return "ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_UNSPECIFIED";
  case RequestAvailableColorSpaceProfilesMap::ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_SRGB:
    return "ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_SRGB";
  case RequestAvailableColorSpaceProfilesMap::ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_DISPLAY_P3:
    return "ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_DISPLAY_P3";
  case RequestAvailableColorSpaceProfilesMap::ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_BT2020_HLG:
    return "ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_BT2020_HLG";
  default:
    return std::to_string(static_cast<int64_t>(val));
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
constexpr inline std::array<aidl::android::hardware::camera::metadata::RequestAvailableColorSpaceProfilesMap, 4> enum_values<aidl::android::hardware::camera::metadata::RequestAvailableColorSpaceProfilesMap> = {
  aidl::android::hardware::camera::metadata::RequestAvailableColorSpaceProfilesMap::ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_UNSPECIFIED,
  aidl::android::hardware::camera::metadata::RequestAvailableColorSpaceProfilesMap::ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_SRGB,
  aidl::android::hardware::camera::metadata::RequestAvailableColorSpaceProfilesMap::ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_DISPLAY_P3,
  aidl::android::hardware::camera::metadata::RequestAvailableColorSpaceProfilesMap::ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_BT2020_HLG,
};
#pragma clang diagnostic pop
}  // namespace internal
}  // namespace ndk
