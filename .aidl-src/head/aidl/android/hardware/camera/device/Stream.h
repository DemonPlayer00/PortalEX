/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: /home/DemonPlayer/Android/Sdk/build-tools/35.0.0/aidl --lang=ndk --structured --stability=vintf --version 2 -I root -o gen -h head root/android/hardware/camera/provider/ICameraProviderCallback.aidl root/android/hardware/camera/provider/ConcurrentCameraIdCombination.aidl root/android/hardware/camera/provider/ICameraProvider.aidl root/android/hardware/camera/provider/CameraIdAndStreamCombination.aidl root/android/hardware/camera/device/BufferCache.aidl root/android/hardware/camera/device/BufferRequestStatus.aidl root/android/hardware/camera/device/BufferRequest.aidl root/android/hardware/camera/device/CameraBlob.aidl root/android/hardware/camera/device/CameraOfflineSessionInfo.aidl root/android/hardware/camera/device/CameraBlobId.aidl root/android/hardware/camera/device/BufferStatus.aidl root/android/hardware/camera/device/CaptureRequest.aidl root/android/hardware/camera/device/CameraMetadata.aidl root/android/hardware/camera/device/CaptureResult.aidl root/android/hardware/camera/device/ErrorMsg.aidl root/android/hardware/camera/device/ICameraDeviceCallback.aidl root/android/hardware/camera/device/HalStream.aidl root/android/hardware/camera/device/ShutterMsg.aidl root/android/hardware/camera/device/ConfigureStreamsRet.aidl root/android/hardware/camera/device/ICameraDeviceSession.aidl root/android/hardware/camera/device/ICameraDevice.aidl root/android/hardware/camera/device/RequestTemplate.aidl root/android/hardware/camera/device/ErrorCode.aidl root/android/hardware/camera/device/PhysicalCameraSetting.aidl root/android/hardware/camera/device/ICameraOfflineSession.aidl root/android/hardware/camera/device/ICameraInjectionSession.aidl root/android/hardware/camera/device/NotifyMsg.aidl root/android/hardware/camera/device/OfflineStream.aidl root/android/hardware/camera/device/OfflineRequest.aidl root/android/hardware/camera/device/PhysicalCameraMetadata.aidl root/android/hardware/camera/device/Stream.aidl root/android/hardware/camera/device/StreamBufferRequestError.aidl root/android/hardware/camera/device/StreamBuffer.aidl root/android/hardware/camera/device/StreamBufferRet.aidl root/android/hardware/camera/device/StreamBuffersVal.aidl root/android/hardware/camera/device/StreamConfigurationMode.aidl root/android/hardware/camera/device/StreamConfiguration.aidl root/android/hardware/camera/device/StreamRotation.aidl root/android/hardware/camera/device/StreamType.aidl root/android/hardware/camera/common/VendorTagSection.aidl root/android/hardware/camera/common/TagBoundaryId.aidl root/android/hardware/camera/common/VendorTag.aidl root/android/hardware/camera/common/TorchModeStatus.aidl root/android/hardware/camera/common/Status.aidl root/android/hardware/camera/common/CameraResourceCost.aidl root/android/hardware/camera/common/CameraMetadataType.aidl root/android/hardware/camera/common/CameraDeviceStatus.aidl root/android/hardware/camera/metadata/ScalerAvailableFormats.aidl root/android/hardware/camera/metadata/LensPoseReference.aidl root/android/hardware/camera/metadata/ControlAeMode.aidl root/android/hardware/camera/metadata/StatisticsOisDataMode.aidl root/android/hardware/camera/metadata/ControlAfMode.aidl root/android/hardware/camera/metadata/AutomotiveLocation.aidl root/android/hardware/camera/metadata/StatisticsSceneFlicker.aidl root/android/hardware/camera/metadata/LedAvailableLeds.aidl root/android/hardware/camera/metadata/LensState.aidl root/android/hardware/camera/metadata/AutomotiveLensFacing.aidl root/android/hardware/camera/metadata/BlackLevelLock.aidl root/android/hardware/camera/metadata/CameraMetadataSection.aidl root/android/hardware/camera/metadata/CameraMetadataSectionStart.aidl root/android/hardware/camera/metadata/CameraMetadataTag.aidl root/android/hardware/camera/metadata/ColorCorrectionAberrationMode.aidl root/android/hardware/camera/metadata/ColorCorrectionMode.aidl root/android/hardware/camera/metadata/ControlAeAntibandingMode.aidl root/android/hardware/camera/metadata/ControlSceneMode.aidl root/android/hardware/camera/metadata/ControlAeLock.aidl root/android/hardware/camera/metadata/ControlAeLockAvailable.aidl root/android/hardware/camera/metadata/ControlAePrecaptureTrigger.aidl root/android/hardware/camera/metadata/ControlAeState.aidl root/android/hardware/camera/metadata/QuirksPartialResult.aidl root/android/hardware/camera/metadata/ControlAfSceneChange.aidl root/android/hardware/camera/metadata/ControlAfState.aidl root/android/hardware/camera/metadata/ControlAfTrigger.aidl root/android/hardware/camera/metadata/ControlAutoframing.aidl root/android/hardware/camera/metadata/ControlAutoframingAvailable.aidl root/android/hardware/camera/metadata/ControlAutoframingState.aidl root/android/hardware/camera/metadata/ControlAwbLock.aidl root/android/hardware/camera/metadata/ControlAwbLockAvailable.aidl root/android/hardware/camera/metadata/ControlAwbMode.aidl root/android/hardware/camera/metadata/ControlAwbState.aidl root/android/hardware/camera/metadata/ControlCaptureIntent.aidl root/android/hardware/camera/metadata/ControlEffectMode.aidl root/android/hardware/camera/metadata/ControlEnableZsl.aidl root/android/hardware/camera/metadata/ControlExtendedSceneMode.aidl root/android/hardware/camera/metadata/ControlLowLightBoostState.aidl root/android/hardware/camera/metadata/ControlMode.aidl root/android/hardware/camera/metadata/ControlSettingsOverride.aidl root/android/hardware/camera/metadata/ControlVideoStabilizationMode.aidl root/android/hardware/camera/metadata/DemosaicMode.aidl root/android/hardware/camera/metadata/DepthAvailableDepthStreamConfigurations.aidl root/android/hardware/camera/metadata/DepthAvailableDepthStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/DepthAvailableDynamicDepthStreamConfigurations.aidl root/android/hardware/camera/metadata/DepthAvailableDynamicDepthStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/DepthDepthIsExclusive.aidl root/android/hardware/camera/metadata/DistortionCorrectionMode.aidl root/android/hardware/camera/metadata/EdgeMode.aidl root/android/hardware/camera/metadata/FlashInfoAvailable.aidl root/android/hardware/camera/metadata/FlashMode.aidl root/android/hardware/camera/metadata/FlashState.aidl root/android/hardware/camera/metadata/HeicAvailableHeicStreamConfigurations.aidl root/android/hardware/camera/metadata/HeicAvailableHeicStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/HeicInfoSupported.aidl root/android/hardware/camera/metadata/HotPixelMode.aidl root/android/hardware/camera/metadata/InfoSupportedBufferManagementVersion.aidl root/android/hardware/camera/metadata/InfoSupportedHardwareLevel.aidl root/android/hardware/camera/metadata/JpegrAvailableJpegRStreamConfigurations.aidl root/android/hardware/camera/metadata/JpegrAvailableJpegRStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/RequestAvailableDynamicRangeProfilesMap.aidl root/android/hardware/camera/metadata/ScalerAvailableStreamConfigurations.aidl root/android/hardware/camera/metadata/LedTransmit.aidl root/android/hardware/camera/metadata/LensFacing.aidl root/android/hardware/camera/metadata/LensInfoFocusDistanceCalibration.aidl root/android/hardware/camera/metadata/LensOpticalStabilizationMode.aidl root/android/hardware/camera/metadata/SensorInfoLensShadingApplied.aidl root/android/hardware/camera/metadata/SensorInfoColorFilterArrangement.aidl root/android/hardware/camera/metadata/ScalerRotateAndCrop.aidl root/android/hardware/camera/metadata/SensorInfoTimestampSource.aidl root/android/hardware/camera/metadata/LogicalMultiCameraSensorSyncType.aidl root/android/hardware/camera/metadata/NoiseReductionMode.aidl root/android/hardware/camera/metadata/ScalerAvailableStreamUseCases.aidl root/android/hardware/camera/metadata/RequestAvailableCapabilities.aidl root/android/hardware/camera/metadata/RequestAvailableColorSpaceProfilesMap.aidl root/android/hardware/camera/metadata/ScalerAvailableRecommendedStreamConfigurations.aidl root/android/hardware/camera/metadata/RequestMetadataMode.aidl root/android/hardware/camera/metadata/RequestType.aidl root/android/hardware/camera/metadata/ScalerAvailableStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/ScalerCroppingType.aidl root/android/hardware/camera/metadata/ScalerMultiResolutionStreamSupported.aidl root/android/hardware/camera/metadata/ScalerPhysicalCameraMultiResolutionStreamConfigurations.aidl root/android/hardware/camera/metadata/SensorPixelMode.aidl root/android/hardware/camera/metadata/StatisticsHistogramMode.aidl root/android/hardware/camera/metadata/ShadingMode.aidl root/android/hardware/camera/metadata/StatisticsFaceDetectMode.aidl root/android/hardware/camera/metadata/StatisticsHotPixelMapMode.aidl root/android/hardware/camera/metadata/SensorRawBinningFactorUsed.aidl root/android/hardware/camera/metadata/SensorReadoutTimestamp.aidl root/android/hardware/camera/metadata/SensorReferenceIlluminant1.aidl root/android/hardware/camera/metadata/SensorTestPatternMode.aidl root/android/hardware/camera/metadata/StatisticsLensShadingMapMode.aidl root/android/hardware/camera/metadata/TonemapPresetCurve.aidl root/android/hardware/camera/metadata/StatisticsSharpnessMapMode.aidl root/android/hardware/camera/metadata/SyncFrameNumber.aidl root/android/hardware/camera/metadata/SyncMaxLatency.aidl root/android/hardware/camera/metadata/TonemapMode.aidl root/android/hardware/common/Ashmem.aidl root/android/hardware/common/MappableFile.aidl root/android/hardware/common/NativeHandle.aidl root/android/hardware/common/fmq/GrantorDescriptor.aidl root/android/hardware/common/fmq/MQDescriptor.aidl root/android/hardware/common/fmq/SynchronizedReadWrite.aidl root/android/hardware/common/fmq/UnsynchronizedWrite.aidl root/android/hardware/graphics/common/Dataspace.aidl root/android/hardware/graphics/common/Compression.aidl root/android/hardware/graphics/common/ColorTransform.aidl root/android/hardware/graphics/common/AlphaInterpretation.aidl root/android/hardware/graphics/common/ChromaSiting.aidl root/android/hardware/graphics/common/BlendMode.aidl root/android/hardware/graphics/common/BufferUsage.aidl root/android/hardware/graphics/common/Cta861_3.aidl root/android/hardware/graphics/common/Rect.aidl root/android/hardware/graphics/common/DisplayDecorationSupport.aidl root/android/hardware/graphics/common/DisplayHotplugEvent.aidl root/android/hardware/graphics/common/ExtendableType.aidl root/android/hardware/graphics/common/FRect.aidl root/android/hardware/graphics/common/PlaneLayoutComponentType.aidl root/android/hardware/graphics/common/HardwareBuffer.aidl root/android/hardware/graphics/common/HardwareBufferDescription.aidl root/android/hardware/graphics/common/Hdr.aidl root/android/hardware/graphics/common/HdrConversionCapability.aidl root/android/hardware/graphics/common/PlaneLayoutComponent.aidl root/android/hardware/graphics/common/PlaneLayout.aidl root/android/hardware/graphics/common/HdrConversionStrategy.aidl root/android/hardware/graphics/common/Interlaced.aidl root/android/hardware/graphics/common/PixelFormat.aidl root/android/hardware/graphics/common/Point.aidl root/android/hardware/graphics/common/Smpte2086.aidl root/android/hardware/graphics/common/StandardMetadataType.aidl root/android/hardware/graphics/common/Transform.aidl root/android/hardware/graphics/common/XyColor.aidl
 */
