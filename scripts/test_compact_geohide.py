import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from compact_geohide import compact, update

class GeoHideTest(unittest.TestCase):
    def test_shared_ip_must_work_for_every_original_name(self):
        active, retired, groups = compact("""1.1.1.1 service.test
2.2.2.2 service.test
2.2.2.2 www.service.test
3.3.3.3 auth.service.test
2.2.2.2 auth.service.test
3.3.3.3 a.cdn.test
3.3.3.3 b.cdn.test
::1 ip6-localhost""".splitlines())
        self.assertEqual(active, {"service.test": "2.2.2.2", "a.cdn.test": "3.3.3.3", "b.cdn.test": "3.3.3.3"})
        self.assertIn(("auth.service.test", "3.3.3.3"), retired)
        self.assertIn("www.service.test", groups["service.test"])

    def test_incompatible_subtree_stops_publication(self):
        with self.assertRaisesRegex(ValueError, "service.test"):
            compact(["1.1.1.1 service.test", "2.2.2.2 auth.service.test"])

    def test_updates_keep_previous_pairs_and_reject_truncated_input(self):
        def source(rows):
            return f"# Регион серверов: EU\n{rows}\n### geohide.ru: end hosts file\n".encode()

        with TemporaryDirectory() as directory:
            output = Path(directory) / "hosts"
            with patch("compact_geohide.OUTPUT", output):
                update(source("1.1.1.1 old.test\n1.1.1.1 sub.old.test"))
                update(source("2.2.2.2 new.test"))
                published = output.read_bytes()
                self.assertIn(b"# retire 1.1.1.1 old.test", published)
                self.assertIn(b"# retire 1.1.1.1 sub.old.test", published)
                with self.assertRaises(ValueError):
                    update(b"# partial file")
                self.assertEqual(output.read_bytes(), published)

if __name__ == "__main__":
    unittest.main()
