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

- `pullFromCloud(imei)` — 调用 `jpush/content?get_all=1` 拉取全量配置（saveToLocalDb 参数已移除）
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
| 密码修改 | 写入镜像库 | 直接 POST `password/upload`（同时写入镜像库备份） |
| 数据库 | 读写镜像库 + Provider | 读写镜像库（云端拉取建立本地数据库） |

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
- 签名和 timestamp 参数**都用秒**（10位）— `getTimestamp()` = `timestamp/1000`，代码为准
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
Body (form-urlencoded):
  imei={serial}&control_list={urlencoded json}&initialize=0
  &sn={getSign 长签名}&signature={getSign 长签名}
  &timestamp={秒}&app_id=parent-manage
```

**⚠️ parentadmin 域验证 signature 使用 getSign 长签名（uid 参与），getSign2 短 MD5 报 7001「签名不能为空」。**

**⚠️ 上传控制项字段（反编译 UploadOnlineAppInfo，`@SerializedName` 全 snake_case）**：
```json
[{"pack_name":"com.xxx","status":0,"operation":"update","system_mode":2,"can_uninstall":1,"second_type":null,"app_time":null,"temp_use":null,"anti_addiction":null}]
```
- **`pack_name` 不是 `packageName`** — Gson 默认 camelCase，必须 `@SerializedName("pack_name")`
- **`system_mode` 必须回传云端原值**（2/404/1/0）— 之前传 0 导致服务器拒绝应用（返回 status:1 但不生效）
- **`initialize` 日常修改用 0**（非首次初始化），1 表示首次全量
- 上传成功判断：HTTP 200 **且** body `{"status":1}`，否则如 errno 7001 也是 HTTP 200

### 密码上传 (password/upload)

```
POST http://parent-manage.readboy.com/api/v1/password/upload
Body: signature=...&imei=...&timestamp=...&app_id=parent-manage&password=...&is_long_pwd=0/1
```

### 允许密码上传 (uploadAllowPwd) — 参数名是 allow！

```
POST http://parent-manage.readboy.com/api/v1/uploadAllowPwd
Body: signature={getSign2}&imei=...&timestamp=秒&app_id=parent-manage&allow=1/0
```

**⚠️ 反编译 UploadAllowPwdResponse 确认：参数名是 `allow`（不是 allow_pwd！）**，值对应服务器 `allow_pwd` 字段（rby_enable_start_app_by_password 应用启动密码）。传 `allow_pwd` 服务器返回 status:1 但不生效。

### ⚠️ allow_input_pwd vs allow_pwd 语义区分（重要！）

服务器返回两个不同字段：
- **`allow_input_pwd`**（开机/进入家长管理时密码框是否允许输入）→ 仅服务器下发，**无上传接口**，设备端改不了。`no_allow_input_pwd=true` 时键盘点击 Toast「根据管控策略，本机禁止输入密码」
- **`allow_pwd`**（应用启动密码，rby_enable_start_app_by_password）→ 可上传（uploadAllowPwd 的 `allow` 参数）

UI 开关控制的是 `allow_pwd`（可上传的那个）。若用户想改的是 allow_input_pwd，无接口可用。

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

### new_time_control（时间管控，6.2.8+）

```sql
CREATE TABLE IF NOT EXISTS new_time_control (
  _id INTEGER PRIMARY KEY AUTOINCREMENT,
  tid long,
  _group varchar(200),        -- group 是保留字，字段名用 _group
  total_time long,
  period_status int,
  periods varchar(1000)       -- JSON 数组 [{"start":28800,"end":82800}]
);
```

### time_control_record（设备已用时间记录）

```sql
CREATE TABLE IF NOT EXISTS time_control_record (
  week INTEGER UNIQUE ON CONFLICT REPLACE,
  used_time integer,
  used_time_control integer,
  enable integer,
  flag integer
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

## 时间管控（control_time）— 无上传接口！

### 数据结构（jpush/content 返回，实测）

```json
"control_time": {
  "anti_addiction": {"use_duration": 3600, "rest_duration": 1800},
  "control_time_list": [{
    "tid": 3462940,
    "group": "1,2,3,4,5,6,0",
    "total_time": 14400,          // 秒，每日管控总时长（14400=4小时）
    "period_status": 1,
    "periods": [{"start": 28800, "end": 82800}]  // 8:00-23:00
  }],
  "model_whitelist": 1,
  "time_lock_status": 0
}
```

### 逆向结论（2026-08 彻底排查）

- **只有拉取（jpush/content），没有设备端上传接口** — 与 allow_input_pwd 相同，服务器单向下发
- 已排查：全部 30+ Response 类、全部 API URL 常量、SyncHandler 全部消息分支（171 条 packed-switch）
- 拉取处理：GetOnlineAppResponse2 → TimeControlHelper 存 `new_time_control` 表（先 deleteAll 再 insert）
- 本地改表**会被服务器下次拉取覆盖**（拉取时 deleteAll 重建）
- 可执行方案：仅展示配置；本地模式可直接改 new_time_control 表（临时生效）

---

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
- [x] 管控列表上传修正 — system_mode 回传 + initialize=0 + pack_name 字段名（已生效）
- [x] uploadAllowPwd 参数名修正 — allow_pwd→allow（已生效，但控制的是 allow_pwd 非 allow_input_pwd）
- [ ] **allow_input_pwd 无法修改** — 无上传接口（服务器单向下发），用户预期落空，需向用户说明
- [ ] **时间管控无法修改** — 无上传接口，仅可展示；本地模式改 new_time_control 表会被拉取覆盖
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
---

## 家长账号登录 + api-super 域（2026-08 新增）

### 背景
时间管控（set_time）与允许输入密码（change_allow_input_pwd）修改**只在手机端家长助手（api-super 域）有接口**，且强制 token（签名验证通过后 8002「token无效」拦截）。parent-manage/parentadmin 域无这两个接口（反编译+40+路径探测确认）。

### 手机版 APK（逆向来源）
- `~/object/ZaralynControl/logdir/jzzs-2.9.57.apk`（com.readboy.rbmanager 家长助手，263MB）
- 反编译输出：`logdir/jzzs_high_smali/`
- 详细接口笔记：`docs/api-super-interfaces.md`

### 签名（SignUtil 新增）
```
getSn(uid8, ts, arg3) = uid8 + ts + MD5(ts + "2f6de49d30f32a4dbf67500b80bb7074" + arg3) + "com.readboy.rbmanager"
登录时 arg3 = MD5("com.readboy.rbmanager")；已登录 arg3 = MD5(uid8)
getUid8 = %08d 前补零；timestamp = 秒
```

### 认证流程
1. 登录：`GET https://api-super.readboy.com/api/mobile_login`，sn=getSnForLogin + username=手机号 + password=MD5(密码) → `{uid, access_token, access_expire}`
2. 之后所有请求带 `sn=getSnLoggedIn(uid)` + `token=access_token`
3. access_expire 兼容时长/时间戳两种语义

### 关键接口（全部需 token）
| 功能 | 方法与参数 |
|------|-----------|
| 拉取时间管控 | GET `parent_control/time_setting`（sn+token+imei）|
| 设置时间管控 | POST `parent_control/set_time`（sn+token+imei+[tid]+group+period_status+[periods]+[total_time]）|
| 允许输入密码 | GET `parent_control/change_allow_input_pwd`（sn+token+imei+allow_input_pwd=0/1）|
| 设备列表 | GET `parent_control/device_list`（sn+token+new_first=1+only_power=1）|
| 修改/清除密码 | POST `parent_control/update_password`（sn+token+imei+new_pwd 明文+is_long_pwd）|

### 代码结构
- `network/SignUtil.kt`：getSn/getUid8/getSnLoggedIn/getSnForLogin
- `network/LoginStore.kt`：登录状态持久化（uid/token/expire/手机号）
- `network/ParentApiClient.kt`：mobile_login/time_setting/set_time/change_allow_input_pwd/device_list
- `ui/SettingsFragment.kt`：登录卡片（手机号+密码+登录/登出）
- `ui/TimeControlFragment.kt`：时间管控 tab（未登录变灰）
- `ui/PasswordFragment.kt`：允许输入密码开关走 change_allow_input_pwd（未登录变灰）

### 注意
- 手机端 update_allow_pwd 参数名是 `allow_pwd`，平板端 uploadAllowPwd 参数名是 `allow`（不同域参数名不同）
- 登录接口/签名密钥仅用于测试验证，未硬编码任何测试序列号进 App
