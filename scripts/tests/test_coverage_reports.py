"""Pure-stdlib regression tests for the CI coverage guard."""
import sys
import tempfile
from pathlib import Path
from unittest import TestCase

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_coverage_reports import validate_report


class CoverageGateTests(TestCase):
    def test_missing_xml_fails_closed(self):
        with self.assertRaisesRegex(AssertionError, "missing JaCoCo report"):
            validate_report("sample", Path("/does/not/exist/jacoco.xml"), {"bundle": {"LINE": .5}})

    def test_missing_critical_class_fails_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            file = Path(folder) / "jacoco.xml"
            file.write_text('<report name="x"><counter type="LINE" missed="1" covered="9"/></report>')
            with self.assertRaisesRegex(AssertionError, "missing critical class"):
                validate_report("sample", file, {"classes": {"Critical": {"LINE": .5}}})

    def test_thresholds_and_empty_counters_are_enforced(self):
        with tempfile.TemporaryDirectory() as folder:
            file = Path(folder) / "jacoco.xml"
            file.write_text('<report name="x"><counter type="LINE" missed="6" covered="4"/></report>')
            with self.assertRaisesRegex(AssertionError, "40.0% < 60.0%"):
                validate_report("sample", file, {"bundle": {"LINE": .6}})
            with self.assertRaisesRegex(AssertionError, "missing/empty BRANCH"):
                validate_report("sample", file, {"bundle": {"BRANCH": .5}})
            self.assertEqual({"bundle/LINE": .4},
                             validate_report("sample", file, {"bundle": {"LINE": .4}}))
