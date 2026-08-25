# 时间管控（control_time）逆向结论

## 数据结构（jpush/content 返回）
```json
"control_time": {
  "anti_addiction": {"use_duration": 3600, "rest_duration": 1800},
  "control_time_list": [{
    "tid": 3462940,
    "group": "1,2,3,4,5,6,0",
    "total_time": 14400,
    "period_status": 1,
    "periods": [{"start": 28800, "end": 82800}]
  }],
  "model_whitelist": 1,
  "time_lock_status": 0
}
```
- total_time=14400秒=4小时（每日管控总时长）
- periods: 8:00-23:00（可管控时间段）
- anti_addiction: 使用1小时休息30分钟

## 拉取处理（GetOnlineAppResponse2）
- control_time_list → TimeControlHelper 存 new_time_control 表
- 表结构: _id, tid(long), _group(varchar200), total_time(long), period_status(int), periods(varchar1000)
- anti_addiction → AntiAddictionHelper 存库

## 上传接口搜索结论
- 全部 30+ Response 类、全部 API URL 常量、SyncHandler 全部 case 均无 control_time 上传
- control_time 与 allow_input_pwd 相同：服务器单向下发，设备端无上传接口
- 本地改 new_time_control 表会被服务器下次拉取覆盖（拉取时 deleteAll 重建）

## 可执行的方案
1. 展示时间管控配置（本地+远程都可看）
2. 本地模式直接改 new_time_control 表（临时生效，服务器拉取覆盖）