#pragma once

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>
#include <android/binder_interface_utils.h>
#include <android/binder_parcelable_utils.h>
#include <android/binder_to_string.h>
#include <aidl/android/hardware/camera/device/StreamRotation.h>
#include <aidl/android/hardware/camera/device/StreamType.h>
#include <aidl/android/hardware/camera/metadata/RequestAvailableDynamicRangeProfilesMap.h>
#include <aidl/android/hardware/camera/metadata/ScalerAvailableStreamUseCases.h>
#include <aidl/android/hardware/camera/metadata/SensorPixelMode.h>
#include <aidl/android/hardware/graphics/common/BufferUsage.h>
#include <aidl/android/hardware/graphics/common/Dataspace.h>
#include <aidl/android/hardware/graphics/common/PixelFormat.h>
#ifdef BINDER_STABILITY_SUPPORT
#include <android/binder_stability.h>
#endif  // BINDER_STABILITY_SUPPORT

namespace aidl {
namespace android {
namespace hardware {
namespace camera {
namespace device {
class Stream {
public:
  typedef std::false_type fixed_size;
  static const char* descriptor;

  int32_t id = 0;
  ::aidl::android::hardware::camera::device::StreamType streamType = ::aidl::android::hardware::camera::device::StreamType(0);
  int32_t width = 0;
  int32_t height = 0;
  ::aidl::android::hardware::graphics::common::PixelFormat format = ::aidl::android::hardware::graphics::common::PixelFormat(0);
  ::aidl::android::hardware::graphics::common::BufferUsage usage = ::aidl::android::hardware::graphics::common::BufferUsage(0);
  ::aidl::android::hardware::graphics::common::Dataspace dataSpace = ::aidl::android::hardware::graphics::common::Dataspace(0);
  ::aidl::android::hardware::camera::device::StreamRotation rotation = ::aidl::android::hardware::camera::device::StreamRotation(0);
  std::string physicalCameraId;
  int32_t bufferSize = 0;
  int32_t groupId = 0;
  std::vector<::aidl::android::hardware::camera::metadata::SensorPixelMode> sensorPixelModesUsed;
  ::aidl::android::hardware::camera::metadata::RequestAvailableDynamicRangeProfilesMap dynamicRangeProfile = ::aidl::android::hardware::camera::metadata::RequestAvailableDynamicRangeProfilesMap(0);
  ::aidl::android::hardware::camera::metadata::ScalerAvailableStreamUseCases useCase = ::aidl::android::hardware::camera::metadata::ScalerAvailableStreamUseCases(0);
  int32_t colorSpace = 0;

