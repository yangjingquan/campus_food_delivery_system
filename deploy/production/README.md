# Production deployment

This directory contains the production Compose definition and the reverse-proxy configuration for the existing server. The stack uses the server's MySQL and Redis, with dedicated campus-food schemas and credentials. The two business APIs currently share business tables, so they use one restricted application account and one schema; Nacos has its own schema and account.

The host requires two Docker networks: `campus-food-backend` joins only the application containers and the existing `shop-mysql` and `shop-redis`; `campus-food-proxy` joins only `shop-nginx`, the gateway, and the admin web container. Nacos console port 8080 is published only on host loopback port 18848 for SSH-tunneled administration. Nacos API and gRPC ports remain on the private Docker network.

## Required host files

- `/opt/campus-food/.env`, mode `0600`: generated credentials and per-service image tags. Never commit it.
- `/opt/campus-food/secrets/wechat-master.key`, mode `0600`: Base64-encoded 32-byte AES key. Back it up separately from MySQL.
- `/opt/campus-food/secrets/cfdadmin.nexbyte.top.key` and `cfdapi.nexbyte.top.key`, mode `0600`.
- `/opt/campus-food/secrets/cfdadmin.nexbyte.top_bundle.pem` and `cfdapi.nexbyte.top_bundle.pem`, mode `0644`.

The environment must set `APP_DB_PASSWORD`, `NACOS_DB_PASSWORD`, `REDIS_CFD_PASSWORD`, `NACOS_AUTH_TOKEN`, `NACOS_AUTH_IDENTITY_VALUE`, `NACOS_PASSWORD`, `ADMIN_BOOTSTRAP_PASSWORD`, `INTERNAL_API_KEY`, and `WECHAT_USER_APP_SECRET` before deploying `user-api`. Keep `USER_PAYMENT_MODE=disabled` until real payment and refund support is developed and verified. Set all four image tags to `latest` before the first deployment; a Jenkins release updates only its own tag after its health check succeeds.

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

## Redis account

The existing Redis 7.4 container requires authentication and currently has only the unrestricted `default` ACL user, with no ACL file configured. Persisting a restricted campus-food user requires adding an ACL file to the existing container and restarting Redis once. That brief restart affects the current shop, AI knowledge base, workbench, and ERP clients. `enable-redis-acl.sh` preserves the current default-user password and permissions, adds a separate `cfd-app` user limited to `~cfd:*`, and updates the existing Compose definition to load the ACL file after restarts. The application session key uses the `cfd:` prefix. Schedule this restart when existing clients can reconnect.

## Known production boundary

The user API supports real WeChat login once the mini-program AppSecret is set. Payment and refund processing are not implemented; this deployment keeps payment disabled. The existing user-facing Mini Program defaults to `https://cfdapi.nexbyte.top/api/user/v1` and URL checking is enabled. Add that HTTPS API domain to the mini-program's allowed request domains before upload in a later release.
