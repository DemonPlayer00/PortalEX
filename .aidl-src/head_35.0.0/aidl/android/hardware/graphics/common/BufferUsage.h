/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: /home/DemonPlayer/Android/Sdk/build-tools/35.0.0/aidl --lang=ndk --structured --stability=vintf --version=2 -I root -o gen_35.0.0 -h head_35.0.0 root/android/hardware/camera/provider/ICameraProviderCallback.aidl root/android/hardware/camera/provider/ConcurrentCameraIdCombination.aidl root/android/hardware/camera/provider/ICameraProvider.aidl root/android/hardware/camera/provider/CameraIdAndStreamCombination.aidl root/android/hardware/camera/device/BufferCache.aidl root/android/hardware/camera/device/BufferRequestStatus.aidl root/android/hardware/camera/device/BufferRequest.aidl root/android/hardware/camera/device/CameraBlob.aidl root/android/hardware/camera/device/CameraOfflineSessionInfo.aidl root/android/hardware/camera/device/CameraBlobId.aidl root/android/hardware/camera/device/BufferStatus.aidl root/android/hardware/camera/device/CaptureRequest.aidl root/android/hardware/camera/device/CameraMetadata.aidl root/android/hardware/camera/device/CaptureResult.aidl root/android/hardware/camera/device/ErrorMsg.aidl root/android/hardware/camera/device/ICameraDeviceCallback.aidl root/android/hardware/camera/device/HalStream.aidl root/android/hardware/camera/device/ShutterMsg.aidl root/android/hardware/camera/device/ConfigureStreamsRet.aidl root/android/hardware/camera/device/ICameraDeviceSession.aidl root/android/hardware/camera/device/ICameraDevice.aidl root/android/hardware/camera/device/RequestTemplate.aidl root/android/hardware/camera/device/ErrorCode.aidl root/android/hardware/camera/device/PhysicalCameraSetting.aidl root/android/hardware/camera/device/ICameraOfflineSession.aidl root/android/hardware/camera/device/ICameraInjectionSession.aidl root/android/hardware/camera/device/NotifyMsg.aidl root/android/hardware/camera/device/OfflineStream.aidl root/android/hardware/camera/device/OfflineRequest.aidl root/android/hardware/camera/device/PhysicalCameraMetadata.aidl root/android/hardware/camera/device/Stream.aidl root/android/hardware/camera/device/StreamBufferRequestError.aidl root/android/hardware/camera/device/StreamBuffer.aidl root/android/hardware/camera/device/StreamBufferRet.aidl root/android/hardware/camera/device/StreamBuffersVal.aidl root/android/hardware/camera/device/StreamConfigurationMode.aidl root/android/hardware/camera/device/StreamConfiguration.aidl root/android/hardware/camera/device/StreamRotation.aidl root/android/hardware/camera/device/StreamType.aidl root/android/hardware/camera/common/VendorTagSection.aidl root/android/hardware/camera/common/TagBoundaryId.aidl root/android/hardware/camera/common/VendorTag.aidl root/android/hardware/camera/common/TorchModeStatus.aidl root/android/hardware/camera/common/Status.aidl root/android/hardware/camera/common/CameraResourceCost.aidl root/android/hardware/camera/common/CameraMetadataType.aidl root/android/hardware/camera/common/CameraDeviceStatus.aidl root/android/hardware/camera/metadata/ScalerAvailableFormats.aidl root/android/hardware/camera/metadata/LensPoseReference.aidl root/android/hardware/camera/metadata/ControlAeMode.aidl root/android/hardware/camera/metadata/StatisticsOisDataMode.aidl root/android/hardware/camera/metadata/ControlAfMode.aidl root/android/hardware/camera/metadata/AutomotiveLocation.aidl root/android/hardware/camera/metadata/StatisticsSceneFlicker.aidl root/android/hardware/camera/metadata/LedAvailableLeds.aidl root/android/hardware/camera/metadata/LensState.aidl root/android/hardware/camera/metadata/AutomotiveLensFacing.aidl root/android/hardware/camera/metadata/BlackLevelLock.aidl root/android/hardware/camera/metadata/CameraMetadataSection.aidl root/android/hardware/camera/metadata/CameraMetadataSectionStart.aidl root/android/hardware/camera/metadata/CameraMetadataTag.aidl root/android/hardware/camera/metadata/ColorCorrectionAberrationMode.aidl root/android/hardware/camera/metadata/ColorCorrectionMode.aidl root/android/hardware/camera/metadata/ControlAeAntibandingMode.aidl root/android/hardware/camera/metadata/ControlSceneMode.aidl root/android/hardware/camera/metadata/ControlAeLock.aidl root/android/hardware/camera/metadata/ControlAeLockAvailable.aidl root/android/hardware/camera/metadata/ControlAePrecaptureTrigger.aidl root/android/hardware/camera/metadata/ControlAeState.aidl root/android/hardware/camera/metadata/QuirksPartialResult.aidl root/android/hardware/camera/metadata/ControlAfSceneChange.aidl root/android/hardware/camera/metadata/ControlAfState.aidl root/android/hardware/camera/metadata/ControlAfTrigger.aidl root/android/hardware/camera/metadata/ControlAutoframing.aidl root/android/hardware/camera/metadata/ControlAutoframingAvailable.aidl root/android/hardware/camera/metadata/ControlAutoframingState.aidl root/android/hardware/camera/metadata/ControlAwbLock.aidl root/android/hardware/camera/metadata/ControlAwbLockAvailable.aidl root/android/hardware/camera/metadata/ControlAwbMode.aidl root/android/hardware/camera/metadata/ControlAwbState.aidl root/android/hardware/camera/metadata/ControlCaptureIntent.aidl root/android/hardware/camera/metadata/ControlEffectMode.aidl root/android/hardware/camera/metadata/ControlEnableZsl.aidl root/android/hardware/camera/metadata/ControlExtendedSceneMode.aidl root/android/hardware/camera/metadata/ControlLowLightBoostState.aidl root/android/hardware/camera/metadata/ControlMode.aidl root/android/hardware/camera/metadata/ControlSettingsOverride.aidl root/android/hardware/camera/metadata/ControlVideoStabilizationMode.aidl root/android/hardware/camera/metadata/DemosaicMode.aidl root/android/hardware/camera/metadata/DepthAvailableDepthStreamConfigurations.aidl root/android/hardware/camera/metadata/DepthAvailableDepthStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/DepthAvailableDynamicDepthStreamConfigurations.aidl root/android/hardware/camera/metadata/DepthAvailableDynamicDepthStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/DepthDepthIsExclusive.aidl root/android/hardware/camera/metadata/DistortionCorrectionMode.aidl root/android/hardware/camera/metadata/EdgeMode.aidl root/android/hardware/camera/metadata/FlashInfoAvailable.aidl root/android/hardware/camera/metadata/FlashMode.aidl root/android/hardware/camera/metadata/FlashState.aidl root/android/hardware/camera/metadata/HeicAvailableHeicStreamConfigurations.aidl root/android/hardware/camera/metadata/HeicAvailableHeicStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/HeicInfoSupported.aidl root/android/hardware/camera/metadata/HotPixelMode.aidl root/android/hardware/camera/metadata/InfoSupportedBufferManagementVersion.aidl root/android/hardware/camera/metadata/InfoSupportedHardwareLevel.aidl root/android/hardware/camera/metadata/JpegrAvailableJpegRStreamConfigurations.aidl root/android/hardware/camera/metadata/JpegrAvailableJpegRStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/RequestAvailableDynamicRangeProfilesMap.aidl root/android/hardware/camera/metadata/ScalerAvailableStreamConfigurations.aidl root/android/hardware/camera/metadata/LedTransmit.aidl root/android/hardware/camera/metadata/LensFacing.aidl root/android/hardware/camera/metadata/LensInfoFocusDistanceCalibration.aidl root/android/hardware/camera/metadata/LensOpticalStabilizationMode.aidl root/android/hardware/camera/metadata/SensorInfoLensShadingApplied.aidl root/android/hardware/camera/metadata/SensorInfoColorFilterArrangement.aidl root/android/hardware/camera/metadata/ScalerRotateAndCrop.aidl root/android/hardware/camera/metadata/SensorInfoTimestampSource.aidl root/android/hardware/camera/metadata/LogicalMultiCameraSensorSyncType.aidl root/android/hardware/camera/metadata/NoiseReductionMode.aidl root/android/hardware/camera/metadata/ScalerAvailableStreamUseCases.aidl root/android/hardware/camera/metadata/RequestAvailableCapabilities.aidl root/android/hardware/camera/metadata/RequestAvailableColorSpaceProfilesMap.aidl root/android/hardware/camera/metadata/ScalerAvailableRecommendedStreamConfigurations.aidl root/android/hardware/camera/metadata/RequestMetadataMode.aidl root/android/hardware/camera/metadata/RequestType.aidl root/android/hardware/camera/metadata/ScalerAvailableStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/ScalerCroppingType.aidl root/android/hardware/camera/metadata/ScalerMultiResolutionStreamSupported.aidl root/android/hardware/camera/metadata/ScalerPhysicalCameraMultiResolutionStreamConfigurations.aidl root/android/hardware/camera/metadata/SensorPixelMode.aidl root/android/hardware/camera/metadata/StatisticsHistogramMode.aidl root/android/hardware/camera/metadata/ShadingMode.aidl root/android/hardware/camera/metadata/StatisticsFaceDetectMode.aidl root/android/hardware/camera/metadata/StatisticsHotPixelMapMode.aidl root/android/hardware/camera/metadata/SensorRawBinningFactorUsed.aidl root/android/hardware/camera/metadata/SensorReadoutTimestamp.aidl root/android/hardware/camera/metadata/SensorReferenceIlluminant1.aidl root/android/hardware/camera/metadata/SensorTestPatternMode.aidl root/android/hardware/camera/metadata/StatisticsLensShadingMapMode.aidl root/android/hardware/camera/metadata/TonemapPresetCurve.aidl root/android/hardware/camera/metadata/StatisticsSharpnessMapMode.aidl root/android/hardware/camera/metadata/SyncFrameNumber.aidl root/android/hardware/camera/metadata/SyncMaxLatency.aidl root/android/hardware/camera/metadata/TonemapMode.aidl root/android/hardware/common/Ashmem.aidl root/android/hardware/common/MappableFile.aidl root/android/hardware/common/NativeHandle.aidl root/android/hardware/common/fmq/GrantorDescriptor.aidl root/android/hardware/common/fmq/MQDescriptor.aidl root/android/hardware/common/fmq/SynchronizedReadWrite.aidl root/android/hardware/common/fmq/UnsynchronizedWrite.aidl root/android/hardware/graphics/common/Dataspace.aidl root/android/hardware/graphics/common/Compression.aidl root/android/hardware/graphics/common/ColorTransform.aidl root/android/hardware/graphics/common/AlphaInterpretation.aidl root/android/hardware/graphics/common/ChromaSiting.aidl root/android/hardware/graphics/common/BlendMode.aidl root/android/hardware/graphics/common/BufferUsage.aidl root/android/hardware/graphics/common/Cta861_3.aidl root/android/hardware/graphics/common/Rect.aidl root/android/hardware/graphics/common/DisplayDecorationSupport.aidl root/android/hardware/graphics/common/DisplayHotplugEvent.aidl root/android/hardware/graphics/common/ExtendableType.aidl root/android/hardware/graphics/common/FRect.aidl root/android/hardware/graphics/common/PlaneLayoutComponentType.aidl root/android/hardware/graphics/common/HardwareBuffer.aidl root/android/hardware/graphics/common/HardwareBufferDescription.aidl root/android/hardware/graphics/common/Hdr.aidl root/android/hardware/graphics/common/HdrConversionCapability.aidl root/android/hardware/graphics/common/PlaneLayoutComponent.aidl root/android/hardware/graphics/common/PlaneLayout.aidl root/android/hardware/graphics/common/HdrConversionStrategy.aidl root/android/hardware/graphics/common/Interlaced.aidl root/android/hardware/graphics/common/PixelFormat.aidl root/android/hardware/graphics/common/Point.aidl root/android/hardware/graphics/common/Smpte2086.aidl root/android/hardware/graphics/common/StandardMetadataType.aidl root/android/hardware/graphics/common/Transform.aidl root/android/hardware/graphics/common/XyColor.aidl
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
enum class BufferUsage : int64_t {
  CPU_READ_MASK = 15L,
  CPU_READ_NEVER = 0L,
  CPU_READ_RARELY = 2L,
  CPU_READ_OFTEN = 3L,
  CPU_WRITE_MASK = 240L,
  CPU_WRITE_NEVER = 0L,
  CPU_WRITE_RARELY = 32L,
  CPU_WRITE_OFTEN = 48L,
  GPU_TEXTURE = 256L,
  GPU_RENDER_TARGET = 512L,
  COMPOSER_OVERLAY = 2048L,
  COMPOSER_CLIENT_TARGET = 4096L,
  PROTECTED = 16384L,
  COMPOSER_CURSOR = 32768L,
  VIDEO_ENCODER = 65536L,
  CAMERA_OUTPUT = 131072L,
  CAMERA_INPUT = 262144L,
  RENDERSCRIPT = 1048576L,
  VIDEO_DECODER = 4194304L,
  SENSOR_DIRECT_DATA = 8388608L,
  GPU_DATA_BUFFER = 16777216L,
  GPU_CUBE_MAP = 33554432L,
  GPU_MIPMAP_COMPLETE = 67108864L,
  HW_IMAGE_ENCODER = 134217728L,
  FRONT_BUFFER = 4294967296L,
  VENDOR_MASK = 4026531840L,
  VENDOR_MASK_HI = -281474976710656L,
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
[[nodiscard]] static inline std::string toString(BufferUsage val) {
  switch(val) {
  case BufferUsage::CPU_READ_MASK:
    return "CPU_READ_MASK";
  case BufferUsage::CPU_READ_NEVER:
    return "CPU_READ_NEVER";
  case BufferUsage::CPU_READ_RARELY:
    return "CPU_READ_RARELY";
  case BufferUsage::CPU_READ_OFTEN:
    return "CPU_READ_OFTEN";
  case BufferUsage::CPU_WRITE_MASK:
    return "CPU_WRITE_MASK";
  case BufferUsage::CPU_WRITE_RARELY:
    return "CPU_WRITE_RARELY";
  case BufferUsage::CPU_WRITE_OFTEN:
    return "CPU_WRITE_OFTEN";
  case BufferUsage::GPU_TEXTURE:
    return "GPU_TEXTURE";
  case BufferUsage::GPU_RENDER_TARGET:
    return "GPU_RENDER_TARGET";
  case BufferUsage::COMPOSER_OVERLAY:
    return "COMPOSER_OVERLAY";
  case BufferUsage::COMPOSER_CLIENT_TARGET:
    return "COMPOSER_CLIENT_TARGET";
  case BufferUsage::PROTECTED:
    return "PROTECTED";
  case BufferUsage::COMPOSER_CURSOR:
    return "COMPOSER_CURSOR";
  case BufferUsage::VIDEO_ENCODER:
    return "VIDEO_ENCODER";
  case BufferUsage::CAMERA_OUTPUT:
    return "CAMERA_OUTPUT";
  case BufferUsage::CAMERA_INPUT:
    return "CAMERA_INPUT";
  case BufferUsage::RENDERSCRIPT:
    return "RENDERSCRIPT";
  case BufferUsage::VIDEO_DECODER:
    return "VIDEO_DECODER";
  case BufferUsage::SENSOR_DIRECT_DATA:
    return "SENSOR_DIRECT_DATA";
  case BufferUsage::GPU_DATA_BUFFER:
    return "GPU_DATA_BUFFER";
  case BufferUsage::GPU_CUBE_MAP:
    return "GPU_CUBE_MAP";
  case BufferUsage::GPU_MIPMAP_COMPLETE:
    return "GPU_MIPMAP_COMPLETE";
  case BufferUsage::HW_IMAGE_ENCODER:
    return "HW_IMAGE_ENCODER";
  case BufferUsage::FRONT_BUFFER:
    return "FRONT_BUFFER";
  case BufferUsage::VENDOR_MASK:
    return "VENDOR_MASK";
  case BufferUsage::VENDOR_MASK_HI:
    return "VENDOR_MASK_HI";
  default:
    return std::to_string(static_cast<int64_t>(val));
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
constexpr inline std::array<aidl::android::hardware::graphics::common::BufferUsage, 27> enum_values<aidl::android::hardware::graphics::common::BufferUsage> = {
  aidl::android::hardware::graphics::common::BufferUsage::CPU_READ_MASK,
  aidl::android::hardware::graphics::common::BufferUsage::CPU_READ_NEVER,
  aidl::android::hardware::graphics::common::BufferUsage::CPU_READ_RARELY,
  aidl::android::hardware::graphics::common::BufferUsage::CPU_READ_OFTEN,
  aidl::android::hardware::graphics::common::BufferUsage::CPU_WRITE_MASK,
  aidl::android::hardware::graphics::common::BufferUsage::CPU_WRITE_NEVER,
  aidl::android::hardware::graphics::common::BufferUsage::CPU_WRITE_RARELY,
  aidl::android::hardware::graphics::common::BufferUsage::CPU_WRITE_OFTEN,
  aidl::android::hardware::graphics::common::BufferUsage::GPU_TEXTURE,
  aidl::android::hardware::graphics::common::BufferUsage::GPU_RENDER_TARGET,
  aidl::android::hardware::graphics::common::BufferUsage::COMPOSER_OVERLAY,
  aidl::android::hardware::graphics::common::BufferUsage::COMPOSER_CLIENT_TARGET,
  aidl::android::hardware::graphics::common::BufferUsage::PROTECTED,
  aidl::android::hardware::graphics::common::BufferUsage::COMPOSER_CURSOR,
  aidl::android::hardware::graphics::common::BufferUsage::VIDEO_ENCODER,
  aidl::android::hardware::graphics::common::BufferUsage::CAMERA_OUTPUT,
  aidl::android::hardware::graphics::common::BufferUsage::CAMERA_INPUT,
  aidl::android::hardware::graphics::common::BufferUsage::RENDERSCRIPT,
  aidl::android::hardware::graphics::common::BufferUsage::VIDEO_DECODER,
  aidl::android::hardware::graphics::common::BufferUsage::SENSOR_DIRECT_DATA,
  aidl::android::hardware::graphics::common::BufferUsage::GPU_DATA_BUFFER,
  aidl::android::hardware::graphics::common::BufferUsage::GPU_CUBE_MAP,
  aidl::android::hardware::graphics::common::BufferUsage::GPU_MIPMAP_COMPLETE,
  aidl::android::hardware::graphics::common::BufferUsage::HW_IMAGE_ENCODER,
  aidl::android::hardware::graphics::common::BufferUsage::FRONT_BUFFER,
  aidl::android::hardware::graphics::common::BufferUsage::VENDOR_MASK,
  aidl::android::hardware::graphics::common::BufferUsage::VENDOR_MASK_HI,
};
#pragma clang diagnostic pop
}  // namespace internal
}  // namespace ndk
