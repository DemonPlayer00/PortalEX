# PortalEX cammock

External camera-hook implementations for PortalEX, grouped by brand and
device codename.

```text
oneplus/
  PKG110/              OnePlus AIDL provider
xiaomi/
  sagit/               Xiaomi MI 6 HAL-overlay module
interface/             shared frame-file protocol
```

Each implementation carries a `cammock.json` with `supportedModels`.
PortalEX checks it against `Build.MODEL`, `Build.DEVICE`, and
`Build.PRODUCT` on import; a mismatch is a warning, not a hard failure.

The OnePlus implementation packages `provider` (+ optional `meta.bin`/
`tags.bin`) for `/data/local/tmp/portalex/cammock/`.
The Xiaomi MI 6 implementation is a root-manager HAL module because its
vendor camera provider is a 32-bit HAL, not an AIDL provider.

PortalEX UI, system_server selection, recording/upload/download and face
calibration remain in the PortalEX repository.
