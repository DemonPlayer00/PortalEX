/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ANDROID_SERVERS_AIDLCAMERA3SHAREDDEVICE_H
#define ANDROID_SERVERS_AIDLCAMERA3SHAREDDEVICE_H

#include <camera/camera2/OutputConfiguration.h>
#include "../Camera3SharedOutputStream.h"
#include "AidlCamera3Device.h"
namespace android {

/**
 * Shared CameraDevice for AIDL HAL devices.
 */
using ::android::camera3::Camera3SharedOutputStream;
class AidlCamera3SharedDevice :
        public AidlCamera3Device {
  public:
    static sp<AidlCamera3SharedDevice> getInstance(
            std::shared_ptr<CameraServiceProxyWrapper>& cameraServiceProxyWrapper,
            std::shared_ptr<AttributionAndPermissionUtils> attributionAndPermissionUtils,
            const std::string& id, bool overrideForPerfClass, int rotationOverride,
            bool legacyClient = false);
    status_t initialize(sp<CameraProviderManager> manager,
            const std::string& monitorTags) override;
    status_t disconnectClient(int clientUid) override;
    status_t beginConfigure() override;
    status_t getSharedStreamId(const OutputConfiguration &config, int *streamId) override;
    status_t addSharedSurfaces(int streamId,
            const std::vector<android::camera3::OutputStreamInfo> &outputInfo,
            const std::vector<SurfaceHolder>& surfaces,
            std::vector<int> *surfaceIds = nullptr) override;
    status_t removeSharedSurfaces(int streamId,
            const std::vector<size_t> &surfaceIds) override;
  private:
    static std::map<std::string, sp<AidlCamera3SharedDevice>> sSharedDevices;
    static std::map<std::string, std::unordered_set<int>> sClientsUid;
    AidlCamera3SharedDevice(
            std::shared_ptr<CameraServiceProxyWrapper>& cameraServiceProxyWrapper,
            std::shared_ptr<AttributionAndPermissionUtils> attributionAndPermissionUtils,
            const std::string& id, bool overrideForPerfClass, int rotationOverride,
            bool legacyClient)
        : AidlCamera3Device(cameraServiceProxyWrapper, attributionAndPermissionUtils, id,
                  overrideForPerfClass, rotationOverride, legacyClient) {}
    std::vector<OutputConfiguration> getSharedOutputConfiguration();
    std::vector<OutputConfiguration> mSharedOutputConfigurations;
    std::vector<int> mSharedSurfaceIds;
    std::vector<sp<Surface>> mSharedSurfaces;
    std::vector<sp<Camera3SharedOutputStream>> mSharedStreams;
    KeyedVector<int32_t, OutputConfiguration> mConfiguredOutputs;
}; // class AidlCamera3SharedDevice
}; // namespace android
#endif
