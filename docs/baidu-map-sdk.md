# 百度地图 / 定位 / 检索 SDK 配置要求

> 适用：PortalEX（`moe.fuqiuluo.portalex`）
> 用途：地图选点、地点搜索（联想检索）、逆地理编码、定位 SDK 初始化

## 1. 关键约束：AK 按「包名 + 签名 SHA1」双重校验

百度 LBS 的 Android 端 AK **不是**一个通用 key，它绑定：

- 应用包名（`applicationId`）
- 签名证书的 **SHA1** 指纹

只要其中一项不匹配，地图/定位/检索的**所有在线接口**都会失败：

- 地点搜索（`SuggestionSearch`）→ 回调空结果，界面表现为「未搜索到相关位置」
- 逆地理（`GeoCoder.reverseGeoCode`）→ 无回调结果，地址显示为「未知地址」
- logcat 中百度 SDK 会打印 AK 校验失败相关日志（过滤 `baidu` / `BDMap` / `AK`）

**本项目现状**：`AndroidManifest.xml` 里的 AK 来自上游工程 `moe.fuqiuluo.portal`（自 `Initial commit` 起未变更），
而本项目包名已改为 `moe.fuqiuluo.portalex`（提交 `3821a86`）→ 旧 AK 必然失效，必须重新申请。

## 2. 需要在百度地图开放平台做的配置

| 项 | 值 / 说明 |
| --- | --- |
| 平台入口 | 百度地图开放平台 → 控制台 → 应用管理 → 创建应用（**Android SDK**） |
| 应用包名 | `moe.fuqiuluo.portalex`（debug 与 release 同包名） |
| 签名 SHA1（本机 debug） | `F5:DA:DB:95:03:29:CD:7F:12:96:6F:23:56:0E:5E:33:6A:9E:BD:66`（`~/.android/debug.keystore`，别名 `androiddebugkey`，口令 `android`） |
| 签名 SHA1（CI） | 工作流自己生成的调试证书，SHA1 打印在该次运行的 Job Summary 里；本地编译则见 `~/.android/debug.keystore` |
| 需启用的服务 | Android **地图 SDK**、Android **定位 SDK**、Android **检索 SDK**（地点检索 / 联想 / 逆地理） |
| SHA1 数量 | 一个 AK 可填多个 SHA1；debug 与 release 证书不同时，**两个 SHA1 都要填**，否则 CI 出的包搜索会失效 |

## 3. AK 注入方式（构建期，AK 不入库）

**AK 不写进版本库**：`build.gradle.kts` 与 `AndroidManifest.xml` 里都不得出现真实 AK。
取值优先级从高到低：

1. 环境变量 `BAIDU_MAP_AK`（CI 用 secrets 注入，或 workflow_dispatch 手动触发时填输入框）
2. Gradle 属性：`./gradlew :app:assembleRelease -PBAIDU_MAP_AK=你的AK`
3. 仓库根目录 `local.properties` 的 `BAIDU_MAP_AK=` 一行（该文件已被 `.gitignore` 排除）
4. 以上都没有时用占位符 `REPLACE_WITH_YOUR_BAIDU_MAP_AK`——构建仍能成功，地图照常出图，
   但检索/逆地理会因 AK 校验失败而不可用

```kotlin
// app/build.gradle.kts
val baiduMapAk = System.getenv("BAIDU_MAP_AK")
    ?: (project.findProperty("BAIDU_MAP_AK") as String?)
    ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }.getProperty("BAIDU_MAP_AK")
    }
manifestPlaceholders["BAIDU_MAP_AK"] = baiduMapAk?.takeIf { it.isNotBlank() }
    ?: "REPLACE_WITH_YOUR_BAIDU_MAP_AK"
```

```xml
<!-- AndroidManifest.xml -->
<meta-data android:name="com.baidu.lbsapi.API_KEY" android:value="${BAIDU_MAP_AK}" />
```

本机开发：把 AK 写进仓库根目录的 `local.properties`（已被忽略）：

```properties
BAIDU_MAP_AK=你的AK
```

## 3.1 fork 后跑 CI 的完整流程（无需任何私有数据）

本仓库不提供签名证书也不提供 AK。fork 之后自己跑一次 `Build Apks`：

1. **先跑一次** Actions → `Build Apks` → Run workflow（AK 输入框留空）
2. 打开这次运行的 **Job Summary**，里面有本仓库的 `包名` 与 `签名 SHA1`
3. 拿着这两行到百度地图开放平台建 Android SDK 应用、勾选「地点检索」、申请 AK
4. **再跑一次**，把 AK 填进 `baidu_map_ak` 输入框（或配仓库 Secret `BAIDU_MAP_AK`）
5. 下载 Artifact 安装

签名证书由工作流首次运行时生成并存入 Actions 缓存，之后复用，SHA1 在本仓库内保持稳定。
缓存被清除（7 天无访问/手动清理）后会重新生成，**SHA1 会变**，需要重新登记。
想固定成自己手里那把证书时，才需要配 Secret `DEBUG_KEYSTORE_BASE64`（`base64 -w0 <keystore>`）。

