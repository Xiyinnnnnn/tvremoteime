# TV远程ime

电视端输入法。手机浏览器打字，内容进电视输入框。

## 用

1. 装 APK，系统设置里启用并设为默认输入法
2. 电视上点输入框，右下角出现二维码和地址
3. 手机扫码，或打开 `http://电视IP:9978`，打字

页面可一直开着，断线自动重连。手机端不装 App。

## 装

APK 见 Releases。

## 构建

Android Studio 打开，跑 `app:assembleDebug`。

AGP 8.13.2 · compileSdk 36 · minSdk 21 · Java 17

## 依赖

- zxing:core 3.5.3 —— 上游仓库 https://github.com/zxing/zxing （Apache-2.0）

## 其他

局域网直连，端口 9978。无云、无账号、无上报。

## 许可

MIT
