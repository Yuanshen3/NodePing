# NodePing · 节点测速

一个用于测试代理订阅节点网络质量的 Android 客户端：一键测试所有节点的 **延迟** 与 **真实下载速度**，并可选中任一节点启动 **本地代理** 或 **全局 VPN**。内嵌 Xray 核心，支持导入订阅、多订阅管理与离线缓存切换。

> 仅供在你本人拥有或已获授权的订阅节点上进行网络质量测试与个人代理使用。

## 功能

- **测延迟**：对每个节点做 TCP 连接计时（`connect` 耗时，即 “ping”），并发探测、可按延迟排序。
- **测网速**：逐个节点通过其代理隧道下载测速文件，测出真实吞吐（Mbps）。串行测量以保证数字准确。
- **本地代理**：选中一个节点在本机启动 SOCKS（`127.0.0.1:10808`）/ HTTP（`127.0.0.1:10809`）代理。
- **全局 VPN**：基于 `VpnService` + Xray 原生 `tun` inbound，接管整机流量走选定节点。
- **订阅管理**：导入 http(s) 订阅链接或直接粘贴节点内容；多订阅卡片切换、长按改名；节点缓存到本地，切换订阅免联网，只有「更新订阅」才会联网刷新。

## 支持的协议

| 协议 | 解析（导入 / 测延迟） | 代理 / 测速 / VPN |
|------|:---:|:---:|
| Shadowsocks (ss) | ✅ | ✅ |
| VMess | ✅ | ✅ |
| VLESS | ✅ | ✅ |
| Trojan | ✅ | ✅ |
| ShadowsocksR (ssr) | ✅ | ❌ |
| Hysteria2 | ✅ | ❌ |
| TUIC | ✅ | ❌ |

> ss / vmess / vless / trojan 由内嵌 Xray 核心运行；ssr / hysteria2 / tuic 目前仅能解析与测延迟，不能启动代理 / 测速 / VPN（会标记为「不支持」并跳过）。

## 下载

前往 [**Releases**](https://github.com/Yuanshen3/NodePing/releases/latest) 下载最新 APK 直接安装。

- 系统要求：Android 7.0（API 24）及以上。
- 提供的是 **debug 构建**（已用调试签名，可直接安装）；首次安装可能需要在系统设置里允许「安装未知来源应用」。
- APK 内含 `arm64-v8a / armeabi-v7a / x86 / x86_64` 四个 ABI，真机与电脑模拟器均可安装。

## 从源码构建

需要 JDK 17+ 与 Android SDK（compileSdk 35 / buildTools 36）。

```bash
git clone https://github.com/Yuanshen3/NodePing.git
cd NodePing
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

> 内嵌的 `app/libs/libv2ray.aar` 是预编译的 Xray 核心（AndroidLibXrayLite），已随仓库提供，无需自行编译 Go 代码。

## 工作原理

- **延迟**：`Socket().connect(InetSocketAddress, timeout)` 计时；DNS 单独解析、不计入延迟（不用 ICMP，因其在 Android 上需要 root）。
- **网速**：为单个节点生成 Xray 出站配置并启本地 HTTP inbound，用 OkHttp 走该代理下载测速文件，`Mbps = bytes * 8 / seconds / 1e6`。
- **全局 VPN**：为 Xray 配置追加 `tun` inbound（gVisor netstack），`VpnService` 建立 TUN 后把文件描述符交给核心，整机 TCP/UDP 流量经隧道转发。

## 技术栈

Kotlin · Jetpack Compose (Material3) · MVVM（ViewModel / StateFlow）· Coroutines · OkHttp · 内嵌 Xray 核心（libv2ray.aar）。
AGP 8.7.3 / Gradle 8.9 / compileSdk 35 / minSdk 24 / JVM 17。

## 免责声明

本项目是一个标准的代理订阅测速与客户端工具（功能等价于 v2rayNG 等开源客户端），仅供测试与管理**你本人拥有或已获授权**的订阅节点。请遵守当地法律法规，勿用于任何非法用途。
