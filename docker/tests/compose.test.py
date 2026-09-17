"""Deployment contract tests. Run: python3 docker/tests/compose.test.py"""
import pathlib
import unittest
import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]


class ComposeContract(unittest.TestCase):
    def setUp(self):
        self.local = yaml.safe_load((ROOT / "docker-compose.yml").read_text())
        self.cloud = yaml.safe_load((ROOT / "docker-compose.coolify.yml").read_text())

    def test_fork_is_built_not_pulled(self):
        for compose in (self.local, self.cloud):
            for name in ("frontend", "livecontext"):
                service = compose["services"][name]
                self.assertIn("build", service)
                self.assertNotIn("image", service)
            args = compose["services"]["frontend"]["build"]["args"]
            self.assertEqual(args["NEXT_PUBLIC_APP_EDITION"], "ce")
            self.assertEqual(args["NEXT_PUBLIC_AUTH_MODE"], "embedded")
            self.assertEqual(args["NEXT_PUBLIC_SPRING_BASE_URL"], "http://livecontext:8080")

    def test_coolify_has_no_host_ports_or_global_names(self):
        self.assertNotIn("name", self.cloud)
        self.assertEqual(len(self.cloud["services"]), 11)
        for service in self.cloud["services"].values():
            self.assertNotIn("ports", service)
            self.assertNotIn("container_name", service)

    def test_storage_and_keys_survive_redeploys(self):
        for volume in ("livecontext_data", "livecontext_minio", "livecontext_keys",
                       "livecontext_files", "livecontext_browser", "livecontext_redis"):
            self.assertIn(volume, self.cloud["volumes"])

    def test_chat_iteration_default_survives_redeploys(self):
        env = self.cloud["services"]["livecontext"]["environment"]
        self.assertEqual(env["CONVERSATION_AGENT_MAX_ITERATIONS"], "100")

    def test_init_must_succeed(self):
        for compose in (self.local, self.cloud):
            services = compose["services"]
            self.assertEqual(services["livecontext"]["depends_on"]["minio-init"]["condition"],
                             "service_completed_successfully")
            self.assertIn("-ec", str(services["minio-init"]["entrypoint"]))
            for name in ("minio", "minio-init"):
                self.assertTrue(services[name]["image"].startswith("quay.io/minio/"))
                self.assertNotIn(":latest", services[name]["image"])

    def test_coolify_secrets_are_required(self):
        env = self.cloud["services"]["livecontext"]["environment"]
        for name in ("DB_PASSWORD", "GATEWAY_FILTER_SECRET_KEY",
                     "WEBSEARCH_CDP_JWT_SECRET", "WEBSEARCH_GATEWAY_SECRET"):
            self.assertIn(":?", env[name])
        for name in ("WEBSEARCH_CDP_JWT_SECRET", "WEBSEARCH_GATEWAY_SECRET"):
            self.assertEqual(env[name], self.cloud["services"]["websearch"]["environment"][name])

    def test_gateway_protects_bootstrap_and_routes_realtime(self):
        gateway = self.cloud["services"]["gateway"]
        self.assertIn(":?", gateway["environment"]["BOOTSTRAP_PASSWORD"])
        config = (ROOT / "docker/gateway/default.conf.template").read_text()
        self.assertIn("auth_basic ${BOOTSTRAP_AUTH_REALM}", config)
        self.assertIn("location /ws", config)
        self.assertIn("location = /api/runtime-config", config)
        self.assertIn("location ^~ /api/proxy/", config)


if __name__ == "__main__":
    unittest.main()
