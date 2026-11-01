# Android 原生 Kotlin + Jetpack Compose（不做跨平台）

Lumina 的核心交互是 USB Host 直连相机（PTP）、系统相册/MediaStore 集成和云相册备份，这些在 Android 上都是第一公民 API，而跨平台框架（Flutter/RN）全部要走原生插件拼桥。用户为单人自用 + Android 真机（尼康 Z30 场景），不存在跨端收益，只存在真实的桥接成本，故直接原生。代价：将来 iOS 版需整体重写——已知并接受。

## Considered Options

- Flutter / React Native：跨端快，但 USB/PTP、MediaStore、WebDAV 后台任务都要平台通道，单人项目桥接维护成本高于收益。
- Kotlin + Compose：全链路第一公民，选它。
