/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: /home/DemonPlayer/Android/Sdk/build-tools/35.0.0/aidl --lang=ndk --structured --stability=vintf --version 2 -I root -o gen -h head root/android/hardware/camera/provider/ICameraProviderCallback.aidl root/android/hardware/camera/provider/ConcurrentCameraIdCombination.aidl root/android/hardware/camera/provider/ICameraProvider.aidl root/android/hardware/camera/provider/CameraIdAndStreamCombination.aidl root/android/hardware/camera/device/BufferCache.aidl root/android/hardware/camera/device/BufferRequestStatus.aidl root/android/hardware/camera/device/BufferRequest.aidl root/android/hardware/camera/device/CameraBlob.aidl root/android/hardware/camera/device/CameraOfflineSessionInfo.aidl root/android/hardware/camera/device/CameraBlobId.aidl root/android/hardware/camera/device/BufferStatus.aidl root/android/hardware/camera/device/CaptureRequest.aidl root/android/hardware/camera/device/CameraMetadata.aidl root/android/hardware/camera/device/CaptureResult.aidl root/android/hardware/camera/device/ErrorMsg.aidl root/android/hardware/camera/device/ICameraDeviceCallback.aidl root/android/hardware/camera/device/HalStream.aidl root/android/hardware/camera/device/ShutterMsg.aidl root/android/hardware/camera/device/ConfigureStreamsRet.aidl root/android/hardware/camera/device/ICameraDeviceSession.aidl root/android/hardware/camera/device/ICameraDevice.aidl root/android/hardware/camera/device/RequestTemplate.aidl root/android/hardware/camera/device/ErrorCode.aidl root/android/hardware/camera/device/PhysicalCameraSetting.aidl root/android/hardware/camera/device/ICameraOfflineSession.aidl root/android/hardware/camera/device/ICameraInjectionSession.aidl root/android/hardware/camera/device/NotifyMsg.aidl root/android/hardware/camera/device/OfflineStream.aidl root/android/hardware/camera/device/OfflineRequest.aidl root/android/hardware/camera/device/PhysicalCameraMetadata.aidl root/android/hardware/camera/device/Stream.aidl root/android/hardware/camera/device/StreamBufferRequestError.aidl root/android/hardware/camera/device/StreamBuffer.aidl root/android/hardware/camera/device/StreamBufferRet.aidl root/android/hardware/camera/device/StreamBuffersVal.aidl root/android/hardware/camera/device/StreamConfigurationMode.aidl root/android/hardware/camera/device/StreamConfiguration.aidl root/android/hardware/camera/device/StreamRotation.aidl root/android/hardware/camera/device/StreamType.aidl root/android/hardware/camera/common/VendorTagSection.aidl root/android/hardware/camera/common/TagBoundaryId.aidl root/android/hardware/camera/common/VendorTag.aidl root/android/hardware/camera/common/TorchModeStatus.aidl root/android/hardware/camera/common/Status.aidl root/android/hardware/camera/common/CameraResourceCost.aidl root/android/hardware/camera/common/CameraMetadataType.aidl root/android/hardware/camera/common/CameraDeviceStatus.aidl root/android/hardware/camera/metadata/ScalerAvailableFormats.aidl root/android/hardware/camera/metadata/LensPoseReference.aidl root/android/hardware/camera/metadata/ControlAeMode.aidl root/android/hardware/camera/metadata/StatisticsOisDataMode.aidl root/android/hardware/camera/metadata/ControlAfMode.aidl root/android/hardware/camera/metadata/AutomotiveLocation.aidl root/android/hardware/camera/metadata/StatisticsSceneFlicker.aidl root/android/hardware/camera/metadata/LedAvailableLeds.aidl root/android/hardware/camera/metadata/LensState.aidl root/android/hardware/camera/metadata/AutomotiveLensFacing.aidl root/android/hardware/camera/metadata/BlackLevelLock.aidl root/android/hardware/camera/metadata/CameraMetadataSection.aidl root/android/hardware/camera/metadata/CameraMetadataSectionStart.aidl root/android/hardware/camera/metadata/CameraMetadataTag.aidl root/android/hardware/camera/metadata/ColorCorrectionAberrationMode.aidl root/android/hardware/camera/metadata/ColorCorrectionMode.aidl root/android/hardware/camera/metadata/ControlAeAntibandingMode.aidl root/android/hardware/camera/metadata/ControlSceneMode.aidl root/android/hardware/camera/metadata/ControlAeLock.aidl root/android/hardware/camera/metadata/ControlAeLockAvailable.aidl root/android/hardware/camera/metadata/ControlAePrecaptureTrigger.aidl root/android/hardware/camera/metadata/ControlAeState.aidl root/android/hardware/camera/metadata/QuirksPartialResult.aidl root/android/hardware/camera/metadata/ControlAfSceneChange.aidl root/android/hardware/camera/metadata/ControlAfState.aidl root/android/hardware/camera/metadata/ControlAfTrigger.aidl root/android/hardware/camera/metadata/ControlAutoframing.aidl root/android/hardware/camera/metadata/ControlAutoframingAvailable.aidl root/android/hardware/camera/metadata/ControlAutoframingState.aidl root/android/hardware/camera/metadata/ControlAwbLock.aidl root/android/hardware/camera/metadata/ControlAwbLockAvailable.aidl root/android/hardware/camera/metadata/ControlAwbMode.aidl root/android/hardware/camera/metadata/ControlAwbState.aidl root/android/hardware/camera/metadata/ControlCaptureIntent.aidl root/android/hardware/camera/metadata/ControlEffectMode.aidl root/android/hardware/camera/metadata/ControlEnableZsl.aidl root/android/hardware/camera/metadata/ControlExtendedSceneMode.aidl root/android/hardware/camera/metadata/ControlLowLightBoostState.aidl root/android/hardware/camera/metadata/ControlMode.aidl root/android/hardware/camera/metadata/ControlSettingsOverride.aidl root/android/hardware/camera/metadata/ControlVideoStabilizationMode.aidl root/android/hardware/camera/metadata/DemosaicMode.aidl root/android/hardware/camera/metadata/DepthAvailableDepthStreamConfigurations.aidl root/android/hardware/camera/metadata/DepthAvailableDepthStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/DepthAvailableDynamicDepthStreamConfigurations.aidl root/android/hardware/camera/metadata/DepthAvailableDynamicDepthStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/DepthDepthIsExclusive.aidl root/android/hardware/camera/metadata/DistortionCorrectionMode.aidl root/android/hardware/camera/metadata/EdgeMode.aidl root/android/hardware/camera/metadata/FlashInfoAvailable.aidl root/android/hardware/camera/metadata/FlashMode.aidl root/android/hardware/camera/metadata/FlashState.aidl root/android/hardware/camera/metadata/HeicAvailableHeicStreamConfigurations.aidl root/android/hardware/camera/metadata/HeicAvailableHeicStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/HeicInfoSupported.aidl root/android/hardware/camera/metadata/HotPixelMode.aidl root/android/hardware/camera/metadata/InfoSupportedBufferManagementVersion.aidl root/android/hardware/camera/metadata/InfoSupportedHardwareLevel.aidl root/android/hardware/camera/metadata/JpegrAvailableJpegRStreamConfigurations.aidl root/android/hardware/camera/metadata/JpegrAvailableJpegRStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/RequestAvailableDynamicRangeProfilesMap.aidl root/android/hardware/camera/metadata/ScalerAvailableStreamConfigurations.aidl root/android/hardware/camera/metadata/LedTransmit.aidl root/android/hardware/camera/metadata/LensFacing.aidl root/android/hardware/camera/metadata/LensInfoFocusDistanceCalibration.aidl root/android/hardware/camera/metadata/LensOpticalStabilizationMode.aidl root/android/hardware/camera/metadata/SensorInfoLensShadingApplied.aidl root/android/hardware/camera/metadata/SensorInfoColorFilterArrangement.aidl root/android/hardware/camera/metadata/ScalerRotateAndCrop.aidl root/android/hardware/camera/metadata/SensorInfoTimestampSource.aidl root/android/hardware/camera/metadata/LogicalMultiCameraSensorSyncType.aidl root/android/hardware/camera/metadata/NoiseReductionMode.aidl root/android/hardware/camera/metadata/ScalerAvailableStreamUseCases.aidl root/android/hardware/camera/metadata/RequestAvailableCapabilities.aidl root/android/hardware/camera/metadata/RequestAvailableColorSpaceProfilesMap.aidl root/android/hardware/camera/metadata/ScalerAvailableRecommendedStreamConfigurations.aidl root/android/hardware/camera/metadata/RequestMetadataMode.aidl root/android/hardware/camera/metadata/RequestType.aidl root/android/hardware/camera/metadata/ScalerAvailableStreamConfigurationsMaximumResolution.aidl root/android/hardware/camera/metadata/ScalerCroppingType.aidl root/android/hardware/camera/metadata/ScalerMultiResolutionStreamSupported.aidl root/android/hardware/camera/metadata/ScalerPhysicalCameraMultiResolutionStreamConfigurations.aidl root/android/hardware/camera/metadata/SensorPixelMode.aidl root/android/hardware/camera/metadata/StatisticsHistogramMode.aidl root/android/hardware/camera/metadata/ShadingMode.aidl root/android/hardware/camera/metadata/StatisticsFaceDetectMode.aidl root/android/hardware/camera/metadata/StatisticsHotPixelMapMode.aidl root/android/hardware/camera/metadata/SensorRawBinningFactorUsed.aidl root/android/hardware/camera/metadata/SensorReadoutTimestamp.aidl root/android/hardware/camera/metadata/SensorReferenceIlluminant1.aidl root/android/hardware/camera/metadata/SensorTestPatternMode.aidl root/android/hardware/camera/metadata/StatisticsLensShadingMapMode.aidl root/android/hardware/camera/metadata/TonemapPresetCurve.aidl root/android/hardware/camera/metadata/StatisticsSharpnessMapMode.aidl root/android/hardware/camera/metadata/SyncFrameNumber.aidl root/android/hardware/camera/metadata/SyncMaxLatency.aidl root/android/hardware/camera/metadata/TonemapMode.aidl root/android/hardware/common/Ashmem.aidl root/android/hardware/common/MappableFile.aidl root/android/hardware/common/NativeHandle.aidl root/android/hardware/common/fmq/GrantorDescriptor.aidl root/android/hardware/common/fmq/MQDescriptor.aidl root/android/hardware/common/fmq/SynchronizedReadWrite.aidl root/android/hardware/common/fmq/UnsynchronizedWrite.aidl root/android/hardware/graphics/common/Dataspace.aidl root/android/hardware/graphics/common/Compression.aidl root/android/hardware/graphics/common/ColorTransform.aidl root/android/hardware/graphics/common/AlphaInterpretation.aidl root/android/hardware/graphics/common/ChromaSiting.aidl root/android/hardware/graphics/common/BlendMode.aidl root/android/hardware/graphics/common/BufferUsage.aidl root/android/hardware/graphics/common/Cta861_3.aidl root/android/hardware/graphics/common/Rect.aidl root/android/hardware/graphics/common/DisplayDecorationSupport.aidl root/android/hardware/graphics/common/DisplayHotplugEvent.aidl root/android/hardware/graphics/common/ExtendableType.aidl root/android/hardware/graphics/common/FRect.aidl root/android/hardware/graphics/common/PlaneLayoutComponentType.aidl root/android/hardware/graphics/common/HardwareBuffer.aidl root/android/hardware/graphics/common/HardwareBufferDescription.aidl root/android/hardware/graphics/common/Hdr.aidl root/android/hardware/graphics/common/HdrConversionCapability.aidl root/android/hardware/graphics/common/PlaneLayoutComponent.aidl root/android/hardware/graphics/common/PlaneLayout.aidl root/android/hardware/graphics/common/HdrConversionStrategy.aidl root/android/hardware/graphics/common/Interlaced.aidl root/android/hardware/graphics/common/PixelFormat.aidl root/android/hardware/graphics/common/Point.aidl root/android/hardware/graphics/common/Smpte2086.aidl root/android/hardware/graphics/common/StandardMetadataType.aidl root/android/hardware/graphics/common/Transform.aidl root/android/hardware/graphics/common/XyColor.aidl
 */
