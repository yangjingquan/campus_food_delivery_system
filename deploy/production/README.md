# Production deployment

This directory contains the production Compose definition and the reverse-proxy configuration for the existing server. The stack uses the server's MySQL and Redis, with dedicated campus-food schemas and credentials. The two business APIs currently share business tables, so they use one restricted application account and one schema; Nacos has its own schema and account.

The host uses `campus-food-backend` for the application containers and the existing MySQL and Redis, `campus-food-proxy` for the gateway and admin web proxy, and the internal `campus-nacos-proxy` network between `shop-nginx` and the Nacos console. Nacos console port 8080 is also published on host loopback port 18848; its API and gRPC ports remain on the private backend network.

## Required host files

- `/opt/campus-food/.env`, mode `0600`: generated credentials and per-service image tags. Never commit it.
- `/opt/campus-food/secrets/wechat-master.key`, mode `0600`: Base64-encoded 32-byte AES key. Back it up separately from MySQL.
- `/opt/campus-food/secrets/cfdadmin.nexbyte.top.key` and `cfdapi.nexbyte.top.key`, mode `0600`.
- `/opt/campus-food/secrets/cfdadmin.nexbyte.top_bundle.pem` and `cfdapi.nexbyte.top_bundle.pem`, mode `0644`.
- `/etc/shop/tls/cfdnacos.nexbyte.top_bundle.pem` and `cfdnacos.nexbyte.top.key`: the Nacos domain certificate and private key. The supplied certificate must be renewed and replaced before expiry.
- `/opt/campus-food/secrets/cfdnacos-basic-password`, mode `0600`: the Nginx Basic Auth password for user `nacos-ops`. The hashed password and Nginx configuration are mounted from `/opt/campus-food/nginx/`.

The environment must set `APP_DB_PASSWORD`, `NACOS_DB_PASSWORD`, `REDIS_CFD_PASSWORD`, `NACOS_AUTH_TOKEN`, `NACOS_AUTH_IDENTITY_VALUE`, `NACOS_PASSWORD`, `ADMIN_BOOTSTRAP_PASSWORD`, and `INTERNAL_API_KEY`. The user API reads the latest user mini-program AppID and encrypted AppSecret from the admin-managed configuration, using the same protected master key as the admin API. It can start before the AppSecret is configured, but real WeChat login returns unavailable until it is saved. The default `USER_PAYMENT_MODE` is `disabled`. After the updated user API is deployed and the channel is verified, set `USER_PAYMENT_MODE=wechat` in the production environment to allow new payments. Set the admin-managed payment callback URL to the HTTPS gateway endpoint `/api/user/v1/payments/wechat/notify`. The user API confirms refunds by querying WeChat Pay with the original refund number. Set all four image tags to `latest` before the first deployment; a Jenkins release updates only its own tag after its health check succeeds.

## First installation order

1. Upgrade the VM to at least 8 GiB RAM (12 GiB recommended) and confirm Docker has enough disk for four Java/Node builds plus image retention. The current 3.7 GiB VM is already under memory pressure. The server has no configured Alibaba Cloud CLI credentials, so resizing must be done through the provider console or a newly configured cloud role.
2. Run `backup-existing-mysql.sh` to make a root-only logical backup, then create `campus_food_delivery` and `campus_food_nacos` plus their dedicated accounts. Initialize the Nacos 3.1.1 schema in the latter using the SQL shipped for that exact Nacos version. The Redis ACL change preserves the existing named data volume.
3. Create the two Docker networks and connect the existing database/cache containers to the backend network and `shop-nginx` to the proxy network. This leaves the other projects' existing networks intact.
4. Install certificates and production secrets under `/opt/campus-food/secrets`; install the HTTP redirect file in `/opt/jenkins/nginx/conf.d` and the two TLS server files in the active `shop-nginx` configuration directory. Test both Nginx configurations before reload.
5. Run `start-nacos.sh` to start Nacos and initialize its administrator password through the loopback-only bootstrap API. Start `admin-api` to apply Flyway migrations and create the bootstrap administrator; run `cleanup-demo-data.sh` once to remove the exact V1 local demo merchants and riders from the new database.
6. Create the four independent Jenkins Pipeline jobs using the repository's `main` branch and these script paths: `deploy/jenkins/Jenkinsfile.gateway`, `deploy/jenkins/Jenkinsfile.admin-api`, `deploy/jenkins/Jenkinsfile.user-api`, and `deploy/jenkins/Jenkinsfile.admin-web`.
7. Deploy `user-api`, `gateway`, and `admin-web` independently, checking the health status and the two public HTTPS hostnames after each release.

## Release behavior

Each Jenkins job builds only its own image, then runs `docker compose up` for only that service. Jenkins serializes image builds, and deployments serialize through a host lock. A failed health check attempts to restore the previous image tag. Image rollback cannot reverse a Flyway schema migration; database changes remain forward-only and need a compatible expand/migrate/contract plan.

## Enable the merchant API route

1. Run the `campus-food-merchant-api` and `campus-food-gateway` Jenkins jobs from a revision that includes the merchant service and the gateway's `/api/merchant/**` route.
2. On the production host, update the active `cfdapi.conf` for `shop-nginx` with the `/api/merchant/` location from `cfdapi.nginx.conf`. Preserve host-specific settings and existing certificate files. Run `docker exec shop-nginx nginx -t` before reloading with `docker exec shop-nginx nginx -s reload`.
3. Verify that an empty JSON body sent to `POST https://cfdapi.nexbyte.top/api/merchant/v1/auth/login` returns HTTP 400 with a JSON response. Verify that the existing user configuration endpoint still returns 200.
4. Save the merchant mini-program AppID and its matching AppSecret in the production admin console. The AppID must match `WECHAT_MERCHANT_APP_ID` in the merchant service and the mini-program's `project.config.json`. Then retry with a new WeChat login code from the developer tools.

## Redis account

The existing Redis 7.4 container uses an unrestricted `default` account. `enable-redis-acl.sh` snapshots the Redis RDB and affected configuration, rotates the shared default password, installs a persistent ACL file, and adds a `cfd-app` account limited to `~cfd:*`. It then recreates the shop admin API, shop Mini Program API, and workbench API one at a time with the new credentials. The inspected host had no active Redis connections from the AI knowledge base or ERP containers. The project session key uses the `cfd:` prefix.

## Known production boundary

The user API supports real WeChat login once the user mini-program AppID and AppSecret are saved in the admin console. Configuration changes are read on subsequent logins without redeploying the service. Payment and refund processing are not implemented; this deployment keeps payment disabled. The existing user-facing Mini Program defaults to `https://cfdapi.nexbyte.top/api/user/v1` and URL checking is enabled. Add that HTTPS API domain to the mini-program's allowed request domains before upload in a later release.

The Nacos console is available at `https://cfdnacos.nexbyte.top/` behind Nginx Basic Auth and Nacos login. `cfdnacos.nginx.conf` is the TLS proxy configuration; `cfdnacos-http.conf` supplies the HTTP redirect and ACME challenge path. The existing `/opt/shop/docker-compose.yml` must retain the Nacos proxy network and the two `/opt/campus-food/nginx/` bind mounts when `shop-nginx` is recreated.

The pinned Nacos 3.1.1 console sends its login token in both `Authorization` and `AccessToken`. The Nginx JavaScript response filter removes the former so the browser can retain its Basic Auth header; the `AccessToken` header remains and Nacos authorization stays enabled. Recheck the filter and both authentication layers when upgrading Nacos.
