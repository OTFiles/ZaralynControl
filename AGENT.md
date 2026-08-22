# ZaralynControl (ZC) — 项目知识库

> 本文件记录 ZaralynControl 项目的全部设计、架构、关键技术决策和当前状态。
> 供后续 AI 会话快速掌握上下文。

---

## 项目定位

基于读书郎家长管理 App 6.2.8 版本的**反编译代码**开发的管控修改工具。维护自己的镜像数据库（Room SQLite），通过双向同步覆盖家长管理本地数据库及云端配置。

**仓库地址**：`https://github.com/OTFiles/ZaralynControl`
**路径**：`~/object/ZaralynControl/`
**包名**：`com.readboy.control`
**APK 名称**：`ZaralynControl-v*.apk`

---

## 核心架构

```
┌─────────────────────────────────────────────────────┐
│ ZaralynControl App                                   │
│                                                       │
│  镜像库 (Room DB)  ←→ SyncEngine → 家长管理 Provider  │
│      ↕                            ↕                   │
│  CloudSyncEngine              ContentProvider         │
│   (jpush/content)          (install_app_list)         │
│   (controlApp/upload)       (forbidden_app)           │
│   (password/upload)         (user_info)               │
└─────────────────────────────────────────────────────┘
```

### 镜像库 (Room) — 4 张表

| 表 | 实体 | 说明 |
|----|------|------|
| `mirror_control_list` | `MirrorControlItem` | 管控应用列表。字段: id, package_name, app_name, disabled_state, app_type, system_mode, version_code, operation, sync_status |
| `mirror_user_info` | `MirrorUserInfo` | 家长密码镜像。字段: id, password, is_long_pwd, is_allow_input_pwd, sync_status |
| `mirror_switches` | `MirrorSwitchItem` | 控制开关（云端 xxx.status）。字段: switch_name, status, sync_status |
| `mirror_meta` | `MirrorMeta` | 元信息。字段: key, value |

### 同步引擎 (SyncEngine.kt)

- `pullFromProvider()` — 从家长管理 ContentProvider 读取全量数据 → 写入镜像库
- `pushToProvider()` — 从镜像库读取 → 写入家长管理 Provider
- `sync()` — 先拉再推（双向同步，镜像库权威）
- `detectSchemaChanges()` — 检测 install_app_list 的列名变化，记录到 mirror_meta，新增/删除列时告警

### 云端同步引擎 (CloudSyncEngine.kt)

- `pullFromCloud(imei, saveToLocalDb)` — 调用 `jpush/content?get_all=1` 拉取全量配置
- `parseAndUpdateMirror(context, responseBody)` — 解析云端响应并更新镜像库
- `pushToCloud(imei, uid)` — 上传管控列表到 `controlApp/upload`
- 重试策略：3 次，每次 2 秒间隔

### 后台保活 (SyncWorker.kt)

- WorkManager 周期任务，默认 1 分钟，可配置 1~60 分钟
- 每次同步：本地双向同步 → 云端上传（如开启）
- 失败自动重试（WorkManager 内置）

---

## 远程模式 (DeviceUtil.kt)

当家长管理 App 未安装时自动进入远程模式：

| 功能 | 本地模式 | 远程模式 |
|------|---------|---------|
| 拉取按钮 | 从家长管理拉取 → ContentProvider | 从远程拉取 → `jpush/content` API |
| 覆盖按钮 | 覆盖家长管理 → ContentProvider | 覆盖远程 → `controlApp/upload` API |
| 密码修改 | 写入镜像库 | 直接 POST `password/upload` |
| 数据库 | 读写镜像库 + Provider | 读写镜像库（仅云端，后台无感） |

关键方法：
- `DeviceUtil.getEffectiveSerial()` — 优先返回自定义序列号，否则本机序列号
- `DeviceUtil.isRemoteMode()` — 有自定义序列号 或 家长管理未安装时 true
- `DeviceUtil.hasRemoteSerial()` — 是否已设置自定义序列号
- `DeviceUtil.isParentManagerAvailable()` — 家长管理是否已安装

---

## 版本检测 (VersionDetector.kt)

```kotlin
enum PmsVersion { OLD, NEW, UNKNOWN }
```

- `detect(context)` — 先直接 content 查询 PRAGMA（不受包可见性影响），失败则全量扫描
- **判据**：`PRAGMA table_info(install_app_list)` 是否包含 `disabled_state` 列
  - 包含 → NEW（6.2.8+，install_app_list 机制）
  - 不包含但表存在 → OLD（forbidden_app + state 机制）
  - 查询失败 → UNKNOWN
