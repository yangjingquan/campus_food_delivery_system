# 校园食递管理端及微服务（本地开发）

Vue 管理端和用户小程序统一经 API Gateway 访问业务 API；Nacos 提供服务注册发现和可选配置中心，MySQL 保存业务、账本和配置，Redis 保存管理员短期会话。前端不使用 Mock 适配器。

## 微服务模块

- 当前可运行：`admin-api`、`user-api`、`gateway` 和 Nacos 注册/配置中心。
- 技术栈规划中仍需建设：`merchant-api`、`rider-api`，以及用于订单通知和跨服务事件的 RabbitMQ 消息模块。
- 后续跨服务业务还需定义内部 API/事件契约、服务身份鉴权和数据写入归属。现在两个 API 暂时共用一个 MySQL schema 和账号；接入注册中心并不自动解决数据边界。
- Nacos 配置按 `admin-api.properties`、`user-api.properties`、`gateway.properties` 导入；未在 Nacos 创建对应配置时，服务继续读取仓库中的本地配置。

## 后端代码结构

`admin-api/src/main/java/com/campusfood/admin/` 按职责分包：

- `controller`：HTTP 路由、请求绑定和内部接口认证。
- `service`：权限检查、业务规则和事务边界。
- `dao`：按业务划分的 MyBatis Mapper 接口。单表条件查询、审核和资金更新使用 MyBatis-Plus Lambda 条件构造器；账户增减在数据库中原子执行，行锁仅追加服务端固定的 `FOR UPDATE` 或 `FOR SHARE` 片段。重复提现请求共享读取已有幂等记录，避免并发重放时发生锁升级死锁。
- `src/main/resources/mapper`：保留多表关联、聚合、返回字段别名及 `INSERT IGNORE` 等不适合 Lambda 构造器的参数化 SQL。
- `entity`：MyBatis-Plus 实体映射，其中部分实体仅包含当前条件构造器使用的字段，不代表完整的业务对象。Mapper 以语句级本地缓存执行，并保留空字段，以兼容事务中的读取和现有接口返回格式。
- 管理员角色关联表使用 `(user_id, role_code)` 复合主键。MyBatis-Plus 启动时会提示该实体没有单列 `@TableId`；其查询和删除使用 Lambda 条件，不使用按单一 ID 操作的方法。
- `dto`：管理员请求的数据结构。多态操作的 `payload` 和部分查询结果仍使用 Map，以兼容现有接口；可在后续按业务逐步细化。
- 根包保留应用入口及金额计算工具，数据库迁移位于 `src/main/resources/db/migration`。

上述迁移仅覆盖 `admin-api`；`user-api` 仍使用 `JdbcTemplate`。

## 启动

生产环境 Docker Compose、域名反向代理、主机初始化步骤和四个独立 Jenkins 流水线位于 [`deploy/production`](deploy/production/README.md)。生产配置启用 Nacos 鉴权、隔离项目数据库和 Redis 会话键，并将小程序默认 API 地址设为 HTTPS；真实支付和退款尚未实现，生产支付保持关闭。

首次在**全新数据库**使用前生成微信配置加密主密钥，之后启动服务：

```bash
cd codes
./scripts/init-local-key.sh  # 仅新环境执行一次；已有密钥时不要运行
./scripts/start-local.sh
```

浏览器访问 http://localhost:5190。统一 API Gateway 本机端口为 8082，Nacos 控制台端口为 8088、服务地址端口为 8848；业务 API 只在容器网络内开放。MySQL 本机端口 3307，Redis 仅容器网络内访问。MySQL、Redis 和 Nacos 使用 Docker 卷；不要用 `docker compose down -v` 删除数据。Nacos 为本地单机开发配置，当前未启用鉴权且只绑定本机回环地址，不能用于生产部署；生产环境启用鉴权时须配置独立的 `NACOS_AUTH_TOKEN`、节点身份值和客户端账号。初始账号默认 `admin` / `campus2026`，可在首次初始化前设置 `ADMIN_USER` 与 `ADMIN_PASSWORD`。登录后可从右上角修改密码；已有账号不会因修改环境变量改变密码。

用户端微信原生小程序项目位于 `miniprograms/user`，AppID 已配置为 `wxb2ef21a4d9800f4e`。用户 API 默认经 Gateway 的 `/api/user/v1` 路由访问；本地 Compose 使用隔离标注的登录和支付模拟能力，不会调用微信扣款。模拟付款会将订单支付状态标记为“本地模拟支付成功”，避免计入管理员端真实已支付金额。

```bash
cd codes
./scripts/start-local.sh
```

