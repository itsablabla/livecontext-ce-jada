"""Safety contracts for the standalone Lago deployments."""
import pathlib
import unittest
import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]

class LagoContract(unittest.TestCase):
    def setUp(self):
        self.compose = yaml.safe_load((ROOT / "docker/lago.coolify.yml").read_text())

    def test_services_are_pinned_and_isolated(self):
        self.assertEqual(len(self.compose["services"]), 8)
        for service in self.compose["services"].values():
            self.assertNotIn("ports", service)
            self.assertNotIn("container_name", service)
            self.assertNotIn(":latest", service["image"])
        for volume in self.compose["volumes"].values():
            self.assertIsNone(volume)

    def test_signup_and_telemetry_are_disabled(self):
        for name in ("api", "worker", "clock", "migrate"):
            env = self.compose["services"][name]["environment"]
            self.assertEqual(env["LAGO_DISABLE_SIGNUP"], "true")
            self.assertEqual(env["LAGO_DISABLE_SEGMENT"], "true")
            self.assertEqual(env["LAGO_SIDEKIQ_WEB"], "false")

    def test_migration_and_owner_provisioning_are_explicit(self):
        services = self.compose["services"]
        self.assertEqual(services["migrate"]["environment"]["LAGO_CREATE_ORG"], "true")
        self.assertEqual(services["migrate"]["restart"], "no")
        self.assertTrue(services["migrate"]["exclude_from_hc"])
        for name in ("api", "worker", "clock"):
            self.assertEqual(services[name]["depends_on"]["migrate"]["condition"],
                             "service_completed_successfully")

    def test_no_secret_defaults_are_committed(self):
        env = self.compose["services"]["api"]["environment"]
        for key in ("SECRET_KEY_BASE", "LAGO_RSA_PRIVATE_KEY", "LAGO_ORG_USER_PASSWORD",
                    "LAGO_ORG_API_KEY", "LAGO_ENCRYPTION_PRIMARY_KEY",
                    "LAGO_ENCRYPTION_DETERMINISTIC_KEY", "LAGO_ENCRYPTION_KEY_DERIVATION_SALT"):
            self.assertEqual(env[key], "${" + key + "}")

if __name__ == "__main__":
    unittest.main()
