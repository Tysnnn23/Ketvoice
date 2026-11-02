"""Live check of the Discord token endpoint with a deliberately invalid code.

Proves the endpoint is reachable, the client id is accepted and the request shape
is valid: Discord answers with an OAuth error instead of a network/HTML failure.
It does not and cannot log a real account in.
"""
import datetime
import json
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/kotlin/com/ketvoice/app/discord/DiscordOAuth.kt').read_text(encoding='utf-8')
import re
app_id = re.search(r'APPLICATION_ID = (\d+)L', source).group(1)
redirect = re.search(r'REDIRECT_URI = "([^"]+)"', source).group(1)

body = urllib.parse.urlencode({
    'grant_type': 'authorization_code',
    'client_id': app_id,
    'code': 'ketvoice-invalid-code-check',
    'redirect_uri': redirect,
    'code_verifier': 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk',
}).encode()
request = urllib.request.Request('https://discord.com/api/oauth2/token', data=body, method='POST', headers={
    'Content-Type': 'application/x-www-form-urlencoded',
    'Accept': 'application/json',
    'User-Agent': 'Ketvoice/3.17',
})
try:
    with urllib.request.urlopen(request, timeout=30) as response:
        status, payload = response.status, response.read().decode()
except urllib.error.HTTPError as error:
    status, payload = error.code, error.read().decode()
except Exception as error:
    raise SystemExit('Discord token endpoint unreachable: ' + type(error).__name__)

try:
    data = json.loads(payload)
except Exception:
    raise SystemExit('Discord returned a non-JSON body (HTML error page?)')
error_code = str(data.get('error', ''))
assert status == 400, (status, data)
assert error_code in ('invalid_grant', 'invalid_request'), data
assert 'access_token' not in data

report = {
    'captured_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'scope': 'live OAuth endpoint shape only; no account consent and no token issued',
    'endpoint': 'https://discord.com/api/oauth2/token',
    'client_id': app_id,
    'redirect_uri': redirect,
    'status': status,
    'error': error_code,
    'description': str(data.get('error_description', ''))[:160],
}
(root / 'build/discord-oauth-live.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
