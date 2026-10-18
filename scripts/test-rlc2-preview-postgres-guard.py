"""Reject unsafe destinations before reading a fixture or opening any database."""
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("preview_pg", Path(__file__).with_name("test-rlc2-preview-postgres.py"))
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)


class DestinationGuard(unittest.TestCase):
    def test_unsafe_urls_never_read_fixture_or_connect(self):
        for url in (
            "postgresql://remote.invalid/romlogger_test",
            "postgresql://127.0.0.1/production",
            "postgresql://127.0.0.1/romlogger_test?host=remote.invalid",
            "postgresql://127.0.0.1/romlogger_test#override",
            "postgresql:///romlogger_test",
            "postgresql://127.0.0.1/romlogger_%74est",
            "postgresql://127.0.0.1/romlogger_test/extra",
        ):
            with self.subTest(url=url), patch.dict(os.environ, {"ROMLOGGER_RLC2_PREVIEW_ENABLE": "1", "DATABASE_URL": url}), patch("sys.argv", ["guard"]), patch.object(preview, "read_fixture") as read, patch.object(preview, "verify_postgres") as connect:
                with self.assertRaises(ValueError):
                    preview.main()
                read.assert_not_called()
                connect.assert_not_called()

    def test_exact_opt_in_required_before_any_database_access(self):
        for value in ("", "0", "true"):
            with self.subTest(value=value), patch.dict(os.environ, {"ROMLOGGER_RLC2_PREVIEW_ENABLE": value, "DATABASE_URL": "postgresql://127.0.0.1/romlogger_test"}), patch("sys.argv", ["guard"]), patch.object(preview, "read_fixture") as read:
                with self.assertRaises(ValueError):
                    preview.main()
                read.assert_not_called()

    def test_loopback_test_destinations(self):
        for url in ("postgresql://127.0.0.1:55432/romlogger_test", "postgresql://localhost/romlogger_preview_e2e"):
            with self.subTest(url=url), patch.dict(os.environ, {"ROMLOGGER_RLC2_PREVIEW_ENABLE": "1", "DATABASE_URL": url}):
                self.assertEqual(preview.destination(), url)


if __name__ == "__main__":
    unittest.main()