用微信开发者工具打开 `codes/miniprograms/user`。项目 URL 校验为本地开发设置，默认 API 地址是 `http://127.0.0.1:8082/api/user/v1`；真机联调时需改为可访问的 HTTPS 域名并在小程序后台配置 request 合法域名。服务端真实微信登录还需设置 `WECHAT_USER_APP_SECRET`。当前没有可用的微信支付商户号、API v3 密钥、商户私钥及回调地址，因此生产支付/退款保持关闭；仅提供真实支付参数到位后的客户端调用分支，不把模拟支付作为生产能力。

本地检查：

```bash
cd codes/miniprograms/user
npm test
npm run check:js
cd ../../user-api
mvn -o test
cd ..
python3 scripts/check-user-integration.py
```

用户 API 的 Compose 集成脚本会新建随机数据库和临时账号，启动临时管理员 API 与用户 API，覆盖单店下单、多店拆单、模拟支付、售后、内部履约和配送位置读取；脚本结束时会移除临时容器和数据库。它需要 Docker daemon 正常运行。该集成脚本与本地模拟支付只能验证项目内接口协作，不能替代微信开发者工具、真机登录、微信支付或生产 MySQL 并发验收。

`.local/wechat-master.key` 是微信敏感材料的加密主密钥，不进入代码仓库或镜像。应使用受控方式单独备份，与数据库备份分开保管；**若数据库已有密文，密钥丢失时应恢复原密钥，不要重新生成**。主密钥轮换和生产 KMS 尚未实现。首次创建的本机密钥只供本地开发；生产应使用受控密钥管理和 HTTPS。

本机直接启动前端：

```bash
cd codes/admin-web
nvm install
nvm use
npm ci
npm run dev
```

开发服务器默认将 `/api` 转发至 `http://localhost:8082`；可在 `.env.local` 配置 `VITE_PROXY_TARGET`。

## 已实现范围

- 运营概览，商户及骑手审核，订单与协调记录，售后、财务和提现记录查询，运营配置版本。
- 四类角色：超级管理员、运营审核、财务、只读；服务端校验权限，超级管理员可维护账号和角色。修改或重置密码使既有会话失效，至少保留一名启用的超级管理员。不设置双人复核。
- 提现申请的内部服务接口支持申请幂等、商户欠款优先扣回、提交时冻结、审核拒绝解冻及账本记录。欠款抵扣后余额不足时仍保留已扣回的欠款结果，并返回剩余可提现余额。审核通过为待打款，不表示到账。
- 面向可信渠道服务的转账准备和结果内部接口支持固定转账单号、未知状态保留冻结、明确失败解冻、成功扣减冻结以及重复通知保护。**这些接口不是微信回调入口**：外部渠道适配服务必须先完成真实签名验签及结果核验。`INTERNAL_API_KEY` 默认未配置，此时内部接口拒绝访问；不要在公网暴露内部接口或把密钥写入仓库。
- 微信设置分为三端小程序、支付配置和配置版本审计；敏感值留空保留、AES-256-GCM 加密保存、读取不回显。配置版本变更和回滚生成审计记录。支付渠道尚未联调，启用支付操作会被拒绝。
- 金额存储为整数分；比例计算工具使用 `HALF_UP` 四舍五入。实际订单结算和拆分尚未实现，不能据此认定完整结算规则已上线。

历史 V1 迁移中的两条商户和两条骑手为本地初始化样例；其余业务表初始为空，等待所属业务服务写入。迁移不会删除已有业务数据。

欠款自动扣回依赖交易服务将 `finance_debt.merchant_account_id` 关联到正确的商户资金账户，并维护平台垫付和已收回金额；历史上未关联账户的欠款不会自动扣回。提现申请目前仅有内部接口，尚未与商户端、骑手端的登录身份和账户权限打通。

## 尚未完成

用户端真实微信支付与退款客户端、回调验签与解密、微信账单对账、地图服务商/坐标配置、生产 request 域名、商户和骑手端真实内部调用、订单结算及自动扣费、消息与持久任务、生产级密钥轮换及安全加固仍需开发和联调。当前系统仅用于本地开发，不具备真实资金交易条件。

隔离联调可执行 `python3 scripts/check-integration.py`；脚本在现有 MySQL 容器内创建随机临时数据库、随机账号和临时后端容器，结束后删除这些临时资源。执行需要本机安装 OpenSSL、Docker 正常运行并允许访问 Docker 守护进程。

## 商户端

商户 API 和原生微信小程序的启动及联调说明见 [merchant-api/README.md](merchant-api/README.md)。
