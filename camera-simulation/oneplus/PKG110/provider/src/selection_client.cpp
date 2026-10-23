#include "selection_client.h"

#include <android/log.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/uio.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <cstdio>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PortalVcam", __VA_ARGS__)

namespace portal {
namespace {
constexpr const char* kSocketName = "portalex_camera_mock";
}

namespace {
ServerSelection selection_from_file() {
    ServerSelection out;
    FILE* f = std::fopen("/data/system/portalex/camera/selection.txt", "r");
    if (!f) return out;
    char video[128]{}; long long start = 0; char path[512]{};
    if (std::fscanf(f, "%127s\t%lld\t%511s", video, &start, path) == 3) {
        std::strncpy(out.videoId, video, sizeof(out.videoId) - 1);
        std::strncpy(out.path, path, sizeof(out.path) - 1);
        out.startMs = start;
        out.valid = out.videoId[0] != '\0';
        out.fd = ::open(out.path, O_RDONLY | O_CLOEXEC);
        LOGI("selection file: video=%s start=%lld fd=%d", out.videoId, start, out.fd);
    }
    std::fclose(f);
    return out;
}
}
ServerSelection selection_request(const std::string& session) {
    ServerSelection out;
    int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return out;
    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    // Android LocalServerSocket 的抽象命名空间：sun_path[0] = '\0'
    addr.sun_path[0] = '\0';
    std::strncpy(addr.sun_path + 1, kSocketName, sizeof(addr.sun_path) - 2);
    socklen_t len = offsetof(sockaddr_un, sun_path) + 1 + std::strlen(kSocketName);
    if (::connect(fd, reinterpret_cast<sockaddr*>(&addr), len) != 0) {
        ::close(fd);
        return out;
    }
    // system_server 选片服务异常时不能把 camera request 永久挂住：
    // 200ms 超时后回退到旧的共享帧路径。
    struct timeval tv{};
    tv.tv_sec = 0;
    tv.tv_usec = 200000;
    ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    char req[512]{};
    std::snprintf(req, sizeof(req), "SELECT\tportal_provider\t\t\t0\t\t%s\n", session.c_str());
    (void)::write(fd, req, std::strlen(req));
    char buf[1024]{};
    char control[CMSG_SPACE(sizeof(int))]{};
    struct iovec iov{ buf, sizeof(buf) - 1 };
    struct msghdr msg{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    ssize_t n = ::recvmsg(fd, &msg, 0);
    for (struct cmsghdr* c = CMSG_FIRSTHDR(&msg); c != nullptr; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) {
            int received = -1;
            std::memcpy(&received, CMSG_DATA(c), sizeof(int));
            out.fd = received;
        }
    }
    ::close(fd);
    if (n <= 0) {
        if (out.fd >= 0) ::close(out.fd);
        return out;
    }
    buf[n] = '\0';
    if (std::strncmp(buf, "OK\t", 3) != 0) {
        LOGI("selection: %s", buf);
        return out;
    }
    char* save = nullptr;
    char* tok = strtok_r(buf + 3, "\t\n", &save);
    int field = 0;
    while (tok != nullptr) {
        switch (field) {
            case 0: std::strncpy(out.pointId, tok, sizeof(out.pointId) - 1); break;
            case 1: std::strncpy(out.videoId, tok, sizeof(out.videoId) - 1); break;
            case 2: std::strncpy(out.file, tok, sizeof(out.file) - 1); break;
            case 3: out.startMs = atoll(tok); break;
            case 4: out.durationMs = atoll(tok); break;
            case 5: std::strncpy(out.path, tok, sizeof(out.path) - 1); break;
            default: break;
        }
        field++;
        tok = strtok_r(nullptr, "\t\n", &save);
    }
    out.valid = out.videoId[0] != '\0';
    if (out.valid) {
        LOGI("selection: point=%s video=%s start=%lld path=%s",
             out.pointId, out.videoId, (long long)out.startMs, out.path);
    }
    return out;
}
}  // namespace portal
