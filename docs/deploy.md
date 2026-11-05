# Deploying Revenant on a free server

This guide puts Revenant on a free Oracle Cloud VM, behind HTTPS, with API key authentication, and ends with the `kill -9` demo running on a public server. Budget about two hours the first time, most of it waiting for account approval and DNS.

> **Honest status.** The files in `deploy/` and the `Dockerfile` are checked in CI (the images must build and the Compose file must validate), but a full deployment has to be proven on a real VM. If a step here fails, the fix is usually a one-line change: paste the error and it can be corrected. Cloud consoles change their screens often; where this guide describes a screen, follow the intent.

## What you get

```
internet --443--> Caddy (HTTPS, automatic certificate)
                    |  only /api/* and /actuator/health
                    v
                  Revenant server --> PostgreSQL   (source of truth)
                    |             \-> Redis        (wake-up hints, optional)
                    +--- (optional) Kafka          (event stream)
                  Demo worker (started on demand)
```

Only ports 80 and 443 are open. PostgreSQL and Redis are not reachable from outside the VM. Kafka is off by default because it needs about 1 GB of RAM; events simply wait in the outbox until it is enabled (ADR-0005).

## 1. Create the Oracle Cloud account

1. Sign up for **Oracle Cloud Free Tier** at oracle.com/cloud/free. You need an email address, a phone number and a debit or credit card that allows online international payments. The card is for identity verification; the "Always Free" resources are not billed.
2. Choose your **home region** carefully, because it cannot be changed. Pick one near you (for example Mumbai or Hyderabad). Always Free VMs can only be created in the home region.
3. Wait for the "account ready" email. This can take from minutes to a day.

## 2. Create the VM

In the console go to **Compute > Instances > Create instance**:

- **Image:** Canonical Ubuntu 24.04 (or 22.04).
- **Shape:** *Ampere* **VM.Standard.A1.Flex** with 2 OCPUs and 12 GB of memory is plenty (the free allowance is up to 4 OCPUs and 24 GB in total). If Ampere shows "Out of host capacity", try again later or in another availability domain. The fallback is **VM.Standard.E2.1.Micro** (1 GB), which is enough only without Kafka and is tight.
- **Networking:** keep the default VCN and a public subnet, and **assign a public IPv4 address**.
- **SSH keys:** generate a key pair in the console and download the private key. Keep it safe; do not commit it.

Note the VM's **public IP**.

## 3. Open ports 80 and 443

Two separate firewalls both have to allow the traffic:

1. **Oracle's network.** In the console open the VM's subnet > its **Security List** > add two **ingress rules**: source `0.0.0.0/0`, protocol TCP, destination ports `80` and `443`.
2. **The VM's own firewall.** Oracle's Ubuntu images block everything but SSH with iptables. After connecting (step 5):

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80  -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo apt-get install -y iptables-persistent   # answer yes to saving the current rules
sudo netfilter-persistent save
```

## 4. Get a free domain name

HTTPS certificates need a name, not a bare IP.

1. Go to duckdns.org, sign in, and create a subdomain such as `myrevenant`. You get `myrevenant.duckdns.org`.
2. Set its **current ip** to the VM's public IP.

## 5. Install Docker on the VM

From PowerShell on your laptop (Windows has `ssh` built in):

```powershell
ssh -i C:\path\to\your-private-key ubuntu@<VM public IP>
```

Then on the VM:

```bash
sudo apt-get update && sudo apt-get install -y ca-certificates curl git
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu
exit            # log in again so the group change applies
```

Check with `docker compose version`.

## 6. Get the code and configure it

```bash
git clone https://github.com/Amitreddy14/revenant.git
cd revenant/deploy
cp .env.example .env
nano .env       # fill in the four values
```

Generate the two secrets on the VM and paste them into `.env`:

```bash
openssl rand -hex 24    # POSTGRES_PASSWORD
openssl rand -hex 24    # REVENANT_API_KEY  (keep a copy: clients need it)
```

Set `REVENANT_DOMAIN` to your DuckDNS name. Leave `REVENANT_KAFKA_ENABLED=false`.

## 7. Start it

```bash
docker compose up -d --build
docker compose ps
```

The first build downloads Gradle and compiles everything, so it takes several minutes (longer on a small VM). When `server` shows `healthy`, Caddy requests a certificate by itself. Check from your laptop:

```powershell
curl.exe https://myrevenant.duckdns.org/actuator/health
curl.exe -i https://myrevenant.duckdns.org/api/v1/workflows/00000000-0000-0000-0000-000000000000
```

The first returns `{"status":"UP"}`. The second must return **401**, which proves authentication is on. With the key it returns 404 (no such workflow):

```powershell
curl.exe -i -H "Authorization: Bearer <your key>" https://myrevenant.duckdns.org/api/v1/workflows/00000000-0000-0000-0000-000000000000
```

## 8. Run the kill -9 demo on the server

```bash
cd ~/revenant
bash ./scripts/demo-kill-worker.sh
```

It starts a worker container, starts an order, kills the container with `SIGKILL` while it is shipping, starts a second worker, and prints `RESULT: PASS` when the charge, stock reservation and receipt each ran exactly once and the interrupted shipping step ran twice.

To use your deployment from your own code or another machine, point a client at it: `new RevenantClient("https://myrevenant.duckdns.org", apiKey)`.

## Operations

| Task | Command (run in `deploy/`) |
|---|---|
| Logs | `docker compose logs -f server` |
| Update to the latest code | `git pull && docker compose up -d --build` |
| Back up the database | `docker compose exec -T postgres pg_dump -U revenant revenant \| gzip > backup-$(date +%F).sql.gz` |
| Restore | `gunzip -c backup.sql.gz \| docker compose exec -T postgres psql -U revenant revenant` |
| Rotate the API key | put the new key in `.env`, `docker compose up -d server`, then update clients |
| Stop (data kept) | `docker compose down` |
| Stop and delete all data | `docker compose down -v` |
| Enable Kafka | set `REVENANT_KAFKA_ENABLED=true`, then `docker compose --profile kafka up -d` |

## Limits and things to know

- **One machine.** There is one server, one PostgreSQL and no failover. This is a demonstration deployment, not a high-availability one. The engine itself tolerates server restarts (everything is in PostgreSQL), but a VM outage is an outage.
- **Idle reclamation.** Oracle may reclaim Always Free instances that sit nearly idle for a long period. Check Oracle's current terms; upgrading the account to "Pay As You Go" while staying inside the free limits removes that risk.
- **Backups are manual.** Schedule the `pg_dump` line with `cron` if the data matters.
- **One shared key.** Every client uses the same API key and has full access (ADR-0014).
- **The demo worker is not a production worker.** Real workers are your own programs built on the SDK, running anywhere that can reach the HTTPS address.
