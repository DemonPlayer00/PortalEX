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
enum class SensorReadoutTimestamp : int32_t {
  ANDROID_SENSOR_READOUT_TIMESTAMP_NOT_SUPPORTED = 0,
  ANDROID_SENSOR_READOUT_TIMESTAMP_HARDWARE = 1,
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
[[nodiscard]] static inline std::string toString(SensorReadoutTimestamp val) {
  switch(val) {
  case SensorReadoutTimestamp::ANDROID_SENSOR_READOUT_TIMESTAMP_NOT_SUPPORTED:
    return "ANDROID_SENSOR_READOUT_TIMESTAMP_NOT_SUPPORTED";
  case SensorReadoutTimestamp::ANDROID_SENSOR_READOUT_TIMESTAMP_HARDWARE:
    return "ANDROID_SENSOR_READOUT_TIMESTAMP_HARDWARE";
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
constexpr inline std::array<aidl::android::hardware::camera::metadata::SensorReadoutTimestamp, 2> enum_values<aidl::android::hardware::camera::metadata::SensorReadoutTimestamp> = {
  aidl::android::hardware::camera::metadata::SensorReadoutTimestamp::ANDROID_SENSOR_READOUT_TIMESTAMP_NOT_SUPPORTED,
  aidl::android::hardware::camera::metadata::SensorReadoutTimestamp::ANDROID_SENSOR_READOUT_TIMESTAMP_HARDWARE,
};
#pragma clang diagnostic pop
}  // namespace internal
}  // namespace ndk
