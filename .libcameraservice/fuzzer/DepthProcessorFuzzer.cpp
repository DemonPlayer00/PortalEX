/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "common/DepthPhotoProcessor.h"

#include <random>

#include <fuzzer/FuzzedDataProvider.h>

using namespace android;
using namespace android::camera3;

static const float kMinRatio = 0.1f;
static const float kMaxRatio = 0.9f;

static const uint8_t kTotalDepthJpegBufferCount = 3;
static const uint8_t kIntrinsicCalibrationSize = 5;
static const uint8_t kLensDistortionSize = 5;

static const DepthPhotoOrientation kDepthPhotoOrientations[] = {
        DepthPhotoOrientation::DEPTH_ORIENTATION_0_DEGREES,
        DepthPhotoOrientation::DEPTH_ORIENTATION_90_DEGREES,
        DepthPhotoOrientation::DEPTH_ORIENTATION_180_DEGREES,
        DepthPhotoOrientation::DEPTH_ORIENTATION_270_DEGREES};

void generateDepth16Buffer(std::vector<uint16_t>* depth16Buffer /*out*/, size_t length,
                           FuzzedDataProvider& fdp) {
    std::default_random_engine gen(fdp.ConsumeIntegral<uint8_t>());
    std::uniform_int_distribution uniDist(0, UINT16_MAX - 1);
    for (size_t i = 0; i < length; ++i) {
        (*depth16Buffer)[i] = uniDist(gen);
    }
}

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    FuzzedDataProvider fdp(data, size);

    DepthPhotoInputFrame inputFrame;

    /**
     * Consuming 80% of the data to set mMainJpegBuffer. This ensures that we
     * don't completely exhaust data and use the rest 20% for fuzzing of APIs.
     */
    std::vector<uint8_t> buffer = fdp.ConsumeBytes<uint8_t>((size * 80) / 100);
    inputFrame.mMainJpegBuffer = reinterpret_cast<const char*>(buffer.data());

    /**
     * Calculate height and width based on buffer size and a ratio within [0.1, 0.9].
     * The ratio adjusts the dimensions while maintaining a relationship to the total buffer size.
     */
    const float ratio = fdp.ConsumeFloatingPointInRange<float>(kMinRatio, kMaxRatio);
    const size_t height = std::sqrt(buffer.size()) * ratio;
    const size_t width = std::sqrt(buffer.size()) / ratio;

    inputFrame.mMainJpegHeight = height;
    inputFrame.mMainJpegWidth = width;
    inputFrame.mMainJpegSize = buffer.size();
    // Worst case both depth and confidence maps have the same size as the main color image.
    inputFrame.mMaxJpegSize = inputFrame.mMainJpegSize * kTotalDepthJpegBufferCount;

    std::vector<uint16_t> depth16Buffer(height * width);
    generateDepth16Buffer(&depth16Buffer, height * width, fdp);
    inputFrame.mDepthMapBuffer = depth16Buffer.data();
    inputFrame.mDepthMapHeight = height;
    inputFrame.mDepthMapWidth = inputFrame.mDepthMapStride = width;

    inputFrame.mIsLogical = fdp.ConsumeBool();

    inputFrame.mOrientation = fdp.PickValueInArray<DepthPhotoOrientation>(kDepthPhotoOrientations);

    if (fdp.ConsumeBool()) {
        for (uint8_t i = 0; i < kIntrinsicCalibrationSize; ++i) {
            inputFrame.mIntrinsicCalibration[i] = fdp.ConsumeFloatingPoint<float>();
        }
        inputFrame.mIsIntrinsicCalibrationValid = 1;
    }

    if (fdp.ConsumeBool()) {
        for (uint8_t i = 0; i < kLensDistortionSize; ++i) {
            inputFrame.mLensDistortion[i] = fdp.ConsumeFloatingPoint<float>();
        }
        inputFrame.mIsLensDistortionValid = 1;
    }

    std::vector<uint8_t> depthPhotoBuffer(inputFrame.mMaxJpegSize);
    size_t actualDepthPhotoSize = 0;

    processDepthPhotoFrame(inputFrame, depthPhotoBuffer.size(), depthPhotoBuffer.data(),
                           &actualDepthPhotoSize);

    return 0;
}
