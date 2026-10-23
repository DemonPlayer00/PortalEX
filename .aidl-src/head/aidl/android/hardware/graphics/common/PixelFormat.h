/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: /home/DemonPlayer/Android/Sdk/build-tools/35.0.0/aidl --lang=ndk --structured --stability=vintf --version 2 -I root -o gen -h head root/android/hardware/camera/provider/ICameraProviderCallback.aidl root/android/hardware/camera/provider/ConcurrentCameraIdCombination.aidl root/android/hardware/camera/provider/ICameraProvider.aidl root/android/hardware/camera/provider/CameraIdAndStreamCombination.aidl root/android/hardware/camera/device/BufferCache.aidl root/android/hardware/camera/device/BufferRequestStatus.aidl root/android/hardware/camera/device/BufferRequest.aidl root/android/hardware/camera/device/CameraBlob.aidl root/android/hardware/camera/device/CameraOfflineSessionInfo.aidl root/android/hardware/camera/device/CameraBlobId.aidl root/android/hardware/camera/device/BufferStatus.aidl root/android/hardware/camera/device/CaptureRequest.aidl root/android/hardware/camera/device/CameraMetadata.aidl root/android/hardware/camera/device/CaptureResult.aidl root/android/hardware/camera/device/ErrorMsg.aidl root/android/hardware/camera/device/ICameraDeviceCallback.aidl root/android/hardware/camera/device/HalStream.aidl root/android/hardware/camera/device/ShutterMsg.aidl root/android/hardware/camera/device/ConfigureStreamsRet.aidl root/android/hardware/camera/device/ICameraDeviceSession.aidl root/android/hardware/camera/device/ICameraDevice.aidl root/android/hardware/camera/device/RequestTemplate.aidl root/android/hardware/camera/device/ErrorCode.aidl root/android/hardware/camera/device/PhysicalCameraSetting.aidl root/android/hardware/camera/device/ICameraOfflineSession.aidl root/android/hardware/camera/device/ICameraInjectionSession.aidl root/android/hardware/camera/device/NotifyMsg.aidl root/android/hardware/camera/device/OfflineStream.aidl root/android/hardware/camera/device/OfflineRequest.aidl root/android/hardware/camera/device/PhysicalCameraMetadata.aidl root/android/hardware/camera/device/Stream.aidl root/android/hardware/camera/device/StreamBufferRequestError.aidl root/android/hardware/camera/device/StreamBuffer.aidl root/android/hardware/camera/device/StreamBufferRet.aidl root/android/hardware/camera/device/StreamBuffersVal.aidl root/android/hardware/camera/device/StreamConfigurationMode.aidl root/android/hardware/camera/device/StreamConfiguration.aidl root/android/hardware/camera/device/StreamRotation.aidl root/android/hardware/camera/device/StreamType.aidl root/android/hardware/camera/common/VendorTagSection.aidl root/android/hardware/camera/common/TagBoundaryId.aidl root/android/hardware/camera/common/VendorTag.aidl root/android/hardware/camera/common/TorchModeStatus.aidl root/android/hardware/camera/common/Status.aidl root/android/hardware/camera/common/CameraResourceCost.aidl root/android/hardware/camera/common/CameraMetadataType.aidl root/android/hardware/camera/common/CameraDeviceStatus.aidl root/android/hardware/camera/metadata/ScalerAvailableFormats.aidl root/android/hardware/camera/metadata/LensPoseReference.aidl root/android/hardware/camera/metadata/ControlAeMode.aidl root/android/hardware/camera/metadata/StatisticsOisDataMode.aidl root/android/hardware/camera/metadata/ControlAfMode.aidl root/android/hardware/camera/metadata/AutomotiveLocation.aidl root/android/hardware/camera/metadata/StatisticsSceneFlicker.aidl root/android/hardware/camera/metadata/LedAvailableLeds.aidl root/android/hardware/camera/metadata/LensState.aidl root/android/hardware/camera/metadata/AutomotiveLensFacing.aidl root/android/hardware/camera/metadata/BlackLevelLock.aidl root/android/hardware/camera/metadata/CameraMetadataSection.aidl root/android/hardware/camera/metadata/CameraMetadataSectionStart.aidl root/android/hardware/camera/metadata/CameraMetadataTag.aidl root/android/hardware/camera/metadata/ColorCorrectionAberrationMode.aidl root/android/hardware/camera/metadata/ColorCorrectionMode.aidl root/android/hardware/camera/metadata/ControlAeAntibandingMode.aidl root/android/hardware/camera/metadata/ControlSceneMode.aidl root/android/hardware/camera/metadata/ControlAeLock.aidl root/android/hardware/camera/metadata/ControlAeLockAvailable.aidl root/android/hardware/camera/metadata/ControlAePrecaptureTrigger.aidl root/android/hardware/camera/metadata/ControlAeState.aidl root/android/hardware/camera/metadata/QuirksPartialResult.aidl root/android/hardware/camera/metadata/ControlAfSceneChange.aidl root/android/hardware/camera/metadata/ControlAfState.aidl root/android/hardware/camera/metadata/ControlAfTrigger.aidl root/android/hardware/camera/metadata/ControlAutoframing.aidl root/android/hardware/camera/metadata/ControlAutoframingAvailable.aidl root/android/hardware/camera/metadata/ControlAutoframingState.aidl root/android/hardware/camera/metadata/ControlAwbLock.aidl root/android/hardware/camera/metadata/ControlAwbLockAvailable.aidl root/android/hardware/camera/metadata/ControlAwbMode.aidl root/android/hardware/camera/metadata/ControlAwbState.aidl root/android/hardware/camera/metadata/ControlCaptureIntent.aidl root/android/hardware/camera/metadata/ControlEffectMode.aidl root/android/hardware/camera/metadata/ControlEnableZsl.aidl root/android/hardware/camera/metadata/ControlExtendedSceneMode.aidl root/android/hardware/camera/metadata/ControlLowLightBoostState.aidl root/android/hardware/camera/metadata/ControlMode.aidl root/android/hardware/camera/metadata/ControlSettingsOverride.aidl root/android/hardware/camera/metadata/ControlVideoStabilizationMode.aidl root/android/hardware/camera/metadata/DemosaicMode.aidl root/android/hardware/camera/metadata/DepthAvailableDepthStreamConfigurations.aidl root/android/hardware/camera/metadata/DepthAvailableDepthStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/DepthAvailableDynamicDepthStreamConfigurations.aidl root/android/hardware/camera/metadata/DepthAvailableDynamicDepthStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/DepthDepthIsExclusive.aidl root/android/hardware/camera/metadata/DistortionCorrectionMode.aidl root/android/hardware/camera/metadata/EdgeMode.aidl root/android/hardware/camera/metadata/FlashInfoAvailable.aidl root/android/hardware/camera/metadata/FlashMode.aidl root/android/hardware/camera/metadata/FlashState.aidl root/android/hardware/camera/metadata/HeicAvailableHeicStreamConfigurations.aidl root/android/hardware/camera/metadata/HeicAvailableHeicStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/HeicInfoSupported.aidl root/android/hardware/camera/metadata/HotPixelMode.aidl root/android/hardware/camera/metadata/InfoSupportedBufferManagementVersion.aidl root/android/hardware/camera/metadata/InfoSupportedHardwareLevel.aidl root/android/hardware/camera/metadata/JpegrAvailableJpegRStreamConfigurations.aidl root/android/hardware/camera/metadata/JpegrAvailableJpegRStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/RequestAvailableDynamicRangeProfilesMap.aidl root/android/hardware/camera/metadata/ScalerAvailableStreamConfigurations.aidl root/android/hardware/camera/metadata/LedTransmit.aidl root/android/hardware/camera/metadata/LensFacing.aidl root/android/hardware/camera/metadata/LensInfoFocusDistanceCalibration.aidl root/android/hardware/camera/metadata/LensOpticalStabilizationMode.aidl root/android/hardware/camera/metadata/SensorInfoLensShadingApplied.aidl root/android/hardware/camera/metadata/SensorInfoColorFilterArrangement.aidl root/android/hardware/camera/metadata/ScalerRotateAndCrop.aidl root/android/hardware/camera/metadata/SensorInfoTimestampSource.aidl root/android/hardware/camera/metadata/LogicalMultiCameraSensorSyncType.aidl root/android/hardware/camera/metadata/NoiseReductionMode.aidl root/android/hardware/camera/metadata/ScalerAvailableStreamUseCases.aidl root/android/hardware/camera/metadata/RequestAvailableCapabilities.aidl root/android/hardware/camera/metadata/RequestAvailableColorSpaceProfilesMap.aidl root/android/hardware/camera/metadata/ScalerAvailableRecommendedStreamConfigurations.aidl root/android/hardware/camera/metadata/RequestMetadataMode.aidl root/android/hardware/camera/metadata/RequestType.aidl root/android/hardware/camera/metadata/ScalerAvailableStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/ScalerCroppingType.aidl root/android/hardware/camera/metadata/ScalerMultiResolutionStreamSupported.aidl root/android/hardware/camera/metadata/ScalerPhysicalCameraMultiResolutionStreamConfigurations.aidl root/android/hardware/camera/metadata/SensorPixelMode.aidl root/android/hardware/camera/metadata/StatisticsHistogramMode.aidl root/android/hardware/camera/metadata/ShadingMode.aidl root/android/hardware/camera/metadata/StatisticsFaceDetectMode.aidl root/android/hardware/camera/metadata/StatisticsHotPixelMapMode.aidl root/android/hardware/camera/metadata/SensorRawBinningFactorUsed.aidl root/android/hardware/camera/metadata/SensorReadoutTimestamp.aidl root/android/hardware/camera/metadata/SensorReferenceIlluminant1.aidl root/android/hardware/camera/metadata/SensorTestPatternMode.aidl root/android/hardware/camera/metadata/StatisticsLensShadingMapMode.aidl root/android/hardware/camera/metadata/TonemapPresetCurve.aidl root/android/hardware/camera/metadata/StatisticsSharpnessMapMode.aidl root/android/hardware/camera/metadata/SyncFrameNumber.aidl root/android/hardware/camera/metadata/SyncMaxLatency.aidl root/android/hardware/camera/metadata/TonemapMode.aidl root/android/hardware/common/Ashmem.aidl root/android/hardware/common/MappableFile.aidl root/android/hardware/common/NativeHandle.aidl root/android/hardware/common/fmq/GrantorDescriptor.aidl root/android/hardware/common/fmq/MQDescriptor.aidl root/android/hardware/common/fmq/SynchronizedReadWrite.aidl root/android/hardware/common/fmq/UnsynchronizedWrite.aidl root/android/hardware/graphics/common/Dataspace.aidl root/android/hardware/graphics/common/Compression.aidl root/android/hardware/graphics/common/ColorTransform.aidl root/android/hardware/graphics/common/AlphaInterpretation.aidl root/android/hardware/graphics/common/ChromaSiting.aidl root/android/hardware/graphics/common/BlendMode.aidl root/android/hardware/graphics/common/BufferUsage.aidl root/android/hardware/graphics/common/Cta861_3.aidl root/android/hardware/graphics/common/Rect.aidl root/android/hardware/graphics/common/DisplayDecorationSupport.aidl root/android/hardware/graphics/common/DisplayHotplugEvent.aidl root/android/hardware/graphics/common/ExtendableType.aidl root/android/hardware/graphics/common/FRect.aidl root/android/hardware/graphics/common/PlaneLayoutComponentType.aidl root/android/hardware/graphics/common/HardwareBuffer.aidl root/android/hardware/graphics/common/HardwareBufferDescription.aidl root/android/hardware/graphics/common/Hdr.aidl root/android/hardware/graphics/common/HdrConversionCapability.aidl root/android/hardware/graphics/common/PlaneLayoutComponent.aidl root/android/hardware/graphics/common/PlaneLayout.aidl root/android/hardware/graphics/common/HdrConversionStrategy.aidl root/android/hardware/graphics/common/Interlaced.aidl root/android/hardware/graphics/common/PixelFormat.aidl root/android/hardware/graphics/common/Point.aidl root/android/hardware/graphics/common/Smpte2086.aidl root/android/hardware/graphics/common/StandardMetadataType.aidl root/android/hardware/graphics/common/Transform.aidl root/android/hardware/graphics/common/XyColor.aidl
 */
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
namespace graphics {
namespace common {
enum class PixelFormat : int32_t {
  UNSPECIFIED = 0,
  RGBA_8888 = 1,
  RGBX_8888 = 2,
  RGB_888 = 3,
  RGB_565 = 4,
  BGRA_8888 = 5,
  YCBCR_422_SP = 16,
  YCRCB_420_SP = 17,
  YCBCR_422_I = 20,
  RGBA_FP16 = 22,
  RAW16 = 32,
  BLOB = 33,
  IMPLEMENTATION_DEFINED = 34,
  YCBCR_420_888 = 35,
  RAW_OPAQUE = 36,
  RAW10 = 37,
  RAW12 = 38,
  RGBA_1010102 = 43,
  Y8 = 538982489,
  Y16 = 540422489,
  YV12 = 842094169,
  DEPTH_16 = 48,
  DEPTH_24 = 49,
  DEPTH_24_STENCIL_8 = 50,
  DEPTH_32F = 51,
  DEPTH_32F_STENCIL_8 = 52,
  STENCIL_8 = 53,
  YCBCR_P010 = 54,
  HSV_888 = 55,
  R_8 = 56,
  R_16_UINT = 57,
  RG_1616_UINT = 58,
  RGBA_10101010 = 59,
};

}  // namespace common
}  // namespace graphics
}  // namespace hardware
}  // namespace android
}  // namespace aidl
namespace aidl {
namespace android {
namespace hardware {
namespace graphics {
namespace common {
[[nodiscard]] static inline std::string toString(PixelFormat val) {
  switch(val) {
  case PixelFormat::UNSPECIFIED:
    return "UNSPECIFIED";
  case PixelFormat::RGBA_8888:
    return "RGBA_8888";
  case PixelFormat::RGBX_8888:
    return "RGBX_8888";
  case PixelFormat::RGB_888:
    return "RGB_888";
  case PixelFormat::RGB_565:
    return "RGB_565";
  case PixelFormat::BGRA_8888:
    return "BGRA_8888";
  case PixelFormat::YCBCR_422_SP:
    return "YCBCR_422_SP";
  case PixelFormat::YCRCB_420_SP:
    return "YCRCB_420_SP";
  case PixelFormat::YCBCR_422_I:
    return "YCBCR_422_I";
  case PixelFormat::RGBA_FP16:
    return "RGBA_FP16";
  case PixelFormat::RAW16:
    return "RAW16";
  case PixelFormat::BLOB:
    return "BLOB";
  case PixelFormat::IMPLEMENTATION_DEFINED:
    return "IMPLEMENTATION_DEFINED";
  case PixelFormat::YCBCR_420_888:
    return "YCBCR_420_888";
  case PixelFormat::RAW_OPAQUE:
    return "RAW_OPAQUE";
  case PixelFormat::RAW10:
    return "RAW10";
  case PixelFormat::RAW12:
    return "RAW12";
  case PixelFormat::RGBA_1010102:
    return "RGBA_1010102";
  case PixelFormat::Y8:
    return "Y8";
  case PixelFormat::Y16:
    return "Y16";
  case PixelFormat::YV12:
    return "YV12";
  case PixelFormat::DEPTH_16:
    return "DEPTH_16";
  case PixelFormat::DEPTH_24:
    return "DEPTH_24";
  case PixelFormat::DEPTH_24_STENCIL_8:
    return "DEPTH_24_STENCIL_8";
  case PixelFormat::DEPTH_32F:
    return "DEPTH_32F";
  case PixelFormat::DEPTH_32F_STENCIL_8:
    return "DEPTH_32F_STENCIL_8";
  case PixelFormat::STENCIL_8:
    return "STENCIL_8";
  case PixelFormat::YCBCR_P010:
    return "YCBCR_P010";
  case PixelFormat::HSV_888:
    return "HSV_888";
  case PixelFormat::R_8:
    return "R_8";
  case PixelFormat::R_16_UINT:
    return "R_16_UINT";
  case PixelFormat::RG_1616_UINT:
    return "RG_1616_UINT";
  case PixelFormat::RGBA_10101010:
    return "RGBA_10101010";
  default:
    return std::to_string(static_cast<int32_t>(val));
  }
}
}  // namespace common
}  // namespace graphics
}  // namespace hardware
}  // namespace android
}  // namespace aidl
namespace ndk {
namespace internal {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wc++17-extensions"
template <>
constexpr inline std::array<aidl::android::hardware::graphics::common::PixelFormat, 33> enum_values<aidl::android::hardware::graphics::common::PixelFormat> = {
  aidl::android::hardware::graphics::common::PixelFormat::UNSPECIFIED,
  aidl::android::hardware::graphics::common::PixelFormat::RGBA_8888,
  aidl::android::hardware::graphics::common::PixelFormat::RGBX_8888,
  aidl::android::hardware::graphics::common::PixelFormat::RGB_888,
  aidl::android::hardware::graphics::common::PixelFormat::RGB_565,
  aidl::android::hardware::graphics::common::PixelFormat::BGRA_8888,
  aidl::android::hardware::graphics::common::PixelFormat::YCBCR_422_SP,
  aidl::android::hardware::graphics::common::PixelFormat::YCRCB_420_SP,
  aidl::android::hardware::graphics::common::PixelFormat::YCBCR_422_I,
  aidl::android::hardware::graphics::common::PixelFormat::RGBA_FP16,
  aidl::android::hardware::graphics::common::PixelFormat::RAW16,
  aidl::android::hardware::graphics::common::PixelFormat::BLOB,
  aidl::android::hardware::graphics::common::PixelFormat::IMPLEMENTATION_DEFINED,
  aidl::android::hardware::graphics::common::PixelFormat::YCBCR_420_888,
  aidl::android::hardware::graphics::common::PixelFormat::RAW_OPAQUE,
  aidl::android::hardware::graphics::common::PixelFormat::RAW10,
  aidl::android::hardware::graphics::common::PixelFormat::RAW12,
  aidl::android::hardware::graphics::common::PixelFormat::RGBA_1010102,
  aidl::android::hardware::graphics::common::PixelFormat::Y8,
  aidl::android::hardware::graphics::common::PixelFormat::Y16,
  aidl::android::hardware::graphics::common::PixelFormat::YV12,
  aidl::android::hardware::graphics::common::PixelFormat::DEPTH_16,
  aidl::android::hardware::graphics::common::PixelFormat::DEPTH_24,
  aidl::android::hardware::graphics::common::PixelFormat::DEPTH_24_STENCIL_8,
  aidl::android::hardware::graphics::common::PixelFormat::DEPTH_32F,
  aidl::android::hardware::graphics::common::PixelFormat::DEPTH_32F_STENCIL_8,
  aidl::android::hardware::graphics::common::PixelFormat::STENCIL_8,
  aidl::android::hardware::graphics::common::PixelFormat::YCBCR_P010,
  aidl::android::hardware::graphics::common::PixelFormat::HSV_888,
  aidl::android::hardware::graphics::common::PixelFormat::R_8,
  aidl::android::hardware::graphics::common::PixelFormat::R_16_UINT,
  aidl::android::hardware::graphics::common::PixelFormat::RG_1616_UINT,
  aidl::android::hardware::graphics::common::PixelFormat::RGBA_10101010,
};
#pragma clang diagnostic pop
}  // namespace internal
}  // namespace ndk
