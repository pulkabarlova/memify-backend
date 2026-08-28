# Production deployment

The production workflow builds the Ktor fat JAR on a GitHub-hosted runner and
streams that artifact through `ssh.memify.space`, which is routed to the VM by
Cloudflare Tunnel. The VM does not need outbound GitHub access, and inbound SSH
is not opened in the Yandex Cloud security group.

## What the workflow deploys

- A push to `main`, or a manual run selected on `main`, builds
  `build/libs/backend-all.jar` with Java 21.
- The workflow verifies the JAR size and SHA-256 checksum on both sides of the
  Cloudflare connection.
- A dedicated SSH key is restricted to the root-owned `memify-receive` command.
- The root-owned `/usr/local/sbin/memify-deploy` wrapper replaces only the
  backend artifact and runs Docker Compose with `--no-deps backend`.
- PostgreSQL is not recreated, and the `memify_pgdata` volume is preserved.
- A failed local health check restores the previous JAR. The five latest JAR
  backups are kept under `/opt/memify/backups`.
- A GitHub-hosted runner verifies `https://api.memify.space/posts` after the VM
  reports a healthy local endpoint.

## One-time VM setup

1. Add `ssh.memify.space` to the existing Cloudflare Tunnel as a published
   application with service URL `ssh://localhost:22`.
2. Create a dedicated Linux user named `memify-deployer` without Docker group
   membership. Put the deployment public key in its `authorized_keys` with
   `restrict` and a forced `/usr/local/bin/memify-receive` command.
3. Copy the reviewed deployment descriptors to `/opt/memify`:
   `Dockerfile.cloud`, `docker-compose.yml`, `docker-compose.cloud.yml`, and
   `Caddyfile`. Keep the existing `.env`, `certs`, and Docker volumes in place.
4. Install the receiver and deployment wrapper as root:

   ```shell
   sudo install -o root -g root -m 0755 deploy/memify-receive /usr/local/bin/memify-receive
   sudo install -o root -g root -m 0755 deploy/memify-deploy /usr/local/sbin/memify-deploy
   ```

5. Allow only that fixed wrapper through passwordless sudo by creating
   `/etc/sudoers.d/memify-deploy` with mode `0440`:

   ```text
   memify-deployer ALL=(root) NOPASSWD: /usr/local/sbin/memify-deploy *
   ```

6. Store the deployment private key as the repository secret
   `PRODUCTION_SSH_KEY`.
7. In GitHub, create the `production` environment. If the repository plan
   supports it, require approval before deployment.

The deployment private key must exist only in GitHub Secrets and the temporary
machine used during setup. It must never be added to the repository or `.env`.

## Infrastructure changes

Routine workflow runs intentionally deploy only `backend-all.jar`. Changes to
Compose, Caddy, the Dockerfile, the root wrapper, or runtime secrets must be
reviewed and installed on the VM separately before the next application deploy.
