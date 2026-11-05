import unittest
from moment_quality_report import summarize


class MomentQualityReportTest(unittest.TestCase):
    def fixtures(self, count):
        pairs = [dict(id=str(i), critical=True, heldOut=True) for i in range(count)]
        key = {str(i): 'A' for i in range(count)}
        assessment = dict(complete=2, faithful=2, standalone=2, relevant=2, criticalFailure=False)
        reviews = [dict(id=str(i), preferred='A', A=dict(assessment), B=dict(assessment)) for i in range(count)]
        return pairs, key, reviews

    def test_missing_reviews_cannot_pass(self):
        pairs, key, reviews = self.fixtures(20)
        with self.assertRaises(ValueError):
            summarize(pairs, key, reviews[:-1])

    def test_critical_failure_blocks_high_preference_score(self):
        pairs, key, reviews = self.fixtures(20)
        reviews[0]['A']['criticalFailure'] = True
        result = summarize(pairs, key, reviews)
        self.assertEqual(['0'], result['criticalFailures'])
        self.assertFalse(result['qualityGatePassed'])

    def test_training_examples_are_not_held_out_evidence(self):
        pairs, key, reviews = self.fixtures(20)
        for row in pairs:
            row['heldOut'] = False
        self.assertFalse(summarize(pairs, key, reviews)['qualityGatePassed'])

    def test_ties_are_not_counted_as_wins(self):
        pairs, key, reviews = self.fixtures(20)
        for row in reviews[:5]:
            row['preferred'] = 'tie'
        self.assertFalse(summarize(pairs, key, reviews)['qualityGatePassed'])
        reviews[4]['preferred'] = 'A'
        self.assertTrue(summarize(pairs, key, reviews)['qualityGatePassed'])