#include "aidl/android/hardware/camera/device/ICameraOfflineSession.h"

#include <android/binder_parcel_utils.h>
#include <aidl/android/hardware/camera/device/BnCameraDeviceCallback.h>
#include <aidl/android/hardware/camera/device/BnCameraOfflineSession.h>
#include <aidl/android/hardware/camera/device/BpCameraDeviceCallback.h>
#include <aidl/android/hardware/camera/device/BpCameraOfflineSession.h>
#include <aidl/android/hardware/camera/device/ICameraDeviceCallback.h>

namespace aidl {
namespace android {
namespace hardware {
namespace camera {
namespace device {
static binder_status_t _aidl_android_hardware_camera_device_ICameraOfflineSession_onTransact(AIBinder* _aidl_binder, transaction_code_t _aidl_code, const AParcel* _aidl_in, AParcel* _aidl_out) {
  (void)_aidl_in;
  (void)_aidl_out;
  binder_status_t _aidl_ret_status = STATUS_UNKNOWN_TRANSACTION;
  std::shared_ptr<BnCameraOfflineSession> _aidl_impl = std::static_pointer_cast<BnCameraOfflineSession>(::ndk::ICInterface::asInterface(_aidl_binder));
  switch (_aidl_code) {
    case (FIRST_CALL_TRANSACTION + 0 /*close*/): {

      ::ndk::ScopedAStatus _aidl_status = _aidl_impl->close();
      _aidl_ret_status = AParcel_writeStatusHeader(_aidl_out, _aidl_status.get());
      if (_aidl_ret_status != STATUS_OK) break;

      if (!AStatus_isOk(_aidl_status.get())) break;

      break;
    }
    case (FIRST_CALL_TRANSACTION + 1 /*getCaptureResultMetadataQueue*/): {
      ::aidl::android::hardware::common::fmq::MQDescriptor<int8_t, ::aidl::android::hardware::common::fmq::SynchronizedReadWrite> _aidl_return;

      ::ndk::ScopedAStatus _aidl_status = _aidl_impl->getCaptureResultMetadataQueue(&_aidl_return);
      _aidl_ret_status = AParcel_writeStatusHeader(_aidl_out, _aidl_status.get());
      if (_aidl_ret_status != STATUS_OK) break;

      if (!AStatus_isOk(_aidl_status.get())) break;

      _aidl_ret_status = ::ndk::AParcel_writeData(_aidl_out, _aidl_return);
      if (_aidl_ret_status != STATUS_OK) break;

      break;
    }
    case (FIRST_CALL_TRANSACTION + 2 /*setCallback*/): {
      std::shared_ptr<::aidl::android::hardware::camera::device::ICameraDeviceCallback> in_cb;

      _aidl_ret_status = ::ndk::AParcel_readData(_aidl_in, &in_cb);
      if (_aidl_ret_status != STATUS_OK) break;

      ::ndk::ScopedAStatus _aidl_status = _aidl_impl->setCallback(in_cb);
      _aidl_ret_status = AParcel_writeStatusHeader(_aidl_out, _aidl_status.get());
      if (_aidl_ret_status != STATUS_OK) break;

      if (!AStatus_isOk(_aidl_status.get())) break;

      break;
    }
    case (FIRST_CALL_TRANSACTION + 16777214 /*getInterfaceVersion*/): {
      int32_t _aidl_return;

      ::ndk::ScopedAStatus _aidl_status = _aidl_impl->getInterfaceVersion(&_aidl_return);
      _aidl_ret_status = AParcel_writeStatusHeader(_aidl_out, _aidl_status.get());
      if (_aidl_ret_status != STATUS_OK) break;

      if (!AStatus_isOk(_aidl_status.get())) break;

      _aidl_ret_status = ::ndk::AParcel_writeData(_aidl_out, _aidl_return);
      if (_aidl_ret_status != STATUS_OK) break;

      break;
    }
  }
  return _aidl_ret_status;
}

static AIBinder_Class* _g_aidl_android_hardware_camera_device_ICameraOfflineSession_clazz = ::ndk::ICInterface::defineClass(ICameraOfflineSession::descriptor, _aidl_android_hardware_camera_device_ICameraOfflineSession_onTransact);

BpCameraOfflineSession::BpCameraOfflineSession(const ::ndk::SpAIBinder& binder) : BpCInterface(binder) {}
BpCameraOfflineSession::~BpCameraOfflineSession() {}

::ndk::ScopedAStatus BpCameraOfflineSession::close() {
  binder_status_t _aidl_ret_status = STATUS_OK;
  ::ndk::ScopedAStatus _aidl_status;
  ::ndk::ScopedAParcel _aidl_in;
  ::ndk::ScopedAParcel _aidl_out;

  _aidl_ret_status = AIBinder_prepareTransaction(asBinder().get(), _aidl_in.getR());
  if (_aidl_ret_status != STATUS_OK) goto _aidl_error;

  _aidl_ret_status = AIBinder_transact(
    asBinder().get(),
    (FIRST_CALL_TRANSACTION + 0 /*close*/),
    _aidl_in.getR(),
    _aidl_out.getR(),
    0
    #ifdef BINDER_STABILITY_SUPPORT
    | FLAG_PRIVATE_LOCAL
    #endif  // BINDER_STABILITY_SUPPORT
    );
  if (_aidl_ret_status == STATUS_UNKNOWN_TRANSACTION && ICameraOfflineSession::getDefaultImpl()) {
    _aidl_status = ICameraOfflineSession::getDefaultImpl()->close();
    goto _aidl_status_return;
  }
  if (_aidl_ret_status != STATUS_OK) goto _aidl_error;

  _aidl_ret_status = AParcel_readStatusHeader(_aidl_out.get(), _aidl_status.getR());
  if (_aidl_ret_status != STATUS_OK) goto _aidl_error;

  if (!AStatus_isOk(_aidl_status.get())) goto _aidl_status_return;
  _aidl_error:
  _aidl_status.set(AStatus_fromStatus(_aidl_ret_status));
  _aidl_status_return:
  return _aidl_status;
}
::ndk::ScopedAStatus BpCameraOfflineSession::getCaptureResultMetadataQueue(::aidl::android::hardware::common::fmq::MQDescriptor<int8_t, ::aidl::android::hardware::common::fmq::SynchronizedReadWrite>* _aidl_return) {
  binder_status_t _aidl_ret_status = STATUS_OK;
  ::ndk::ScopedAStatus _aidl_status;
  ::ndk::ScopedAParcel _aidl_in;
  ::ndk::ScopedAParcel _aidl_out;

  _aidl_ret_status = AIBinder_prepareTransaction(asBinder().get(), _aidl_in.getR());
  if (_aidl_ret_status != STATUS_OK) goto _aidl_error;

  _aidl_ret_status = AIBinder_transact(
    asBinder().get(),
    (FIRST_CALL_TRANSACTION + 1 /*getCaptureResultMetadataQueue*/),
    _aidl_in.getR(),
    _aidl_out.getR(),
    0
    #ifdef BINDER_STABILITY_SUPPORT
    | FLAG_PRIVATE_LOCAL
    #endif  // BINDER_STABILITY_SUPPORT
    );
  if (_aidl_ret_status == STATUS_UNKNOWN_TRANSACTION && ICameraOfflineSession::getDefaultImpl()) {
    _aidl_status = ICameraOfflineSession::getDefaultImpl()->getCaptureResultMetadataQueue(_aidl_return);
    goto _aidl_status_return;
  }
  if (_aidl_ret_status != STATUS_OK) goto _aidl_error;

  _aidl_ret_status = AParcel_readStatusHeader(_aidl_out.get(), _aidl_status.getR());
  if (_aidl_ret_status != STATUS_OK) goto _aidl_error;

  if (!AStatus_isOk(_aidl_status.get())) goto _aidl_status_return;
  _aidl_ret_status = ::ndk::AParcel_readData(_aidl_out.get(), _aidl_return);
  if (_aidl_ret_status != STATUS_OK) goto _aidl_error;

  _aidl_error:
  _aidl_status.set(AStatus_fromStatus(_aidl_ret_status));
  _aidl_status_return:
  return _aidl_status;
}
::ndk::ScopedAStatus BpCameraOfflineSession::setCallback(const std::shared_ptr<::aidl::android::hardware::camera::device::ICameraDeviceCallback>& in_cb) {
  binder_status_t _aidl_ret_status = STATUS_OK;
  ::ndk::ScopedAStatus _aidl_status;
  ::ndk::ScopedAParcel _aidl_in;
  ::ndk::ScopedAParcel _aidl_out;

  _aidl_ret_status = AIBinder_prepareTransaction(asBinder().get(), _aidl_in.getR());
  if (_aidl_ret_status != STATUS_OK) goto _aidl_error;

  _aidl_ret_status = ::ndk::AParcel_writeData(_aidl_in.get(), in_cb);
  if (_aidl_ret_status != STATUS_OK) goto _aidl_error;

  _aidl_ret_status = AIBinder_transact(
    asBinder().get(),
    (FIRST_CALL_TRANSACTION + 2 /*setCallback*/),
    _aidl_in.getR(),
    _aidl_out.getR(),
    0
    #ifdef BINDER_STABILITY_SUPPORT
    | FLAG_PRIVATE_LOCAL
    #endif  // BINDER_STABILITY_SUPPORT
    );
  if (_aidl_ret_status == STATUS_UNKNOWN_TRANSACTION && ICameraOfflineSession::getDefaultImpl()) {
    _aidl_status = ICameraOfflineSession::getDefaultImpl()->setCallback(in_cb);
    goto _aidl_status_return;
  }
  if (_aidl_ret_status != STATUS_OK) goto _aidl_error;

  _aidl_ret_status = AParcel_readStatusHeader(_aidl_out.get(), _aidl_status.getR());
  if (_aidl_ret_status != STATUS_OK) goto _aidl_error;

  if (!AStatus_isOk(_aidl_status.get())) goto _aidl_status_return;
  _aidl_error:
  _aidl_status.set(AStatus_fromStatus(_aidl_ret_status));
  _aidl_status_return:
  return _aidl_status;
}
::ndk::ScopedAStatus BpCameraOfflineSession::getInterfaceVersion(int32_t* _aidl_return) {
  binder_status_t _aidl_ret_status = STATUS_OK;
  ::ndk::ScopedAStatus _aidl_status;
  if (_aidl_cached_version != -1) {
    *_aidl_return = _aidl_cached_version;
    _aidl_status.set(AStatus_fromStatus(_aidl_ret_status));
    return _aidl_status;
  }
  ::ndk::ScopedAParcel _aidl_in;
  ::ndk::ScopedAParcel _aidl_out;

  _aidl_ret_status = AIBinder_prepareTransaction(asBinder().get(), _aidl_in.getR());
  if (_aidl_ret_status != STATUS_OK) goto _aidl_error;

  _aidl_ret_status = AIBinder_transact(
    asBinder().get(),
    (FIRST_CALL_TRANSACTION + 16777214 /*getInterfaceVersion*/),
    _aidl_in.getR(),
    _aidl_out.getR(),
    0
    #ifdef BINDER_STABILITY_SUPPORT
    | FLAG_PRIVATE_LOCAL
    #endif  // BINDER_STABILITY_SUPPORT
    );
  if (_aidl_ret_status == STATUS_UNKNOWN_TRANSACTION && ICameraOfflineSession::getDefaultImpl()) {
    _aidl_status = ICameraOfflineSession::getDefaultImpl()->getInterfaceVersion(_aidl_return);
    goto _aidl_status_return;
  }
  if (_aidl_ret_status != STATUS_OK) goto _aidl_error;

  _aidl_ret_status = AParcel_readStatusHeader(_aidl_out.get(), _aidl_status.getR());
  if (_aidl_ret_status != STATUS_OK) goto _aidl_error;

  if (!AStatus_isOk(_aidl_status.get())) goto _aidl_status_return;
  _aidl_ret_status = ::ndk::AParcel_readData(_aidl_out.get(), _aidl_return);
  if (_aidl_ret_status != STATUS_OK) goto _aidl_error;

  _aidl_cached_version = *_aidl_return;
  _aidl_error:
  _aidl_status.set(AStatus_fromStatus(_aidl_ret_status));
  _aidl_status_return:
  return _aidl_status;
}
// Source for BnCameraOfflineSession
BnCameraOfflineSession::BnCameraOfflineSession() {}
BnCameraOfflineSession::~BnCameraOfflineSession() {}
::ndk::SpAIBinder BnCameraOfflineSession::createBinder() {
  AIBinder* binder = AIBinder_new(_g_aidl_android_hardware_camera_device_ICameraOfflineSession_clazz, static_cast<void*>(this));
  #ifdef BINDER_STABILITY_SUPPORT
  AIBinder_markVintfStability(binder);
  #endif  // BINDER_STABILITY_SUPPORT
  return ::ndk::SpAIBinder(binder);
}
::ndk::ScopedAStatus BnCameraOfflineSession::getInterfaceVersion(int32_t* _aidl_return) {
  *_aidl_return = ICameraOfflineSession::version;
  return ::ndk::ScopedAStatus(AStatus_newOk());
}
// Source for ICameraOfflineSession
const char* ICameraOfflineSession::descriptor = "android.hardware.camera.device.ICameraOfflineSession";
ICameraOfflineSession::ICameraOfflineSession() {}
ICameraOfflineSession::~ICameraOfflineSession() {}


std::shared_ptr<ICameraOfflineSession> ICameraOfflineSession::fromBinder(const ::ndk::SpAIBinder& binder) {
  if (!AIBinder_associateClass(binder.get(), _g_aidl_android_hardware_camera_device_ICameraOfflineSession_clazz)) {
    #if __ANDROID_API__ >= 31
    const AIBinder_Class* originalClass = AIBinder_getClass(binder.get());
    if (originalClass == nullptr) return nullptr;
    if (0 == strcmp(AIBinder_Class_getDescriptor(originalClass), descriptor)) {
      return ::ndk::SharedRefBase::make<BpCameraOfflineSession>(binder);
    }
    #endif
    return nullptr;
  }
  std::shared_ptr<::ndk::ICInterface> interface = ::ndk::ICInterface::asInterface(binder.get());
  if (interface) {
    return std::static_pointer_cast<ICameraOfflineSession>(interface);
  }
  return ::ndk::SharedRefBase::make<BpCameraOfflineSession>(binder);
}

binder_status_t ICameraOfflineSession::writeToParcel(AParcel* parcel, const std::shared_ptr<ICameraOfflineSession>& instance) {
  return AParcel_writeStrongBinder(parcel, instance ? instance->asBinder().get() : nullptr);
}
binder_status_t ICameraOfflineSession::readFromParcel(const AParcel* parcel, std::shared_ptr<ICameraOfflineSession>* instance) {
  ::ndk::SpAIBinder binder;
  binder_status_t status = AParcel_readStrongBinder(parcel, binder.getR());
  if (status != STATUS_OK) return status;
  *instance = ICameraOfflineSession::fromBinder(binder);
  return STATUS_OK;
}
bool ICameraOfflineSession::setDefaultImpl(const std::shared_ptr<ICameraOfflineSession>& impl) {
  // Only one user of this interface can use this function
  // at a time. This is a heuristic to detect if two different
  // users in the same process use this function.
  assert(!ICameraOfflineSession::default_impl);
  if (impl) {
    ICameraOfflineSession::default_impl = impl;
    return true;
  }
  return false;
}
const std::shared_ptr<ICameraOfflineSession>& ICameraOfflineSession::getDefaultImpl() {
  return ICameraOfflineSession::default_impl;
}
std::shared_ptr<ICameraOfflineSession> ICameraOfflineSession::default_impl = nullptr;
::ndk::ScopedAStatus ICameraOfflineSessionDefault::close() {
  ::ndk::ScopedAStatus _aidl_status;
  _aidl_status.set(AStatus_fromStatus(STATUS_UNKNOWN_TRANSACTION));
  return _aidl_status;
}
::ndk::ScopedAStatus ICameraOfflineSessionDefault::getCaptureResultMetadataQueue(::aidl::android::hardware::common::fmq::MQDescriptor<int8_t, ::aidl::android::hardware::common::fmq::SynchronizedReadWrite>* /*_aidl_return*/) {
  ::ndk::ScopedAStatus _aidl_status;
  _aidl_status.set(AStatus_fromStatus(STATUS_UNKNOWN_TRANSACTION));
  return _aidl_status;
}
::ndk::ScopedAStatus ICameraOfflineSessionDefault::setCallback(const std::shared_ptr<::aidl::android::hardware::camera::device::ICameraDeviceCallback>& /*in_cb*/) {
  ::ndk::ScopedAStatus _aidl_status;
  _aidl_status.set(AStatus_fromStatus(STATUS_UNKNOWN_TRANSACTION));
  return _aidl_status;
}
::ndk::ScopedAStatus ICameraOfflineSessionDefault::getInterfaceVersion(int32_t* _aidl_return) {
  *_aidl_return = 0;
  return ::ndk::ScopedAStatus(AStatus_newOk());
}
::ndk::SpAIBinder ICameraOfflineSessionDefault::asBinder() {
  return ::ndk::SpAIBinder();
}
bool ICameraOfflineSessionDefault::isRemote() {
  return false;
}
}  // namespace device
}  // namespace camera
}  // namespace hardware
}  // namespace android
}  // namespace aidl