```yaml
# .github/workflows/build-apk.yml 相关片段
on:
  workflow_dispatch:
    inputs:
      baidu_map_ak:
        description: '百度地图 AK（留空则回退到本仓库 Secret BAIDU_MAP_AK）'
        required: false
        type: string
```

```yaml
      - name: Build with Gradle
        env:
          BAIDU_MAP_AK: ${{ inputs.baidu_map_ak || secrets.BAIDU_MAP_AK }}
          KEYSTORE_PATH: ${{ env.KEYSTORE_PATH }}
          KEYSTORE_PASSWORD: android
          KEY_ALIAS: androiddebugkey
          KEY_PASSWORD: android
        run: ./gradlew :app:assembleRelease --build-cache --parallel
```

> ⚠️ `workflow_dispatch` 的输入值会记录在该次运行的记录里（`::add-mask::` 只遮日志），
> 公开仓库可优先用 Secret。

提交前自检（必须为空）：

```bash
git grep -n "你的AK" -- .
```

## 4. 客户端侧硬性要求（仓库已满足，改动时勿破坏）

- **meta-data**：`com.baidu.lbsapi.API_KEY`
- **定位服务声明**：
  ```xml
  <service android:name="com.baidu.location.f"
      android:enabled="true"
      android:foregroundServiceType="location"
      android:process=":remote" />
  ```
- **权限**：`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE`、
  `ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION`、`ACCESS_BACKGROUND_LOCATION`、
  `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_LOCATION`、`READ_PHONE_STATE`
- **隐私合规（顺序不能颠倒）**：`Portal.kt` 中必须先
  `SDKInitializer.setAgreePrivacy(this, true)` + `LocationClient.setAgreePrivacy(true)`，
  再 `SDKInitializer.initialize(this)`；否则 SDK 拒绝提供任何服务
- **SDK 资产**：`app/libs/BaiduLBS_Android.jar` + `app/libs/{arm64-v8a,x86_64}/*.so`
  （`libBaiduMapSDK_base_v7_6_2.so`、`libBaiduMapSDK_map_v7_6_2.so`、`liblocSDK8b.so`、
  `libtiny_magic.so`、`libc++_shared.so`），通过 `sourceSets.main.jniLibs.srcDirs("libs")` 引入
- `abiFilters` 目前仅 `arm64-v8a`

## 5. 排障顺序

1. 确认设备联网（检索/逆地理均为在线接口）
2. `adb logcat | grep -iE "baidu|BDMap|AK"`：出现 AK 校验失败/APP 不存在 → 包名或 SHA1 不匹配
3. 确认安装包签名 SHA1 与 AK 中登记的一致：
   `apksigner verify --print-certs <apk> | grep "SHA-1"`
4. 确认安装包实际包名：`adb shell dumpsys package moe.fuqiuluo.portalex | grep versionName`
5. 控制台确认对应服务（地图/定位/检索）已勾选、配额未用尽

## 6. 两个实测坑（2026-09-10）

**（1）`SuggestionSearch` 的 `city` 是必填项，与官方文档相反。**

内置的 `BaiduLBS_Android.jar` 里 `SuggestionSearch.requestSuggestion` 会同时校验
`option` / `mKeyword` / `mCity` 非 null：

```
javap -c com.baidu.mapapi.search.sug.SuggestionSearch
  21: getfield mKeyword ; 25: ifnull  → throw
  28: getfield mCity    ; 32: ifnonnull → 45
  35: new IllegalArgumentException("BDMapSDKException: option or keyword or city can not be null")
```

`city` 为 null 时异常在**发请求前**就抛，表现为弹「搜索出错」而永远不会真正联网。
未拿到逆地理城市时用 `city("全国")` 回退。

**（2）AK 校验结果被 SDK 缓存在本地，换 AK 后必须清。**

```
/data/data/<pkg>/shared_prefs/authStatus_<pkg>.xml
/data/data/<pkg>/shared_prefs/authStatus_<pkg>:remote.xml

{"status":201,"message":"APP被用户自己禁用，请在控制台解禁",
 "user_permission":-1,"ak_permission":0,"up":-1,"ap":-1,"current":1788920395297}
```

`status:0` 才算通过；换 AK 后不清这个文件就不会立刻生效。进程名不同的两个文件都要清。

## 7. 为什么 AK 校验失败时地图/卫星图照常显示

因为**瓦片走的是 CDN 静态资源通道，与接口服务的鉴权不是一套**。实测：

```bash
# 地图（矢量）瓦片，不带任何 ak 参数
curl -s "https://maponline0.bdimg.com/tile/?qt=tile&x=100&y=40&z=10&styles=pl&scaler=1&udt=20180102"
# → 200 image/png 256x256（带旧 AK 请求结果字节数完全一致）

# 卫星影像层
curl -s "...&styles=sl&..."   # → 200 image/png 256x256
```

所以「地图正常」不能反推 AK 正确，判断 AK 只能看接口服务（检索/逆地理）的返回。
