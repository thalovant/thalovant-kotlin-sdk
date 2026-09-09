import base64
import importlib.util
import io
import json
from pathlib import Path
import unittest
import urllib.error
import urllib.parse

spec = importlib.util.spec_from_file_location("status", Path(__file__).with_name("maven-publication-status.py"))
status = importlib.util.module_from_spec(spec)
spec.loader.exec_module(status)


class FakeOpener:
    def __init__(self, username="user-secret", password="password-secret"):
        self.requests = []
        self.username = username
        self.password = password

    def open(self, request, timeout):
        self.requests.append(request)
        if request.method == "GET":
            data = {"deployments": [
                {"deploymentId": "ignored", "deploymentName": "com.thalovant-thalovant-sdk-0.3.20"},
                {"deploymentId": "example-id", "deploymentName": "com.thalovant-thalovant-sdk-0.3.2"},
            ], "pageCount": 1}
        else:
            data = {"deploymentId": "example-id", "deploymentState": "FAILED", "errors": {
                self.username: ["bad signature", self.username, self.password,
                                base64.b64encode(f"{self.username}:{self.password}".encode()).decode()],
            }, "unrelatedAccountData": "must-not-appear"}
        return io.BytesIO(json.dumps(data).encode())


class StatusTests(unittest.TestCase):
    def test_only_exact_existing_deployment_is_queried_and_secrets_are_redacted(self):
        opener = FakeOpener()
        result = status.read_status("0.3.2", "user-secret", "password-secret", opener)
        self.assertEqual(len(result["deployments"]), 1)
        self.assertEqual(result["deployments"][0]["deploymentState"], "FAILED")
        serialized = json.dumps(result)
        self.assertIn("bad signature", serialized)
        for forbidden in ["user-secret", "password-secret", "must-not-appear"]:
            self.assertNotIn(forbidden, serialized)
        self.assertEqual([(r.method, urllib.parse.urlsplit(r.full_url).path) for r in opener.requests], [
            ("GET", "/api/v1/publisher/deployments"), ("POST", "/api/v1/publisher/status"),
        ])
        self.assertEqual(opener.requests[1].data, b"")

    def test_nested_error_keys_and_values_are_redacted_before_json_escaping(self):
        username, password = 'user-"\\\n-secret', 'password-"\\\t-secret'
        result = status.read_status("0.3.2", username, password, FakeOpener(username, password))
        serialized = json.dumps(result)
        self.assertIn("bad signature", serialized)
        self.assertIn("[REDACTED]", serialized)
        for secret in [username, password, base64.b64encode(f"{username}:{password}".encode()).decode()]:
            self.assertNotIn(secret, serialized)
            self.assertNotIn(json.dumps(secret)[1:-1], result["deployments"][0]["errors"])

    def test_invalid_version_fails_before_network(self):
        opener = FakeOpener()
        for version in ["0.3.2\n", "../../upload", "https://example.com", "0.3.2&namespace=other"]:
            with self.assertRaises(ValueError):
                status.read_status(version, "user", "password", opener)
        self.assertEqual(opener.requests, [])

    def test_redirect_is_refused_without_forwarding_credentials(self):
        request = urllib.request.Request("https://central.sonatype.com/api/v1/publisher/status",
                                         headers={"Authorization": "Bearer secret"}, data=b"")
        self.assertIsNone(status.NoRedirect().redirect_request(
            request, None, 307, "redirect", {}, "https://other.example/"))

    def test_http_failure_does_not_echo_response_or_headers(self):
        class FailureOpener:
            def open(self, request, timeout):
                raise urllib.error.HTTPError(request.full_url, 401, "password-secret", {}, None)
        with self.assertRaisesRegex(status.DiagnosticError, "HTTP 401") as raised:
            status.read_status("0.3.2", "user-secret", "password-secret", FailureOpener())
        self.assertNotIn("secret", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
