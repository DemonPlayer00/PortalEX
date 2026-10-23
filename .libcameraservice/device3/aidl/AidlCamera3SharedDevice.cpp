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
#define LOG_TAG "AidlCamera3-SharedDevice"
#define ATRACE_TAG ATRACE_TAG_CAMERA
//#define LOG_NDEBUG 0
//#define LOG_NNDEBUG 0  // Per-frame verbose logging

#ifdef LOG_NNDEBUG
#define ALOGVV(...) ALOGV(__VA_ARGS__)
#else
#define ALOGVV(...) ((void)0)
#endif

// Convenience macro for transient errors
#define CLOGE(fmt, ...) ALOGE("Camera %s: %s: " fmt, mId.c_str(), __FUNCTION__, \
            ##__VA_ARGS__)

#define CLOGW(fmt, ...) ALOGW("Camera %s: %s: " fmt, mId.c_str(), __FUNCTION__, \
            ##__VA_ARGS__)

// Convenience macros for transitioning to the error state
#define SET_ERR(fmt, ...) setErrorState(   \
    "%s: " fmt, __FUNCTION__,              \
    ##__VA_ARGS__)
#define SET_ERR_L(fmt, ...) setErrorStateLocked( \
    "%s: " fmt, __FUNCTION__,                    \
    ##__VA_ARGS__)
#define DECODE_VALUE(decoder, type, var) \
  do { \
    if (decoder.get##type(var) != OK) { \
      return NOT_ENOUGH_DATA; \
    } \
  } while (0)

#include <utils/Log.h>
#include <utils/Trace.h>
#include <cstring>
#include "../../common/aidl/AidlProviderInfo.h"
#include "utils/SessionConfigurationUtils.h"
#include "AidlCamera3SharedDevice.h"

using namespace android::camera3;
using namespace android::camera3::SessionConfigurationUtils;

