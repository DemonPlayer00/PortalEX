# Xiaomi MI 6 / sagit cammock

MI 6 uses a 32-bit vendor Camera HAL (`/vendor/lib/hw/camera.msm8998.so`),
so it does not use the OnePlus AIDL provider path.

This directory is an APatch/Magisk HAL-overlay module:

- `system/vendor/lib/hw/camera.msm8998.so` — virtual camera HAL payload
- `post-fs-data.sh` — mounts the HAL overlay
- `service.sh` / `sepolicy.rule` — module lifecycle and policy

`cammock.json` declares:

```json
{
  "type": "magisk-module",
  "supportedModels": ["MI 6", "sagit"]
}
```

Install the directory as a module zip through the device's root manager.
A reboot is required before the HAL is used. Disable the module and reboot to
restore the stock camera.
