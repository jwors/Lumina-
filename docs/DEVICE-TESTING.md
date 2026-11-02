# 真机调试流程（USB 口被相机占用的场合）

PDA 手机只有一个 USB-C 口。接上相机（OTG）之后它**没法同时插电脑**，所以：

## 1. Wi-Fi 无线 adb（主力路径）

前提：电脑和手机在**同一个 Wi-Fi**。手上有 USB 连接时一次性切换：

```
adb tcpip 5555                        # adbd 重启为 TCP 模式
adb shell ip -f inet addr show wlan0  # 记下手机 IP（例 192.168.1.4）
adb connect 192.168.1.4:5555          # 之后拔线也保持连接
```

注意：手机重启后失效，需要重新走一遍（也可以用系统的"无线调试"配对，HyperOS 设置→开发者选项）。

## 2. App 内事件日志（备份路径）

`ProbeController` 会把每次相机事件（attach / 授权 / detached / 各类失败的人话）追加到应用私有目录：

```
files/m0-probe.log
```

**测试前清空、测试后取回**（debug 包可 run-as）：

```
adb shell run-as com.lumina sh -c "rm -f files/m0-probe.log"
# ……用 OTG 接相机测试一顿操作……
adb shell run-as com.lumina cat files/m0-probe.log
```

## 3. 实时 logcat（无线 adb 时）

```
adb -s 192.168.1.4:5555 logcat -v time AndroidRuntime:E Lumina:V *:S
```
