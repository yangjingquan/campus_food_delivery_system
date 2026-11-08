# User API

Spring Boot service for the native WeChat user mini program. It shares the existing MySQL business schema with `admin-api`; its Flyway history table is separate and starts from baseline version `0` before applying user-domain migrations.

## Run locally

From `codes/`, run `./scripts/start-local.sh`. Compose registers `user-api` with Nacos and exposes its API through Gateway at `http://127.0.0.1:8082/api/user/v1`. The service uses the `local` profile, seeded sample merchants/products, stable mock login, and local-only simulated payments. Configure `WECHAT_USER_APP_SECRET` for WeChat `jscode2session`; a public AppID alone cannot authenticate real users.

The mini program API root is `/api/user/v1`. Authenticated calls send `Authorization: Bearer <token>`.

| Route family | Purpose |
| --- | --- |
| `POST /auth/wechat`, `POST /auth/logout`, `GET /me` | Session login and user identity |
| `GET /config`, `GET /catalog` | Current fee, delivery schedule, and available merchants/products |
| `/addresses` | User-owned address list and CRUD |
| `POST /checkout` | Idempotent single-store or multi-store checkout; creates one payment record and one business order per merchant |
| `/payments/{id}` | Payment status and merchant-order split; `POST /mock-success` is local-profile only |
| `/orders` and `/orders/{id}` | User-owned merchant orders and details |
| `/orders/{id}/location` | Latest location only while the order is being delivered; stale locations are labelled |
| `/orders/{id}/aftersales`, `/aftersales/{id}` | Refund/after-sale submission and status |
| `/internal/v1/...` | Fulfillment, rider location, merchant decisions, and verified refund results; requires `X-Internal-Key` |

The checkout fee is read from `operations_config` and applied once per merchant. Single-store payment creates one merchant order; a multi-store payment creates one merchant order per store. Unpaid reservations expire using the configured timeout. Refunds close only the merchant order they apply to, and `first_closed_at` preserves the original 24-hour after-sale deadline.

Production payment and refund channels are disabled until the WeChat merchant account, certificates/keys, API v3 key, callback URL, and HTTPS request domain are configured and verified. The mock payment route refuses to run outside the `local` profile.

## Checks

```bash
mvn -o test
python3 ../scripts/check-user-integration.py
```

The integration script creates a random temporary database and account, then removes them after checking single-store checkout, multi-store splitting, partial refund limits, the non-resetting 24-hour window, independent merchant fulfillment, and delivery-location state.
