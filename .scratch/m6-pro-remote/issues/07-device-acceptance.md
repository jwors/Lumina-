# 07 · 真机验收与回归

> Status: ready-for-agent
> 上游：全部 M6 工单

## 步骤

1. 相机 Wi-Fi 打开，手机连 `NIKON_Z_30_82949`，App 相机页连接
2. 逐项验收 01–06 的勾选项，记录日志 `files/m6-remote.log`
3. 回归：M5 参数只读/快门仍可用；USB 导出不受影响
4. 记录设备型号/Android 版本/每项结果

## 取证

- `adb shell run-as com.lumina cat files/m6-remote.log`
- 失败时 `adb logcat -d | findstr /i "lumina"`
