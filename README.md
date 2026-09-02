# openminis-agent-android

Android companion agent skeleton for OpenMinis.

功能
- 通过 WebSocket 与 server (openminis-key-pool) 建立控制通道
- 注册 deviceId 并接收命令（tap, swipe, input, screencap, install）
- 目前为最小可运行骨架，后续可加入 WebRTC/视频流、自动更新/安装、权限提升

快速开始
1. 克隆仓库并在 Android Studio 中打开
2. 构建并在设备或模拟器上运行
3. 在 App 中填写 server 地址（ws:// 或 wss://）和 token，按 Connect 注册

注意
- 初始版本为调试构建（debug keystore），请在生产发布前签名并校验指纹
- 需要在 server 端使用 token 进行认证（参考 openminis-key-pool 的 /api/agent/token）
