import os
import random
import datetime
from locust import HttpUser, task, between

import jwt as pyjwt

JWT_SECRET = os.environ["JWT_SECRET"]
QR_SECRET  = os.environ["QR_SECRET"]


# ---------------------------------------------------------------------------
# Scenario 1 — Authentication load
# ---------------------------------------------------------------------------

class AuthLoadTest(HttpUser):
    """
    Simulates concurrent logins against circleguard-auth-service.
    Target: measure JWT issuance throughput and p95 latency.
    Run with: locust -f locustfile.py AuthLoadTest --host=http://localhost:8180
    """
    wait_time = between(0.5, 2)

    _users = [
        ("staff_guard",  "password"),
        ("health_user",  "password"),
        ("super_admin",  "password"),
    ]

    @task(3)
    def login_valid_user(self):
        user, pwd = random.choice(self._users)
        with self.client.post(
            "/api/v1/auth/login",
            json={"username": user, "password": pwd},
            name="/api/v1/auth/login [valid]",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"Unexpected status {resp.status_code}")

    @task(1)
    def login_invalid_credentials(self):
        with self.client.post(
            "/api/v1/auth/login",
            json={"username": "nobody", "password": "wrong"},
            name="/api/v1/auth/login [invalid]",
            catch_response=True,
        ) as resp:
            if resp.status_code in (401, 403):
                resp.success()
            else:
                resp.failure(f"Expected 401/403, got {resp.status_code}")


# ---------------------------------------------------------------------------
# Scenario 2 — Gate QR validation load
# ---------------------------------------------------------------------------

class GatewayLoadTest(HttpUser):
    """
    Simulates QR scans at campus gates against circleguard-gateway-service.
    Target: measure validation throughput and Redis read latency under load.
    Run with: locust -f locustfile.py GatewayLoadTest --host=http://localhost:8087
    """
    wait_time = between(0.2, 1)

    def on_start(self):
        subject = f"user-{random.randint(1000, 9999)}"
        payload = {
            "sub": subject,
            "exp": datetime.datetime.utcnow() + datetime.timedelta(hours=2),
        }
        self._qr_token = pyjwt.encode(payload, QR_SECRET, algorithm="HS256")

    @task(5)
    def scan_valid_qr(self):
        with self.client.post(
            "/api/v1/gate/validate",
            json={"token": self._qr_token},
            name="/api/v1/gate/validate [valid]",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200 and resp.json().get("valid") is True:
                resp.success()
            else:
                resp.failure(f"Expected valid=true, got {resp.text}")

    @task(1)
    def scan_invalid_qr(self):
        with self.client.post(
            "/api/v1/gate/validate",
            json={"token": "invalid.jwt.string"},
            name="/api/v1/gate/validate [invalid]",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200 and resp.json().get("valid") is False:
                resp.success()
            else:
                resp.failure(f"Expected valid=false, got {resp.text}")


# ---------------------------------------------------------------------------
# Scenario 3 — Promotion health stats read load
# ---------------------------------------------------------------------------

class PromotionStatsLoadTest(HttpUser):
    """
    Simulates concurrent reads of health statistics from circleguard-promotion-service.
    Target: measure Neo4j + PostgreSQL query performance under read load.
    Run with: locust -f locustfile.py PromotionStatsLoadTest --host=http://localhost:8088
    """
    wait_time = between(1, 3)

    _departments = ["Engineering", "Medicine", "Law", "Sciences", "Arts"]

    @task(3)
    def get_global_stats(self):
        with self.client.get(
            "/api/v1/health-status/stats",
            name="/api/v1/health-status/stats",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"Unexpected status {resp.status_code}")

    @task(2)
    def get_department_stats(self):
        dept = random.choice(self._departments)
        with self.client.get(
            f"/api/v1/health-status/stats/department/{dept}",
            name="/api/v1/health-status/stats/department/[dept]",
            catch_response=True,
        ) as resp:
            if resp.status_code in (200, 404):
                resp.success()
            else:
                resp.failure(f"Unexpected status {resp.status_code}")


# ---------------------------------------------------------------------------
# Scenario 4 — Dashboard analytics read load
# ---------------------------------------------------------------------------

class DashboardLoadTest(HttpUser):
    """
    Simulates concurrent reads of analytics from circleguard-dashboard-service.
    Target: measure combined PromotionClient HTTP + k-anonymity computation under load.
    Run with: locust -f locustfile.py DashboardLoadTest --host=http://localhost:8084
    """
    wait_time = between(2, 5)

    @task(3)
    def get_health_board(self):
        with self.client.get(
            "/api/v1/analytics/health-board",
            name="/api/v1/analytics/health-board",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"Unexpected status {resp.status_code}")

    @task(2)
    def get_summary(self):
        with self.client.get(
            "/api/v1/analytics/summary",
            name="/api/v1/analytics/summary",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"Unexpected status {resp.status_code}")

    @task(1)
    def get_time_series(self):
        with self.client.get(
            "/api/v1/analytics/time-series",
            name="/api/v1/analytics/time-series",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"Unexpected status {resp.status_code}")
