from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from collect_m4_baseline import parse_start_output, resolve_activity


class CollectM4BaselineTest(unittest.TestCase):
    def test_successful_start_output_is_parsed(self):
        parsed = parse_start_output(
            "Status: ok\nLaunchState: COLD\nActivity: io.example/.MainActivity\n"
            "ThisTime: 321\nTotalTime: 350\nWaitTime: 360\nComplete\n"
        )
        self.assertEqual(350, parsed["TotalTime"])
        self.assertEqual("COLD", parsed["LaunchState"])

    def test_missing_success_status_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_start_output("Status: timeout\nTotalTime: 999\n")

    def test_emui_resolve_activity_metadata_is_ignored(self):
        raw = (
            "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\n"
            "io.github.sumirenokai.vesqen/.MainActivity\n"
        )
        self.assertEqual("io.github.sumirenokai.vesqen/.MainActivity", resolve_activity(raw))


if __name__ == "__main__":
    unittest.main()
