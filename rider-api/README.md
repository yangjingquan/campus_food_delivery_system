# 骑手端接入

小程序工程位于 `miniprograms/rider`，AppID 为 `wx8ebb7eaaac795c54`。服务名为 `rider-api`，端口 8084，网关入口为 `/api/rider/v1`。管理员端保存的小程序配置与该 AppID 一致时，服务使用共享主密钥文件解密 AppSecret。

## 启动与联调

1. 使用 Java 21+、MySQL 8 和 Nacos。各服务共享业务库。先启动 `admin-api`，再启动 `user-api` 和 `merchant-api`，最后启动 `rider-api`、网关。Flyway 会应用各自的迁移。
2. 设置 `RIDER_USER_API_URL`、`RIDER_ADMIN_API_URL`、`USER_API_INTERNAL_KEY`、`INTERNAL_API_KEY` 和 `WECHAT_MASTER_KEY_FILE`。生产 Compose 已映射对应配置。部署时使用同一版本的管理端前后端、用户服务、骑手服务及网关。
3. 在微信开发者工具导入小程序目录。联调本机网关时，把 `utils/config.js` 的 `develop` 地址改为 `http://127.0.0.1:8082`。发布前在微信公众平台配置 HTTPS 请求及上传域名、隐私保护指引和位置权限。
4. 在共享库的 `delivery_destination` 中录入楼栋名称及 GCJ-02 坐标，名称与用户订单楼栋字段一致。缺少坐标时仍可按文字地址配送。骑手前台每 20 秒为仍在配送的订单更新位置，服务端默认 60 秒后标记位置过期。定位拒绝时暂停扫码领取并提示打开授权。
5. 配送提醒需要骑手小程序的订阅模板。配置 `RIDER_REMINDER_TEMPLATE_ID`、`RIDER_REMINDER_TIME_FIELD`、`RIDER_REMINDER_MESSAGE_FIELD`，其中字段名取自获批模板。一次授权用于一条通知，服务按后台提醒间隔、骑手可配送时段和实际待领取数发送。
6. 微信商家转账需实际商户号已开通相应场景，并绑定骑手小程序 AppID。在 `user-api` 配置 `RIDER_TRANSFER_SCENE_ID` 和 `RIDER_TRANSFER_SCENE_REPORTS`（已获批场景要求的 `info_type` 与 `info_content` 对象数组）。渠道按原申请查单，结果未知时保留冻结；渠道明确失败时沿用管理员端解冻规则。渠道状态以微信签名响应为准。[发起转账](https://pay.wechatpay.cn/doc/v3/merchant/4012716434) · [查单](https://pay.wechatpay.cn/doc/v3/merchant/4012716437)

## 端侧 API

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/auth/login` | 以微信登录 code 换取骑手会话 |
| GET | `/profile` | 读取本人档案与审核状态 |
| POST | `/profile/application` | 提交资料和学生证 multipart 文件 |
| GET | `/batches` | 查看批次、待领取数及待领餐列表 |
| POST | `/batches/subscription` | 记录提醒授权 |
| POST | `/pickup` | 逐单领取小票上的二维码 |
| GET | `/orders?scope=active\|history\|income` | 查询本人配送及结算记录 |
| GET | `/orders/{id}` | 查询本人订单、售后和配送详情 |
| POST | `/orders/{id}/complete` | 确认送达 |
| POST | `/orders/{id}/location` | 上传该单定位 |
| GET | `/finance` | 查询本人收益和账户余额 |
| GET / POST | `/withdrawals` | 查询记录或提交幂等提现申请 |
| GET | `/withdrawals/{id}` | 查询审核、渠道结果和收款参数 |

除登录外，请求使用骑手 Bearer 会话。位置、送达和提现金额分别由交易服务及管理员账本处理。学生证图片由数据库保存，仅授权管理员可通过管理端查看。
