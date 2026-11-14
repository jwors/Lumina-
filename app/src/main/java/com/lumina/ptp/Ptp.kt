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
    const val GET_OBJECT = 0x1009

    /** 标准快门（15740）：参数 storageID + objectFormat。Z 系用 Nikon 0x9207。 */
    const val INITIATE_CAPTURE = 0x100E

    /** 设备属性描述/取值/写入（15740 表 3，2026-11-14 对 gphoto2 ptp.h 校正）。 */
    const val GET_DEVICE_PROP_DESC = 0x1014
    const val GET_DEVICE_PROP_VALUE = 0x1015
    const val SET_DEVICE_PROP_VALUE = 0x1016
}

/** 尼康厂商扩展操作码（gphoto2 ptp.h 口径） */
object NikonOpCode {
    /** 尼康快门（InitiateCaptureRecInMedia）：1 个参数 storageID；Z 系实测可用。 */
    const val CAPTURE = 0x9207

    /** 实时取景：Start / End / GetImage（legacy 0x9203）。 */
    const val START_LIVE_VIEW = 0x9201
    const val END_LIVE_VIEW = 0x9202
    const val GET_LIVE_VIEW_IMAGE = 0x9203
    const val GET_LIVE_VIEW_IMAGE_EX = 0x9428

    /** 对焦：AfDrive（无参）/ ChangeAfArea（2 参）/ Cancel。 */
    const val AF_DRIVE = 0x90C1
    const val CHANGE_AF_AREA = 0x9205
    const val AF_CANCEL = 0x9206

    /** 相机模式与就绪。 */
    const val CHANGE_CAMERA_MODE = 0x90C2
    const val DEVICE_READY = 0x90C8
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
