import unittest
from tools.e2e_validation.latency import frame_stats, input_draw, summary, gfx_summary

class LatencyTests(unittest.TestCase):
    def test_unknown_oem_flags_do_not_become_zero_jank(self):
        text='Total frames rendered: 20\nJanky frames: 3 (15%)\n---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted\n32,10000000,30000000\n---PROFILEDATA---'
        result=frame_stats(text)
        self.assertEqual(result['availability'],'unavailable')
        self.assertIsNone(result['deadlineMisses'])
        self.assertEqual(result['skippedFlags'],{'32':1})
        self.assertEqual(gfx_summary(text)['jankyFrames'],3)

    def test_frame_header_order_invalid_frames_and_deadlines(self):
        text='---PROFILEDATA---\nFrameCompleted,Flags,IntendedVsync,FrameDeadline\n30000000,0,10000000,26000000\n40000000,1,10000000,26000000\n9223372036854775807,0,10000000,0\n---PROFILEDATA---'
        result=frame_stats(text)
        self.assertEqual(result['samples'],1)
        self.assertEqual(result['medianMs'],20)
        self.assertEqual(result['deadlineMisses'],1)

    def test_failed_draws_are_retained_and_not_reported_as_fast(self):
        log='I MikeUiLatency: {"status":"no_draw"}\nI MikeUiLatency: {"status":"drawn","inputToDrawMs":42}'
        result=input_draw(log)
        self.assertEqual(result['samples'],1)
        self.assertEqual(result['statuses']['no_draw'],1)
        self.assertEqual(result['medianMs'],42)
        self.assertEqual(summary(range(1,21))['p95Ms'],19)
