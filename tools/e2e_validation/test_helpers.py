import unittest
from tools.e2e_validation.collect import redact, safe_value
from tools.e2e_validation.analyze_trace import analyze

class EvidenceTests(unittest.TestCase):
    def test_explicit_cancel_pairs_without_hiding_missing_or_duplicate_results(self):
        call={'type':'tool_call','threadId':'t','turnId':'u','requestId':'r','name':'act_plan','arguments':{},'timestampMs':1}
        cancel={**call,'type':'tool_cancelled','success':False,'errorType':'cancelled','elapsedMs':10,'timestampMs':11}
        self.assertEqual(analyze([call,cancel])['cancelledCalls'],1)
        self.assertEqual(analyze([call,cancel])['status'],'PASS')
        self.assertEqual(analyze([call,cancel,cancel])['status'],'FAIL')
        self.assertEqual(analyze([call])['status'],'FAIL')

    def test_secrets_are_removed_without_changing_payload_types(self):
        data = safe_value({'ok': False, 'access_token': 'private-token', 'text': 'Bearer private-token sk-example123456789', 'count': 4})
        self.assertIs(data['ok'], False)
        self.assertEqual(data['count'], 4)
        self.assertNotIn('private-token', str(data))
        self.assertNotIn('sk-example123456789', str(data))

    def test_call_result_pairing_and_expected_failure(self):
        call = {'type':'tool_call','threadId':'t','turnId':'u','requestId':'r','name':'files_media','arguments':{'operation':'ws_read_text'},'timestampMs':1}
        result = {**call,'type':'tool_result','success':False,'text':'{"ok":false,"errorType":"not_found"}','timestampMs':3}
        expected = [{'name':'files_media','arguments':{'operation':'ws_read_text'},'success':False,'json':True,'fields':{'errorType':'not_found'}}]
        self.assertEqual(analyze([call,result],expected,True)['status'],'PASS')
        self.assertEqual(analyze([call],expected)['status'],'FAIL')
        self.assertEqual(analyze([result],expected)['status'],'FAIL')

    def test_wrong_envelope_duplicate_and_extra_call_are_failures(self):
        call={'type':'tool_call','threadId':'t','turnId':'u','requestId':'r','name':'apps_settings','arguments':{},'timestampMs':1}
        result={**call,'type':'tool_result','success':True,'text':'{"ok":false}','timestampMs':2}
        self.assertEqual(analyze([call,result])['status'],'FAIL')
        result['text']='{"ok":true}'
        self.assertEqual(analyze([call,result,result])['status'],'FAIL')
        self.assertEqual(analyze([call,result],[],True)['status'],'FAIL')

    def test_plain_status_text_is_valid(self):
        call={'type':'tool_call','threadId':'t','turnId':'u','requestId':'r','name':'device_status','arguments':{},'timestampMs':1}
        result={**call,'type':'tool_result','success':True,'text':'Wireless ADB disconnected','timestampMs':2}
        self.assertEqual(analyze([call,result])['status'],'PASS')

if __name__=='__main__': unittest.main()
