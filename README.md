# Orbit

Orbit is a self-hosted server for your Eclipse devices on the local network. It
keeps Eclipse users, settings and devices in one place, and gives you a web
dashboard to manage them from a phone or computer.

## Run it

```sh
docker compose up -d --build
```

Open `http://<server-ip>:7340` and create the admin account. You can also set
`ORBIT_ADMIN_PASSWORD` to create it on first start.

Without Compose:

```sh
docker build -t orbit .
docker run -d --name orbit --network host -v orbit-data:/data orbit
```

Host networking lets TVs find Orbit with UDP broadcast. If you publish ports
instead (`-p 7340:7340 -p 7341:7341/udp`), discovery usually won't cross Docker's
bridge network, so devices need the server URL entered by hand.

## Published image

[image.yml](.github/workflows/image.yml) builds Orbit for
`linux/amd64` and `linux/arm64` and publishes it to
`ghcr.io/onishax/orbit` in the GitHub Container Registry:

- `latest`: every push to `main` (pushes that change only Markdown files are skipped)
- `1.2.0`: a pushed `v1.2.0` tag
- `sha-<commit>`: every published build

Pull requests build the image but don't publish it. New GHCR packages are
private. To let other machines pull without signing in, open the package on
GitHub, choose **Package settings**, and change its visibility to **Public**.
Then, on a NAS or any other machine, you need only `compose.yaml` with
`image: ghcr.io/onishax/orbit:latest` and the `build:` line removed.

## Add device to Orbit

1. On the TV, choose **Add device to Orbit** during onboarding or in
   **Settings › Orbit**. The TV finds the server, calls `POST /api/v1/pair`, and
   shows `pairUrl` as a QR code with the six-character code.
2. Scan the QR code with your phone, sign in to the dashboard, check that the
   code matches the TV, name the device, and choose **Add to Orbit**.
3. The TV polls `POST /api/v1/pair/{code}/poll` with its secret. After approval,
   it receives its device token once and finishes setup by itself.

Pairing codes expire after 10 minutes by default (`ORBIT_PAIRING_TTL_SECONDS`).

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
| `PUT` / `GET /api/v1/backup` | Back up and restore this device's Eclipse settings (JSON) |
| `WS /api/v1/signal` | Relays `{to, type, payload}` between devices and adds `from`; also sends `presence` events |

The signalling socket handles messaging, file-sharing offers and WebRTC call
setup (offer, answer and ICE). Call audio and video go directly between devices
on the LAN.

## Roadmap

- Eclipse client: discovery, QR onboarding, and backup in Settings
- Marketplace install sync for each user
- Messaging and AirDrop-style file sharing, with file transfers over WebRTC
  data channels or Orbit upload/download
- Voice calls, then video and group video (group calls may need an SFU)
- Assign Eclipse users to devices from the dashboard

## Development

```sh
./gradlew run   # data goes to ./data
```
