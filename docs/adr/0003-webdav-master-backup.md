# Master 备份终点采用 WebDAV（不依赖云相册）

手机系统云相册只可靠同步 JPEG 类图片，对 NEF/RAW 通常不认或不计费备份，无法承担徒步场景"手机丢失 = 原片全丢"的不可逆风险。故 Phase 1 实现 WebDAV 备份终点（坚果云/Alist/NAS 均为此协议），回网后手动触发将 Master 清出手机；JPEG 与 Work 成品则写入系统相册交给云相册自动同步。**蓝牙批量导出被明确拒绝**：尼康 BLE 仅支持 SnapBridge 式低分辨率补传，无法承载 RAW 导出。

## Considered Options

- 仅依赖系统云相册：对 NEF 不可靠，否决。
- 自己实现厂商网盘私有 API：维护成本高且有封号风险，否决。
- WebDAV：单一协议覆盖坚果云/Alist/NAS 全生态，选它。
