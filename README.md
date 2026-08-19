# ZaralynControl

基于家长管理 App 反编译代码的**管控修改工具**。维护自己的镜像库，通过双向同步覆盖家长管理数据库与云端配置。

## 原理

```
用户修改镜像库
  ↓
同步引擎覆盖家长管理 Provider (install_app_list / forbidden_app)
  ↓
同步引擎上传 controlApp/upload → 云端持久化
  ↓
下次拉取 jpush/content → 云端返回一致数据 → 不被回滚 ✅
```

## 功能

- **镜像库权威**：镜像库是数据源，自动同步到家长管理 Provider
- **版本自动适配**：新版（install_app_list disabled_state）/旧版（forbidden_app state）
- **云端双向同步**：jpush/content 全量拉取 + controlApp/upload 上传
- **后台自动同步**：WorkManager 周期任务，默认 1 分钟，可配置
- **失败重试**：失败 3 次重试，全程 debug 日志
- **MD3 风格**：Material Design 3 界面

## 技术栈

- Kotlin + Room 数据库
- Android ContentProvider 操作
- Volley/OkHttp 风格 HTTP 请求（反编译自家长管理 6.2.8）
- GitHub Actions 编译

## 构建

```bash
./gradlew assembleDebug  # Debug APK
./gradlew assembleRelease # Release APK（需 keystore）
```

## 子项目

- [ZaralynSetting](https://github.com/OTFiles/ZaralynSetting) — 家长管理兼容性检测与设置工具
- [ZaralynUnbind](https://github.com/OTFiles/ZaralynUnbind) — 设备解绑工具