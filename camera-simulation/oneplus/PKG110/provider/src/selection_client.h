#pragma once
#include <cstdint>
#include <string>

namespace portal {

struct ServerSelection {
    bool valid = false;
    char pointId[128]{};
    char videoId[128]{};
    char file[256]{};
    char path[512]{};
    int64_t startMs = 0;
    int64_t durationMs = 0;
    int fd = -1;
};

/** 向 system_server 的 portalex_camera_mock 请求一次选片；失败返回 invalid。 */
ServerSelection selection_request(const std::string& session = std::string());

}  // namespace portal
