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
}

/** 响应码：OK 之外的都是错误 */
object RespCode {
    const val OK = 0x2001
    const val SESSION_NOT_OPEN = 0x2003
}

/** 相机应答了非 OK 响应码：单测与真机日志都按错误来源标注 */
class PtpResponseException(val code: Int, val opCode: Int) :
    RuntimeException("op=0x%04X got response 0x%04X".format(opCode, code))
