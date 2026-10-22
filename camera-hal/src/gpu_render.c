/*
 * GPU/EGL 渲染到 HAL 拿到的 gralloc buffer（PRIVATE 面）。
 *
 * 为什么需要：SurfaceView 预览的流是 IMPLEMENTATION_DEFINED（0x22），
 * 在 QTI 上通常是 UBWC/私有布局，CPU 按线性 YUV 写会黑屏/花屏。
 * 这里用 EGLImage + renderbuffer 把 buffer 变成 GL 渲染目标，由 GPU
 * 按 gralloc 的真实布局写入。
 *
 * 失败一律返回 false，由调用方回退到 CPU 路径；绝不 abort。
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/log.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#define LOG_TAG "PortalVCamGL"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* 只为了给 eglCreateImageKHR 一个 ANativeWindowBuffer*；EGL 实际读 handle。
 * 布局按 AOSP system/window.h（32 位）。 */
struct portal_native_base;
static void anb_inc(struct portal_native_base *b) { (void)b; }
static void anb_dec(struct portal_native_base *b) { (void)b; }
struct portal_native_base {
    int magic;
    int version;
    void (*incRef)(struct portal_native_base *);
    void (*decRef)(struct portal_native_base *);
};
struct portal_anb {
    struct portal_native_base common;
    int width;
    int height;
    int stride;
    int format;
    uint32_t usage;
    void *handle;
    void *reserved[8];
};

static EGLDisplay g_dpy = EGL_NO_DISPLAY;
static EGLContext g_ctx = EGL_NO_CONTEXT;
static EGLSurface g_surf = EGL_NO_SURFACE;
static PFNEGLCREATEIMAGEKHRPROC p_create_image;
static PFNEGLDESTROYIMAGEKHRPROC p_destroy_image;
static PFNGLEGLIMAGETARGETRENDERBUFFERSTORAGEOESPROC p_image_rb;

static GLuint g_prog, g_vbo, g_fbo, g_rb;
static GLint g_u_res = -1, g_u_frame = -1;
static bool g_tried, g_ok;

static const char *kVS =
    "attribute vec2 a_pos;\n"
    "void main() { gl_Position = vec4(a_pos, 0.0, 1.0); }\n";

static const char *kFS =
    "precision mediump float;\n"
    "uniform vec2 uRes;\n"
    "uniform float uFrame;\n"
    "void main() {\n"
    "  vec2 p = gl_FragCoord.xy;\n"
    "  float barW = uRes.x / 8.0;\n"
    "  float sx = mod(p.x + uFrame * 7.0, uRes.x);\n"
    "  float b = floor(sx / barW);\n"
    "  vec3 c = vec3(1.0, 0.0, 1.0);\n"
    "  if (b < 1.0) c = vec3(1.0, 0.0, 0.0);\n"
    "  else if (b < 2.0) c = vec3(1.0, 0.5, 0.0);\n"
    "  else if (b < 3.0) c = vec3(1.0, 1.0, 0.0);\n"
    "  else if (b < 4.0) c = vec3(0.0, 1.0, 0.0);\n"
    "  else if (b < 5.0) c = vec3(0.0, 1.0, 1.0);\n"
    "  else if (b < 6.0) c = vec3(0.0, 0.0, 1.0);\n"
    "  else if (b < 7.0) c = vec3(0.5, 0.0, 1.0);\n"
    "  float r = min(uRes.x, uRes.y) / 7.0;\n"
    "  float spanX = uRes.x - 2.0 * r;\n"
    "  float spanY = uRes.y - 2.0 * r;\n"
    "  float cx = r + mod(uFrame * 9.0, spanX);\n"
    "  float ty = mod(uFrame * 5.0, 2.0 * spanY);\n"
    "  float cy = r + (ty < spanY ? ty : 2.0 * spanY - ty);\n"
    "  float d = distance(p, vec2(cx, cy));\n"
    "  if (d < r) c = (d > r - 4.0) ? vec3(0.0) : vec3(1.0);\n"
    "  gl_FragColor = vec4(c, 1.0);\n"
    "}\n";

