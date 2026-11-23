# 商户端开发与联调

原生微信小程序目录为 `miniprograms/merchant`，当前 AppID 为 `wxc93dfbb2d1cb6fb4`。商户 API 使用父工程的 Spring Boot 4、Nacos、MySQL 和 Flyway，HTTP 路径为 `/api/merchant/v1`。微信登录实时读取管理后台最新的商户小程序配置，使用管理端的同一只读主密钥文件解密。若部署使用其他商户 AppID，请同时更新 `project.config.json`、服务环境变量 `WECHAT_MERCHANT_APP_ID` 和管理后台商户端微信配置。

## 本地启动

1. 在 `codes` 目录使用 JDK 21 或兼容的更高版本构建。
   ```sh
   mvn -pl merchant-api,admin-api,user-api,gateway -am package
   ```
2. 在环境中配置随机 `INTERNAL_API_KEY`，运行 `scripts/start-local.sh`。Compose 将该值传给管理端和用户端以及商户端调用方。复用已有数据库时保留加密配置所用的 `.local/wechat-master.key`。管理员配置正确的商户 AppID 和 AppSecret 后，重新登录即可读取最新版本。
3. 微信开发者工具导入 `miniprograms/merchant`。在 `miniprograms/merchant/utils/config.js` 手动设置 `apiEnvironment` 后重新编译。`development` 访问本机网关 `http://127.0.0.1:8082`，`production` 访问正式网关 `https://cfdapi.nexbyte.top`。当前默认使用 `production`。两套环境分别保存登录会话。正式联调前需在该 AppID 的微信后台登记 HTTPS 请求及下载域名。
4. 首次微信登录后提交店铺资料，由管理后台的商户页面审核。资料变更独立审核。旧档案与微信身份需由平台核验后绑定；新登录无法按名称或手机号认领旧店。

## 数据和联调边界

- 商户端写入店铺、商品及小票。交易状态、退款、扫码领取和位置由用户端交易服务维护，提现申请与冻结复用管理端账本。内部接口使用服务间密钥，公网网关仅转发端侧接口。
- 暂存点从到点时间起计算两个实际批次，按当时的运营配置计算。普通与高峰窗口各从起点按各自间隔计算，窗口结束时结束当前批次。领取码为随机、稳定的订单码，骑手服务鉴权后可调用交易服务的 `POST /internal/v1/delivery/pickup`。实体打印机需要型号和传输协议。
- 经营金额以支付日期归集。真实订单从完成或最后一次退款成功起，按创建订单时锁定的商户等待时长计时。达到时长且无未处理售后后，按成交规则和剩余餐品金额入账，金额四舍五入到分。商户端可查看实际抽成和入账金额。本地模拟单不入账。历史订单缺少成交抽成依据时暂停自动入账。
- 提现会冻结余额并进入管理员审核，审核通过后由转账渠道处理。新单订阅需要微信模板和服务端投递。商户地图显示交易服务最近一次有效位置，骑手端上线后可接续实时上报。
- 生产部署需先升级管理端与用户端的迁移，再部署商户服务、网关和反向代理配置。

## 微信提现

管理员审核通过后，`user-api` 使用已通过审核的店铺所绑定的商户端微信身份发起转账。生产 `user-api` 要配置独立的 `MERCHANT_TRANSFER_SCENE_ID` 和 `MERCHANT_TRANSFER_SCENE_REPORTS`，并在管理员端保存商户小程序 AppID 和对应微信商户的签名材料。场景和报备信息取自实际获批的商家转账业务。[微信支付接入指引](https://pay.wechatpay.cn/doc/v3/merchant/4054257704)

发布时先启动更新后的 `user-api` 应用 V12 迁移，再发布 `merchant-api` 和商户小程序。微信提示待收款确认时，商户在「提现记录」操作。已提交的申请按原单查单；签名验证失败或结果未知时保留冻结。需核验收款人实名的金额目前保留在待打款，等待财务处理。
