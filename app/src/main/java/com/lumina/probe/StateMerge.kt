package com.lumina.probe

/**
 * 发射台账（M3 提取为纯函数，让"哪些字段保底"可被单测锁死）。
 *
 * 背景：M1-07 系列 bug（3 例）全部同根——部分状态包冲掉未列入合并清单的字段：
 *  - 第 1 例：切 Tab 相机列表空
 *  - 第 2 例：滤镜确认弹层"应用到已选 0 张"（passSelectedNames 被滤镜页部分包冲掉）
 *  - 第 3 例：一点击应用滤镜列表空（cards 被 startCardRun 早退分支包冲掉）
 *
 * 语义：
 * - 集合/可空引用类字段：新包"未携带"（空集/null）→ 保留 base 值；非空 → 新值生效
 * - 布尔 engineReady：只进不退（历史语义）
 * - 明确清空语义（清勾选/关弹层）走专用发射器（emitSelection/emitCardRun），不经此合并
 */
internal object StateMerge {

    fun merge(base: ProbeUiState, state: ProbeUiState): ProbeUiState = state.copy(
        cameraItems = if (state.cameraItems.isEmpty()) base.cameraItems else state.cameraItems,
        checkedNames = if (state.checkedNames.isEmpty()) base.checkedNames else state.checkedNames,
        engineReady = state.engineReady || base.engineReady,
        galleryItems = if (state.galleryItems.isEmpty()) base.galleryItems else state.galleryItems,
        cards = if (state.cards.isEmpty()) base.cards else state.cards,
        cardRun = if (state.cardRun == null) base.cardRun else state.cardRun,
        passSelectedNames = if (state.passSelectedNames.isEmpty()) base.passSelectedNames
        else state.passSelectedNames,
        // WebDAV 备份（M3-04）：部分状态包不得冲掉（清空走 emitBackup 专用发射器）
    /** WebDAV 备份（M3-04）：部分状态包不得冲掉（清空走 emitBackup 专用发射器） */
    backup = if (state.backup == null) base.backup else state.backup,
    // WebDAV 配置展示（设置页）：同走保留式
    webdav = if (state.webdav == null) base.webdav else state.webdav,
    )
}