  binder_status_t readFromParcel(const AParcel* parcel);
  binder_status_t writeToParcel(AParcel* parcel) const;

  inline bool operator==(const Stream& _rhs) const {
    return std::tie(id, streamType, width, height, format, usage, dataSpace, rotation, physicalCameraId, bufferSize, groupId, sensorPixelModesUsed, dynamicRangeProfile, useCase, colorSpace) == std::tie(_rhs.id, _rhs.streamType, _rhs.width, _rhs.height, _rhs.format, _rhs.usage, _rhs.dataSpace, _rhs.rotation, _rhs.physicalCameraId, _rhs.bufferSize, _rhs.groupId, _rhs.sensorPixelModesUsed, _rhs.dynamicRangeProfile, _rhs.useCase, _rhs.colorSpace);
  }
  inline bool operator<(const Stream& _rhs) const {
    return std::tie(id, streamType, width, height, format, usage, dataSpace, rotation, physicalCameraId, bufferSize, groupId, sensorPixelModesUsed, dynamicRangeProfile, useCase, colorSpace) < std::tie(_rhs.id, _rhs.streamType, _rhs.width, _rhs.height, _rhs.format, _rhs.usage, _rhs.dataSpace, _rhs.rotation, _rhs.physicalCameraId, _rhs.bufferSize, _rhs.groupId, _rhs.sensorPixelModesUsed, _rhs.dynamicRangeProfile, _rhs.useCase, _rhs.colorSpace);
  }
  inline bool operator!=(const Stream& _rhs) const {
    return !(*this == _rhs);
  }
  inline bool operator>(const Stream& _rhs) const {
    return _rhs < *this;
  }
  inline bool operator>=(const Stream& _rhs) const {
    return !(*this < _rhs);
  }
  inline bool operator<=(const Stream& _rhs) const {
    return !(_rhs < *this);
  }