- Android 10+ 兼容：使用 `GET_PROVIDERS | MATCH_UNINSTALLED_PACKAGES` 标志扫描
- 扫描时遍历 `pkg.providers` 而非字符串拼接 authority（参照 ZaralynSetting）
- 缓存版本到 `mirror_meta` 的 `cached_version` 键

---

## 签名算法 (SignUtil.kt)

密钥常量（反编译自 `Sign.smali`，APK 内置公开密钥，非泄露）：

```kotlin
APPSECRET = "de917e0e6b4962061d66d24f6cfdb5bf0d1b9b39"
APP_ID2   = "parent-manage"
APP_KEY   = "9b332c2653ce7189da101dac5a63fd4e"
APP_ID    = "parentsadmin"
DEFAULT_UID = "00000000"
```

### getSign2 — 用于请求参数 signature（主接口域）

```kotlin
fun getSign2(timestampMs: Long): String {
    val seconds = timestampMs / 1000  // 签名用【秒】(10位)
    val input = seconds + APPSECRET + MD5(APP_ID2)
    return MD5(input)  // 32 位小写 hex
}
```

### getSign — 用于 header "sn"（parentadmin 域身份认证）

```kotlin
fun getSign(uid: String, timestampMs: Long): String {
    val seconds = timestampMs / 1000
    val inner = seconds + APP_KEY + MD5(APP_ID)
    val md5Inner = MD5(inner)
    return uid + seconds + md5Inner + APP_ID  // 长字符串，非纯 MD5
}
```

**⚠️ 关键区别**：
- `getSign2` 用于 `parent-manage.readboy.com` 域（signature 参数）
- `getSign` 用于 `parentadmin.readboy.com` 域（sn header + signature 参数）
- 签名用秒（10位），请求 timestamp 参数用毫秒（13位）
- 解绑 `cancel_bindings` 必须用 getSign 长签名 + 同时传 `signature` 和 `sn` 两个参数

---

## 关键网络接口

### 拉取配置 (jpush/content)

```
GET http://parent-manage.readboy.com/api/v1/jpush/content?get_all=1
```

**响应结构（实测修正）**：
```json
{
  "status": 1,
  "data": {
    "app_control": { "control_list": [...] },
    "password": { "password": "123456", "is_long_pwd": 0 },
    "allow_input_pwd": { "status": 1 },
    ...
  }
}
```

### 上传管控列表 (controlApp/upload)

```
POST https://parentadmin.readboy.com/v1/appinfo/controlApp/upload
Headers:
  signature: getSign2()
  sn: getSign(uid, timestampMs)
  imei: {serial}
  timestamp: {秒}
  app_id: parent-manage
  initialize: 1
  control_list: JSON.stringify([{packageName, status, operation, system_mode}])
Body: signature=...&imei=...&timestamp=...&app_id=parent-manage
```

### 密码上传 (password/upload)

```
POST http://parent-manage.readboy.com/api/v1/password/upload
Body: signature=...&imei=...&timestamp=...&app_id=parent-manage&password=...&is_long_pwd=0/1
```

### 解绑 (cancel_bindings)

```
GET https://parentadmin.readboy.com/v1/machine/cancel_bindings
  ?signature={getSign 长签名}&sn={同一长签名}&imei=...&timestamp=秒&app_id=parent-manage
```

---

## 数据库表结构（家长管理 App）

### install_app_list（新版 6.2.8+）

```sql
CREATE TABLE IF NOT EXISTS install_app_list(
  _id INTEGER PRIMARY KEY AUTOINCREMENT,
  package_name varchar(100) UNIQUE,
  app_name varchar(100),
  app_type int,          -- 应用类型
  disabled_state int,    -- 0=允许 1=禁用（核心字段）
  upload_state int,
  app_state int,
  network_app_state int,
  app_user_type int,
  version_code varchar(100),
  app_time varchar(3000),
  temporary_start_time long,
  temporary_end_time long,
  extra int,
  use_duration int,
  rest_duration int,
  addiction_time long,
  is_addiction INTEGER DEFAULT 0,
  system_mode INTEGER DEFAULT 0
);
```

### forbidden_app（旧版）

```sql
CREATE TABLE IF NOT EXISTS forbidden_app (
  _id INTEGER PRIMARY KEY AUTOINCREMENT,
  package_name varchar(200),
  state int              -- 0=黑名单(禁用) 1=白名单(允许)
);
```

### un_mall_app_state（旧版安装门禁）

```sql
CREATE TABLE IF NOT EXISTS un_mall_app_state (
  _id INTEGER PRIMARY KEY AUTOINCREMENT,
  state int              -- 0=禁止安装 1=允许安装
);
```

