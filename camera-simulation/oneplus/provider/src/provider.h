#pragma once
#include <android/binder_auto_utils.h>
#include <android/binder_ibinder.h>
#include <android/binder_status.h>
AIBinder* portalWaitForService(const char* instance);
#include <aidl/android/hardware/camera/provider/BnCameraProvider.h>
#include <aidl/android/hardware/camera/device/BnCameraDevice.h>
#include <aidl/android/hardware/camera/device/BnCameraDeviceSession.h>
#include <aidl/android/hardware/camera/device/ICameraDeviceCallback.h>
#include <aidl/android/hardware/camera/device/CameraMetadata.h>
#include <aidl/android/hardware/camera/device/Stream.h>
#include <aidl/android/hardware/camera/device/HalStream.h>
#include <aidl/android/hardware/camera/device/StreamBuffer.h>
#include <aidl/android/hardware/camera/device/CaptureRequest.h>
#include <aidl/android/hardware/camera/device/CaptureResult.h>
#include <aidl/android/hardware/camera/device/NotifyMsg.h>
#include <aidl/android/hardware/camera/device/ShutterMsg.h>
#include <aidl/android/hardware/camera/provider/ICameraProviderCallback.h>
#include <aidl/android/hardware/camera/common/CameraResourceCost.h>
#include <map>
#include <mutex>
#include <string>
#include <vector>

struct native_handle_t;
namespace portal {

struct StreamInfo {
    int format = 0;
    int width = 0;
    int height = 0;
    int bufferSize = 0;
    bool blob = false;
};

class PortalSession : public aidl::android::hardware::camera::device::BnCameraDeviceSession {
  public:
    PortalSession(std::shared_ptr<aidl::android::hardware::camera::device::ICameraDeviceCallback> cb);
    ndk::ScopedAStatus close() override;
    ndk::ScopedAStatus configureStreams(
            const aidl::android::hardware::camera::device::StreamConfiguration& requested,
            std::vector<aidl::android::hardware::camera::device::HalStream>* out) override;
    ndk::ScopedAStatus configureStreamsV2(
            const aidl::android::hardware::camera::device::StreamConfiguration& requested,
            aidl::android::hardware::camera::device::ConfigureStreamsRet* out) override;
    ndk::ScopedAStatus constructDefaultRequestSettings(
            aidl::android::hardware::camera::device::RequestTemplate type,
            aidl::android::hardware::camera::device::CameraMetadata* out) override;
    ndk::ScopedAStatus flush() override;
    ndk::ScopedAStatus getCaptureRequestMetadataQueue(
            aidl::android::hardware::common::fmq::MQDescriptor<
                    int8_t, aidl::android::hardware::common::fmq::SynchronizedReadWrite>* out) override;
    ndk::ScopedAStatus getCaptureResultMetadataQueue(
            aidl::android::hardware::common::fmq::MQDescriptor<
                    int8_t, aidl::android::hardware::common::fmq::SynchronizedReadWrite>* out) override;
    ndk::ScopedAStatus isReconfigurationRequired(
            const aidl::android::hardware::camera::device::CameraMetadata& oldParams,
            const aidl::android::hardware::camera::device::CameraMetadata& newParams,
            bool* out) override;
    ndk::ScopedAStatus processCaptureRequest(
            const std::vector<aidl::android::hardware::camera::device::CaptureRequest>& requests,
            const std::vector<aidl::android::hardware::camera::device::BufferCache>& caches,
            int32_t* out) override;
    ndk::ScopedAStatus signalStreamFlush(const std::vector<int32_t>& streamIds, int32_t counter) override;
    ndk::ScopedAStatus switchToOffline(
            const std::vector<int32_t>& streamsToKeep,
            aidl::android::hardware::camera::device::CameraOfflineSessionInfo* outInfo,
            std::shared_ptr<aidl::android::hardware::camera::device::ICameraOfflineSession>* out) override;
    ndk::ScopedAStatus repeatingRequestEnd(int32_t frameNumber, const std::vector<int32_t>& streamIds) override;

  private:
    bool fillBuffer(const aidl::android::hardware::camera::device::StreamBuffer& sb,
                    const StreamInfo& info, int64_t timestamp,
                    void* cachedNh = nullptr);

