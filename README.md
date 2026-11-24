# Orbit

Orbit is a self-hosted server for your Eclipse devices on the local network,
with a web dashboard for managing devices and Orbit users. Roaming user sync is
experimental; settings backup and Marketplace sync are not yet available.

## Run it

```sh
docker compose up -d --build
```

Open `http://<server-ip>:7340` and create the admin account. You can also set
`ORBIT_ADMIN_PASSWORD` to create it on first start.

Without Compose:

```sh
docker build -t eclipse-orbit .
docker run -d --name eclipse-orbit --network host -v orbit-data:/data eclipse-orbit
```

Host networking lets TVs find Orbit with UDP broadcast. If you publish ports
instead (`-p 7340:7340 -p 7341:7341/udp`), discovery usually won't cross Docker's
bridge network, so devices need the server URL entered by hand.

On Docker Desktop for macOS or Windows, turn on **Settings › Resources ›
Network › Enable host networking**. Without it, the dashboard isn't reachable
at port 7340.

## Published image

[image.yml](.github/workflows/image.yml) builds Orbit for
`linux/amd64` and `linux/arm64` and publishes it to
`ghcr.io/onishax/eclipse-orbit` in the GitHub Container Registry:

- `latest`: every push to `main` (pushes that change only Markdown files are skipped)
- `1.2.0`: a pushed `v1.2.0` tag
- `sha-<commit>`: every published build

Pull requests build the image but don't publish it. New GHCR packages are
private. To let other machines pull without signing in, open the package on
GitHub, choose **Package settings**, and change its visibility to **Public**.
Then, on a NAS or any other machine, you need only `compose.yaml` with
`image: ghcr.io/onishax/eclipse-orbit:latest` and the `build:` line removed.

## Add device to Orbit

1. On the TV, choose **Add device to Orbit** during onboarding or in
   **Settings › Connections › Orbit**. The TV finds the server, calls `POST /api/v1/pair`, and
   shows `pairUrl` as a QR code with the six-character code.
2. Scan the QR code with your phone, sign in to the dashboard, check that the
   code matches the TV, name the device, and choose **Add to Orbit**.
3. The TV polls `POST /api/v1/pair/{code}/poll` with its secret. After approval,
   it receives its device token once and finishes setup by itself.

Pairing codes expire after 10 minutes by default (`ORBIT_PAIRING_TTL_SECONDS`).

The dashboard can assign Orbit users to a device. On the TV, open
**Settings › Connections › Orbit**, set the same strong Orbit passphrase on
each device, and choose **Orbit users**. For each assigned user, add a separate
local user or explicitly link an existing one. Linking a PIN-protected user
requires the PIN, and a settings choice is offered when necessary. New users
without an existing Orbit profile start without a PIN.

Linked users sync while the Eclipse launcher activity is running. Remote
changes are deferred until Home is safe to update; conflicts require an explicit
choice in Orbit users. Assignments never automatically remove local users.
This feature is still experimental, so keep local copies of important settings.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `ORBIT_SERVER_NAME` | `Orbit` | Name shown in the dashboard and discovery |
| `ORBIT_HTTP_PORT` | `7340` | Dashboard, API and WebSocket |
| `ORBIT_DISCOVERY_PORT` | `7341` | UDP discovery |
| `ORBIT_DATA_DIR` | `/data` | State and backups (Docker volume) |
| `ORBIT_ADMIN_USER` / `ORBIT_ADMIN_PASSWORD` | `admin` / unset | Optional first-run admin account |
| `ORBIT_PUBLIC_URL` | unset | Base URL in QR codes when Orbit runs behind a reverse proxy |

## Device API

Device requests send `Authorization: Bearer <token>`.

| Endpoint | Purpose |
| --- | --- |
| UDP `who is Orbit?` → port 7341 | Discovery reply: `{id, name, port}` |
| `GET /api/v1/info` | Server identity (no authentication) |
| `POST /api/v1/pair`, `POST /api/v1/pair/{code}/poll` | QR pairing |
| `GET /api/v1/me`, `GET /api/v1/devices`, `GET /api/v1/profiles` | Device, peer devices and Eclipse users |
| `GET /api/v1/profile-vaults`, `PUT /api/v1/profile-vaults/{id}` | Encrypted-profile storage foundation; not yet used by Eclipse |
| `GET /api/v1/assigned-profiles` | Assigned users and their encrypted profiles in one consistent response |
| `GET` / `PUT /api/v1/passphrase-verifier` | Read or initialize the encrypted, write-once shared-passphrase verifier |
| `DELETE /api/v1/me` | Leave Orbit (removes this device and its backup) |
| `PUT` / `GET /api/v1/backup` | Store or retrieve a device's JSON backup (not yet used by Eclipse; not encrypted by Orbit) |
| `WS /api/v1/signal` | Relays `{to, type, payload}` between devices and adds `from`; also sends `presence` events |

The signalling socket handles messaging, file-sharing offers and WebRTC call
setup (offer, answer and ICE). Call audio and video go directly between devices
on the LAN.

Profile vault writes require assignment to the device and a `baseRevision`;
stale writes return HTTP 409. Vaults use base64-encoded 16-byte salt, 12-byte
nonce and AES-256-GCM ciphertext with its 16-byte tag. Eclipse derives the key
from the shared passphrase using PBKDF2-HMAC-SHA256 with 310,000 iterations;
the authenticated data is `eclipse-orbit-profile-v1:<serverId>:<profileId>`.
Orbit only validates the envelope, not whether a client actually encrypted
the contents. Eclipse's experimental sync does not run while its launcher
activity is stopped by another app.
Use HTTPS for transport: encryption does not protect device tokens sent over
Orbit's default HTTP connection.
The verifier encrypts `eclipse-orbit-passphrase-v1` using the same profile
cipher and the associated-data profile ID `passphrase-verifier`. The first
initializer wins; every device must decrypt it before synchronizing. Existing
experimental vaults created without a verifier require migration and cannot
initialize one automatically. The verifier allows offline passphrase guessing
if Orbit's data is exposed, so use a strong passphrase.
The experimental Eclipse profile snapshot includes the user name, colour, PIN
verifier and portable preferences. Device-specific input mappings, install
flags, last focus, USB folder grants, permission prompts, and device-encrypted
media connections are excluded.

## Roadmap

- Passphrase-encrypted settings backup and restore, including sign-ins, offered
  during onboarding and in Settings
- Harden and validate roaming user sync, including lifecycle and recovery cases
- Optional Marketplace sync or one-time install, excluding inputs
- Messaging and AirDrop-style file sharing, with file transfers over WebRTC
  data channels or Orbit upload/download
- Voice calls, then video and group video (group calls may need an SFU)

## Development

```sh
./gradlew run   # data goes to ./data
```
