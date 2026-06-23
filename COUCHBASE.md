# Vork with Couchbase

Use this guide when you want Vork to run with Couchbase instead of the default embedded Nitrite backend.

## When to use Couchbase

- Distributed JSON document storage
- Horizontal scaling with external cluster management
- Existing Couchbase infrastructure and tooling

## Docker Compose Example

Create a compose file and run Vork with Couchbase:

```yaml
services:
  couchbase:
    image: couchbase:community-7.6.2
    restart: unless-stopped
    ports:
      - "8091:8091"
      - "11210:11210"
    volumes:
      - couchbase_data:/opt/couchbase/var

  vork:
    image: justvork/vork-server:latest
    restart: unless-stopped
    depends_on:
      - couchbase
    ports:
      - "8080:8080"
      - "8443:8443"
    environment:
      COUCHBASE_HOST: couchbase
      COUCHBASE_PORT: 8091
      COUCHBASE_BUCKET: vork
      COUCHBASE_USERNAME: Administrator
      COUCHBASE_PASSWORD: password
    volumes:
      - vork_conf:/app/conf.d

volumes:
  couchbase_data:
  vork_conf:
```

Start:

```bash
docker compose up -d
```

## Setup Wizard Configuration

During first-run setup:

1. Open https://localhost:8443
2. Complete admin user creation
3. In the setup wizard database step, select Couchbase
4. Enter host, port, bucket, username, and password
5. Save and continue

If you already completed setup, you can switch database settings from the settings UI and restart the container.

## Notes

- Keep conf.d mounted as a volume so your settings persist.
- Ensure the Couchbase bucket exists and credentials have read/write access.
- Couchbase query service must be enabled because list/search operations use N1QL.
