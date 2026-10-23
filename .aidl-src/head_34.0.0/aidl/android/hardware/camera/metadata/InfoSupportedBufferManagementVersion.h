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
enum class InfoSupportedBufferManagementVersion : int32_t {
  ANDROID_INFO_SUPPORTED_BUFFER_MANAGEMENT_VERSION_AIDL_DEVICE = 0,
  ANDROID_INFO_SUPPORTED_BUFFER_MANAGEMENT_VERSION_SESSION_CONFIGURABLE = 1,
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
[[nodiscard]] static inline std::string toString(InfoSupportedBufferManagementVersion val) {
  switch(val) {
  case InfoSupportedBufferManagementVersion::ANDROID_INFO_SUPPORTED_BUFFER_MANAGEMENT_VERSION_AIDL_DEVICE:
    return "ANDROID_INFO_SUPPORTED_BUFFER_MANAGEMENT_VERSION_AIDL_DEVICE";
  case InfoSupportedBufferManagementVersion::ANDROID_INFO_SUPPORTED_BUFFER_MANAGEMENT_VERSION_SESSION_CONFIGURABLE:
    return "ANDROID_INFO_SUPPORTED_BUFFER_MANAGEMENT_VERSION_SESSION_CONFIGURABLE";
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
constexpr inline std::array<aidl::android::hardware::camera::metadata::InfoSupportedBufferManagementVersion, 2> enum_values<aidl::android::hardware::camera::metadata::InfoSupportedBufferManagementVersion> = {
  aidl::android::hardware::camera::metadata::InfoSupportedBufferManagementVersion::ANDROID_INFO_SUPPORTED_BUFFER_MANAGEMENT_VERSION_AIDL_DEVICE,
  aidl::android::hardware::camera::metadata::InfoSupportedBufferManagementVersion::ANDROID_INFO_SUPPORTED_BUFFER_MANAGEMENT_VERSION_SESSION_CONFIGURABLE,
};
#pragma clang diagnostic pop
}  // namespace internal
}  // namespace ndk
