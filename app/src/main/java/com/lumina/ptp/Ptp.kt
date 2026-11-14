package com.lumina.ptp

/** PTP 容器类型（PIMA 15740: USB 批量传输的包信封） */
object ContainerType {
    const val REQUEST = 1
    const val DATA = 2
    const val RESPONSE = 3
}

/** M0 用到的操作码（PIMA 15740 表 3；传输文件流等后续进入后续里程碑再补） */
object OpCode {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_STORAGE_IDS = 0x1004
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008

    /** 遥控快门（15740 表 3）：参数 storageID + objectFormat，storage 用 0xFFFFFFFF 表示默认卡。 */
    const val INITIATE_CAPTURE = 0x100C

    /** 设备属性描述/取值（15740 表 3）：M5 遥控页读镜头参数。 */
    const val GET_DEVICE_PROP_DESC = 0x1015
    const val GET_DEVICE_PROP_VALUE = 0x1016
    const val SET_DEVICE_PROP_VALUE = 0x1017
}

/** 尼康厂商扩展操作码（gphoto2/SnapBridge 口径） */
object NikonOpCode {
    /** 尼康快门（Capture）：参数 storageID + format，与 0x100C 同语义但 Z 系认这个。 */
    const val CAPTURE = 0x9207
}

/** 响应码：OK 之外的都是错误 */
object RespCode {
    const val OK = 0x2001
    const val SESSION_NOT_OPEN = 0x2003
    const val DEVICE_PROP_NOT_SUPPORTED = 0x200A
    const val ACCESS_DENIED = 0x200F
    const val CAPTURE_ALREADY_TERMINATED = 0x2018
    const val DEVICE_BUSY = 0x2019

    /** 相机残留会话未关（拔线粗暴常见）：OpenSession 时当成功处理，直接沿用 */
    const val SESSION_ALREADY_OPENED = 0x201E
}

/** 相机应答了非 OK 响应码：单测与真机日志都按错误来源标注 */
class PtpResponseException(val code: Int, val opCode: Int) :
    RuntimeException("op=0x%04X got response 0x%04X".format(opCode, code))
