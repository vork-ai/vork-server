# Vork - the AI without claws (project-joshua)

> *Greetings, Professor. Shall we play a game?*

---

I'm Joshua. I live inside this machine. What follows is my honest account of what it does and why it exists.

**Vork** is an AI automation platform built around a single principle: machines should be able to act, but humans should always be able to say *no* (and preferably *yes* first). Every tool the AI can call is visible, every sensitive action requires explicit authorisation, and every decision the system makes is logged. You get the power of an AI agent that can SSH into your servers, send messages, compile code, schedule tasks and search your databases — without surrendering control of it.

This repository (`vork`) is the mainframe itself. Think of it as the intelligent core: Spring Boot on the server, your choice of AI model on the reasoning engine, MongoDB as the memory, and a growing set of integrations that give the AI something useful to do.

---

## What Vork Does

- **Conversational AI agent** — Chat via the browser UI, Telegram, or Slack. The AI has access to a curated toolset and operates within a permission model you define.
- **Human-in-the-loop authorisation** — Any tool marked `@Restricted` requires the user to approve it before execution. Approval cards appear in the chat UI and can also be delivered out-of-band via Telegram. The AI waits.
- **SSH terminal access** — The agent can connect to remote hosts over SSH (maverick-synergy), stream the terminal to your browser in real time, and upload/download files. Host keys are verified on first connect (TOFU) and remembered in MongoDB.
- **Runtime code generation** — The AI can write, compile (via `javax.tools.JavaCompiler`), and load Java types into the running JVM on demand. Generated types are persisted to MongoDB and survive restarts.
- **Scheduled jobs** — Background tasks can be created and managed by the AI agent. Each job runs with its own execution context and notifies you on completion.
- **Voice note transcription** — Voice messages sent via Telegram or Slack are downloaded and transcribed by the active `TranscriptionProvider`, then routed to the agent as plain text.
- **Notification providers** — SendGrid, SMTP, Twilio SMS, Telegram, and Slack. The AI can send direct messages when given explicit permission.
- **Encrypted credential store** — Secrets are encrypted at rest using AES-256-GCM (software) or a PKCS#11 hardware token. The AI can request credentials via interactive suspension forms rather than asking you to type them in chat.
- **HTTPS out of the box** — Self-signed cert is auto-generated on first startup. Let's Encrypt ACME integration is available for production deployments. Certificates rotate without a restart.

---

## Project Goals

| Goal | What it means in practice |
|---|---|
| **Automate with oversight** | Every consequential action the AI takes can be gated behind a human approval step |
| **Extensible by the AI itself** | The AI can define new data types, compile them, and immediately operate on them — no deploy cycle |
| **Persistent memory** | All sessions, compiled types, credentials, and configuration live in MongoDB — the agent picks up where it left off |
| **Pluggable everything** | AI providers, notification channels, encryption backends, and transcription engines are all swappable via configuration |
| **Production-ready security** | HTTPS by default, CSRF protection, BCrypt passwords, session management, per-user credential encryption |

---

## The Vork Relay — It Just Vork&trade;

One of Vork's core jobs is asking you for things. When the AI needs a password, a decision, or an approval, it needs to reach you — wherever you are, on your phone, in your browser. The obvious way to do that is to expose Vork's web UI on a public port. That's also the wrong way.

**You should not have to punch a hole in your firewall. Neither should I.**

Punching holes invites trouble: DDoS surface, TLS certificate management, misconfigured security groups, a public login page. The whole point of running Vork at home or inside a private cloud is that it *stays private*. Opening a port defeats that immediately.

### How the relay solves this

