# Deploying OpsHub on Rocky Linux 9

This guide covers a single-host production deployment of OpsHub (Nginx
gateway, React frontend, Spring Boot backend, PostgreSQL) using Docker
Compose on Rocky Linux 9.

## 1. Install Docker Engine and the Compose plugin

Rocky Linux 9 does not ship Docker in its default repos; install from
Docker's official CentOS/RHEL repo (compatible with Rocky 9):

```bash
sudo dnf -y install dnf-plugins-core
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo dnf -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
```

Add the deploying user to the `docker` group if you don't want to run
Compose as root:

```bash
sudo usermod -aG docker "$USER"
# log out and back in for the group change to take effect
```

## 2. Open only the required firewall ports

OpsHub's gateway is the only container that publishes host ports. The
backend and PostgreSQL are reachable only on the private Docker network
`opshub-internal` and are never exposed to the host or the network.

```bash
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --reload
```

Do **not** open 5432 (Postgres) or 8080 (backend) — they should stay
unreachable from outside the Docker host.

## 3. SELinux

Rocky Linux 9 runs SELinux in `Enforcing` mode by default. The bind
mounts in `deploy/compose.yaml` use the `:Z` mount flag, which tells
Docker to apply a private SELinux label to each mounted directory so only
the owning container can access it — no manual `chcon`/`semanage fcontext`
is required. `deploy/scripts/preflight.sh` reports the current SELinux
mode and re-applies labels defensively if `chcon` is available.

If you use bind mounts with custom SELinux policy management, keep the
`:Z` flag in `deploy/compose.yaml` rather than removing it — without it,
Enforcing mode will deny the backend and Postgres containers access to
their data directories.

## 4. Create persistent data directories

```bash
sudo mkdir -p /opt/opshub/data/evidence /opt/opshub/postgres /opt/opshub/tls
sudo chown -R "$USER":"$USER" /opt/opshub
```

| Host path                | Mounted into      | Purpose                                   |
|---------------------------|-------------------|--------------------------------------------|
| `/opt/opshub/data`         | `backend` (`/var/lib/opshub/evidence`) | Uploaded evidence files, persists across restarts |
| `/opt/opshub/postgres`     | `postgres` (`/var/lib/postgresql/data`) | Database files |
| `/opt/opshub/tls`          | `gateway` (`/etc/nginx/tls`)            | TLS certificate/key |

Both `/opt/opshub/data` and `/opt/opshub/postgres` are mounted with the
`:Z` SELinux flag and must survive `docker compose down` — they are **not**
declared as anonymous or named Docker volumes, so they are never deleted
by `docker compose down -v`.

## 5. TLS certificate placement

Place your certificate and private key at:

```
/opt/opshub/tls/fullchain.pem
/opt/opshub/tls/privkey.pem
```

- If you have a certificate from a CA (e.g. via `certbot` or your
  organization's PKI), copy `fullchain.pem` and `privkey.pem` there.
- If no certificate is present yet, `deploy/scripts/preflight.sh`
  generates a temporary self-signed certificate so the stack can start;
  replace it with a real certificate before exposing the host to the
  internet. Browsers/Hub clients will show a trust warning until you do.
- The gateway listens on both 80 and 443; port 80 serves the application
  directly (useful during initial bring-up before certificates exist).
  Once you have a real certificate, prefer directing all external traffic
  to 443.

## 6. Configure secrets

```bash
cp deploy/env/backend.env.example deploy/env/backend.env
chmod 600 deploy/env/backend.env
$EDITOR deploy/env/backend.env
```

Set at minimum:

- `POSTGRES_PASSWORD` — a strong, random database password.
- `OPSHUB_HUB_TOKEN` — the shared bearer token the Local Hub uses to
  authenticate to `/api/v1/hubs/*` and `/ws/v1/hubs/{hubId}` (generate
  with `openssl rand -hex 32`).
- `GEMINI_API_KEY` (optional) — enables Gemini-backed text/thumbnail
  validation; leave blank to disable that validator.

`deploy/env/backend.env` is git-ignored and must never be committed.
`deploy/scripts/preflight.sh` checks that every variable **name** from
`backend.env.example` is present in `backend.env` — it never reads or
prints the values.

## 7. Run preflight checks

```bash
bash deploy/scripts/preflight.sh
```

This verifies Docker/Compose are installed and the daemon is reachable,
ports 80/443 are free, `/opt/opshub/data`, `/opt/opshub/postgres`, and
`/opt/opshub/tls` exist and are writable, SELinux labels are compatible,
the secrets file is present with all expected variable names, there is at
least 10 GB free on the data volume, and a TLS certificate exists
(bootstrapping a self-signed one if not).

## 8. Build and start the stack

```bash
docker compose -f deploy/compose.yaml build
docker compose -f deploy/compose.yaml up -d
docker compose -f deploy/compose.yaml ps
```

Wait for all four services (`postgres`, `backend`, `frontend`, `gateway`)
to report `healthy`. The backend's health check does not pass until
Flyway has migrated the schema and Spring's readiness probe reports `UP`,
so `backend` will show `starting` for the first tens of seconds on a
fresh database.

## 9. Smoke test

```bash
bash deploy/scripts/smoke.sh
```

Checks, through the public gateway only (except the backend readiness
check, which uses `docker compose exec` over the private network since
actuator endpoints are intentionally not exposed publicly):

- the frontend is served at `/`, and deep links (e.g. `/plans/...`) fall
  back to `index.html` for client-side routing;
- backend readiness is `UP` (implies Flyway migration succeeded);
- `/api/v1/operations` reaches the backend through the gateway;
- a WebSocket upgrade request to `/ws/v1/hubs/{hubId}` reaches the
  backend;
- the backend and Postgres containers do not publish any host port.

## 10. Updating

```bash
git pull
docker compose -f deploy/compose.yaml build
docker compose -f deploy/compose.yaml up -d
```

Data in `/opt/opshub/data` and `/opt/opshub/postgres` is untouched by
rebuilds or `docker compose up`. Only `docker compose down` without any
extra volume flags is safe for routine restarts — bind-mounted host
directories are never removed by Compose regardless.

## 11. Backups

At minimum, back up:

- `/opt/opshub/postgres` (or better, a `pg_dump` taken from inside the
  running `postgres` container, which is safer for point-in-time
  restores than copying data files live).
- `/opt/opshub/data/evidence` (uploaded evidence files).
- `deploy/env/backend.env` (store in a secrets manager, not alongside the
  data backups).
