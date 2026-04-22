# kaochang-controller-android11

安卓11设备专用上位机代码，仅适用于安卓11现场版本。

## 说明

- 本仓库仅整理安卓11设备上位机代码
- 不包含下位机代码
- 当前版本默认屏蔽自清洁功能，下位机尚未完成对应开发
- 公开仓库中不包含生产签名文件与本地崩溃转储文件

## 构建说明

- 如果本地不存在 `kaochang.jks`，项目会自动回退到默认 debug 签名
- 如果需要使用专用签名，请在本地提供：
  - `kaochang.jks`
  - `KAOCHANG_STORE_PASSWORD`
  - `KAOCHANG_KEY_ALIAS`
  - `KAOCHANG_KEY_PASSWORD`