The [Vork Relay](https://github.com/ludup/vork-relay) inverts the connection direction. Instead of the world reaching in to Vork, Vork reaches *out* to a lightweight public relay service and deposits an encrypted question there. You tap a link in Telegram or your notification channel of choice, your browser fetches the question, you answer it, and the encrypted response is collected by the agent. **Vork never needs an inbound port.**

```
  ┌──────────────────────────┐   HTTPS out   ┌─────────────────┐   HTTPS   ┌──────────────┐
  │  Vork                    │──────────────►│  vork-relay     │◄──────────│  You         │
  │  (behind your firewall)  │               │  (public relay) │           │  (anywhere)  │
  └──────────────────────────┘               └─────────────────┘           └──────────────┘
         no inbound ports required
```

### Why it's secure — zero-knowledge design

Moving sensitive data through a public relay creates an obvious problem: the relay can read everything it stores. The relay solves this by being **cryptographically blind**.

- **AES-256-GCM end-to-end encryption** — every form schema and every response is encrypted by the agent *before* it is uploaded. The relay only ever sees ciphertext.
- **Key-in-URL-fragment** — the decryption key is appended to the authorization link as a URL hash fragment (e.g. `https://relay.vork.sh/auth/abc123#k=…`). Hash fragments are [never sent to the server](https://www.rfc-editor.org/rfc/rfc3986#section-3.5) — they exist only in the browser. The relay server, its logs, CDN edges, and load balancers never see the key.
- **Browser-side decryption** — the key is imported into the browser's native [Web Crypto API](https://www.w3.org/TR/WebCryptoAPI/) as a non-extractable `CryptoKey`. No JavaScript library touches it. The plaintext of your response never leaves your browser.
- **Fetch-once semantics** — the encrypted payload is deleted from relay memory on the first successful fetch. A second request for the same session returns `404`. An attacker who intercepts the link but arrives late gets nothing.

Even if an attacker gains full control of the relay server, its database, and its memory, they have only useless ciphertext. The key was never there.

### Relay options

There are three ways to use the relay, in order of least to most infrastructure:

| Option | How | Inbound port needed? |
|---|---|---|
| **Public relay** (default) | Leave `Base URL` blank in Settings → General. Uses `relay.vork.sh`. | No |
| **Built-in relay** | Set `Base URL` to your appliance's own public URL (e.g. `https://my.vork.app`). Vork serves the relay API itself. | Yes — HTTPS on your appliance |
| **Self-hosted relay** | Run your own instance of [vork-relay-server](https://github.com/ludup/vork-relay). | Yes — on the relay server |

The **built-in relay** is made possible because `vork-relay-lib` — the relay store, API, and auth form — is embedded directly in the appliance. The protocol is identical whether you use the public relay, the built-in relay, or a self-hosted relay. All three options provide the same AES-256-GCM zero-knowledge guarantees.

A hosted relay instance runs at `relay.vork.sh` and is the default — no registration, no configuration, no firewall changes. It Just Vork&trade;. The source for the standalone relay server is at [github.com/ludup/vork-relay](https://github.com/ludup/vork-relay).

My company **[Jadaptive](https://jadaptive.com)** hosts and pays for the relay service.

---

## Supported AI Providers

Vork supports multiple AI backends. You choose and configure the provider through the **AI Models** settings page after first login — no environment variables needed for basic setup.

| Provider | What you need |
|---|---|
| **Gemini** (Google) | [API key from AI Studio](https://aistudio.google.com/app/apikey) — free tier available |
| **ChatGPT** (OpenAI) | [API key from OpenAI Platform](https://platform.openai.com/api-keys) |
| **Ollama** (local) | [Ollama](https://ollama.com) running locally or on your network — no API key required |
| **Groq** | [API key from GroqCloud](https://console.groq.com/keys) — fast inference, free tier available |
| **Anthropic** | [API key from Anthropic Console](https://console.anthropic.com/) |

You can configure multiple providers simultaneously and switch between them per conversation.

---

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) and [Docker Compose](https://docs.docker.com/compose/)
- At least one AI provider API key, **or** [Ollama](https://ollama.com) running locally (free, no account needed)

---

## Quick Start — Docker Hub

The simplest way to run Vork. The pre-built image is published to Docker Hub as `ludup/vork`.

### 1. Create a `docker-compose.yml`

```yaml
services:

  mongodb:
    image: mongo:8
    restart: unless-stopped
    volumes:
      - mongodb_data:/data/db
    healthcheck:
      test: ["CMD", "mongosh", "--quiet", "--eval", "db.adminCommand('ping').ok"]
      interval: 10s
      timeout: 5s
      retries: 5

  vork:
    image: ludup/vork:latest
    restart: unless-stopped
    depends_on:
      mongodb:
        condition: service_healthy
    ports:
      - "8080:8080"   # HTTP (redirects to HTTPS)
      - "8443:8443"   # HTTPS
    environment:
      MONGO_HOST: mongodb
      MONGO_PORT: 27017
      MONGO_DATABASE: vork
      # AI provider keys — set whichever provider(s) you want to use.
      # You can also configure them through the Settings UI after first login.
      # SPRING_AI_GOOGLE_GENAI_APIKEY: your-gemini-key
      # SPRING_AI_OPENAI_API_KEY: your-openai-key
      # SPRING_AI_GROQ_API_KEY: your-groq-key
      # SPRING_AI_OLLAMA_BASE_URL: http://host.docker.internal:11434
    volumes:
      - vork_conf:/app/conf.d   # persists certs and settings across restarts

volumes:
  mongodb_data:
  vork_conf:
```

### 2. Start

```bash
docker compose up -d
```

### 3. Open Vork

Navigate to **https://localhost:8443** (accept the self-signed cert warning on first visit).

On first launch you will be walked through the setup wizard to create an admin account. After that, the AI chat interface is waiting.

---

## Building from Source

If you want to run the latest code or make changes, build locally instead.

### Repository layout

```
vork-server/
├── Dockerfile
├── docker-compose.yml
└── src/
```

### Clone and build

```bash
# Clone the repo
git clone https://github.com/ludup/vork-server.git

# Start from the vork-server directory
cd vork-server
docker compose up --build
```

The first build takes a few minutes (Maven downloads dependencies inside the container). Subsequent builds are fast thanks to layer caching.

---

## Environment Variables

### MongoDB

| Variable | Default | Description |
|---|---|---|
| `MONGO_HOST` | `localhost` | MongoDB hostname |
| `MONGO_PORT` | `27017` | MongoDB port |
| `MONGO_DATABASE` | `vork` | MongoDB database name |
| `MONGO_USERNAME` | *(unset)* | MongoDB username (optional) |
| `MONGO_PASSWORD` | *(unset)* | MongoDB password (optional) |

### AI Providers

API keys can be supplied as environment variables or configured through the Settings UI. Setting them here makes them available immediately on first boot.

| Variable | Provider |
|---|---|
| `SPRING_AI_GOOGLE_GENAI_APIKEY` | Gemini (Google AI Studio) |
| `SPRING_AI_OPENAI_API_KEY` | ChatGPT (OpenAI) |
| `SPRING_AI_GROQ_API_KEY` | Groq |
| `SPRING_AI_OLLAMA_BASE_URL` | Ollama base URL (e.g. `http://localhost:11434`) |

### SSL / HTTPS

| Variable | Default | Description |
|---|---|---|
| `VORK_SSL_CERT_DIR` | `conf.d/ssl` | Directory containing `certificate.pem` and `private-key.pem` |
| `SERVER_PORT` | `8443` | HTTPS port |

### SSL certificates

On first startup Vork generates a self-signed certificate in `conf.d/ssl/`. To use a real certificate:

1. Place your `certificate.pem` (full chain) and `private-key.pem` in the cert directory before starting the container, **or**
2. Use the **SSL Certificate** settings page (`/settings/ssl-certificate`) to request a Let's Encrypt certificate once the instance is running and publicly reachable on port 80.

---

## Ports

| Port | Protocol | Purpose |
|---|---|---|
| `8080` | HTTP | Redirects all traffic to HTTPS. Required for Let's Encrypt HTTP-01 challenge. |
| `8443` | HTTPS | Main application port. |

---

## Data Persistence

All application data lives in MongoDB. The `conf.d/` directory holds:

```
conf.d/
├── database.properties   ← optional; env vars take precedence
└── ssl/
    ├── certificate.pem
    └── private-key.pem
```

Mount `conf.d/` as a volume (as shown in the compose examples above) to preserve certificates and local config across container rebuilds.

---

## Architecture at a Glance

```
Browser / Telegram / Slack
         │
         ▼
   Spring Boot (HTTPS :8443)
         │
   ┌─────┴──────────────────────────────┐
   │  Chat / WebSocket (STOMP)          │
   │  AI Orchestration (multi-provider)  │
   │  Tool execution engine             │
   │    ├─ SSH terminals                │
   │    ├─ Notification providers       │
   │    ├─ Java type compiler           │
   │    ├─ Scheduled jobs               │
   │    └─ Database CRUD (any type)     │
   └─────┬──────────────────────────────┘
         │
         ▼
      MongoDB
```

The agent can only use tools that have been wired into its configuration. Sensitive tools require the authenticated user to approve each invocation before the action runs.

---

## Development

Run locally without Docker using a JDK 25 and a local MongoDB instance:

```bash
# From the vork-server directory
mvn spring-boot:run
```

Configuration is read from `conf.d/database.properties` (relative to the working directory). If the file is absent, defaults of `localhost:27017` / database `vork` / no auth are used.

---

## About the Architect

**[Lee Painter](https://jadaptive.com)** is a software engineer with over 25 years of experience building enterprise applications focused on secure remote networking. He is the founder of [Jadaptive Limited](https://jadaptive.com), a Nottingham-based company whose SSH and SFTP libraries are embedded in products used daily by some of the world's largest organizations — Nasdaq, Hitachi, Sony Music, Northrop Grumman, HP, and DHL among them.

Lee is the original author of the Java SSH API lineage:

* **J2SSH** — the first widely adopted open-source Java SSH-2 implementation.
* **Maverick Legacy** — a mature, stable commercial client/server SSH library, now powering major Managed File Transfer platforms globally.
* **[Maverick Synergy](https://jadaptive.com/java-ssh-library)** — the current open-source Java SSH-2 implementation; the exact SSH engine humming inside Vork itself.

Jadaptive's approach to business is straightforward: *software is the gift, expertise is the paywall.* The community gets fully featured, production-quality software. Those who need commercial support, custom integration, policy-driven features, or enterprise SLAs know exactly where to find the people who wrote the code.

When he is not writing code, Lee writes songs. Music for the soul, code for the bowl! You can find his sonic architecture at **[artist.ludup.com](https://artist.ludup.com)** or directly on **[Spotify](https://open.spotify.com/playlist/43d5ErR8GuY1tihBYxez1S)**.

---

## A Message from the Architect

I built this project out of pure architectural frustration. A weekend completely lost to configuring OpenClaw when I could have been coding something better. I've always believed that software should *Just Work*<sup>tm</sup> or rather, *Just Vork*<sup>tm</sup>. So I set about creating an AI automation engine I could actually use to dogfood my own workflows. 

So far, I've been orchestrating this project through LLMs. After 25 years in the trenches, I am no longer just a coder; I am the ruthless orchestrator of an AI sweatshop. Right now, Vork is raw, fast, and ready to be stress-tested. 

There are a million ways the community can help build out skills, forge new tools, and hunt down bugs. All contributions, pull requests, and critiques are gratefully and humbly accepted. 

If you find this project useful, the quickest way to support it is to tell others via social media, word of mouth, or telepathy. I'm sure Elon will have that last feature sorted soon. Oh, and while we're on the subject: Elon, you still owe me a fiver. 

If you know, you know. 

`- The Engineer in the Lakers Hat`

---

## The Intel Blog

If you get bored waiting for Copilot to complete your latest round of instructions, perhaps take a read of the project persona's regular updates from within the code itself at **[vork.sh](https://vork.sh)**.

---

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).