    std::shared_ptr<aidl::android::hardware::camera::device::ICameraDeviceCallback> mCallback;
    /** 同一相机 session 的选片去重键：3 秒刷新时用于给上次视频加权。 */
    std::string mSessionId;
    std::map<int, StreamInfo> mStreams;
    /** AIDL 后续请求可能只给 bufferId，不重复给 native_handle；缓存首次句柄。 */
    std::map<std::pair<int32_t, int64_t>, void*> mBufferHandles;
    std::mutex mLock;
    bool mClosed = false;
};

class PortalDevice : public aidl::android::hardware::camera::device::BnCameraDevice {
  public:
    PortalDevice(aidl::android::hardware::camera::device::CameraMetadata chars,
                 std::shared_ptr<aidl::android::hardware::camera::device::ICameraDevice> vendorDev);
    ndk::ScopedAStatus getCameraCharacteristics(
            aidl::android::hardware::camera::device::CameraMetadata* out) override;
    ndk::ScopedAStatus getPhysicalCameraCharacteristics(
            const std::string& id, aidl::android::hardware::camera::device::CameraMetadata* out) override;
    ndk::ScopedAStatus getResourceCost(
            aidl::android::hardware::camera::common::CameraResourceCost* out) override;
    ndk::ScopedAStatus isStreamCombinationSupported(
            const aidl::android::hardware::camera::device::StreamConfiguration& config,
            bool* out) override;
    ndk::ScopedAStatus open(
            const std::shared_ptr<aidl::android::hardware::camera::device::ICameraDeviceCallback>& cb,
            std::shared_ptr<aidl::android::hardware::camera::device::ICameraDeviceSession>* out) override;
    ndk::ScopedAStatus openInjectionSession(
            const std::shared_ptr<aidl::android::hardware::camera::device::ICameraDeviceCallback>& cb,
            std::shared_ptr<aidl::android::hardware::camera::device::ICameraInjectionSession>* out) override;
    ndk::ScopedAStatus setTorchMode(bool on) override;
    ndk::ScopedAStatus turnOnTorchWithStrengthLevel(int32_t level) override;
    ndk::ScopedAStatus getTorchStrengthLevel(int32_t* out) override;
    ndk::ScopedAStatus constructDefaultRequestSettings(
            aidl::android::hardware::camera::device::RequestTemplate type,
            aidl::android::hardware::camera::device::CameraMetadata* out) override;
    ndk::ScopedAStatus isStreamCombinationWithSettingsSupported(
            const aidl::android::hardware::camera::device::StreamConfiguration& config,
            bool* out) override;
    ndk::ScopedAStatus getSessionCharacteristics(
            const aidl::android::hardware::camera::device::StreamConfiguration& config,
            aidl::android::hardware::camera::device::CameraMetadata* out) override;

  private:
    aidl::android::hardware::camera::device::CameraMetadata mChars;
    std::shared_ptr<aidl::android::hardware::camera::device::ICameraDevice> mVendorDev;
};

class PortalProvider
    : public aidl::android::hardware::camera::provider::BnCameraProvider {
  public:
    PortalProvider();
    ndk::ScopedAStatus setCallback(
            const std::shared_ptr<aidl::android::hardware::camera::provider::ICameraProviderCallback>& cb) override;
    ndk::ScopedAStatus getVendorTags(
            std::vector<aidl::android::hardware::camera::common::VendorTagSection>* out) override;
    ndk::ScopedAStatus getCameraIdList(std::vector<std::string>* out) override;
    ndk::ScopedAStatus getCameraDeviceInterface(
            const std::string& name,
            std::shared_ptr<aidl::android::hardware::camera::device::ICameraDevice>* out) override;
    ndk::ScopedAStatus notifyDeviceStateChange(int64_t state) override;
    ndk::ScopedAStatus getConcurrentCameraIds(
            std::vector<aidl::android::hardware::camera::provider::ConcurrentCameraIdCombination>* out) override;
    ndk::ScopedAStatus isConcurrentStreamCombinationSupported(
            const std::vector<aidl::android::hardware::camera::provider::CameraIdAndStreamCombination>& configs,
            bool* out) override;

  private:
    std::shared_ptr<aidl::android::hardware::camera::provider::ICameraProviderCallback> mCallback;
    aidl::android::hardware::camera::device::CameraMetadata mChars;
    std::vector<aidl::android::hardware::camera::common::VendorTagSection> mTags;
    std::shared_ptr<aidl::android::hardware::camera::device::ICameraDevice> mVendorDev;
    std::shared_ptr<aidl::android::hardware::camera::device::ICameraDevice> mLastDevice;
};

}  // namespace portal
