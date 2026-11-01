package com.lumina.usb

/**
 * USB 设备分类（工单 04）：纯逻辑（int 参数），JVM 可测。
 *
 * 事实依据：
 * - PTP 相机：USB 设备类或接口类 = 0x06（Still Image / PIMA 15740）。
 * - MTP/文件设备：Android/PC 手机互连惯例走厂商自定义类 0xFF（无法凭类码确证 MTP，
 *   只做"疑似"标记，供诊断，不阻塞主路径）。
 */
object UsbDeviceClassifier {

    enum class DeviceKind {
        /** PTP 影像设备（Still Image class 0x06）——主路径 */
        STILL_IMAGE_PTP,

        /** 疑似 MTP 文件设备（厂商自定义接口 0xFF）——诊断标记 */
        MTP_SUSPECT,

        /** 与影像无关 */
        OTHER,
    }

    fun classify(deviceClass: Int, interfaceClasses: List<Int>): DeviceKind =
        when {
            deviceClass == CLASS_STILL_IMAGE || interfaceClasses.any { it == CLASS_STILL_IMAGE } ->
                DeviceKind.STILL_IMAGE_PTP
            interfaceClasses.any { it == CLASS_VENDOR_SPECIFIC } -> DeviceKind.MTP_SUSPECT
            else -> DeviceKind.OTHER
        }

    const val CLASS_STILL_IMAGE = 6
    const val CLASS_VENDOR_SPECIFIC = 0xFF
}
