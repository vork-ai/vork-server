# Docker

## Quick start (local)

```bash
cd vork-server
cp .env.example .env          # then fill in GEMINI_API_KEY
docker compose up --build
```

The app is available at http://localhost:8080.  
MongoDB data is persisted in the `mongodb_data` Docker volume.

### `.env` file

Create a `.env` file in `vork-server/` (next to `docker-compose.yml`):

```
GEMINI_API_KEY=your-key-here
```

Get a key at https://aistudio.google.com/app/apikey.

---

## Building for Docker Hub (multi-platform)

### One-time setup

Create a buildx builder that supports multi-platform emulation:

```bash
docker buildx create --name multi --driver docker-container --bootstrap --use
```

### Build and push

Run from inside `vork-server/`:

```bash
cd vork-server

docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --file Dockerfile \
  --tag yourusername/vork:latest \
  --tag yourusername/vork:1.0.0 \
  --push \
  .
```

Log in first if needed:

```bash
docker login
```

### Load a local copy (Apple Silicon)

`--push` bypasses the local Docker daemon. To also have a runnable local image:

```bash
docker buildx build \
  --platform linux/arm64 \
  --file Dockerfile \
  --tag yourusername/vork:latest \
  --load \
  .
```

### Verify the published manifest

```bash
docker buildx imagetools inspect yourusername/vork:latest
```

---

## Notes

- **Full JDK required at runtime** — the app uses `javax.tools.JavaCompiler` to compile
  user-defined types on the fly, so a JRE-only image will not work.
- **First multi-platform build is slow** — `amd64` runs under QEMU on Apple Silicon;
  expect roughly twice the normal build time.
- **MongoDB connection** — `MONGO_HOST`, `MONGO_PORT`, and `MONGO_DATABASE` environment
  variables override `conf.d/database.properties`. The compose file sets these
  automatically to point at the bundled `mongodb` service.
- **Custom config** — mount your local `conf.d/` as a volume (see `docker-compose.yml`)
  to preserve settings across container rebuilds.
