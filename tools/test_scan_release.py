"""Private deny-list tests use synthetic values, never an actual provider key."""
import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

class PrivateCredentialScanTest(unittest.TestCase):
    def check_payload(self, payload):
        fake = 'synthetic-provider-test-key-not-a-real-credential'
        root = Path(os.environ['LOCALAPPDATA']) / 'Temp/opencode'
        with tempfile.TemporaryDirectory(prefix='kv-scan-test-', dir=root) as directory:
            path = Path(directory) / 'payload.bin'
            path.write_bytes(payload(fake))
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('scan_release.py')),
                                     str(path), '--deny-list-env', 'KV_TEST_PRIVATE_DENY'],
                                    env=dict(os.environ, KV_TEST_PRIVATE_DENY=json.dumps([fake])),
                                    capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertIn('1 findings', result.stdout)
            self.assertNotIn(fake, result.stdout + result.stderr)

    def test_utf8_private_key_is_rejected_without_echo(self):
        self.check_payload(lambda key: key.encode())

    def test_utf16_private_key_is_rejected_without_echo(self):
        self.check_payload(lambda key: key.encode('utf-16le'))

    def test_base64_private_key_is_rejected_without_echo(self):
        self.check_payload(lambda key: base64.b64encode(key.encode()))

    def test_missing_deny_environment_fails_closed(self):
        result = subprocess.run([sys.executable, str(Path(__file__).with_name('scan_release.py')),
                                 __file__, '--deny-list-env', 'KV_TEST_MISSING_DENY'],
                                env={k: v for k, v in os.environ.items() if k != 'KV_TEST_MISSING_DENY'},
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('unavailable', result.stderr)

if __name__ == '__main__': unittest.main()
