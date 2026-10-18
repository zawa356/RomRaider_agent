import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location("http_e2e",Path(__file__).with_name("test-rlc2-real-server-http.py"))
subject=importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)

class GuardTests(unittest.TestCase):
    def test_http_requires_its_own_opt_in_before_fixture_or_database(self):
        with patch.dict(os.environ,{"ROMLOGGER_RLC2_HTTP_ENABLE":"0"},clear=True), patch.object(subject,"fixture",side_effect=AssertionError("must not read fixture")):
            with self.assertRaises(ValueError):subject.main()

    def test_unsafe_destinations_fail_before_fixture_or_server_import(self):
        urls=["postgresql://u:p@remote/romlogger_test","postgresql://u:p@127.0.0.1/production","postgresql://u:p@127.0.0.1/romlogger_test?options=-csearch_path=public"]
        for url in urls:
            with self.subTest(url=url),patch.dict(os.environ,{"ROMLOGGER_RLC2_HTTP_ENABLE":"1","ROMLOGGER_RLC2_PREVIEW_ENABLE":"1","DATABASE_URL":url},clear=True),patch.object(subject,"fixture",side_effect=AssertionError("must not read fixture")):
                with self.assertRaises(ValueError):subject.main()

if __name__=="__main__":unittest.main()
