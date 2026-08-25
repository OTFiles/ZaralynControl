# 手机版家长助手（家长端）API 逆向笔记

> APK: `jzzs-2.9.57.apk` / `jzzs-2.6.4.apk`（家长助手，com.readboy.rbmanager）
> 反编译: `~/object/ZaralynControl/logdir/jzzs_high_smali/`（apktool 全量）
> 域名: `https://api-super.readboy.com/api/`
> 用途: 家长手机端 App，**拥有完整管控权限**（时间管控、密码均可修改）

## 签名算法（Util.smali getSn）

```kotlin
fun getSn(uid8: String, timestamp: String, arg3: String): String {
    // arg3 = MD5(uid8) 已登录；= MD5("com.readboy.rbmanager") 未登录/登录时
    val inner = MD5(timestamp + "2f6de49d30f32a4dbf67500b80bb7074" + arg3)
    return uid8 + timestamp + inner + "com.readboy.rbmanager"
}
fun getUid8(uid: Int) = String.format("%08d", uid)   // 8位前补零
fun getTimestamp() = System.currentTimeMillis() / 1000  // 秒（10位补零）
```

**常量**：密钥 `2f6de49d30f32a4dbf67500b80bb7074`；包名后缀 `com.readboy.rbmanager`

## 认证

### 登录（账号密码）
```
GET /api/mobile_login
  sn=getSn("00000000", ts, MD5("com.readboy.rbmanager"))
  username=手机号&password=MD5(密码)
```
响应 `MobileRegisterResponse`: `{uid, access_token, access_expire, mobile, ...}`
（登录/注册后所有请求带 `token=access_token`）

### 手机号+验证码注册/登录
```
GET /api/mobile_reg_login
  sn=...&mobile=...&serial=...&verify=验证码
```

## 时间管控接口

### 设置时间管控 ⭐（核心）
```
POST /api/parent_control/set_time
  sn=getSn(uid8, ts, MD5(uid8))
  token=access_token
  imei=设备序列号
  tid=时间管控ID（修改已有配置时传；新建不传）
  group="1,2,3,4,5,6,0"     逗号分隔的星期（0=周日）
  period_status=0/1          是否启用时间段
  periods=[{"start":28800,"end":82800}]   JSON 字符串（period_status=1 时传）
  total_time=14400           每日管控总时长秒（启用总时长时传）
```

### 时间开关
```
GET /api/parent_control/time_switch
  sn+token+imei+...（TimeSettingPresenter.setTimeSwitch(Map, int, int)）
```

### 设置防沉迷
```
GET /api/parent_control/set_anti_addiction
  sn+token+imei+...
```

### 删除时间设置
```
GET /api/parent_control/delete_time_setting
  sn+token+imei+...
```

### 拉取时间管控配置
```
GET /api/parent_control/time_setting
  sn+token+imei+[use_duartion+rest_duration]（回传当前防沉迷配置）
```
响应 `TimeSettingResponse`:
```
{errno, errmsg, anti_switch, limit_switch, time_switch, extra_day,
 anti_addiction: {use_duration, rest_duration},
 data: [{tid, imei, group, period_status, periods: [{start,end}], total_time, status}]}
```

### 今日时间设置
```
GET /api/parent_control/today_time_setting
```

## 密码接口

### 修改家长管理密码 ⭐
```
POST /api/parent_control/update_password
  sn+token+imei+new_pwd=明文密码+is_long_pwd=1
  （new_pwd 明文 trim 后直接传，无 MD5！）
```

### 清除家长管理密码
```
POST /api/parent_control/update_password
  sn+token+imei+new_pwd=""+is_long_pwd=0/1
  （空字符串即清除，Toast「已清除平板端家长管理密码」）
```

### 允许启动密码（allow_pwd）
```
POST /api/parent_control/update_allow_pwd
  sn+token+imei+allow_pwd=0/1
```
注意：**手机版 api-super 域参数名是 `allow_pwd`**，平板端 parent-manage 域的 uploadAllowPwd 是 `allow`——两个域参数名不同！

### 允许输入密码（allow_input_pwd）⭐ 之前无接口！
```
GET /api/parent_control/change_allow_input_pwd
  sn+token+imei+allow_input_pwd=0/1
```
PasswordchangelistActivity.requestAllowPassword(int) 调用，成功回调 onAllowPasswordSuccess 更新 DeviceListResponse.DataBean.allow_input_pwd。

### 获取管控密码
```
GET /api/parent_control/get_control_pwd
  sn+token+imei+...
```

### 密码记录
```
GET /api/parent_control/password_record
```

## 设备接口

### 设备列表（获取绑定设备 imei）
```
GET /api/parent_control/device_list
  sn+token+new_first=1+only_power=1
```
响应 `DeviceListResponse.DataBean`: `{imei, allow_input_pwd, has_allow_pwd, has_control_time, bluetooth_status, ...}`

### 管控应用
```
GET /api/parent_control/control_app
GET /api/parent_control/app_list
```

## 关键类映射

| 类 | 文件 | 说明 |
|----|------|------|
| ApiService | smali_classes4/api/ApiService.smali | 全部 Retrofit 接口定义 |
| TimeSettingPresenter | smali_classes5/presenter/TimeSettingPresenter.smali | setTime/setTimeSwitch/deleteTimeSetting/setTimeAnti |
| SetTimeActivity | smali_classes5/ui/activity/SetTimeActivity.smali | 时间管控设置 UI + getGroup/getPeriodStatus/getPeriodString |
| TimeSettingFragment | smali_classes5/modeule/machineCtl/TimeSettingFragment.smali | 拉取 time_setting |
| UpdatePasswordPresenter | smali_classes5/presenter/UpdatePasswordPresenter.smali | updatePassword/clearPassword/allowPassword |
| PasswordchangelistActivity | smali_classes5/ui/activity/password/PasswordchangelistActivity.smali | 密码开关 UI + requestAllowPassword |
| PasswordChangeActivity | smali_classes5/ui/activity/password/PasswordChangeActivity.smali | 修改密码（new_pwd 明文） |
| Util | smali_classes5/util/Util.smali | getSn/getUid8/getTimestamp |
| MobileLoginActivity | smali_classes5/ui/activity/MobileLoginActivity.smali | 登录（username+MD5密码） |
| TimeSettingResponse | smali_classes4/mode/response/TimeSettingResponse.smali | 时间管控响应结构 |

## 与平板端差异

| 项目 | 平板端 parent-manage | 手机端 api-super |
|------|---------------------|------------------|
| 签名 | getSign2 MD5(秒+APPSECRET+MD5("parent-manage")) / getSign 长签名 | getSn uid8+ts+MD5(ts+KEY+arg3)+包名 |
| 认证 | sn/signature 请求头 | sn+token 参数（登录后 access_token） |
| allow_pwd 参数名 | `allow` | `allow_pwd` |
| 时间管控 | ❌ 无上传接口 | ✅ set_time/time_switch/set_anti_addiction |
| allow_input_pwd | ❌ 无上传接口 | ✅ change_allow_input_pwd |
