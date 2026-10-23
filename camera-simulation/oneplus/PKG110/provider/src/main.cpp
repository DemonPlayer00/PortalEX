#include "provider.h"

#include <dlfcn.h>
#include <cstdio>
#include <cstdlib>
#include <string>

int main() {
    void* lib = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_GLOBAL);
    if (!lib) { fprintf(stderr, "dlopen binder_ndk failed\n"); return 1; }
    auto setPool = (void (*)(uint32_t))dlsym(lib, "ABinderProcess_setThreadPoolMaxThreadCount");
    auto addSvc = (binder_status_t (*)(AIBinder*, const char*))dlsym(lib, "AServiceManager_addService");
    auto join = (void (*)())dlsym(lib, "ABinderProcess_joinThreadPool");
    if (!setPool || !addSvc || !join) { fprintf(stderr, "binder symbols missing\n"); return 1; }
    setPool(4);
    auto provider = ndk::SharedRefBase::make<portal::PortalProvider>();
    if (getenv("PORTAL_DUMP_META")) {
        return 0;
    }
    const char* inst = getenv("PORTAL_INSTANCE");
    std::string name = "android.hardware.camera.provider.ICameraProvider/";
    name += (inst && *inst) ? inst : "internal/1";
    binder_status_t st = addSvc(provider->asBinder().get(), name.c_str());
    if (st != STATUS_OK) { fprintf(stderr, "addService failed: %d\n", st); return 1; }
    join();
    return 0;
}

AIBinder* portalWaitForService(const char* instance) {
    static void* lib = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_GLOBAL);
    if (!lib) return nullptr;
    auto wait = (AIBinder* (*)(const char*))dlsym(lib, "AServiceManager_waitForService");
    return wait ? wait(instance) : nullptr;
}
