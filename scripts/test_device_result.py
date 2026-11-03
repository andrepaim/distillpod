import unittest
from device_result import verify_result


class DeviceResultTest(unittest.TestCase):
    def test_requires_successful_completion_not_adb_exit_status(self):
        self.assertEqual(7, verify_result('OK (7 tests)\r\nINSTRUMENTATION_CODE: -1\r\n'))
        for result in ['FAILURES!!!\nTests run: 7, Failures: 1\nINSTRUMENTATION_CODE: -1',
                       'INSTRUMENTATION_STATUS_CODE: 1\n',
                       'INSTRUMENTATION_RESULT: shortMsg=Process crashed.', 'OK (0 tests)',
                       'INSTRUMENTATION_STATUS_CODE: -3\nOK (7 tests)',
                       'INSTRUMENTATION_STATUS_CODE: -4\nOK (7 tests)']:
            with self.assertRaises(ValueError):
                verify_result(result)

    def test_rejects_stale_success_followed_by_failure(self):
        with self.assertRaises(ValueError):
            verify_result('OK (7 tests)\nFAILURES!!!\n')


if __name__ == '__main__':
    unittest.main()