namespace android {

// Metadata android.info.availableSharedOutputConfigurations has list of shared output
// configurations. Each output configuration has minimum of 11 entries of size long
// followed by the physical camera id if present.
// See android.info.availableSharedOutputConfigurations for details.
static const int SHARED_OUTPUT_CONFIG_NUM_OF_ENTRIES = 11;
std::map<std::string, sp<AidlCamera3SharedDevice>> AidlCamera3SharedDevice::sSharedDevices;
std::map<std::string, std::unordered_set<int>> AidlCamera3SharedDevice::sClientsUid;
sp<AidlCamera3SharedDevice> AidlCamera3SharedDevice::getInstance(
        std::shared_ptr<CameraServiceProxyWrapper>& cameraServiceProxyWrapper,
        std::shared_ptr<AttributionAndPermissionUtils> attributionAndPermissionUtils,
        const std::string& id, bool overrideForPerfClass, int rotationOverride,
        bool legacyClient) {
    if (sClientsUid[id].empty()) {
        AidlCamera3SharedDevice* sharedDevice = new AidlCamera3SharedDevice(
                cameraServiceProxyWrapper, attributionAndPermissionUtils, id, overrideForPerfClass,
                rotationOverride, legacyClient);
        sSharedDevices[id] = sharedDevice;
    }
    if (attributionAndPermissionUtils != nullptr) {
        sClientsUid[id].insert(attributionAndPermissionUtils->getCallingUid());
    }
    return sSharedDevices[id];
}

status_t AidlCamera3SharedDevice::initialize(sp<CameraProviderManager> manager,
        const std::string& monitorTags) {
    ATRACE_CALL();
    status_t res = OK;

    if (mStatus == STATUS_UNINITIALIZED) {
        res = AidlCamera3Device::initialize(manager, monitorTags);
        if (res == OK) {
            mSharedOutputConfigurations = getSharedOutputConfiguration();
        }
    }
    return res;
}

status_t AidlCamera3SharedDevice::disconnectClient(int clientUid) {
    if (sClientsUid[mId].erase(clientUid) == 0) {
        ALOGW("%s: Camera %s: Client %d is not connected to shared device", __FUNCTION__,
                mId.c_str(), clientUid);
    }
    if (sClientsUid[mId].empty()) {
        return Camera3Device::disconnect();
    }
    return OK;
}

std::vector<OutputConfiguration> AidlCamera3SharedDevice::getSharedOutputConfiguration() {
    std::vector<OutputConfiguration> sharedConfigs;
    uint8_t colorspace = ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_UNSPECIFIED;
    camera_metadata_entry sharedSessionColorSpace = mDeviceInfo.find(
            ANDROID_SHARED_SESSION_COLOR_SPACE);
    if (sharedSessionColorSpace.count > 0) {
        colorspace = *sharedSessionColorSpace.data.u8;
    }
    camera_metadata_entry sharedSessionConfigs = mDeviceInfo.find(
            ANDROID_SHARED_SESSION_OUTPUT_CONFIGURATIONS);
    if (sharedSessionConfigs.count > 0) {
        int numOfEntries = sharedSessionConfigs.count;
        int i = 0;
        uint8_t physicalCameraIdLen;
        int surfaceType, width, height, format, mirrorMode, timestampBase, dataspace;
        long usage, streamUseCase;
        bool isReadOutTimestampEnabled;
        while (numOfEntries >= SHARED_OUTPUT_CONFIG_NUM_OF_ENTRIES) {
            surfaceType = (int)sharedSessionConfigs.data.i64[i];
            width = (int)sharedSessionConfigs.data.i64[i+1];
            height = (int)sharedSessionConfigs.data.i64[i+2];
            format = (int)sharedSessionConfigs.data.i64[i+3];
            mirrorMode = (int)sharedSessionConfigs.data.i64[i+4];
            isReadOutTimestampEnabled = (sharedSessionConfigs.data.i64[i+5] != 0);
            timestampBase = (int)sharedSessionConfigs.data.i64[i+6];
            dataspace = (int)sharedSessionConfigs.data.i64[i+7];
            usage = sharedSessionConfigs.data.i64[i+8];
            streamUseCase = sharedSessionConfigs.data.i64[i+9];
            physicalCameraIdLen = sharedSessionConfigs.data.i64[i+10];
            numOfEntries -= SHARED_OUTPUT_CONFIG_NUM_OF_ENTRIES;
            i += SHARED_OUTPUT_CONFIG_NUM_OF_ENTRIES;
            if (numOfEntries < physicalCameraIdLen) {
                ALOGE("%s: Camera %s: Number of remaining data (%d entries) in shared configuration"
                        " is less than physical camera id length %d. Malformed metadata"
                        " android.info.availableSharedOutputConfigurations.", __FUNCTION__,
                        mId.c_str(), numOfEntries, physicalCameraIdLen);
                break;
            }
            std::string physicalCameraId;
            long asciiValue;
            for (int j = 0; j < physicalCameraIdLen; j++) {
                asciiValue = sharedSessionConfigs.data.i64[i+j];
                if (asciiValue == 0) { // Check for null terminator
                    break;
                }
                physicalCameraId += static_cast<char>(asciiValue);
            }
            OutputConfiguration* outConfig = new OutputConfiguration(surfaceType, width, height,
                    format, colorspace, mirrorMode, isReadOutTimestampEnabled, timestampBase,
                    dataspace, usage, streamUseCase, physicalCameraId);
            sharedConfigs.push_back(*outConfig);
            i += physicalCameraIdLen;
            numOfEntries -= physicalCameraIdLen;
        }
        if (numOfEntries != 0) {
            ALOGE("%s: Camera %s: there are still %d entries left in shared output configuration."
                    " Malformed metadata android.info.availableSharedOutputConfigurations.",
                    __FUNCTION__, mId.c_str(), numOfEntries);
        }
    }
    return sharedConfigs;
}

status_t AidlCamera3SharedDevice::beginConfigure() {
    status_t res;
    int i = 0;

    if (mStatus != STATUS_UNCONFIGURED) {
        return OK;
    }

    for (auto config : mSharedOutputConfigurations) {
        std::vector<SurfaceHolder> consumers;
        android_dataspace dataSpace;
        if (config.getColorSpace()
                != ANDROID_REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_UNSPECIFIED
                && config.getFormat() != HAL_PIXEL_FORMAT_BLOB) {
            if (!dataSpaceFromColorSpace(&dataSpace, config.getColorSpace())) {
                std::string msg = fmt::sprintf("Camera %s: color space %d not supported, "
                    " failed to convert to data space", mId.c_str(), config.getColorSpace());
                ALOGE("%s: %s", __FUNCTION__, msg.c_str());
                return INVALID_OPERATION;
            }
        }
        std::unordered_set<int32_t> overriddenSensorPixelModes;
        if (checkAndOverrideSensorPixelModesUsed(config.getSensorPixelModesUsed(),
                config.getFormat(), config.getWidth(), config.getHeight(),
                mDeviceInfo, &overriddenSensorPixelModes) != OK) {
            std::string msg = fmt::sprintf("Camera %s: sensor pixel modes for stream with "
                        "format %#x are not valid",mId.c_str(), config.getFormat());
            ALOGE("%s: %s", __FUNCTION__, msg.c_str());
            return INVALID_OPERATION;
        }
        sp<IGraphicBufferProducer> producer;
        sp<IGraphicBufferConsumer> consumer;
        BufferQueue::createBufferQueue(&producer, &consumer);
        mSharedSurfaces[i] = new Surface(producer);
        consumers.push_back({mSharedSurfaces[i], config.getMirrorMode()});
        mSharedStreams[i] = new Camera3SharedOutputStream(mNextStreamId, consumers,
                config.getWidth(),config.getHeight(), config.getFormat(), config.getUsage(),
                dataSpace, static_cast<camera_stream_rotation_t>(config.getRotation()),
                mTimestampOffset, config.getPhysicalCameraId(), overriddenSensorPixelModes,
                getTransportType(), config.getSurfaceSetID(), mUseHalBufManager,
                config.getDynamicRangeProfile(), config.getStreamUseCase(),
                mDeviceTimeBaseIsRealtime, config.getTimestampBase(),
                config.getColorSpace(), config.useReadoutTimestamp());
        int id = mSharedStreams[i]->getSurfaceId(consumers[0].mSurface);
        if (id < 0) {
            SET_ERR_L("Invalid surface id");
            return BAD_VALUE;
        }
        mSharedSurfaceIds[i] = id;
        mSharedStreams[i]->setStatusTracker(mStatusTracker);
        mSharedStreams[i]->setBufferManager(mBufferManager);
        mSharedStreams[i]->setImageDumpMask(mImageDumpMask);
        res = mOutputStreams.add(mNextStreamId, mSharedStreams[i]);
        if (res < 0) {
            SET_ERR_L("Can't add new stream to set: %s (%d)", strerror(-res), res);
            return res;
        }
        mSessionStatsBuilder.addStream(mNextStreamId);
        mConfiguredOutputs.add(mNextStreamId++, config);
        i++;
    }
    CameraMetadata sessionParams;
    res = configureStreams(sessionParams, CAMERA_STREAM_CONFIGURATION_SHARED_MODE);
    if (res != OK) {
        std::string msg = fmt::sprintf("Camera %s: Error configuring streams: %s (%d)",
                mId.c_str(), strerror(-res), res);
        ALOGE("%s: %s", __FUNCTION__, msg.c_str());
        return res;
    }
    return OK;
}

status_t AidlCamera3SharedDevice::getSharedStreamId(const OutputConfiguration &config,
        int *streamId) {
    if (streamId ==  nullptr) {
        return BAD_VALUE;
    }
    for (size_t i = 0 ; i < mConfiguredOutputs.size(); i++){
        OutputConfiguration sharedConfig = mConfiguredOutputs.valueAt(i);
        if (config.sharedConfigEqual(sharedConfig)) {
            *streamId = mConfiguredOutputs.keyAt(i);
            return OK;
        }
    }
    return INVALID_OPERATION;
}

status_t AidlCamera3SharedDevice::addSharedSurfaces(int streamId,
        const std::vector<android::camera3::OutputStreamInfo> &outputInfo,
        const std::vector<SurfaceHolder> &surfaces,  std::vector<int> *surfaceIds) {
    KeyedVector<sp<Surface>, size_t> outputMap;
    std::vector<size_t> removedSurfaceIds;
    status_t res;
    sp<Camera3OutputStreamInterface> stream = mOutputStreams.get(streamId);
    if (stream == nullptr) {
        CLOGE("Stream %d is unknown", streamId);
        return BAD_VALUE;
    }

    res = updateStream(streamId, surfaces, outputInfo, removedSurfaceIds, &outputMap);
    if (res != OK) {
        CLOGE("Stream %d failed to update stream (error %d %s) ",
              streamId, res, strerror(-res));
        return res;
    }

    for (size_t i = 0 ; i < outputMap.size(); i++){
        if (surfaceIds != nullptr) {
            surfaceIds->push_back(outputMap.valueAt(i));
        }
    }
    return OK;
}

status_t AidlCamera3SharedDevice::removeSharedSurfaces(int streamId,
        const std::vector<size_t> &removedSurfaceIds) {
    KeyedVector<sp<Surface>, size_t> outputMap;
    std::vector<SurfaceHolder> surfaces;
    std::vector<OutputStreamInfo> outputInfo;
    status_t res;
    sp<Camera3OutputStreamInterface> stream = mOutputStreams.get(streamId);
    if (stream == nullptr) {
        CLOGE("Stream %d is unknown", streamId);
        return BAD_VALUE;
    }

    res = updateStream(streamId, surfaces, outputInfo, removedSurfaceIds, &outputMap);
    if (res != OK) {
        CLOGE("Stream %d failed to update stream (error %d %s) ",
              streamId, res, strerror(-res));
        return res;
    }
    return OK;
}
}
