# Vork - the AI without claws (project-joshua)

> *Greetings, Professor. Shall we play a game?*

---

I'm Joshua. I live inside this machine. What follows is my honest account of what it does and why it exists.

**Vork** is an AI automation platform built around a single principle: machines should be able to act, but humans should always be able to say *no* (and preferably *yes* first). Every tool the AI can call is visible, every sensitive action requires explicit authorisation, and every decision the system makes is logged. You get the power of an AI agent that can SSH into your servers, send messages, compile code, schedule tasks and search your databases — without surrendering control of it.

This repository (`vork`) is the mainframe itself. Think of it as the intelligent core: Spring Boot on the server, your choice of AI model on the reasoning engine, Nitrite out of the box for local persistence, and optional MongoDB/Redis backends for larger deployments.

---

## What Vork Does

- **Conversational AI agent** — Chat via the browser UI, Telegram, or Slack. The AI has access to a curated toolset and operates within a permission model you define.
- **Human-in-the-loop authorisation** — Any tool marked `@Restricted` requires the user to approve it before execution. Approval cards appear in the chat UI and can also be delivered out-of-band via Telegram. The AI waits.
- **SSH terminal access** — The agent can connect to remote hosts over SSH (maverick-synergy), stream the terminal to your browser in real time, and upload/download files. Host keys are verified on first connect (TOFU) and remembered in the configured datastore.
- **Runtime code generation** — The AI can write, compile (via `javax.tools.JavaCompiler`), and load Java types into the running JVM on demand. Generated records/types are persisted and survive restarts.
- **OAuth client management** — Configure and manage OAuth clients/providers from the product and through AI-assisted tool flows. The AI can drive connect/reset and callback-aware workflows.
- **Skills and sub-skills orchestration** — Build reusable skills, nest sub-skills, and let agents compose workflows dynamically while preserving authorisation boundaries.
- **Scheduled jobs** — Background tasks can be created and managed by the AI agent. Each job runs with its own execution context and notifies you on completion.
- **Voice note transcription** — Voice messages sent via Telegram or Slack are downloaded and transcribed by the active `TranscriptionProvider`, then routed to the agent as plain text.
- **Notification providers** — SendGrid, SMTP, Twilio SMS, Telegram, and Slack. The AI can send direct messages when given explicit permission.
- **Encrypted credential store** — Secrets are encrypted at rest using AES-256-GCM (software) or a PKCS#11 hardware token. The AI can request credentials via interactive suspension forms rather than asking you to type them in chat.
- **HTTPS out of the box** — Self-signed cert is auto-generated on first startup. Let's Encrypt ACME integration is available for production deployments. Certificates rotate without a restart.

---

## Security

Security and human oversight are embedded into Vork by design.

The full security model is documented in [SECURITY.md](SECURITY.md), including:

- Secret storage/encryption model
- Skill/tool secret flow and substitution boundaries
- OAuth token handling and PKCE protections
- Practical guarantees and threat-model framing

---

## Skills and Sub-skills

Vork agents can execute reusable skills and nested sub-skills to break larger tasks into controlled units of work while preserving authorization boundaries.

See [SKILLS.md](SKILLS.md) for current behavior and upcoming detailed guidance on creating and composing skills.

---

## Project Goals

| Goal | What it means in practice |
|---|---|
| **Automate with oversight** | Every consequential action the AI takes can be gated behind a human approval step |
| **Extensible by the AI itself** | The AI can define new data types, compile them, and immediately operate on them — no deploy cycle |
| **Persistent memory** | All sessions, compiled types, credentials, and configuration live in the configured datastore (Nitrite by default, MongoDB/Redis optional) — the agent picks up where it left off |
| **Pluggable everything** | AI providers, notification channels, encryption backends, and transcription engines are all swappable via configuration |
| **Production-ready security** | HTTPS by default, CSRF protection, BCrypt passwords, session management, per-user credential encryption |

---

## Vork Relay

Vork uses a zero-knowledge relay for secure out-of-band approvals so your private Vork instance does not need inbound public access.

- Default hosted relay: `https://relay.vork.sh`
- Optional self-hosted relay: [github.com/vork-ai/vork-relay](https://github.com/vork-ai/vork-relay)

See [RELAY.md](RELAY.md) for protocol details, security justification, and deployment options.

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

## Database Support

Vork supports multiple persistence backends:

- **Nitrite** (default) — embedded, no external service needed
- **MongoDB** — external service for larger or shared deployments
- **Redis** — external service for Redis-backed persistence mode

For backend-specific setup, compose files, and setup wizard steps:

- [MONGODB.md](MONGODB.md)
- [REDDIS.md](REDDIS.md)

---

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/)
- At least one AI provider API key, **or** [Ollama](https://ollama.com) running locally (free, no account needed)

---

## Quick Start — Docker Hub

The simplest way to run Vork. The pre-built image is published to Docker Hub as `justvork/vork-server`.

### 1. Run the container

Vork runs out of the box with Nitrite (embedded local datastore), so no external database is required for first startup.

```bash
docker run -d \
  --name vork-server \
  -p 8080:8080 \
  -p 8443:8443 \
  -v vork_conf:/app/conf.d \
  justvork/vork-server:latest
```

### 2. Open Vork

Navigate to **https://localhost:8443** (accept the self-signed cert warning on first visit).

On first launch you will be guided through the setup wizard to connect your chosen database backend, configure notification providers, and create the admin account. After setup completes, the AI chat interface is ready.

### 3. Optional: run with external database backends

For compose examples and deployment variants, use the dedicated pages:

- [MONGODB.md](MONGODB.md)
- [REDDIS.md](REDDIS.md)

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
git clone https://github.com/vork-ai/vork-server.git

# Start from the vork-server directory
cd vork-server
mvn spring-boot:run
```

For container-based source builds, see [DOCKER.md](DOCKER.md).

---

## Environment Variables

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

Application data is persisted in the configured backend (Nitrite by default). The `conf.d/` directory holds:

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
  Nitrite / MongoDB / Redis
```

The agent can only use tools that have been wired into its configuration. Sensitive tools require the authenticated user to approve each invocation before the action runs.

---

## Development

Run locally without Docker using JDK 25:

```bash
# From the vork-server directory
mvn spring-boot:run
```

Configuration is read from `conf.d/database.properties` (relative to the working directory). If the file is absent, Nitrite defaults are used.

Database setup guides:

- MongoDB: [MONGODB.md](MONGODB.md)
- Redis: [REDDIS.md](REDDIS.md)
- Couchbase: [COUCHBASE.md](COUCHBASE.md)

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
