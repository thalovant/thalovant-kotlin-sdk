#!/usr/bin/env python3
"""Read the existing Sonatype deployment; never upload, publish, or delete.

Current official OpenAPI: https://central.sonatype.com/api-doc
GET /api/v1/publisher/deployments (listDeployments) provides deploymentName
filtering and pageCount; POST /api/v1/publisher/status reads the matching ID.
Credentials stay in the runner environment and are never written to an artifact.
"""
import base64
import datetime
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class DiagnosticError(Exception):
    """A fixed diagnostic message that contains no upstream response material."""


def read_status(version, username, password, opener=None):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("Version must have three numeric components")
    if not username or not password:
        raise ValueError("Maven Central credentials are required")
    combined = f"{username}:{password}"
    token = base64.b64encode(combined.encode()).decode()
    secrets = sorted({username, password, combined, token}, key=len, reverse=True)
    opener = opener or urllib.request.build_opener(NoRedirect())

    def redact(value):
        text = str(value)
        for secret in secrets:
            text = text.replace(secret, "[REDACTED]")
        return text[:2048]

    def redact_errors(value):
        # Redact before JSON escaping; credentials can contain quotes/backslashes.
        if isinstance(value, str):
            return redact(value)
        if isinstance(value, list):
            return [redact_errors(item) for item in value]
        if isinstance(value, dict):
            return {redact(key): redact_errors(item) for key, item in value.items()}
        return value

    def request(path, query, method):
        # The allowlist also guards future changes from adding publication here.
        if (method, path) not in {
            ("GET", "/api/v1/publisher/deployments"),
            ("POST", "/api/v1/publisher/status"),
        }:
            raise ValueError("Only deployment listing and status are permitted")
        req = urllib.request.Request(
            "https://central.sonatype.com" + path + "?" + urllib.parse.urlencode(query),
            headers={"Authorization": "Bearer " + token, "Accept": "application/json"},
            data=b"" if method == "POST" else None, method=method,
        )
        try:
            with opener.open(req, timeout=30) as response:
                body = response.read(1_048_577)
        except urllib.error.HTTPError as error:
            raise DiagnosticError(f"Sonatype status request returned HTTP {error.code}") from None
        except (OSError, urllib.error.URLError):
            raise DiagnosticError("Sonatype status request could not complete") from None
        if len(body) > 1_048_576:
            raise DiagnosticError("Sonatype status response exceeded one MiB")
        try:
            return json.loads(body)
        except ValueError:
            raise DiagnosticError("Sonatype status response was not JSON") from None

    name = f"com.thalovant-thalovant-sdk-{version}"
    purl = f"pkg:maven/com.thalovant/thalovant-sdk@{version}"
    deployments = []
    for page in range(5):
        result = request("/api/v1/publisher/deployments", {
            "namespace": "com.thalovant", "deploymentName": name,
            "page": page, "size": 20, "sortField": "createTimestamp", "sortDirection": "desc",
        }, "GET")
        for item in result.get("deployments") or []:
            if item.get("deploymentName") != name:
                continue
            deployment_id = item.get("deploymentId", "")
            if not isinstance(deployment_id, str) or not re.fullmatch(r"[a-zA-Z0-9-]{1,100}", deployment_id):
                raise DiagnosticError("Sonatype returned an invalid deployment identifier")
            status = request("/api/v1/publisher/status", {"id": deployment_id}, "POST")
            # Keep only diagnostic fields; never dump response headers or account data.
            row = {key: redact(status.get(key) or item.get(key) or "") for key in (
                "deploymentId", "deploymentName", "deploymentState", "createTimestamp", "updateTimestamp",
            )}
            purls = status.get("purls") or []
            row["purls"] = [redact(value) for value in purls[:20]]
            row["errors"] = json.dumps(redact_errors(status.get("errors") or {}), ensure_ascii=True)[:2048]
            row["target_present"] = purl in purls
            deployments.append(row)
        if page + 1 >= (result.get("pageCount") or 1):
            break
    else:
        raise DiagnosticError("Deployment listing exceeded the five-page diagnostic limit")
    return {
        "checked_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "version": version, "mode": "read-only", "deployments": deployments,
        "lookup_result": "matched" if deployments else "no-match",
    }


def main():
    try:
        result = read_status(os.environ.get("RELEASE_VERSION", ""),
                             os.environ.get("MAVEN_CENTRAL_USERNAME", ""),
                             os.environ.get("MAVEN_CENTRAL_PASSWORD", ""))
    except DiagnosticError as error:
        print(str(error), file=sys.stderr)
        return 1
    except Exception:
        # Exceptions from an upstream/library must not expose credential material.
        print("Maven deployment diagnostics failed; no raw response or credentials were logged.", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
