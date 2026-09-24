import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_security_route_contract import routes, explicit_rules, is_covered, verify


class SecurityRouteContractTests(unittest.TestCase):
    def test_every_real_controller_is_explicitly_guarded(self):
        self.assertGreaterEqual(verify(), 30)

    def test_new_operation_is_not_inferred_from_fallback(self):
        rules = explicit_rules('''
            a.requestMatchers(HttpMethod.GET, "/api/v1/emails/**").hasAuthority("PERM_EMAIL_READ");
            a.requestMatchers("/api/**").denyAll();
        ''')
        self.assertTrue(is_covered('GET', '/api/v1/emails/{id}', rules))
        self.assertFalse(is_covered('POST', '/api/v1/emails/{id}', rules))
        self.assertFalse(is_covered('GET', '/api/v1/future', rules))

    def test_rejects_permissive_api_catchall(self):
        with self.assertRaisesRegex(AssertionError, 'deny'):
            explicit_rules('a.requestMatchers("/api/**").hasRole("INTERNAL");')

    def test_multiline_controller_mappings(self):
        controller = '''@RequestMapping("/api/v1/example") class Example {
            @PostMapping(path="/upload", consumes="multipart/form-data")
            void upload() {}
            @GetMapping void list() {}
        }'''
        self.assertEqual(routes(controller), [('POST', '/api/v1/example/upload'),
                                               ('GET', '/api/v1/example')])


if __name__ == '__main__':
    unittest.main()