  static const ::ndk::parcelable_stability_t _aidl_stability = ::ndk::STABILITY_VINTF;
  inline std::string toString() const {
    std::ostringstream _aidl_os;
    _aidl_os << "Stream{";
    _aidl_os << "id: " << ::android::internal::ToString(id);
    _aidl_os << ", streamType: " << ::android::internal::ToString(streamType);
    _aidl_os << ", width: " << ::android::internal::ToString(width);
    _aidl_os << ", height: " << ::android::internal::ToString(height);
    _aidl_os << ", format: " << ::android::internal::ToString(format);
    _aidl_os << ", usage: " << ::android::internal::ToString(usage);
    _aidl_os << ", dataSpace: " << ::android::internal::ToString(dataSpace);
    _aidl_os << ", rotation: " << ::android::internal::ToString(rotation);
    _aidl_os << ", physicalCameraId: " << ::android::internal::ToString(physicalCameraId);
    _aidl_os << ", bufferSize: " << ::android::internal::ToString(bufferSize);
    _aidl_os << ", groupId: " << ::android::internal::ToString(groupId);
    _aidl_os << ", sensorPixelModesUsed: " << ::android::internal::ToString(sensorPixelModesUsed);
    _aidl_os << ", dynamicRangeProfile: " << ::android::internal::ToString(dynamicRangeProfile);
    _aidl_os << ", useCase: " << ::android::internal::ToString(useCase);
    _aidl_os << ", colorSpace: " << ::android::internal::ToString(colorSpace);
    _aidl_os << "}";
    return _aidl_os.str();
  }
};
}  // namespace device
}  // namespace camera
}  // namespace hardware
}  // namespace android
}  // namespace aidl