static GLuint compile(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    if (!s) return 0;
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[256];
        glGetShaderInfoLog(s, sizeof(log), NULL, log);
        LOGE("shader compile failed: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static bool gpu_init(void) {
    if (g_tried) return g_ok;
    g_tried = true;

    g_dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_dpy == EGL_NO_DISPLAY) { LOGE("eglGetDisplay failed"); return false; }
    EGLint major = 0, minor = 0;
    if (!eglInitialize(g_dpy, &major, &minor)) { LOGE("eglInitialize failed"); return false; }
    eglBindAPI(EGL_OPENGL_ES_API);

    EGLint cfg_attr[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLConfig cfg = NULL;
    EGLint n = 0;
    if (!eglChooseConfig(g_dpy, cfg_attr, &cfg, 1, &n) || n < 1) {
        LOGE("eglChooseConfig failed");
        return false;
    }
    EGLint pb_attr[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    g_surf = eglCreatePbufferSurface(g_dpy, cfg, pb_attr);
    if (g_surf == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface failed (0x%x)", eglGetError());
        return false;
    }
    EGLint ctx_attr[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    g_ctx = eglCreateContext(g_dpy, cfg, EGL_NO_CONTEXT, ctx_attr);
    if (g_ctx == EGL_NO_CONTEXT) { LOGE("eglCreateContext failed"); return false; }
    if (!eglMakeCurrent(g_dpy, g_surf, g_surf, g_ctx)) {
        LOGE("eglMakeCurrent failed (0x%x)", eglGetError());
        return false;
    }

    p_create_image = (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
    p_destroy_image = (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
    p_image_rb = (PFNGLEGLIMAGETARGETRENDERBUFFERSTORAGEOESPROC)
            eglGetProcAddress("glEGLImageTargetRenderbufferStorageOES");
    if (!p_create_image || !p_destroy_image || !p_image_rb) {
        LOGE("EGL image procs missing: create=%p destroy=%p rb=%p",
             (void *)p_create_image, (void *)p_destroy_image, (void *)p_image_rb);
        return false;
    }

    GLuint vs = compile(GL_VERTEX_SHADER, kVS);
    GLuint fs = compile(GL_FRAGMENT_SHADER, kFS);
    if (!vs || !fs) return false;
    g_prog = glCreateProgram();
    glAttachShader(g_prog, vs);
    glAttachShader(g_prog, fs);
    glBindAttribLocation(g_prog, 0, "a_pos");
    glLinkProgram(g_prog);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint linked = 0;
    glGetProgramiv(g_prog, GL_LINK_STATUS, &linked);
    if (!linked) { LOGE("program link failed"); return false; }
    g_u_res = glGetUniformLocation(g_prog, "uRes");
    g_u_frame = glGetUniformLocation(g_prog, "uFrame");

    static const GLfloat verts[] = {
        -1.f, -1.f,  1.f, -1.f,  -1.f, 1.f,  1.f, 1.f
    };
    glGenBuffers(1, &g_vbo);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
    glGenFramebuffers(1, &g_fbo);
    glGenRenderbuffers(1, &g_rb);

    g_ok = true;
    LOGI("EGL 就绪：EGL %d.%d，GL_RENDERER=%s", major, minor,
         (const char *)glGetString(GL_RENDERER));
    return true;
}

bool portal_gpu_paint(void *handle, uint32_t w, uint32_t h, uint32_t frame, int format) {
    if (handle == NULL || w == 0 || h == 0) return false;
    if (!gpu_init()) return false;

    struct portal_anb anb;
    memset(&anb, 0, sizeof(anb));
    anb.common.magic = 0x5f626e62; /* ANDROID_NATIVE_BUFFER_MAGIC */
    anb.common.version = (int)sizeof(anb);
    anb.common.incRef = anb_inc;
    anb.common.decRef = anb_dec;
    anb.width = (int)w;
    anb.height = (int)h;
    anb.stride = (int)w;
    anb.format = 1; /* 本机 gralloc ints 显示 4bpp / format=1 */
    anb.usage = 0x933;
    anb.handle = handle;

    EGLint img_attr[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    EGLImageKHR img = p_create_image(g_dpy, EGL_NO_CONTEXT,
            EGL_NATIVE_BUFFER_ANDROID, (EGLClientBuffer)&anb, img_attr);
    if (img == EGL_NO_IMAGE_KHR) {
        static bool logged = false;
        if (!logged) { logged = true; LOGE("eglCreateImageKHR failed (0x%x)", eglGetError()); }
        return false;
    }

    glBindRenderbuffer(GL_RENDERBUFFER, g_rb);
    p_image_rb(GL_RENDERBUFFER, img);
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        static bool logged = false;
        if (!logged) { logged = true; LOGE("glEGLImageTargetRenderbufferStorageOES failed 0x%x", err); }
        p_destroy_image(g_dpy, img);
        return false;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, g_rb);
    GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (st != GL_FRAMEBUFFER_COMPLETE) {
        static bool logged = false;
        if (!logged) { logged = true; LOGE("FBO incomplete 0x%x", st); }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        p_destroy_image(g_dpy, img);
        return false;
    }

    glViewport(0, 0, (GLsizei)w, (GLsizei)h);
    glUseProgram(g_prog);
    glUniform2f(g_u_res, (GLfloat)w, (GLfloat)h);
    glUniform1f(g_u_frame, (GLfloat)frame);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, (const void *)0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(0);
    glFinish();

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    p_destroy_image(g_dpy, img);
    return true;
}