### user_info（新旧通用）

```sql
CREATE TABLE IF NOT EXISTS user_info (
  _id INTEGER PRIMARY KEY AUTOINCREMENT,
  password varchar(50),       -- 明文密码！6.2.8 无加密
  state int,
  is_long_pwd int DEFAULT 0,
  is_allow_input_pwd int DEFAULT 0
);
```

### app_limited（管控时长限制）

```sql
CREATE TABLE IF NOT EXISTS app_limited (
  _id INTEGER PRIMARY KEY AUTOINCREMENT,
  package_name varchar(200)
);
```

### app_allow（允许列表）

```sql
CREATE TABLE IF NOT EXISTS app_allow (
  _id INTEGER PRIMARY KEY AUTOINCREMENT,
  package_name varchar(200)
);
```

---

## status → disabled_state 映射（实测修正）

| 云端 `control_list[i].status` | 本地 `disabled_state` | 含义 |
|-------------------------------|----------------------|------|
| 1 | 0 | 允许（放行） |
| 0 | 1 | 禁用 |
| 2 | 1 | 禁用（删减状态，文档此前遗漏） |

公式：`disabled_state = (status == 1) ? 0 : 1`

---

## 云端响应解析陷阱（实测确认）

1. **jpush/content 响应在 `data` 对象内**，不在顶层
2. `control_list` 在 `data.app_control.control_list`，非直接顶层
3. **空 control_list 不放行** — 设备端只处理非空列表，空列表什么都不做
4. **status=2 也禁用** — 非仅 status=0
5. **get_all=0** — 只返回 4 个版本号，不返回配置
6. 心跳接口 `heart_beat` 仅返回 `{"status":1}`，不拉取任何配置
7. 解绑接口 `cancel_bindings` 只接受 GET（POST 返回 404）

---

## 构建方式

- GitHub Actions 自动构建（双 job: build + release）
- 本地不编译，靠 Actions artifact 获取 APK
- Keystore：`otf-control.jks`（alias OTFiles，密码 OTFiles-ABC345abc）
- Secrets：`OTF_JKS_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
- AGP 8.2.0 + Kotlin 1.9.20 + JDK 17
- 依赖：Room 2.6.1, WorkManager 2.9.0, Gson 2.10.1, coroutines 1.7.3

---

## 已知问题 / 待办

- [x] HTTP 明文阻断（Android 9+）— 已加 `networkSecurityConfig`
- [x] Android 10 包扫描 — 已加 `MATCH_UNINSTALLED_PACKAGES`
- [x] 远程模式 — 未安装时自动弹窗输入序列号
- [x] 自定义 MD3 弹窗 — 替换系统灰色 AlertDialog
- [x] 日志完整输出（不截断）+ 按日期存文件（logdir/yyyy-MM-dd.log）
- [ ] 网络请求因 Cleartext 失败时，deviceStatus 显示具体错误信息
- [ ] 添加应用的对话框可改为 MD3 风格
- [ ] 后台服务通知（前台服务粘性通知）
- [ ] 应用图标借用 ZaralynSetting 的 PIL 生成方式

---

## 相关项目

| 项目 | 路径 | 说明 |
|------|------|------|
| ZaralynSetting | `~/object/ZaralynSetting/` | 家长管理兼容性检测与设置工具（版本检测逻辑可复用） |
| ZaralynUnbind | `~/object/ZaralynUnbind/` | 设备解绑工具（cancel_bindings API 调用） |
| 博客 | `~/blog/OTFiles/` | heXo 博客，包含 API 文档和教程 |
| 破解文档 | `/storage/emulated/0/破解/` | 详细的反编译分析文档 |

---

## 文件结构

```
ZaralynControl/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/
│       │   ├── layout/     (activity_main, fragment_*, item_*, dialog_*)
│       │   ├── values/     (strings, colors, themes)
│       │   ├── drawable/   (ic_launcher_foreground)
│       │   ├── mipmap-anydpi-v26/ (ic_launcher)
│       │   └── xml/        (network_security_config)
│       └── java/com/readboy/control/
│           ├── AppLogger.kt
│           ├── ZaralynControlApp.kt
│           ├── db/          (Entities, Daos, MirrorDatabase)
│           ├── network/     (SignUtil, VersionDetector, SyncEngine, CloudSyncEngine, DeviceUtil)
│           ├── service/     (SyncWorker)
│           └── ui/          (MainActivity, MainPagerAdapter, Fragments*)
├── .github/workflows/build-apk.yml
├── .gitignore
├── gradle/ & gradlew
├── settings.gradle.kts
└── AGENT.md
```