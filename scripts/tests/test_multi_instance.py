import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from multi_instance_smoke import base_urls


class MultiInstanceGuardTests(unittest.TestCase):
    def test_requires_approval_and_distinct_local_nodes(self):
        with patch.dict(os.environ, {'HA_TEST_APPROVED':'no'}):
            with self.assertRaises(ValueError): base_urls()
        with patch.dict(os.environ, {'HA_TEST_APPROVED':'yes','HA_API_URLS':'http://127.0.0.1:8080,http://127.0.0.1:8080'}):
            with self.assertRaises(ValueError): base_urls()
        with patch.dict(os.environ, {'HA_TEST_APPROVED':'yes','HA_API_URLS':'http://127.0.0.1:8080,https://example.org'}):
            with self.assertRaises(ValueError): base_urls()
