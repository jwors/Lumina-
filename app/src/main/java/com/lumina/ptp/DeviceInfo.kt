package com.lumina.ptp

/**
 * DeviceInfo 数据集（PIMA 15740）解析：返回 Model 字段。
 * 字段顺序：StandardVersion(u16), VendorExtensionID(u32), VendorExtensionVersion(u16),
 * VendorExtensionDesc(string), FunctionalMode(u16), Operation/Event/DeviceProp/
 * Capture/Image 代码数组（各 u32 count + u16 项）, Manufacturer/Model/DeviceVersion/Serial(string)。
 */
fun parseDeviceInfo(payload: ByteArray): String {
    val r = MtpReader(payload)
    r.u16()                 // StandardVersion
    r.u32()                 // VendorExtensionID
    r.u16()                 // VendorExtensionVersion
    r.mtpString()           // VendorExtensionDesc
    r.u16()                 // FunctionalMode
    r.u16Array()            // Operationcodes
    r.u16Array()            // Eventcodes
    r.u16Array()            // DevicePropcodes
    r.u16Array()            // CaptureFormats
    r.u16Array()            // ImageFormats
    r.mtpString()           // Manufacturer
    val model = r.mtpString() // Model
    r.mtpString()           // DeviceVersion
    r.mtpString()           // SerialNumber
    return model
}
